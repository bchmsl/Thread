# M1 results

Real output of the M1 experiments. Every run used synthetic data only.

| Part | Where |
|---|---|
| M1-A (Phase 1): editor, paste, IME, local drafts, Phase 1 E1, E2, E4 | `ADR-003`, "Verification" |
| M1-B (Phase 2): Firebase sync and conflicts, E1–E4, C1–C3 | This file |
| M1-C (Phase 3): offline shell S1–S2; Jira J2–J4 and GitHub G1–G3 on SYNTHETIC fixtures | This file, "M1-C" |
| M1-C live: J1 (Jira CORS) and live Jira and GitHub reads, gate P3-G2 | This file, "M1-C live" |

## M1-B, 2026-11-13

### Environment

| Item | Version |
|---|---|
| Firebase JS SDK | `firebase` 13.3.0 |
| Rules test library | `@firebase/rules-unit-testing` 6.0.0 (tests only) |
| Emulators | `firebase-tools` 15.36.0 through pinned `npx`; Firestore emulator JAR v1.22.0 (cached, accepted by the owner) |
| Java, Node | OpenJDK 21.0.12.1, Node v25.6.1 |
| Browser | Chrome 157.0.8103.79, headless |
| Project | `demo-thread` (no cloud project), Auth on 127.0.0.1:9099, Firestore on 127.0.0.1:8085 |

No `firebase login`, no real project, no deploy, no billing. No JDK or system trust setting was changed.

### Method

- The production bundle was served from localhost. A script drove headless Chrome over the DevTools protocol.
- Each device is its own browser context, with its own IndexedDB, Firestore cache and device id. This is the same as a separate browser profile.
- Offline is DevTools network emulation (`Network.emulateNetworkConditions`). It also fires the page's `offline` and `online` events.
- Sign-in used the spike's test hook, "Sign in as synthetic-user-a" or "-b". It signs in to the Auth emulator with synthetic claims, without the popup.
- The script read the emulator data over the emulator's REST API with the emulator's admin token. The data was cleared once before the run, so the last check sees everything the run wrote.
- Each check waited for the status line's state, not for fixed times. Waits of 2.3 to 3 s were used only to let the 2 s push debounce pass.
- Evidence: the script log, `results.json` and 10 screenshots, kept outside the repository in the session scratchpad.

### Browser checks: 13 of 13 passed

| Check | Steps | Result |
|---|---|---|
| Sign-in, first sync | Open signed out, then sign in | Signed out: "Saved locally / Local only, no sync", banner "Local only". After sign-in: Synced. Server: 1 Note, 1 revision with the sample text. No page errors. |
| E1 offline write, reopen | Sign in, go offline, type, wait past the debounce, close the tab while offline, reopen online in the same profile | While offline: "Saved locally / Syncs when online", banner "Offline", states Saving then Offline, never Synced. The server did not have the edit. Reopened: Synced, the text is there, the server's current version has the edit, 2 revisions. |
| E2 close right after typing | Type, close the tab 0 ms after the last key, reopen | Status at close: Saving. Reopened: Synced, the text is there, 1 current version on the server. |
| E3 server refuses a pending write | Turn on "Make the server refuse writes", go offline, type, go online | "Needs attention / Saved locally, sync failed", banner "Sync failed". States Saving, Offline, Syncing, SyncFailed, never Synced. The text stayed in the editor. The refused write is not on the server. Hook off, Retry sync: Synced, and the server has the text. |
| E4 local save fails | Turn on "Make local saves fail", type, wait 3 s | "Needs attention / Not saved on this device". States Saving, LocalFailed, never Synced. Hook off, Retry save: Synced, text kept. |
| C1 two devices edit offline, keep this device | Two devices of one account, both offline, both type, both online. Device A: Compare versions, Keep this device's version | Both devices: "Changed on two devices". The compare view shows A's and B's text; focus on its heading. Toast: "Kept this device's version. The other one is in Trash." B takes A's version with no prompt. B's version is a Note in Trash with B's text. One current version on the server. |
| C1b keep the other device | Same, then Keep other device's version | Toast: "Kept the other device's version. Yours is in Trash." A shows B's text. A's version is a Note in Trash. |
| C1c keep both | Same, then Keep both | Toast: "Kept both. The other device's copy is a second note." Both devices show A's text. B's version is a second Note, "Synthetic note for the editor spike (other device copy)", not in Trash. |
| Narrow conflict view | C1 at 390 × 844 | Columns stacked, no sideways scroll. The spike header steps aside while comparing (as in v3, the compare view takes the pane) and comes back after resolving. |
| C2 repeated reconnects | 5 rounds: type online, go offline, type, go online | 10 edits made 5 new revisions. No two live revisions share a parent (no duplicates). One current version with the last edit. |
| C3 Trash plus a stale offline edit | A goes offline and types. B moves the Note to Trash. A goes online | The Note stays in Trash on both devices. A's edit is kept inside it as the current version. Restore on B takes it out of Trash on A too. |
| Account isolation | A third device signs in as synthetic-user-b | User B sees only their own Note. The server holds 2 accounts. |
| Emulator holds only app content | Read all emulator data after the run | Root collections: `users` only. 6 Notes, 24 revisions. No field outside the ADR-004 shapes. Every revision text has the synthetic sample's or a check's synthetic wording (0 without). |

