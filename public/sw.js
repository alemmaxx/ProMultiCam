const C='promulticam-v69';
self.addEventListener('install',e=>{e.waitUntil(caches.open(C).then(c=>c.addAll(['./','./index.html','./i18n.js','./account.js','./editor.js','./textfx.js','./camctl.js','./config.js','./landing.html','./legal.html','./manifest.json','./icon-192.png','./icon-512.png','./icon.svg','./favicon.png','./apple-touch-icon.png'])));self.skipWaiting();});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==C).map(k=>caches.delete(k)))));self.clients.claim();});
self.addEventListener('fetch',e=>{if(e.request.method!=='GET')return;e.respondWith(fetch(e.request).catch(()=>caches.match(e.request)));});
