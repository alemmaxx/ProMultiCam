// ProMultiCam — Cloud Functions (codebase "promulticam")
// Every function is prefixed pmc_ so it never clashes with other apps in the same Firebase project.
const { onCall, onRequest, HttpsError } = require('firebase-functions/v2/https');
const { defineSecret } = require('firebase-functions/params');
const { initializeApp } = require('firebase-admin/app');
const { getFirestore, FieldValue, Timestamp } = require('firebase-admin/firestore');

initializeApp();
const db = getFirestore('promulticam');
const TOYYIB_KEY = defineSecret('TOYYIBPAY_SECRET_KEY'); // existing project secret

const REGION = 'asia-southeast1';
const TOYYIB = 'https://toyyibpay.com';
const SITE = 'https://promulticam.web.app';
const TOYYIB_CATEGORY = 'popvay7w';
const PLAN_MONTHS = { monthly: 1, yearly: 12 };
const PLAN_NAME = { monthly: 'Bulanan', yearly: 'Tahunan' };
const DEFAULT_TRIAL_DAYS = 7;
const SUPER_UID = 'D3U6Dxs5joZQq5EYRdjZQUmRV773'; // owner account

const clean = (s, n) => String(s || '').replace(/[^\p{L}\p{N} .,_@()-]/gu, ' ').replace(/\s+/g, ' ').trim().slice(0, n);
const projectId = () => process.env.GCLOUD_PROJECT || JSON.parse(process.env.FIREBASE_CONFIG || '{}').projectId;

function requireAuth(req) {
  if (!req.auth) throw new HttpsError('unauthenticated', 'Sila log masuk.');
  return req.auth;
}
async function requireSuper(req) {
  const a = requireAuth(req);
  if (a.uid !== SUPER_UID) throw new HttpsError('permission-denied', 'Super Admin sahaja.');
  return a;
}
async function plansCfg() { return (await db.doc('config/plans').get()).data() || {}; }

// ---------------------------------------------------------------- Account + free trial (length set in Admin)
exports.pmc_initUser = onCall({ region: REGION }, async (req) => {
  const a = requireAuth(req);
  const ref = db.doc(`users/${a.uid}`);
  if (a.uid === SUPER_UID) {
    const far = new Date('2099-12-31T00:00:00Z');
    await ref.set({ email: a.token.email || '', plan: 'admin', status: 'active', expiry: Timestamp.fromDate(far), lastSeen: FieldValue.serverTimestamp() }, { merge: true });
    return { expiry: far.getTime(), plan: 'admin', status: 'active' };
  }
  return db.runTransaction(async (t) => {
    const s = await t.get(ref);
    const cfg = (await t.get(db.doc('config/plans'))).data() || {};
    const days = Number.isFinite(Number(cfg.trialDays)) ? Math.max(0, Number(cfg.trialDays)) : DEFAULT_TRIAL_DAYS;
    if (s.exists) {
      const d = s.data();
      const upd = { lastSeen: FieldValue.serverTimestamp() };
      let expiry = d.expiry?.toMillis?.() || 0, plan = d.plan || 'free';
      // Accounts still on the free trial follow the current trial length set by the admin
      if (plan === 'trial' && d.createdAt?.toMillis) {
        expiry = d.createdAt.toMillis() + days * 86400000;
        upd.expiry = Timestamp.fromMillis(expiry);
        if (days === 0) { plan = 'free'; upd.plan = 'free'; }
      }
      t.update(ref, upd);
      return { expiry, plan, status: d.status || 'active' };
    }
    const exp = new Date(Date.now() + days * 86400000);
    t.set(ref, {
      email: a.token.email || '', name: clean(a.token.name || '', 60), provider: a.token.firebase?.sign_in_provider || '',
      plan: days > 0 ? 'trial' : 'free', status: 'active', expiry: Timestamp.fromDate(exp),
      createdAt: FieldValue.serverTimestamp(), lastSeen: FieldValue.serverTimestamp()
    });
    return { expiry: exp.getTime(), plan: days > 0 ? 'trial' : 'free', status: 'active', created: true };
  });
});

// ---------------------------------------------------------------- Promo helpers
async function readPromo(t, code, uid) {
  if (!/^[A-Z0-9-]{3,30}$/.test(code)) throw new HttpsError('invalid-argument', 'Kod promo tidak sah.');
  const pRef = db.doc(`promos/${code}`);
  const ps = await t.get(pRef);
  const p = ps.exists ? ps.data() : null;
  const invalid = () => new HttpsError('failed-precondition', 'Kod promo tidak sah, telah tamat atau telah digunakan.');
  if (!p || p.active === false) throw invalid();
  if (p.expiresAt && p.expiresAt.toMillis() < Date.now()) throw invalid();
  if (Number(p.maxUses || 0) > 0 && Number(p.uses || 0) >= Number(p.maxUses)) throw invalid();
  const used = await t.get(db.doc(`promos/${code}/uses/${uid}`));
  if (used.exists) throw invalid();
  return { pRef, p };
}

