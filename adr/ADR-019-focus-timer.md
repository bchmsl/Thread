# ADR-019: Focus timer

Status: Proposed (2026-11-16). Phase 14 builds the Focus timer (Pomodoro, D25, D34, D45). The owner answered the plan's three questions with "Approve, all A" (F1 A, F2 A, F3 A; D56) and left the plan's defaults FD1 to FD9 as written. The plan is `focus-timer-plan.md` in the session notes. The details below are the worker's choices and are queued for review (owner-review-queue.md, Q104 onwards). Checked with unit tests, emulator rules and store tests, and headless Chrome on the emulators, with SYNTHETIC data only.

## Context

- 01 and 02 ask for a compact Focus control in the status line: 25/5 by default, editable lengths, an optional Task or Topic, start, pause, stop, the time left, and a history. No large card on Today or a Topic (D45, acceptance 10). End messages are in-app only (D27).
- 04 asked for timestamps and pause accounting, not a UI tick count, and left two questions open: what a reload or a closed tab does to a running timer, and who owns a timer when Thread is open on two devices.
- The approved v3 prototype has the status line tool (`tool--focus`, `is-running`) and the panel (`dlg--pop`, `?focus=1`). It kept everything in one local object; its concept badge and demo note are not part of the product.

## Decision

### Owner answers (D56)

| Question | Answer |
|---|---|
| F1. A reload or a closed tab during a period | A: the timer keeps its end time. A period that ended while Thread was closed or hidden counts at its real end time, and Thread says so on return. |
| F2. Thread open on two devices | A: each device has its own timer. The lengths and the history sync. |
| F3. History in the panel | A: the last 4 sessions, as v3. Every session is kept. |

### Model (`thread.domain.focus`, no platform types)

| Type | Holds |
|---|---|
| `FocusSettings` | Focus 1 to 90 minutes, break 1 to 30, defaults 25 and 5. Text that is not a number reads as the default; a number outside the range as the nearest limit (v3). |
| `FocusTarget` | `task` or `topic` and its id (1 to 128 characters). |
| `FocusRun` | `runId`, phase (Work or Break), running or paused, `endsAt` while running, the time left while paused, the planned lengths, `startedAt` and the target. |
| `FocusDevice` | This device's run, or none (Ready), and the last picked target (FD4). |
| `FocusSession` | id `f-<runId>`, `startedAt`, `endedAt`, time focused, planned minutes, outcome (`completed` or `stopped`) and target. |
| `FocusEvent` | `WorkEnded` and `BreakEnded`, each with its real time, for the end messages. |

**Accounting.** A running period keeps only its end time; a paused one keeps the time left, so a pause never counts. `settleFocus` applies every end that passed, each at its real time: the focus end records a Completed session with `endedAt = endsAt` and starts the break at that instant; the break end gives Ready. A long gap gives both, with one session. The time left is kept between 0 and the period's length, so a clock moved back never makes a negative time. Stop during focus records a Stopped session with the time focused when that is 1 minute or more (FD2); Stop during the break records nothing.

**Actions** (`FocusTimer`). Start, Pause, Resume, Stop and picking a target each settle first, then act on what the store holds, not on what a tab showed: an action made from an older view of the run (another tab already paused, stopped or moved it on) changes nothing. Picking a target changes the next Start and, as in v3, the run that exists. The store's change and the recording of its sessions run as one step that a closed panel or a cancelled caller cannot cut in half (`NonCancellable`).

### This device's timer (F2 A)

- A `FocusRunStore` port. The web store, `IndexedDbFocusRunStore`, keeps one JSON record (`v` 1) in the IndexedDB database `thread-focus-<uid>`, store `timer`, key `device`. It never goes to Firestore.
- Each action is one IndexedDB read-write transaction: read, settle, act, write. Two tabs never interleave, and an end two tabs see at once is recorded once.
- After a change, the other tabs of the browser profile hear of it on a `BroadcastChannel` named `thread-focus-<uid>` and read the record again. A tab also reads it again whenever it settles (on load, on return and at each end), so it catches up without the channel.
- A record this build cannot read counts as no timer; the next action replaces it.
- Sign-out keeps the record; the next sign-in of that account on this device settles it (FD8).

### History (F3 A)

- Firestore `users/{uid}/focusSessions/f-<runId>`: `startedAt`, `endedAt`, `focusedMs`, `plannedMin`, `outcome`, `targetKind`, `targetId`, `schemaVersion` 1. The times are the device's epoch milliseconds, not a server timestamp, so the same write again changes nothing.
- Written through `WorkspaceStore.write` as `WorkspaceWrite.PutFocusSession`, so the outbox, the sync status and the refused-change list (Retry, Copy, Discard) cover it, and a change whose answer was lost in a reload is checked against the stored session.
- Read with one bounded listener: the newest 50 by `endedAt` into `WorkspaceData.focusSessions`. The panel shows the newest 4. Nothing is deleted in this phase (FD7).

