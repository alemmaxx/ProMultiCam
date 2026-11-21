/* ProMultiCam settings
   TURN relay (optional, recommended for mobile-data users worldwide):
   1. Sign up free at https://www.metered.ca/tools/openrelay/  (20 GB/month free)
   2. Copy your app name (the part before .metered.live) and your TURN API key below.
   Leave empty to run without a relay (Wi-Fi works fine without it). */
window.PMC_CONFIG = {
  meteredApp: '',      // e.g. 'promulticam'
  meteredApiKey: '',   // e.g. 'abc123...'
  iceServers: [],      // or paste your own TURN servers: [{urls:'turn:host:3478',username:'u',credential:'p'}]

  /* Analytics (optional): Google Analytics 4 / Firebase Analytics measurement ID, e.g. 'G-XXXXXXXXXX'.
     Tracks only anonymous app events (role chosen, recording started/stopped, style used). */
  ga4Id: ''
};
