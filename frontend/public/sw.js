// Lemuel Settlement PWA service worker — app shell 캐시(오프라인 껍데기) + network-first
// SPA 라 라우트는 index.html 로 폴백. API(axios) 응답은 캐시하지 않음(정산 데이터 신선도).
const CACHE = 'settlement-shell-v1';
const SHELL = ['/', '/index.html', '/manifest.webmanifest', '/icon-192.png', '/icon-512.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  // API·외부 호출은 캐시 개입 없이 그대로 (정산 데이터 신선도 보장)
  if (url.pathname.startsWith('/api') || url.origin !== self.location.origin) return;

  // SPA 네비게이션: network-first → 실패 시 캐시된 index.html
  if (req.mode === 'navigate') {
    e.respondWith(fetch(req).catch(() => caches.match('/index.html')));
    return;
  }
  // 정적 자원: cache-first, 없으면 네트워크 후 캐시
  e.respondWith(
    caches.match(req).then((hit) => hit || fetch(req).then((res) => {
      const copy = res.clone();
      caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
      return res;
    }).catch(() => hit))
  );
});
