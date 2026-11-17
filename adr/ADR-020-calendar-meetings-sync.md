# ADR-020: Calendar meetings synced through a Mac uploader

Status: Accepted (2026-11-17, D78). The owner approved the direction and the D row before the spike ("Approve (Recommended)"), then ended the spike with "End spike, build (Recommended)". The plan is `calendar-plan.md` v2.2 and the spike report `calendar-spike-report.md`, both in the session notes. Phase P2 lands this ADR with the Firestore rules, their emulator tests and the shared domain types. P3 adds the Mac app (section "Mac app"). P4 adds the web read (section "Web"). P5 adds Topic links and P5b search (sections "Topic links (P5)" and "Search (P5b)"). P6 updates the product documents (01 to 06, ADR-005, ADR-009, README) and records the owner's live check: Today matches Outlook on the Mac and the phone. A decline, a reply change, start at login and a night running are still open (see Checks, "P6").

## Context

- The owner wants today's work meetings on Today, synced to every device (owner change #19): "Wait, the calendar should be synced to firebase, we use mac only to retrieve the events. It should be synced across devices thoroughly".
- The work calendar is Outlook/Exchange. Microsoft Graph needs admin consent, so it is not used. The calendar already syncs into Apple Calendar on the owner's Mac.
- Thread is a web app with no server and no Blaze billing (ADR-006). Firestore rules are the security boundary (ADR-005).
- The rule "Firebase stores app-owned content; do not upload external cache contents accidentally" needs a deliberate, narrow exception. The owner approved the new wording (plan section 1b); it lands in CLAUDE.md with P2.

## Decision

