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
  try { await A.setPersistence(auth, A.browserLocalPersistence); } catch (e) { /* ignore */ }

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
    call
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
