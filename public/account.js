// ProMultiCam — account, subscription & payments (Firebase)
const C = window.PMC_CONFIG || {};
const V = C.sdk || '10.12.2';
const ev = () => window.dispatchEvent(new Event('pmc-auth'));
const PMC = window.PMC = {
  ready: false, user: null, profile: null, plans: {}, error: null,
  isAdmin() { return !!(this.user && C.superUid && this.user.uid === C.superUid); },
  isPro() { if (this.isAdmin()) return true; const p = this.profile; return !!(p && p.status !== 'suspended' && (p.expiry || 0) > Date.now()); },
  daysLeft() { const p = this.profile; return p ? Math.max(0, Math.ceil(((p.expiry || 0) - Date.now()) / 86400000)) : 0; }
};

try {
  const [{ initializeApp }, A, F, Fn] = await Promise.all([
    import(`https://www.gstatic.com/firebasejs/${V}/firebase-app.js`),
    import(`https://www.gstatic.com/firebasejs/${V}/firebase-auth.js`),
    import(`https://www.gstatic.com/firebasejs/${V}/firebase-firestore.js`),
    import(`https://www.gstatic.com/firebasejs/${V}/firebase-functions.js`)
  ]);
  const app = initializeApp(C.firebase);
  const auth = A.getAuth(app);
  const db = F.getFirestore(app, C.dbId || 'promulticam');
  const fns = Fn.getFunctions(app, C.region || 'asia-southeast1');
  const call = (name, data) => Fn.httpsCallable(fns, name)(data || {}).then(r => r.data);

  Object.assign(PMC, {
    login: (email, pw) => A.signInWithEmailAndPassword(auth, email, pw),
    register: (email, pw) => A.createUserWithEmailAndPassword(auth, email, pw),
    google: async () => {
      const prov = new A.GoogleAuthProvider();
      try { return await A.signInWithPopup(auth, prov); }
      catch (e) { if (/popup-blocked|operation-not-supported/.test(e.code || '')) return A.signInWithRedirect(auth, prov); throw e; }
    },
    reset: (email) => A.sendPasswordResetEmail(auth, email),
    logout: () => A.signOut(auth),
    createBill: (plan, promo, phone) => call('pmc_createBill', { plan, promo, phone }),
    checkPayment: (id) => call('pmc_checkPayment', { id }),
    redeem: (code) => call('pmc_redeemPromo', { code }),
    call,
    chatListen: (cb) => {
      const u = auth.currentUser; if (!u) return () => {};
      const q = F.query(F.collection(db, `supportChats/${u.uid}/messages`), F.orderBy('at', 'asc'), F.limitToLast(200));
      return F.onSnapshot(q, s => cb(s.docs.map(d => ({ id: d.id, ...d.data(), ms: d.data().at?.toMillis?.() || Date.now() }))), () => cb([]));
    },
    chatSend: async (text) => {
      const u = auth.currentUser; if (!u) throw new Error('auth');
      text = String(text || '').trim().slice(0, 2000); if (!text) return;
      await F.addDoc(F.collection(db, `supportChats/${u.uid}/messages`), { from: 'user', text, at: F.serverTimestamp() });
      await F.setDoc(F.doc(db, `supportChats/${u.uid}`), { uid: u.uid, email: u.email || '', lastMsg: text.slice(0, 300), lastAt: F.serverTimestamp(), lastFrom: 'user', unreadAdmin: true, unreadUser: false });
    },
    chatMarkRead: async () => {
      const u = auth.currentUser; if (!u) return;
      const ref = F.doc(db, `supportChats/${u.uid}`); const s = await F.getDoc(ref);
      if (s.exists() && s.data().unreadUser) { const d = s.data(); await F.setDoc(ref, { uid: u.uid, email: d.email || u.email || '', lastMsg: d.lastMsg || '', lastAt: d.lastAt || F.serverTimestamp(), lastFrom: d.lastFrom || 'admin', unreadAdmin: !!d.unreadAdmin, unreadUser: false }); }
    },
    listPayments: async () => {
      if (!auth.currentUser) return [];
      const q = F.query(F.collection(db, 'payments'), F.where('uid', '==', auth.currentUser.uid));
      const s = await F.getDocs(q);
      return s.docs.map(d => ({ id: d.id, ...d.data(), created: d.data().createdAt?.toMillis?.() || 0, paid: d.data().paidAt?.toMillis?.() || 0, until: d.data().newExpiry?.toMillis?.() || 0 }))
        .sort((x, y) => y.created - x.created);
    }
  });
  A.getRedirectResult(auth).catch(() => {});

  F.onSnapshot(F.doc(db, 'config/plans'), s => { PMC.plans = s.data() || {}; ev(); }, () => {});

  let unsubUser = null;
  A.onAuthStateChanged(auth, async (u) => {
    PMC.user = u; PMC.profile = null;
    if (unsubUser) { unsubUser(); unsubUser = null; }
    if (u) {
      try { const r = await call('pmc_initUser'); PMC.profile = { expiry: r.expiry, plan: r.plan, status: r.status }; }
      catch (e) { PMC.error = e.message; }
      if (PMC._adm) { PMC._adm.forEach(f => f()); PMC._adm = null; }
      if (C.superUid && u.uid === C.superUid) {
        PMC._adm = [
          F.onSnapshot(F.query(F.collection(db, 'supportChats'), F.where('unreadAdmin', '==', true)), s => {
            PMC.adminChats = s.docs.map(d => ({ id: d.id, ...d.data(), ms: d.data().lastAt?.toMillis?.() || 0 })); ev(); window.dispatchEvent(new Event('pmc-admin'));
          }, () => {}),
          F.onSnapshot(F.query(F.collection(db, 'payments'), F.where('status', '==', 'paid')), s => {
            PMC.adminPaid = s.docs.map(d => ({ id: d.id, ...d.data(), ms: d.data().paidAt?.toMillis?.() || 0 })); ev(); window.dispatchEvent(new Event('pmc-admin'));
          }, () => {})
        ];
      }
      if (PMC._chatMeta) PMC._chatMeta();
      PMC._chatMeta = F.onSnapshot(F.doc(db, `supportChats/${u.uid}`), s => { PMC.chatUnread = !!(s.data() && s.data().unreadUser); ev(); }, () => {});
      unsubUser = F.onSnapshot(F.doc(db, `users/${u.uid}`), s => {
        const d = s.data(); if (!d) return;
        PMC.profile = { expiry: d.expiry?.toMillis?.() || 0, plan: d.plan || 'free', status: d.status || 'active', email: d.email || u.email };
        ev();
      }, () => {});
    }
    PMC.ready = true; ev();
  });
} catch (e) {
  PMC.error = 'offline'; PMC.ready = true; ev();
}