### Gradle

Command, run once after the last code change:

```bash
sh firebase/emulators.sh exec "./gradlew build :shared:domain:iosSimulatorArm64Test -Pthread.emulatorTests --continue"
```

Result: `BUILD SUCCESSFUL in 48s`, 102 tasks, exit code 0. ktlint ran as part of `build` and passed.

| Test task | Passed | Contents |
|---|---|---|
| `:shared:domain:iosSimulatorArm64Test` | 64 | NoteSyncer 18, DraftAutosaver 18, RichTextJson 16, RevisionHistory 5, NoteSaveState 4, LinkHref 3 |
| `:shared:domain:jsNodeTest` | 64 | Same common tests. UP-TO-DATE in this run, from an earlier passing run of the same inputs. |
| `:shared:data:jsBrowserTest` | 23 | IndexedDbDraftStore 9, FirestoreRulesEmulator 8, FirestoreNoteSyncEmulator 6 |
| `:webApp:jsBrowserTest` | 16 | RichTextEditorJson 8, NoteEditor 7, SearchShortcut 1 |

New in Phase 2:

- NoteSyncer: first sync, content that is not the person's waits for or yields to the server, Synced only after acknowledgement, max wait, one write in flight, a newer version replaces unchanged content, edits on two devices become a conflict, each D41 choice, devices that discarded each other's versions, refused write keeps the text, a write still waiting after a reload is not written again, unreadable versions pause writing, Trash keeps the content.
- RevisionHistory: leaves, newest first, discarded revisions, missing parents and cycles.
- NoteSaveState: status order and Synced only after acknowledgement.
- DraftAutosaver: 3 rebase tests. IndexedDbDraftStore: the base revision is stored, a Phase 1 record is read without one. NoteEditor: replacing the content is not an edit.
- FirestoreNoteSyncEmulator, through the real SDK and emulator: a push is acknowledged only by the server; a refused write fails and disappears from the local copy; another account cannot listen or write; a resolution discards and copies in one write; a later edit does not take a Note out of Trash; two devices editing offline get a conflict and resolving it loses no text.
- FirestoreRulesEmulator: the 8 rules tests listed in ADR-005.

Kotlin/Native printed "could not open … cache" linker warnings during the iOS test link. They did not fail the build.

### Other checks

