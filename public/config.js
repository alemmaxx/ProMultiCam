/* ProMultiCam settings
   TURN relay (optional, recommended for mobile-data users worldwide):
   1. Sign up free at https://www.metered.ca/tools/openrelay/  (20 GB/month free)
   2. Copy your app name (the part before .metered.live) and your TURN API key below.
   Leave empty to run without a relay (Wi-Fi works fine without it). */
window.PMC_CONFIG = {
  /* Firebase (web config — public by design) */
  firebase: {
    apiKey: 'AIzaSyDIncQ3Nt6v6_WbMCs42t81DZVg6f35Hwc',
    authDomain: 'kukusanmy.firebaseapp.com',
    projectId: 'kukusanmy',
    storageBucket: 'kukusanmy.firebasestorage.app',
    messagingSenderId: '710803283811',
    appId: '1:710803283811:web:3cedeaa7911135f739fd56'
  },
  dbId: 'promulticam',
  region: 'asia-southeast1',
  sdk: '10.12.2',

  meteredApp: '',      // e.g. 'promulticam'
  meteredApiKey: '',   // e.g. 'abc123...'
  iceServers: [],      // or paste your own TURN servers: [{urls:'turn:host:3478',username:'u',credential:'p'}]

  /* Analytics (optional): Google Analytics 4 / Firebase Analytics measurement ID, e.g. 'G-XXXXXXXXXX'.
     Tracks only anonymous app events (role chosen, recording started/stopped, style used). */
  ga4Id: ''
};
