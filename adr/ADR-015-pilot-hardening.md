# ADR-015: Pilot hardening (CSP, rendering, tokens, revoked access)

Status: Proposed (2026-11-13). Written in Phase 12 while the owner was away. Built on ADR-005 (auth and rules), ADR-006 and ADR-007 (Jira and GitHub access), ADR-008 (offline shell and token storage), ADR-013 and ADR-014. Checked with the Phase 12 browser checks on the production build (`webApp/build/dist/js/productionExecutable`) against the emulators, on SYNTHETIC data only. Google sign-in, real Jira and real GitHub were not reachable, so the hosts for them are not verified. The owner review is queued (owner-review-queue.md, Q57 onwards).

## Context

- Q7 (provisional): the pilot runs as the local production build on 127.0.0.1. No Hosting deploy, so Thread cannot set HTTP response headers. The local server sends none.
- 01 and 04: render untrusted content safely, tokens only on the device, "revoked external access: stop retry loops; apply a defined cache cleanup policy".
- The editor (ADR-003) already turns off Tiptap's injected CSS (`injectCSS: false`) and copies the ProseMirror base rules into `thread.css`.

## Decision

### Content Security Policy

A `<meta http-equiv="Content-Security-Policy">` in `index.html`, because there are no response headers:

| Directive | Value | Why |
|---|---|---|
| `default-src` | `'self'` | Everything else stays on the app's origin. |
| `script-src` | `'self' https://apis.google.com` | One bundle (`thread.js`), no inline or evaluated script. `apis.google.com` loads the Google sign-in popup helper. |
| `style-src` | `'self'` | Only `tokens.css`, `app.css`, `thread.css`. No inline `<style>` and no `style="…"` attributes. |
| `img-src` | `'self' data: https://www.google.com` | The CSS check marks and the empty favicon are `data:` URLs. Firestore's connection test loads a 1-pixel image from `www.google.com` when the network fails (seen in the Phase 12 offline check). Thread shows no remote images itself. |
| `font-src`, `worker-src`, `manifest-src` | `'self'` | System fonts; `sw.js`. |
| `connect-src` | `'self'`, `firestore`, `identitytoolkit`, `securetoken`, `www.googleapis.com`, `api.atlassian.com`, `api.github.com`, `127.0.0.1:9099`, `127.0.0.1:8085` | Firebase Auth and Firestore, read-only Jira and GitHub, and the local emulators. |
| `frame-src` | `https://*.firebaseapp.com https://apis.google.com` | The Firebase Auth helper frame for the sign-in popup. |
| `object-src`, `base-uri` | `'none'` | No plugins; no `<base>` tricks. |
| `form-action` | `'self'` | Forms never post anywhere else. |

- Styles that depend on data (a Topic's color `--tc`, the hex sticker `--hc`, the progress share `--p`, three small paddings) are set through the CSSOM with Compose `style { … }`, never with a `style` attribute. The CSP blocks attributes; CSSOM writes are allowed. Phase 12 found 15 `attr("style", …)` calls that the CSP blocked (Topic colors and progress bars did not show) and moved them to `style { … }`.
- `frame-ancestors`, `X-Content-Type-Options` and `Referrer-Policy` cannot be set from a meta tag. They belong in response headers once Thread is hosted (queued).

### Rendering untrusted content

- Jira rich text is built as DOM elements from the document tree, never from HTML strings (ADR-006). GitHub descriptions are plain text (ADR-014).
- The only `innerHTML` writes are the constant icon and brand SVGs that ship in the bundle.
- Links in Notes keep only `href`, are not opened on click inside the editor, and must pass the link check (`https:`, `http:`, `mailto:`; no spaces or control characters). Links to Jira and GitHub open in a new tab with `rel="noopener noreferrer"`.

### Keyboard and screen readers

- Each Refresh button has an accessible name that says what it refreshes: "Refresh the list", "Refresh this ticket", "Refresh this pull request". The Jira and Pull requests pages show a list and an open record side by side, each with a Refresh. The visible word stays "Refresh" (v3), and the name starts with it, so speech input still finds the button.

### Tokens

- Jira and GitHub tokens are saved only in IndexedDB on this browser profile (`connections`, ADR-008). They never go to Firestore, localStorage, sessionStorage, logs or the console. sessionStorage holds only the emulator's synthetic-mode flag; localStorage holds only a random device id.
- The only requests that carry a token go to `api.atlassian.com` and `api.github.com`, method GET.

### Revoked or expired access (401)

- A 401 keeps every saved copy readable and shows "access expired" with Reconnect (defined cache policy: copies stay until "Delete saved copies", Disconnect hides them, a 404 forgets one copy).
- After a 401 the five-minute automatic refresh stops for that provider. Nothing goes out on its own until the person saves a token again, opens another item, or presses Refresh. Each of those loads once (one ticket or pull request, one list); there is no retry loop.
- The Jira and Pull requests pages show the banner of the view that has a problem, so a list that got a 401 still says why while the open ticket or pull request is fine.

### What ships

- The production folder also holds `thread.js.map` (source map). It contains the app's own source only, no secrets. Kept for the pilot so errors can be read; the service worker does not cache it.

## Consequences

- The pilot build runs with no inline script or style. A later feature that needs inline styles must use the CSSOM, or the CSP must change (and this ADR with it).
- Google sign-in hosts are a best reading of the Firebase documentation. If the real project uses a custom auth domain, `frame-src` must list it. Phase 13 checks real sign-in.
- The emulator hosts in `connect-src` are unused in a real build. They allow only 127.0.0.1 on two ports.
- After a 401 the copies on screen can get old without new requests. The page says so (access expired, saved copy time).
- Edge case: if the person presses Refresh more than 10 times in a minute after a 401, Thread's own GitHub search budget (ADR-014) answers "limiting requests" instead of "access expired". No request goes out while it lasts. When it ends, one list refresh may go out, gets the 401 again, and stops.

## Alternatives considered

- `style-src 'unsafe-inline'`: keeps `style` attributes working but weakens the policy for no need.
- A build-time CSP without emulator hosts: cleaner, needs a second index template or a Gradle step. Queued for when Thread is hosted.
- Stopping all requests after a 401, including opening items and Refresh: safest for the provider, but the person could not check whether a fixed token works without Settings. Not chosen.
