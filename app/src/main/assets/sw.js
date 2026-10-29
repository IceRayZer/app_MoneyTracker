// Cache l'app pour qu'elle s'ouvre hors ligne. Incrémenter V à chaque modification d'index.html.
const V = 'mt-2', FILES = ['./', 'index.html', 'manifest.webmanifest', 'icon-192.png', 'icon-512.png'];
self.addEventListener('install', e => e.waitUntil(caches.open(V).then(c => c.addAll(FILES)).then(() => self.skipWaiting())));
self.addEventListener('activate', e => e.waitUntil(
    caches.keys().then(ks => Promise.all(ks.filter(k => k !== V).map(k => caches.delete(k)))).then(() => self.clients.claim())));
self.addEventListener('fetch', e => e.respondWith(
    caches.match(e.request, { ignoreSearch: true }).then(r => r || fetch(e.request))));
