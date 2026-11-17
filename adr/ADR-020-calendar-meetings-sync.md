# ADR-020: Calendar meetings synced through a Mac uploader

Status: Accepted (2026-11-17, D78). The owner approved the direction and the D row before the spike ("Approve (Recommended)"), then ended the spike with "End spike, build (Recommended)". The plan is `calendar-plan.md` v2.2 and the spike report `calendar-spike-report.md`, both in the session notes. Phase P2 lands this ADR with the Firestore rules, their emulator tests and the shared domain types. The Mac app (P3), the web read (P4), Topic links (P5) and search (P5b) follow; the product documents (01 to 05, ADR-005, ADR-009, README) are updated in P6, after the owner's live check.

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
| Writes | Firestore REST `commit` with the request-time transform for `updatedAt`; a diff against a local index; at most 250 writes per commit; on change, every 15 minutes, on wake and at day change. |
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