| Check | Result |
|---|---|
| Licences | `firebase` and `@firebase/rules-unit-testing` with all their dependencies: 128 npm packages. Apache-2.0 88, MIT 24, BSD-3-Clause 10, ISC 5, 0BSD 1. All permissive. |
| Bundle | `thread.js` 2.16 MiB minified, up from 1.25 MiB in Phase 1. Mostly the Firebase SDK (app, auth, firestore). |
| Secrets | A key-format search (private keys, Google, GitHub, Atlassian, Slack, `client_secret`) over the sources, config and ADRs found nothing. The only key is the emulator placeholder `demo-api-key-for-emulators-only`, which is not a secret. `firestore-debug.log` is git-ignored. |

## Observations

- **Long-offline device.** In an earlier run, a script error left an offline device page open. Its queued write reached the server minutes later, when the script disconnected. The result was a conflict (a second current version), not an overwrite, so the person would have been asked. Not planned as a check, but it is the behaviour ADR-004 intends.
- **E4 and sync.** While local saves failed, sync still pushed the on-screen text to the server. The status kept saying "Needs attention / Not saved on this device", which is true for this device. The text was safer than the status says. Proposal: keep it, because the device storage problem is the one the person must fix.
- **E2 and the draft store.** E2 passed with both stores. It does not show that Firestore alone would pass, and that was not tested. The draft store stays for the reasons in ADR-002: the SDK gives no "stored on this device" signal, and it drops a refused write (E3).

## Not verified

- Safari and Firefox. Only headless Chrome 157.
- Real network faults: slow, flaky or half-open connections. Offline was DevTools emulation only.
- An offline reload. There is no offline app shell yet, so E1 closes offline and reopens online.
- Two tabs of one profile editing the same Note.
- The real Google popup, and any real Firebase project.
- Crash or OS kill right after typing.
- Long-lived Notes with many revisions (listener size, ADR-004).
- C2 for Tasks and links: they do not exist yet. Only Note revisions were checked.

## M1-C, 2026-11-13

Offline app shell (ADR-008), Jira probe (ADR-006) and GitHub probe (ADR-007). Every Jira and GitHub answer in this section is a SYNTHETIC fixture. No request reached Jira or GitHub, and no real token was used. The live results are in "M1-C live" below.

### Environment

| Item | Version |
|---|---|
| Browser | Chrome 157.0.8103.79, headless, fresh scratch profile |
| Node, Python | Node v25.6.1 (check scripts, development server), Python 3.9.6 (`http.server` for the production build) |
| Kotlin, Compose HTML | Unchanged from Phase 2. No new Gradle or npm dependency. `shared:data` now also uses `kotlinx-serialization-json`, which `shared:domain` already used. |

No download, no install, no cloud project, no deploy.

### Method

- `#/m1-connections` has two modes. **Live** (default) uses the browser's `fetch` and the real IndexedDB databases. **Fixtures** answers from SYNTHETIC data in the app: a banner says so, the connections are in memory, saved copies go to `thread-external-cache-synthetic`, and a per-tab switch can make the next answer 401, 403, 404, 429, 500 or "no answer".
- A script drove headless Chrome over the DevTools protocol. In live mode the Atlassian and GitHub hosts were blocked with `Network.setBlockedURLs`, and every request URL was recorded. Live mode was checked only without a token, or with an obviously fake Jira token that no request used: no ticket was opened and "Look up" was not pressed.
- Browser checks 1 (`checks.mjs`) ran against the development build on 127.0.0.1:8765. Browser checks 2 (`s1s2.mjs`) ran against the production distribution on 127.0.0.1:8766, where the service worker exists.
- For S2, the script made three builds, A, B and C, from the same Gradle output. Each had a marker in its HTML, script and styles and its own build id in `sw.js`, so every check could tell which build each part came from.
- Evidence: script logs and 9 screenshots, kept outside the repository in the session scratchpad.

### Browser checks 1: 60 of 60 passed (`#/m1-connections`)

