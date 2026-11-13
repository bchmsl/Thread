/*
 * Thread offline app shell (adr/ADR-008-offline-shell-and-token-storage.md).
 *
 * Template. The generateServiceWorker Gradle task fills in the build id and the file list and writes the result
 * into the production distribution. Hand-written on purpose: no Workbox and no other download (P3-G1).
 *
 * - The shell files of one build go into one cache, named after that build, so a page never mixes two builds.
 * - The shell is served from that cache first. A new build installs in the background and waits.
 * - On its next load, a page asks the waiting build to take over (TAKE_OVER_IF_ALONE). It does so only when that page
 *   is the only open Thread tab; the page then reloads once into the new build. With other tabs open, every tab keeps
 *   the old build until the others close. The page asks, not the navigation, because during a navigation a worker
 *   cannot tell a reload of the only tab from a new tab opened next to another one.
 * - Only same-origin GET requests are touched. Firebase, Jira and GitHub requests never pass through here, and
 *   nothing they return is cached here.
 */
'use strict';

const BUILD_ID = '__BUILD_ID__';
const PRECACHE = __PRECACHE__;
const CACHE_PREFIX = 'thread-shell-';
const CACHE_NAME = CACHE_PREFIX + BUILD_ID;
const SHELL = 'index.html';

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE_NAME);
    // 'reload' skips the HTTP cache, so every file comes from this build.
    await cache.addAll(PRECACHE.map((path) => new Request(path, { cache: 'reload' })));
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const names = await caches.keys();
    await Promise.all(
      names
        .filter((name) => name.startsWith(CACHE_PREFIX) && name !== CACHE_NAME)
        .map((name) => caches.delete(name)),
    );
  })());
});

/** Sent by a page to a waiting worker. The answer, true or false, goes back on the message port. */
self.addEventListener('message', (event) => {
  if (!event.data || event.data.type !== 'TAKE_OVER_IF_ALONE') return;
  const reply = event.ports[0];
  event.waitUntil((async () => {
    const tabs = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const alone = tabs.length < 2;
    if (alone) await self.skipWaiting();
    if (reply) reply.postMessage(alone);
  })());
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  // Every navigation gets the shell page. Routes are in the URL hash.
  if (request.mode === 'navigate') {
    event.respondWith(fromShell(SHELL));
    return;
  }
  const path = relativePath(url);
  if (path !== null && PRECACHE.includes(path)) event.respondWith(fromShell(path));
});

async function fromShell(path) {
  const cache = await caches.open(CACHE_NAME);
  const cached = await cache.match(path);
  return cached || fetch(path);
}

/** The path below this worker's scope, such as 'css/tokens.css', or null outside the scope. */
function relativePath(url) {
  const scope = new URL(self.registration.scope).pathname;
  return url.pathname.startsWith(scope) ? url.pathname.slice(scope.length) : null;
}