### Lengths (FD1)

`users/{uid}/prefs/main` gets `focusMin` and `breakMin` (whole minutes). A document without them reads as 25 and 5. The fields change only while the timer is Ready (v3) and save when the person leaves the field.

### Rules

- `validPrefs`: `hasOnly` adds `focusMin` and `breakMin`; each, when present, is an int within its limits. Old documents stay valid.
- `focusSessions/{sessionId}`: read by the owner. Create when the id matches `f-[A-Za-z0-9_-]{1,64}` and the shape is valid: every field present and no other; `startedAt <= endedAt`; `plannedMin` 1 to 90; `focusedMs` from 60,000 to `plannedMin` minutes; `outcome` `completed` or `stopped`; a target is both fields null, or `task`/`topic` with an id of 1 to 128 characters. Update only when nothing changes (`diff().affectedKeys().size() == 0`), so Retry and a second tab are accepted. No delete.
- **Deploy the rules before the build ships.** Until then the server refuses every session, and any preferences save that stores a Focus or Break length (a changed length, or the first preferences save of a new account). They stay in the sync panel with Retry; nothing is lost.

### Screens

- The status line tool sits between Search and the sync chip: "Focus" when Ready, else the time left, with the filled `is-running` style while a period runs or is paused. Its name for screen readers carries the time ("Focus timer, 12:34 left", "Focus timer, break, 04:59 left", FD6); it is not a live region, so nothing is read out every second.
- The panel (`?focus=1` on any page, `dlg--pop`) follows v3 without the concept badge and the demo note: time and phase (Ready, Focusing, Paused, Break, Break paused), "For:", Start focus, Pause or Resume, Stop, Focus min and Break min, "Attach to (optional)" (no Task or Topic, the Topics, the open Tasks), "Recent sessions" and v3's note on timestamps. A Task or Topic picked earlier that is done, in Trash or deleted stays shown under its name (FD5). The first control stays the same element from Start to Pause to Resume, so keyboard focus stays on it; Stop hands focus back to it. Esc and the close button return focus to the tool. No keyboard shortcut and no Views sheet entry (FD9, v3).
- `FocusClock` moves the time once a second while a period runs, and settles on load, on `visibilitychange`, `pageshow`, window focus, a test clock change and at each end. A hidden page never settles: it checks the page's visibility when the end comes, because a hidden page does not recompose and a remembered value would still say visible. Its message then comes on return, with the real times.
- Messages (D27, in-app): "Focus session done. Break: 5 min." and "Break over." when seen at once. Seen more than 5 seconds late, one message with the real times: "Focus session done at 14:25. Break until 14:30." or "... Break ended at 14:30.", with "yesterday" or a day for another day. A device that cannot save the timer says "The Focus timer could not be saved on this device." A refused session shows in the sync panel as "The focus session that ended today 12:40 was not saved."

## Consequences

- A timer started on the laptop does not show on the phone (F2 A). The history and the lengths do. One account document per period, no write on Pause or Resume.
- The device clock sets the session times. The rules cannot compare them with `request.time` without breaking the "same write again" rule; a wrong device clock gives wrong times, not lost data.
- Nothing runs while Thread is closed: the end is counted on return, and no notification is sent (D27, closed-app delivery deferred).
- `WorkspaceData` holds at most 50 sessions; a later "show all" (F3 B) needs its own query, not a bigger listener.

## Checks (Phase 14)

- Domain unit tests (fake clock): accounting, pause, several ends, settle twice, clock moved back, limits, labels, ids, two tabs, older views and a cancelled caller.
- Data tests: session and preferences mapping, the outbox JSON, the device record JSON, and the IndexedDB store (round trip, one change at a time, stale run id or phase, another tab).
- Emulator tests: the rules (valid, same write again, changed field, delete, bad shapes, another account, preferences old and new) and the store (an offline session through reload and reconnect, Retry with no duplicate).
- Headless Chrome on the emulators, SYNTHETIC account: v3 side by side at 1440, 1199, 899 and 390 px; start and pages; reload; pause and reload; +25 and +5 minutes; a hidden (minimized) page; closed during focus and during the break; two windows; offline; a second browser profile for the lengths; a Task moved to Trash; a refused session and Retry; keyboard and reduced motion; no console or CSP errors and no request to Atlassian or GitHub.
- Not checked: Safari, Firefox, phones (a phone can suspend a background tab; the timestamps make that safe, but it is not tested) and the real Firebase project before the rules deploy.