| Part | Choice |
|---|---|
| Source | EventKit, one picked calendar, read-only. Full access is the only level that can read events. |
| Uploader | "Thread Calendar", a Swift menu bar app in `macCompanion/` (SwiftPM, macOS 14+, Apple Development signing, login item). It never changes events. |
| Auth | Google "Desktop app" OAuth client (loopback redirect, PKCE, `openid email`), then Firebase `signInWithIdp`, the refresh token in the Keychain. No Firebase SDK. The client ID and secret come from the untracked `macCompanion/oauth-desktop.properties` (ignored by git). |
| Writes | Firestore REST `commit` with the request-time transform for `updatedAt`; a diff against a local index; at most 250 writes per commit; on change (30 s debounce), every 15 minutes, on wake, at day change and when the network is back. A pass with no changes writes nothing; `meetingSource/main` is rewritten at most every 30 minutes as a heartbeat. |
| Window | From the start of yesterday to the end of today + 14 days, in the workspace zone. The Mac app deletes what leaves it. |
| Not stored | Declined and cancelled meetings; attendees, organizer, notes and e-mail addresses. |
| Ids | Meeting id and series key: SHA-256 of the calendar ids, 32 lowercase hex, so no raw calendar id is stored. A moved occurrence keeps its series key (the `/RID=` suffix is stripped from a recurring id's end; spike U7). |
| Retention | The Mac app deletes; the rules refuse timed meetings outside the window. No TTL policy: it needs billing. |
| Web | Reads `meetings` and `meetingSource`, never writes them; writes only `topicMeetingLinks`. `MeetingStore` port, `FirestoreMeetingStore`, the Firestore cache as for all synced data. No CSP or `sw.js` change. |

### Collections (`users/{uid}/…`, owner only)

| Path | Writer | Shape |
|---|---|---|
| `meetings/{32 hex}` | The Mac app | `title` (≤ 300), `allDay`, `start` and `end` (timed) or `firstDay` and `lastDayExclusive` (all-day, `YYYY-MM-DD`), `response` (`accepted`, `tentative`, `pending`, `none`), optional `location` (≤ 300), optional `joinUrl` (≤ 2048, allowlisted), `seriesKey` (32 hex), `updatedAt`, `schemaVersion` 1 |
| `meetingSource/main` | The Mac app | `state` (`ok`, `noAccess`, `noCalendar`, `paused`), optional `calendarTitle` (≤ 100), optional `fromDay` and `toDay` (the days the last successful pass covered, both included), `zone`, `uploaderId` (≤ 64 each), `appVersion` (≤ 20), `lastSuccessAt` (null or a time not after the write), `updatedAt`, `schemaVersion` 1 |
| `topicMeetingLinks/{topicId}_{seriesKey}` | Thread web | `topicId` (1 to 128), `seriesKey`, `linked`, optional `title` (≤ 300, a snapshot kept after the meeting leaves the window), `updatedAt`, `schemaVersion` 1 |

### Rules (`firebase/firestore.rules`)

- **Timed window.** `end >= start`, at most 14 days long, `end` after request time − 3 days, `start` before request time + 16 days. All-day meetings have no window check (rules cannot compare day strings with `request.time`); the Mac app's window and deletes still apply.
- **Join link.** `https://` and a host on the allowlist, then nothing or `/`, `?` or `#`: `teams.microsoft.com`, `teams.live.com`, `zoom.us` and its subdomains, `meet.google.com`, and subdomains of `webex.com`. So `http`, a lookalike host, a `user@` part and a port are refused. The web checks the same pattern again before it shows Join (`joinServiceOf`).
- **Deletes.** The owner can delete meetings and `meetingSource/main`: the first content collections with deletes (before, only Note revisions and the vault). Topic links are never deleted; unlinking writes `linked: false`.
- **Not enforceable.** The rules cannot tell the Mac app from the browser: both are the same uid and provider, and custom claims need a server. "The Mac app is the only writer of meetings" is a code rule, backed by tests.
- **Sizes.** The emulator's `size()` counts UTF-16 units (spike U9: 300 Georgian letters pass, 200 emoji refused), so the Mac app caps text at 300 UTF-16 units. Production is assumed to count the same; not proven.

### Domain (`thread.domain.calendar`, no platform types)

| Type or rule | Holds |
|---|---|
| `Meeting`, `MeetingTime` | Id, series key, title (blank shows "(No title)"), Timed (two wall-clock times) or AllDay (two days), response, location, join link |
| `MeetingSource`, `MeetingLink` | The Mac app's last pass; one Topic's link to a series, id `{topicId}_{seriesKey}` |
| `todayMeetings` | Today's rows: all-day first, then by start, end, title, id; phase Ended, Now, Next or Later; midnight spans marked |
| `nextMeeting` | The status line: "in N min" for the hour before a timed meeting that starts today, "now" for 5 minutes after its start; all-day never |
| `isJoinTime` | Join uses the action style from 10 minutes before the start until the end |
| `todayKnown`, `isMeetingSourceStale` | "No meetings today" only after a successful pass that covered today; the "Is your Mac awake?" hint after 1 hour |
| `meetingsForTopic` | One row per linked series, with its next occurrence or the link's title snapshot |

### Mac app (P3, `macCompanion/`)

| Part | Choice |
|---|---|
| Layout | SwiftPM, no dependencies: `ThreadCalendarKit` (ids, mapper, join links, differ, Firestore and Auth REST, sign-in, Keychain, sync engine, diagnostics), the `ThreadCalendar` app (menu, EventKit reader, triggers), and tests. Ported from the spike's code and its tests. |
| Upload pass | Signed in and online, read the picked calendar, map, diff with the local index. Nothing changed and the source written under 30 minutes ago with the same fields: done, no request. Otherwise GET the source first, then the meeting writes in commits of up to 250, `meetingSource/main` last. |
| Failure states | `noAccess` and `noCalendar` write only the source, with the last good `calendarTitle`, `fromDay`, `toDay` and `lastSuccessAt` (null before any pass); the uploaded meetings stay. |
| One uploader | Each Mac has a random `uploaderId`. If the source names another one written in the last 24 hours, the pass stops ("Another Mac is uploading meetings") until the owner approves a takeover in the menu. |
| Strays | Once a day, after sign-in, and when the index is missing, the pass lists the remote ids: it deletes ids the calendar does not have and writes again those the index has but Firestore lost. |
| Errors | 401: renew the token once, then sign out ("Sign in again"); nothing is deleted. 403: "Upload refused: update Thread Calendar", no retry until Sync now. Other failures wait 1, 5, then 15 minutes. Offline: skip. |
| Zone | `prefs/main.timeZone`, read at most once an hour; `Asia/Tbilisi` when missing or invalid, as on the web. |
| Stop syncing | Deletes every uploaded meeting and the source, clears the index, then signs out. Topic links stay. If a delete fails, the account and index stay. |
| Config | `scripts/build-app.sh` reads the Web API key and project id (`firebase/web-config.properties`) and the Desktop client (`macCompanion/oauth-desktop.properties`) without printing them, into `Config.plist` inside the bundle in the cache build folder. No values: an emulator-only variant with its own bundle id. |
| Keychain | The Firebase refresh token, uid and e-mail as one generic password item in the login keychain, not synchronizable. The login keychain unlocks at login; the data-protection keychain would need a provisioning profile (spike U11). |
| Diagnostics | `~/Library/Application Support/ThreadCalendar/`: the last pass's counts and codes, and an event log with keys and values limited to `A-Z a-z 0-9 _ . : -`. No titles, times, links, e-mail, uid or tokens. |
| Signing | The Apple Development identity, no hardened runtime, unsandboxed. A build fails if an EventKit write call appears. |

### Web (P4, `webApp`)

| Part | Choice |
|---|---|
| Read | `FirestoreMeetingStore` listens to `meetings` and `meetingSource/main` and never writes them. Times become wall-clock times in the workspace zone. A source with another `schemaVersion` or an unknown state: "Update Thread". A meeting this version cannot read is left out; a join link off the allowlist is dropped and the meeting kept. A missing source read from the cache waits for the server before it says Not set up. A listener error ends in Unavailable (the Firestore error code, never shown raw); it listens again at the next network change. |
| Today | The Meetings card heads the right column once meetings are set up. While a page loads or after a read error it shows as last seen on this device (`thread.meetings.seen`, not synced), so it neither flashes in nor disappears; "Hide empty sections in Today" never hides it (D77). One status line in the card: a problem on the Mac first, then a stale upload with its time, then "No meetings today." only after a pass that covered today. The last upload's time closes the card. |
| Rows | Time first (start over end, or "All day"), the title on its own line, then Now or Next with how long until it starts or ends, "Started yesterday" or "Ends tomorrow", and "Tentative" or "Not answered", then the place. Ended rows are greyed. Each row is one button whose name is the whole sentence; Join sits beside it. |
| Meeting card | `#/today?meeting=<id>` in the card host (D70), read-only, no full page: title, when, where, your answer, Join. An id that is not there shows "That meeting is no longer in Thread." A link waits for the first read. |
| Join | Only for a link that passes `joinServiceOf`; "Join (Teams)" in a new tab with `noopener noreferrer`. The link is never shown as text: it may hold a passcode. Action style from 10 minutes before the start to the end. |
| Status line | The chip names the next meeting: "Standup in 12 min" in the hour before, "Standup now" for 5 minutes after the start; "12m" or "Now" on a narrow line. It opens the Meeting card. Not a live region; its name is a full sentence. |
| Layout (D79) | Settings > Preferences "Meetings layout" (`meetingsLayout` in `prefs/main`, Vertical by default). Horizontal moves the Meetings card across the page above both columns as a timeline (`meetingTimeline` in `thread.domain.calendar`): 08:00 to 18:00 widened to whole hours for the day's timed meetings, all-day meetings in a strip above, blocks by start and end in rows while what is drawn overlaps (a meeting under an hour is a one-line chip an hour wide, D80), a now line, Join for now and next in the card's head. Same note, footer, rows' sentences and Meeting card. |
| Settings | "Calendar from your Mac": up to date, waiting for the first upload, not updated since a time, no access, no calendar, paused, not set up, or update Thread; the calendar's name, the last upload and the app version. No buttons: syncing starts, pauses and stops on the Mac. |

### Topic links (P5, `webApp`)

| Part | Choice |
|---|---|
| Store | `topicMeetingLinks/{topicId}_{seriesKey}` is a workspace write (`PutMeetingLink`) in `FirestoreWorkspaceStore`, with the pending, saved and refused states of every other write. One link covers the whole series. Linking writes `title`, the series title at that time; unlinking writes `linked: false` and no title; nothing is deleted. A Topic in Trash hides its links, and Restore brings them back. |
| Meeting card | A "Topics" fact: the linked Topics as chips with Unlink, "Not linked to a Topic yet" when there are none, and "Link to Topic", a multi-select picker ("Save links"). Every change has Undo. The footer says "From Apple Calendar on your Mac. Thread does not change it." |
| Today rows | The linked Topics as tags under the title, and "linked to A and B" at the end of the row's name. |
| Topic page and card | A "Meetings" section once meetings are set up on this device, or while the Topic has a link. One row per series: its next occurrence ("Now, until 11:30", "Tomorrow, 09:00", "Today, all day") or "None in the next 14 days", Join when that occurrence is today and not over, and Unlink. A row opens the next occurrence as a card and is marked current. A series gone from the window keeps the stored title. "Link" opens a single-select picker of the series not linked yet: upcoming first by start, then ended ones by title, with "repeats" for a series with more than one occurrence. |
| Targets | The small Link and Unlink buttons keep their look and take a 40 px target. |

### Search (P5b)

| Part | Choice |
|---|---|
| Match | `searchMeetings` (`thread.domain.search`) matches the title or the location, ignoring case, over the meetings on this device (yesterday to 14 days ahead). The join link is never matched or shown. |
| Order | Upcoming and running first by start, then ended ones, the latest first; three per series and six in all. |
| Row | A "Meetings" group after Notes: the title, then when ("Today, 14:00", "Tomorrow, all day") and the linked Topics. A pick, by click or Enter, opens the Meeting card. The placeholder and the footer name meetings. |

## Consequences

- Meetings show on every device, also while the Mac is asleep, as of its last pass, with that time shown.
- Work meeting data, and passcodes inside join links, live in the owner's Firebase project and in browser caches. Only the owner's uid can read them.
- A second client with a full-account token lives on the Mac.
- The rules cannot enforce which client writes; tests and code do.
- The source depends on Apple's Exchange support. Apple Calendar's Exchange sync uses EWS, which Microsoft shuts down on 1 Apr 2027.
- The rules must be deployed before any client ships. Before that, the Mac app's writes and the web's meeting listeners are refused; the web keeps that error inside the Meetings card.

## Alternatives considered

- v1, a localhost memory-only bridge: no cross-device sync.
- The Firebase Apple SDK with Google Sign-In: entitlements, and macOS Auth support is "partial".
- An anonymous delegate identity: little gain on the same Mac.
- Uploading from the web app: needs Thread open.
- A server-made custom token: no server.
- A TTL policy: needs billing.
- Outlook ICS publishing: a public link.

## Checks

- **Spike (2026-11-17):** U0 to U4, U8, U9 and U11 pass; U7 passes with the `/RID=` id fix; cancelled meetings are hidden on Exchange; Zoom join links are found. Moved into the build as acceptance checks: the real sign-in, start at login, wake and day change, a night's energy (P3); declined, a response change, Teams join links (P6). The App Sandbox was not tried; the app ships unsandboxed.
- **P2:** `FirestoreMeetingRulesEmulatorTest` runs every rules-side spike U9 case against these rules in the emulator: the 22 refused meeting shapes and more, UTF-16 sizes, owner updates and deletes, other accounts and signed-out visitors refused, the source's shape and kept `lastSuccessAt`, link ids, and a 250-write commit. The four auth REST cases and the mapper checks of U9 belong to the Mac app's Swift tests (P3). Domain unit tests cover `thread.domain.calendar`.
- **P3:** Swift tests: the spike's tests ported (ids and the `/RID=` recipe, mapper, join links, differ, encoding, PKCE, loopback), U9's four mapper checks, the 30 s debounce, the 1/5/15 minute backoff, the sync engine against fakes (a quiet pass makes no request, heartbeat, change and delete, failure states, one uploader and takeover, 401, 403, 429, offline, drift counters in the diagnostics, strays, zone, stop syncing), and parity with the rules' field list and join-link pattern. With the emulators: U9's four auth REST cases and the engine against the committed rules (passes accepted; another user's path and a meeting outside the window refused). The owner's first real run passed: sign-in, the first pass, token renewal, and Today on the web showing the same meetings (P6 entry below). Start at login after a real log-in and a night running are still open.
- **P4:** Unit tests: the store's mapping (schema versions, unknown states, bad ids, all-day and timed, an end before the start, join links off the allowlist), the card's texts and row sentences, the notice order, the card's visibility while loading, the Settings status, and Today's sections with the Meetings card under "Hide empty sections in Today". With the emulators: `FirestoreMeetingStoreEmulatorTest` reads and follows a source and meetings through the committed rules, Not set up, and another account refused. In a browser against the emulators with SYNTHETIC meetings, an empty Firebase config and a fixed clock and zone: 89 checks at 1440 and 390 px, plus the layout at 1199, 1000 and 899 px (states, rows, Join attributes, the chip, the Meeting card from a row, the chip and a reload of its link, the keyboard, Settings, a newer schema, removal, no console or CSP errors, no request off the host). Still open: live meetings from the owner's Mac (P6), Safari, Firefox and a real phone.
- **P5:** Unit tests: links (`MeetingLinksTest`: one document per Topic and series, the stored title, unlink and Undo with nothing deleted, a Topic in Trash hiding its links, a series gone from the window, a refused link unconfirmed until the stored link shows it), search (`MeetingSearchTest` in the domain: title and location ignoring case, never the join link, the order, three per series and six in all), a pending link and unlink kept across reloads and a damaged series key dropped (`WorkspaceWriteJsonTest`), the row's Topics and the Topic's next meeting (`MeetingTextTest`), and the search palette's rows and Enter (`MeetingSearchTest` in `webApp`). With the emulators: `FirestoreMeetingLinksEmulatorTest` links a series to two Topics through the committed rules, unlinks offline and syncs, keeps the title, and follows the Topic into Trash. In a browser against the emulators with SYNTHETIC meetings, an empty Firebase config and a fixed clock and zone: 92 checks at 1440 and 390 px, with the layout at 1199 and 899 px (the card's Topics, both pickers, chips and Unlink with Undo, the Today tags, Trash and Undo, the Topic page section and rows, a row opening its next occurrence, closing the card every time, Escape from a picker, search and Enter, a series gone from the window, 40 px targets, contrast, no console or CSP errors, no request off the host). Closing a card now runs once: Chrome can force a dialog closed on Escape, and the second `history.go(-1)` left the Topic page. Still open: live meetings (P6), Safari, Firefox and a real phone.
- **P6 (2026-11-17):** 01 to 06, ADR-005, ADR-009 and README describe meetings. From the Mac app's diagnostics (counts and codes only): the owner's real sign-in succeeded and its first pass uploaded 46 meetings; the login item is enabled; quiet passes write nothing and the heartbeat writes one document; changes reached Firestore: one calendar change as one upsert, another as one delete, and a Sync now pass as 12 upserts; writes still succeed more than an hour after sign-in, so the token renews; `hiddenDeclined` is 0 in every pass so far.
  - **Owner's live check (2026-11-17):** Today matches Outlook on the Mac and the phone. The owner: "Yes, they match". Pass. It also shows that the Mac app and the web use the same account.
  - **Teams Join: not applicable.** The owner: "I don't have teams meeting and I will probably never have". Teams links stay on the allowlist, checked by unit tests only. Join and Safe Links unwrapping are not checked on real meetings.
  - **Declined, and not answered to accepted (open):** no longer owner steps. The diagnostics close them when one happens on its own: `hiddenDeclined` above 0 and the meeting gone from Thread; a pass with one upsert, the same meeting count and one fewer `pending`. Not seen yet: `hiddenDeclined` is 0 in every pass, no upsert left the meeting count unchanged, and the latest pass counts 0 `tentative` and 0 `pending` meetings.
  - **Start at login (open):** only the first launch is logged; the owner's next log-in has not happened yet.
  - **One night running (open):** a pass after a wake and after midnight, CPU under 0.1 % over the night.
  - **Later, not in this plan:** a Focus timer warning when a meeting starts during focus (Q7) and the App Sandbox (U12 not run).