// ---------------------------------------------------------------- Create ToyyibPay bill
exports.pmc_createBill = onCall({ region: REGION, secrets: [TOYYIB_KEY] }, async (req) => {
  const a = requireAuth(req);
  const plan = String(req.data?.plan || '');
  const months = PLAN_MONTHS[plan];
  if (!months) throw new HttpsError('invalid-argument', 'Pakej tidak sah.');
  const cfg = await plansCfg();
  const price0 = Number(cfg[plan] || 0);
  if (!(price0 > 0)) throw new HttpsError('failed-precondition', 'Harga pakej belum ditetapkan.');

  let promo = '', discount = 0;
  const pcode = String(req.data?.promo || '').trim().toUpperCase();
  if (pcode) {
    const { p } = await db.runTransaction(t => readPromo(t, pcode, a.uid));
    if (p.type !== 'percent') throw new HttpsError('failed-precondition', 'Kod ini untuk hari percuma — tebus di bahagian Kod Promo.');
    discount = Math.min(100, Math.max(0, Number(p.value || 0))); promo = pcode;
  }
  const amount = Math.round(price0 * (100 - discount)) / 100;
  if (!(amount >= 1)) throw new HttpsError('failed-precondition', 'Jumlah selepas diskaun terlalu rendah.');

  const user = (await db.doc(`users/${a.uid}`).get()).data() || {};
  const ref = db.collection('payments').doc();
  const body = new URLSearchParams({
    userSecretKey: TOYYIB_KEY.value(),
    categoryCode: TOYYIB_CATEGORY,
    billName: clean(`ProMultiCam Pro ${PLAN_NAME[plan]}`, 30),
    billDescription: clean(`Langganan ProMultiCam Pro ${months} bulan`, 100),
    billPriceSetting: '1',
    billPayorInfo: '1',
    billAmount: String(Math.round(amount * 100)),
    billReturnUrl: `${SITE}/?pay=${ref.id}`,
    billCallbackUrl: `https://${REGION}-${projectId()}.cloudfunctions.net/pmc_toyyibCallback`,
    billExternalReferenceNo: ref.id,
    billTo: clean(user.name || a.token.name || (a.token.email || '').split('@')[0] || 'Pelanggan', 60) || 'Pelanggan',
    billEmail: a.token.email || 'noreply@promulticam.web.app',
    billPhone: String(req.data?.phone || '').replace(/\D/g, '').slice(0, 15) || '0123456789',
    billPaymentChannel: '0',
    billExpiryDays: '3'
  });
  const res = await fetch(`${TOYYIB}/index.php/api/createBill`, { method: 'POST', body });
  const text = await res.text();
  let billCode = null;
  try { billCode = JSON.parse(text)?.[0]?.BillCode; } catch { /* ignore */ }
  if (!billCode) { console.error('ToyyibPay createBill failed:', text); throw new HttpsError('internal', 'Gagal mencipta bil bayaran.'); }

  await ref.set({
    uid: a.uid, email: a.token.email || '', plan, months, amount, listPrice: price0, promo, discount,
    billCode, status: 'pending', createdAt: FieldValue.serverTimestamp()
  });
  return { url: `${TOYYIB}/${billCode}`, id: ref.id };
});

// ---------------------------------------------------------------- Verify with ToyyibPay & extend Pro
async function settle(paymentId) {
  const pRef = db.doc(`payments/${paymentId}`);
  const pSnap = await pRef.get();
  if (!pSnap.exists) return { status: 'not-found' };
  const p = pSnap.data();
  if (p.status === 'paid') return { status: 'paid' };

  // Always confirm directly with ToyyibPay (never trust the callback alone)
  const res = await fetch(`${TOYYIB}/index.php/api/getBillTransactions`, { method: 'POST', body: new URLSearchParams({ billCode: p.billCode }) });
  let tx = [];
  try { tx = JSON.parse(await res.text()); } catch { tx = []; }
  const paid = Array.isArray(tx) && tx.find(x => String(x.billpaymentStatus) === '1');
  if (!paid) {
    const failed = Array.isArray(tx) && tx.length && tx.every(x => String(x.billpaymentStatus) === '3');
    if (failed) await pRef.update({ status: 'failed' });
    return { status: failed ? 'failed' : 'pending' };
  }
  let newExpiry = null;
  await db.runTransaction(async (t) => {
    const ps = await t.get(pRef);
    if (ps.data().status === 'paid') return;
    const uRef = db.doc(`users/${p.uid}`);
    const us = await t.get(uRef);
    const prRef = p.promo ? db.doc(`promos/${p.promo}`) : null;
    if (prRef) await t.get(prRef);
    const cur = us.data()?.expiry?.toMillis?.() || 0;
    const base = new Date(Math.max(Date.now(), cur));
    base.setMonth(base.getMonth() + Number(p.months || 1));
    newExpiry = base;
    t.set(uRef, { expiry: Timestamp.fromDate(base), status: 'active', plan: p.plan, lastPaidAt: FieldValue.serverTimestamp(), totalPaid: FieldValue.increment(Number(p.amount || 0)) }, { merge: true });
    if (prRef) {
      t.set(prRef, { uses: FieldValue.increment(1) }, { merge: true });
      t.set(db.doc(`promos/${p.promo}/uses/${p.uid}`), { email: p.email || '', at: FieldValue.serverTimestamp(), payment: paymentId });
    }
    t.update(pRef, { status: 'paid', paidAt: FieldValue.serverTimestamp(), refNo: paid.billpaymentInvoiceNo || '', newExpiry: Timestamp.fromDate(base) });
  });
  return { status: 'paid', expiry: newExpiry ? newExpiry.getTime() : null };
}