| Group | Checks | Result |
|---|---|---|
| Live, no token | A1–A6, A14 | Live is the default. Both providers "Not connected", with the v3 empty states. Token fields are password inputs with autocomplete off, outside any `<form>`. An empty Jira form shows 4 field errors. No request to Jira or GitHub. |
| Live, fake token stored | A7–A13 | Save shows "Connected" and empties the token field. The record is in IndexedDB `thread-connections`, not in `localStorage`, `sessionStorage` or the page text. It survives a reload with the field still empty. Disconnect deletes it. |
| Fixture mode | B1–B3 | Banner and synthetic connections. The mode flag is in `sessionStorage` only. "Delete saved copies" empties the saved list. |
| GitHub lists and detail | G1a–d, G2a–g | "Mine" has 2 rows, the draft is marked. Arrow keys move between tabs and focus follows. Reviewers: one per person, re-requested shows Requested, team listed. Indicators: Approved with CI running; on #13 CI failing and changes requested. Description is plain text. No outbound link in fixture mode. Focus moves to the title once it exists. |
| GitHub problems | G3a–d | A 403 with remaining 0 reads as rate limited. The request log shows readable rate-limit headers, endpoint templates without ids, owners or keys, and a "No answer" row. |
| Jira rich text | J2a–i | Headings render small, never as page headings. Unsupported content shows the v3 placeholder. HTML inside ADF text stays text. A `javascript:` link becomes text; an `https` link is kept. Code blocks, an ordered list starting at 3 and a task list render. Comments, sprint and labels show. Saved badge and saved list row. Focus moves to the title once it exists. |
| Jira saved copies | J3a–b | Refresh keeps the saved copy on screen, then shows "Updated". After a reload the saved ticket shows at once, then refreshes. |
| Jira problems | J4a–k | 401 "access expired" with the copy kept, and "Update the token" moves focus to Connections. 403 "refused access". 500 "Refresh failed (http-500)". No answer: "did not answer … browser blocked". Offline with a copy: Offline banner, copy kept; without: "Not saved on this device"; no request sent. 404 "Ticket not found" and the copy deleted. 429 with Retry-After 120: "about 2 minutes", and no request inside the limit. |
| Narrow, 390 px | C1–C4 | No sideways scroll, also with a ticket open. Opening a detail hides the list and shows a back link; back shows the list. |
| Whole run | D1–D2 | No request left 127.0.0.1. No console errors. |

### Browser checks 2: 11 of 11 passed (offline shell, production build)

| Check | Result |
|---|---|
| S1a | The first load installs the worker. Its cache holds exactly the 5 shell files of build A (`index.html`, `thread.js`, 3 style sheets). |
| S1b | Signed out, a typed line is stored on this device ("Local only"). |
| S1c | Server stopped and the tab offline: a reload shows the app, all three parts from build A, controlled by the worker. |
| S1d | The typed line is in the editor after the offline reload. |
| S1e | `#/m1-connections` also opens offline. |
| S2a | Build B on the server: the next reload still runs A everywhere while B installs and waits. |
| S2b | The reload after that runs B in HTML, script and styles. Only B's cache is left. |
| S2c | Build C waiting, two tabs open: both tabs stay on B, also after a reload. |
| S2d | Second tab closed: the next reload runs C in all three parts. Only C's cache is left. |
| S0 (2 checks) | No request left 127.0.0.1:8766. No console errors. |

### Gradle

Command, run after the last code change:

```bash
./gradlew ktlintCheck allTests :webApp:jsBrowserDistribution --continue
```

Result: exit code 0. ktlint passed. Tasks whose inputs had not changed since an earlier passing run were UP-TO-DATE.

| Test task | Passed | New in Phase 3 |
|---|---|---|
| `:shared:domain:jsNodeTest` | 87 | ExternalLoader 12, PullRequestStates 6, ExternalIds 5 |
| `:shared:domain:iosSimulatorArm64Test` | 87 | Same common tests |
| `:shared:data:jsBrowserTest` | 49 | JiraIssueRepository 13, GitHubRepositories 10, AdfMapper 8, RecordingTransport 4, KeyValueConnectionStore 3, IndexedDbKeyValueDatabase 2 |
| `:webApp:jsBrowserTest` | 16 | None |

