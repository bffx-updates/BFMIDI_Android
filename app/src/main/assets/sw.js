// BFMIDI Editor — Service Worker (gerado por webApp/build.mjs).
const CACHE_NAME = 'bfmidi-prod-5a80c8665f64';
const APP_SHELL = [
  './',
  './index.html',
  './app.css',
  './app.js',
  './manifest.webmanifest',
  './icons/app.svg',
  './icons/app-192.png',
  './icons/app-512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(APP_SHELL).catch(() => null))
  );
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(
        keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k))
      )
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;

  const url = new URL(req.url);
  // Cross-origin (ex: API HTTP do dispositivo em outro IP) -> passthrough.
  if (url.origin !== self.location.origin) return;

  const p = url.pathname.toLowerCase();
  // Rotas de DADOS do device (/icon/N.png, /img/N.jpg — uploads de midia):
  // passthrough total (o conteudo muda por upload sem a URL mudar; cachear
  // serviria o stale pra sempre).
  const isDeviceMedia = /\/(icon|img)\/\d+\.(png|jpg)$/.test(p);
  if (isDeviceMedia) return;

  // APP SHELL (navegacao + app.js/app.css/index/manifest/json): NETWORK-FIRST.
  // Sempre busca a versao ATUAL do device quando alcancavel; o cache entra so
  // como fallback offline. Evita "UI velha apos reflashar o littlefs" — o
  // cache-first servia o app.js antigo ate o SW trocar de CACHE_NAME. E o
  // equivalente PWA do "limpa o cache ao abrir" do APK Android.
  const isShell = req.mode === 'navigate' ||
                  p === '/' || p.endsWith('/') || p.endsWith('/index.html') ||
                  p.endsWith('.js') || p.endsWith('.css') ||
                  p.endsWith('.webmanifest') || p.endsWith('.json');
  if (isShell) {
    event.respondWith(
      fetch(req).then((resp) => {
        if (resp && resp.ok) {
          const copy = resp.clone();
          caches.open(CACHE_NAME).then((c) => c.put(req, copy)).catch(() => {});
        }
        return resp;
      }).catch(() =>
        caches.match(req).then((cached) =>
          cached ||
          (req.mode === 'navigate'
            ? caches.match('./index.html')
            : new Response('', { status: 504, statusText: 'offline' }))
        )
      )
    );
    return;
  }

  // ESTATICOS RAROS (icones .png/.svg): CACHE-FIRST — mudam raramente e o
  // CACHE_NAME (hash do conteudo) ja invalida quando a arte muda.
  event.respondWith(
    caches.match(req).then((cached) => {
      if (cached) return cached;
      return fetch(req).then((resp) => {
        if (resp && resp.ok && (p.endsWith('.png') || p.endsWith('.svg'))) {
          const copy = resp.clone();
          caches.open(CACHE_NAME).then((c) => c.put(req, copy)).catch(() => {});
        }
        return resp;
      }).catch(() => new Response('', { status: 504, statusText: 'offline' }));
    })
  );
});
