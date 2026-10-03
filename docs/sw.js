/* Aces & Eclipses Companion App: keeps the app on the phone so it opens with no internet. */
const VERSION = 'sst-498c3c94500e';
const FONTS = 'sst-fonts';
const CORE = ['./', './index.html', './manifest.webmanifest', './icons/icon-192.png', './icons/icon-512.png', './icons/maskable-512.png', './icons/apple-touch-icon.png', './icons/favicon-32.png'];
self.addEventListener('install', e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(CORE)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== VERSION && k !== FONTS).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  // Fonts: keep a copy the first time they load, then use it offline.
  if (url.hostname === 'fonts.googleapis.com' || url.hostname === 'fonts.gstatic.com'){
    e.respondWith(caches.open(FONTS).then(async c => {
      const hit = await c.match(req);
      if (hit) return hit;
      try { const res = await fetch(req); if (res && (res.ok || res.type === 'opaque')) c.put(req, res.clone()); return res; }
      catch (err) { return hit || Response.error(); }
    }));
    return;
  }
  if (url.origin !== self.location.origin) return;
  // The app: answer from the phone at once, and fetch any newer version in the background.
  e.respondWith(caches.open(VERSION).then(async c => {
    const key = req.mode === 'navigate' ? './index.html' : req;
    const hit = await c.match(key, {ignoreSearch: req.mode === 'navigate'});
    const net = fetch(req).then(res => { if (res && res.ok && req.mode !== 'navigate') c.put(req, res.clone()); return res; }).catch(() => null);
    if (hit) return hit;
    const res = await net;
    return res || (req.mode === 'navigate' ? c.match('./index.html') : Response.error());
  }));
});
