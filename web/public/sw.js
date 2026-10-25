// Service worker: makes SplitLedger installable and lets the app shell open without a network.
//
// It caches ONLY the shell (the page, its hashed scripts and styles, icons). API requests are never
// touched, so no balances, expenses, bank data or tokens ever land in the browser's cache, and
// everything shown is always fresh from the server.

const CACHE = 'splitledger-shell-v1';
const SHELL = ['/', '/manifest.webmanifest', '/favicon.svg', '/icons/icon-192.png'];
const MAX_ASSETS = 40;

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

const isShell = (url) => url.pathname === '/' || url.pathname === '/index.html' || url.pathname === '/manifest.webmanifest'
  || url.pathname === '/favicon.svg' || url.pathname.startsWith('/icons/');

self.addEventListener('fetch', (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin) return;

  // The page: network first, so a new deploy shows up at once; the cached copy only when offline.
  if (request.mode === 'navigate' || isShell(url)) {
    event.respondWith(
      fetch(request)
        .then((response) => {
          if (response.ok) {
            const copy = response.clone();
            caches.open(CACHE).then((cache) => cache.put(request.mode === 'navigate' ? '/' : request, copy));
          }
          return response;
        })
        .catch(() => caches.match(request.mode === 'navigate' ? '/' : request)),
    );
    return;
  }

  // Scripts and styles have a content hash in their name and never change: cache first.
  if (url.pathname.startsWith('/assets/')) {
    event.respondWith(
      caches.match(request).then((cached) => cached || fetch(request).then((response) => {
        if (response.ok) {
          const copy = response.clone();
          caches.open(CACHE).then(async (cache) => {
            await cache.put(request, copy);
            // Old builds' files are dropped once enough new ones have arrived.
            const assets = (await cache.keys()).filter((r) => new URL(r.url).pathname.startsWith('/assets/'));
            await Promise.all(assets.slice(0, Math.max(0, assets.length - MAX_ASSETS)).map((r) => cache.delete(r)));
          });
        }
        return response;
      })),
    );
  }
  // Everything else (the API) goes straight to the network, untouched.
});
