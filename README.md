# ProMultiCam
**A multi-camera studio in your pocket.** Record studio-quality cooking videos with several phones. 12 languages (BM, English, Indonesia, 中文, Español, Português, Français, العربية, हिन्दी, ไทย, Tiếng Việt, 日本語).

## How it works
1. **Director:** the phone that switches angles and records the final video.
2. **Camera:** any other phone. Same Wi-Fi → the Director appears automatically. Mobile data → enter the 6-digit Studio Code.
3. Tap a camera tile to switch angles, pick a **Visual Style**, then **Start recording**.

## Install (web app)
Open the app link in Chrome → **Install App** (or menu ⋮ → *Add to Home screen*). On iPhone: Share → *Add to Home Screen*.

## Structure
- `public/index.html` — the app (Director / Camera)
- `public/landing.html` — sales / landing page
- `public/admin.html` — Super Admin (customers, payments, prices, promo codes)
- `functions/` — Cloud Functions: trial, ToyyibPay billing, promo codes
- `firestore.rules` — database rules (database `promulticam`)
- `index.html` (root) — redirects GitHub Pages to promulticam.web.app

## Optional setup (`public/config.js`)
- **TURN relay:** `meteredApp` + `meteredApiKey` (free 20 GB/month at metered.ca/tools/openrelay)
- **Analytics:** `ga4Id` (Google Analytics 4 measurement ID)

## Deploy
Add the repo secret `FIREBASE_SERVICE_ACCOUNT` (a Firebase service-account JSON). Every push to `main` deploys hosting (site `promulticam`), database rules and Cloud Functions.