After the docs were written, `./gradlew build --continue` (the plan's acceptance command, with tests and ktlint) also ran: `BUILD SUCCESSFUL in 12s`, 107 tasks, 12 executed, 95 up to date.

The 14 Firestore emulator tests need `-Pthread.emulatorTests` and were not run in Phase 3. Phase 3 did not change Firestore code.

### Other checks

| Check | Result |
|---|---|
| Secrets | The same key-format search as M1-B over all 265 files git would track, plus the production `thread.js` and `sw.js`: no key or token. The only hits are the words `client_secret` in the ADRs that describe the search. |
| Bundle | `thread.js` 2.50 MiB minified, up from 2.16 MiB. `sw.js` 3.4 KB. |
| Visual review | Screenshots of the fixture and live screens, wide and at 390 px, reviewed against the v3 patterns. The token form is the spike-only deviation agreed at P3-G1. |

### Observations

- **Hidden headless pages.** Headless Chrome renders only one page at a time. Pages left open by an earlier run got no animation frames, so Compose never redrew them, and clicks looked dead. Not an app bug. The scripts now close old pages and check that the page under test is the visible one.
- **First S2 design failed.** The first worker decided at navigation time whether to switch builds, and answered with a small page holding `Refresh: 0`. Chrome stayed on that blank page. During a navigation the worker also cannot tell a reload of the only tab from a new tab opened next to another one. ADR-008's "page asks" flow replaced it, and S2 then passed.
- **Focus timing.** Focus went to the ticket or pull request title before the title existed, on the first open of an item. Fixed: focus waits until the content is on screen (J2i, G2g).
- **A new build shows on the second load after a deploy**, and that load briefly starts the old build. This is the cost of never mixing builds (ADR-008).

### Not verified

- J1 and every live answer: see "M1-C live".
- Safari and Firefox. Only headless Chrome 157.
- A real network outage. Offline was DevTools emulation plus a stopped local server.
- Hosting headers for `sw.js`. No deploy.

## M1-C live, gate P3-G2, 2026-11-13

### Method

- The owner made a Jira API token and a GitHub token. The P3-G2 instructions asked for a scoped, read-only Jira token and a fine-grained GitHub token; which kinds were actually made was not reported. The owner typed both into the production build at `http://127.0.0.1:8780/#/m1-connections` in their own desktop Chrome 157, in live mode. No token, site, Cloud ID, ticket key, repository or content reached the worker session, the repository or a log.
- The owner pressed "Look up", opened tickets, opened both pull request lists and one pull request, then pressed "Copy results". The orchestrator session passed the log on unchanged.
- The owner said the two Jira 404 rows came from mistyped ticket keys.
- The log holds only endpoint templates, statuses, durations and readable rate-limit headers. It is copied below as received. The `＠` in the search rows is a full-width sign, so a pasted log never mentions a GitHub user.

```text
Thread M1-C request log. Origin http://127.0.0.1:8780. Mode: live. Browser: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36
```

| Time | Provider | Request | Status | ms | Online | Readable headers |
| --- | --- | --- | --- | --- | --- | --- |
| 17:36:35 | Jira | GET /_edge/tenant_info | No answer | 227 | yes | None |
| 17:37:02 | GitHub | GET /search/issues (author:＠me) | 200 | 1736 | yes | x-ratelimit-limit=30, x-ratelimit-remaining=29, x-ratelimit-reset=1794577083, x-ratelimit-used=1, x-ratelimit-resource=search, x-oauth-scopes |
| 17:37:20 | GitHub | GET /search/issues (review-requested:＠me) | 200 | 917 | yes | x-ratelimit-limit=30, x-ratelimit-remaining=28, x-ratelimit-reset=1794577083, x-ratelimit-used=2, x-ratelimit-resource=search, x-oauth-scopes |
| 17:39:14 | GitHub | GET /search/issues (author:＠me) | 200 | 1659 | yes | x-ratelimit-limit=30, x-ratelimit-remaining=29, x-ratelimit-reset=1794577215, x-ratelimit-used=1, x-ratelimit-resource=search, x-oauth-scopes |
| 17:40:45 | Jira | GET /rest/api/3/field | 200 | 669 | yes | x-ratelimit-limit=350, x-ratelimit-remaining=349 |
| 17:40:46 | Jira | GET /rest/api/3/issue/{key} | 404 | 294 | yes | x-ratelimit-limit=400, x-ratelimit-remaining=397 |
| 17:40:52 | Jira | GET /rest/api/3/issue/{key} | 404 | 313 | yes | x-ratelimit-limit=400, x-ratelimit-remaining=399 |
| 17:41:16 | Jira | GET /rest/api/3/issue/{key} | 200 | 418 | yes | x-ratelimit-limit=400, x-ratelimit-remaining=397 |
| 17:41:16 | Jira | GET /rest/api/3/issue/{key}/comment | 200 | 496 | yes | x-ratelimit-limit=350, x-ratelimit-remaining=349 |
| 17:41:27 | GitHub | GET /repos/{owner}/{repo}/pulls/{number} | 200 | 1097 | yes | x-ratelimit-limit=5000, x-ratelimit-remaining=4268, x-ratelimit-reset=1794578384, x-ratelimit-used=732, x-ratelimit-resource=core, etag, x-oauth-scopes |
| 17:41:28 | GitHub | GET /repos/{owner}/{repo}/pulls/{number}/reviews | 200 | 863 | yes | x-ratelimit-limit=5000, x-ratelimit-remaining=4267, x-ratelimit-reset=1794578384, x-ratelimit-used=733, x-ratelimit-resource=core, etag, x-oauth-scopes |
| 17:41:29 | GitHub | GET /repos/{owner}/{repo}/commits/{sha}/status | 200 | 571 | yes | x-ratelimit-limit=5000, x-ratelimit-remaining=4266, x-ratelimit-reset=1794578384, x-ratelimit-used=734, x-ratelimit-resource=core, etag, x-oauth-scopes |
| 17:41:30 | GitHub | GET /repos/{owner}/{repo}/commits/{sha}/check-runs | 200 | 655 | yes | x-ratelimit-limit=5000, x-ratelimit-remaining=4265, x-ratelimit-reset=1794578384, x-ratelimit-used=735, x-ratelimit-resource=core, etag, x-oauth-scopes |

How to read it: "Readable headers" lists the headers the page could read. The request log asks for `retry-after`, `x-ratelimit-*`, `ratelimit-reason`, `beta-retry-after`, `link`, `etag`, `x-accepted-github-permissions`, `x-oauth-scopes` and `x-github-sso`. A header missing from a row was either not sent or not exposed to the page (CORS); the log cannot tell which.

### Jira results

| Check | Result |
|---|---|
| **J1 CORS through the gateway** | **Passed** with the owner's token. `/field`, `/issue/{key}` and `/issue/{key}/comment` on `api.atlassian.com` answered 200 to a cross-origin page sending Basic auth. A request with an `Authorization` header needs a CORS preflight, so the preflight passed too. Gate P3-G3 is not needed. |
| Error answers are readable | The two 404 answers reached the page with their status. So the gateway adds CORS headers to error answers too, and the app can show "Ticket not found" instead of "No answer". Jira documents 404 for "not found or the user does not have permission to view it" (OpenAPI spec, checked today). |
| Cloud ID look-up | `/_edge/tenant_info` on the site URL: no answer after 227 ms while online. That is how a CORS block shows, but without the console line it is not proven. The manual Cloud ID path worked: every later gateway call used the typed Cloud ID. |
| Sprint field cache | One `/field` request for three ticket opens in 31 s. The once-a-day cache (ADR-006) worked. |
| Comments only after the ticket | No comment request after the two 404s; one after the 200. As designed. |
| Rate-limit headers | `x-ratelimit-limit` and `x-ratelimit-remaining` are readable on 200 answers. The limit differs by endpoint: 350 for `/field` and `/comment`, 400 for `/issue`. Atlassian documents per-endpoint rate-limit scopes, but its published per-second figures (100 for most GETs, 150 for get issue) do not match 350 and 400, so the unit of these values is not known. Thread does not use them. `Retry-After`, `X-RateLimit-Reset` and `RateLimit-Reason` are documented as sent only with 429, so their readability is still unknown. |
| Speed | 294 to 669 ms per request. |

### GitHub results

| Check | Result |
|---|---|
| **G1 lists** | Passed. Both searches answered 200. GitHub accepted the `review-requested:@me` qualifier, which the docs only imply. Whether the rows were the right pull requests was not reported. |
| **G2 detail** | Passed. Pull request, reviews, combined status and check runs all answered 200. |
| **G3 permissions and limits** | All four detail calls worked with this token, so the CI state could use both statuses and check runs. Search and core limits are separate: `x-ratelimit-resource` says `search` or `core`, which matches the two `RateLimitGate`s. By 17:39 a new search window had started (new reset time, remaining back to 29). |
| Readable GitHub headers | `x-ratelimit-limit`, `-remaining`, `-reset`, `-used`, `-resource`, `etag` (core only) and `x-oauth-scopes`. GitHub's CORS page lists only `limit`, `remaining` and `reset` of the rate-limit headers, so the real server exposes more than the docs show. `link` did not appear (no answer had a next page). `x-accepted-github-permissions` and `x-github-sso` did not appear. `retry-after` did not appear; GitHub documents it for secondary-limit errors, and none happened. |
| Shared core budget | The first core request already showed `x-ratelimit-used=732`. This page load sent only 4 core requests, so the rest came from other use of the same account in that hour. GitHub documents that a token's requests count toward the person's limit of 5,000 an hour, shared with OAuth and GitHub Apps acting for that person. Which tools used it is not known. |
| Speed | Search 0.9 to 1.7 s. One pull request: four requests one after another, 3.2 s in all. |

### Why check runs answered 200: not determined

- The plan expected that check runs might not be readable with a fine-grained token (ADR-007).
- What the docs say, checked today: GitHub's page of permissions for fine-grained tokens has no "Checks" section and lists the check-runs endpoint under no permission. The endpoint's own page says only that classic tokens and OAuth app tokens need the `repo` scope on a private repository.
- What the log cannot show: whether the token was fine-grained or classic, whether a "Checks" permission was offered and granted, and whether the repository is public. `x-accepted-github-permissions`, which would name the permission, is not readable by the page.
- Open possibilities, none confirmed: the repository is public; the token is a classic token with the `repo` scope (documented to work); or the fine-grained token has a permission that the docs do not list for this endpoint. The owner can settle it from the token's settings page on GitHub and the repository's visibility. No token value is needed.

### Leak check after the live run

| Check | Result |
|---|---|
| Key formats | The M1-B search over all 265 files git would track: no key or token. |
| Live data in the repository | No token, Cloud ID, ticket key, repository name or content from the live run. The only real Jira site name in the repository is the owner's decision D07 in `README.md`, `05-integrations.md` and `06-roadmap-decisions.md`, from before Phase 3. |
| Firestore | The Jira, GitHub, connection and HTTP code imports no Firebase module, so external content has no path to Firestore. The emulator data was not read: no emulator ran in Phase 3. |

### Not verified live

- 401, 403, 429 and "rate limited" answers from either provider. They are checked on fixtures only.
- Whether the real ticket and pull request looked right on screen: the log has no content, and the owner has not reported on it yet. No check covered real ADF.
- An organization-owned token waiting for approval.
- Paging (`link`) and `Retry-After` readability.
- Offline and saved-copy behavior with real data. Checked on fixtures only (J3, J4f, J4g).
- Safari and Firefox.