exports.pmc_toyyibCallback = onRequest({ region: REGION }, async (req, res) => {
  try {
    const id = req.body?.order_id || req.query?.order_id;
    if (!id) { res.status(400).send('missing order_id'); return; }
    const r = await settle(String(id));
    res.status(200).send(`OK ${r.status}`);
  } catch (e) { console.error(e); res.status(500).send('ERR'); }
});

exports.pmc_checkPayment = onCall({ region: REGION }, async (req) => {
  const a = requireAuth(req);
  const id = String(req.data?.id || '');
  const s = await db.doc(`payments/${id}`).get();
  if (!s.exists || s.data().uid !== a.uid) throw new HttpsError('not-found', 'Bayaran tidak dijumpai.');
  return settle(id);
});

// ---------------------------------------------------------------- Promo: 'days' adds Pro days now; 'percent' is validated here and used at checkout
exports.pmc_redeemPromo = onCall({ region: REGION }, async (req) => {
  const a = requireAuth(req);
  const code = String(req.data?.code || '').trim().toUpperCase();
  return db.runTransaction(async (t) => {
    const { pRef, p } = await readPromo(t, code, a.uid);
    if (p.type === 'percent') return { type: 'percent', value: Number(p.value || 0) };
    const days = Number(p.value || 0);
    if (!(days > 0)) throw new HttpsError('failed-precondition', 'Kod promo tidak sah.');
    const uRef = db.doc(`users/${a.uid}`);
    const us = await t.get(uRef);
    const cur = us.data()?.expiry?.toMillis?.() || 0;
    const base = new Date(Math.max(Date.now(), cur) + days * 86400000);
    t.set(uRef, { expiry: Timestamp.fromDate(base), status: 'active', plan: us.data()?.plan === 'free' || !us.exists ? 'promo' : us.data().plan, email: a.token.email || '' }, { merge: true });
    t.set(pRef, { uses: FieldValue.increment(1) }, { merge: true });
    t.set(db.doc(`promos/${code}/uses/${a.uid}`), { email: a.token.email || '', at: FieldValue.serverTimestamp() });
    return { type: 'days', value: days, expiry: base.getTime() };
  });
});

// ---------------------------------------------------------------- Super Admin: extend / suspend / activate a customer
exports.pmc_adminUser = onCall({ region: REGION }, async (req) => {
  await requireSuper(req);
  const uid = String(req.data?.uid || '');
  const action = String(req.data?.action || '');
  const uRef = db.doc(`users/${uid}`);
  const us = await uRef.get();
  if (!us.exists) throw new HttpsError('not-found', 'Pelanggan tidak dijumpai.');
  if (action === 'extend') {
    const days = Math.round(Number(req.data?.days || 0));
    if (!(days > 0 && days <= 3650)) throw new HttpsError('invalid-argument', 'Bilangan hari tidak sah.');
    const cur = us.data().expiry?.toMillis?.() || 0;
    const base = new Date(Math.max(Date.now(), cur) + days * 86400000);
    await uRef.update({ expiry: Timestamp.fromDate(base), status: 'active', note: `+${days} hari oleh admin` });
    return { expiry: base.getTime() };
  }
  if (action === 'suspend' || action === 'activate') {
    await uRef.update({ status: action === 'suspend' ? 'suspended' : 'active' });
    return { status: action === 'suspend' ? 'suspended' : 'active' };
  }
  if (action === 'expire') {
    await uRef.update({ expiry: Timestamp.fromDate(new Date()), plan: 'free' });
    return { expiry: Date.now() };
  }
  throw new HttpsError('invalid-argument', 'Tindakan tidak sah.');
});
