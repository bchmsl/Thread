# ADR-008: Offline app shell and token storage

Status: Proposed (2026-11-13). Written in Phase 3 (M1-C). The offline shell is checked in headless Chrome (S1, S2). Token storage is checked with fixtures and fake values in headless Chrome. At gate P3-G2 the owner stored real tokens in their own Chrome, and every live call used them. Evidence: `adr/M1-RESULTS.md`, "M1-C" and "M1-C live".

Phase 4 review (2026-11-13, owner away): checked for consistency with the other ADRs. Still Proposed; the owner review is queued.

## Context

- Thread must open with no network and show the last local content (D02, local-first). Until now a reload while offline gave the browser's error page.
- P3-G1 decision 1: a hand-written service worker plus a Gradle step. No Workbox and no other download.
- Jira and GitHub need the owner's tokens in the browser, because there is no app server (ADR-006, ADR-007).
- P3-G1 decision 4: tokens and saved copies are stored per browser profile. Which product scope they get (per device, per account) is decided in Phase 4.

## Decision

### Offline shell

| Part | Choice |
|---|---|
| Worker | `webApp/serviceWorker/sw.js`, a template of about 80 lines |
| Build step | Gradle task `generateServiceWorker`. It runs after the production webpack build, lists the shell files (webpack output and processed resources, without `.map`, `.LICENSE.txt` and `sw.js`), and fails when `index.html` or `thread.js` is missing. It writes the list and a build id into the template. `jsBrowserDistribution` copies the result. |
| Build id | First 16 hex digits of SHA-256 over every shell file's path and bytes. The same output gives the same id; any change gives a new id. |
| Registration | Production build only (`process.env.NODE_ENV`), only where the browser has service workers. The development server has no worker, so development is never served stale files. |
| Cache | One cache per build, `thread-shell-<buildId>`, filled on install with `cache: 'reload'` so the HTTP cache cannot mix in an older file. Cache first. Older `thread-shell-` caches are deleted on activate. |
| Requests handled | Same-origin `GET` only. A navigation gets `index.html` (routes live in the hash). A listed shell file comes from the cache. Everything else, including Firebase, Jira and GitHub, goes straight to the network and is never cached by the worker. |

**Update flow.** A page never mixes two builds:

```text
load 1 (build A runs)   registration.update() finds B -> B installs, waits
load 2                  page asks waiting B: TAKE_OVER_IF_ALONE
                          B counts window tabs (includeUncontrolled)
                          one tab  -> skipWaiting, answers true
                                      page waits for ACTIVATED, reloads once -> B
                          two+ tabs -> answers false; every tab stays on A
```

- The page asks, not the navigation. During a navigation the worker cannot tell a reload of the only tab from a new tab opened next to another one. A first try that decided at navigation time, and sent a `Refresh: 0` page, left Chrome on a blank page (see M1-RESULTS, M1-C observations).
- The cost: a new build shows on the second load after a deploy, and that load briefly starts the old build before it reloads.
- There is no "new version" banner. v3 has none, and the take-over needs no user action.

### Token storage

| Item | Choice |
|---|---|
| Where | IndexedDB database `thread-connections`, store `connections`, in this browser profile only |
| Records | `jira` → `{recordVersion: 1, site, cloudId, email, token}`; `github` → `{recordVersion: 1, token}` |
| Writes | `durability: "strict"`, so "Connected" shows only after the browser reports the record on disk |
| Damaged or unknown record | Reads as "not connected"; the person connects again |
| In memory | `SecretText`. `toString()` gives `SecretText(hidden)`. `reveal()` is used only to build the `Authorization` header and to write the record. Storage errors use fixed texts that never contain the record. |
| Never | In Firestore, in the request log, in the console, in a URL, in the service worker cache, or in the repository |
| Disconnect | Deletes the record. Saved copies stay, but are hidden until that provider is connected again (v3). |
| Firebase sign-out | Does not touch connections or saved copies, because they belong to the browser profile, not to the Firebase account (decision 4) |
| Fixture mode | In-memory connections and the `thread-external-cache-synthetic` database. A per-tab flag (`sessionStorage`) turns it on, so it never survives into a fresh tab. |

The Note drafts (`thread-local`, `thread-drafts-*`) are separate databases. Clearing connections or saved copies can never touch a Note.

## Consequences

- Any script running on the Thread origin can read the tokens. Thread loads one script, `thread.js`, from its own origin. Third-party code (Firebase SDK, TipTap) is bundled into it at build time, not loaded from a CDN. External text is rendered only as text (ADR-006). A browser extension with access to the page could also read them. That is why the tokens are read-only, short-lived and narrow (O5, O6).
- Anyone with access to the unlocked browser profile can use the tokens. Thread does not encrypt them: the key would sit in the same profile.
- Clearing site data in the browser removes the tokens, the saved copies and the unsynced Note drafts together. The browser shows that as one action.
- A deploy needs the production build with the generated `sw.js`. Hosting must not cache `sw.js` for long; browsers re-check it at least every 24 hours anyway.
- Phase 4 decides the product scope of tokens and saved copies. This storage works for either choice. Phase 4 proposal (provisional, in the owner review queue): they stay per browser profile. A token is typed once per browser, and saved copies never follow the Firebase account. The alternative, per account, would need the tokens or copies in Firestore or a server, which D08 and the token rules rule out.

## Not verified

- With real tokens: whether they survive a reload and whether Disconnect removes them. Checked only with fake values (A8 to A13). The live run's log covers one page load.
- Safari and Firefox. Only headless Chrome 157 ran S1 and S2.
- Firebase hosting headers for `sw.js` (no deploy in this phase).
- Behavior when the browser evicts storage under disk pressure. Thread does not yet ask for persistent storage.
