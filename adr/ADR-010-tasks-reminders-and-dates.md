# ADR-010: Tasks, reminders and calendar dates

Status: Proposed (2026-11-13). Written in Phase 7 while the owner was away. Built on ADR-009 (one document per entity, field-level merge writes, the Firestore persistent cache as outbox) and the "Dates and time" section of 04-data-and-sync.md. Checked with the domain tests (`shared/domain/src/commonTest/.../task`, `.../time`, `.../reminder`), the emulator tests `FirestoreTaskEmulatorTest` and `FirestoreTaskRulesEmulatorTest`, and the Phase 7 browser checks. The owner review is queued (owner-review-queue.md, Q20 to Q27).

## Context

- Phase 7 adds personal Tasks (D10, D37, D38), Inbox triage for Tasks, in-app reminders, and the Today view.
- Planned days and deadlines are calendar dates. A date stored as a UTC instant moves to the wrong day near midnight in Tbilisi (UTC+4).
- Today, Overdue and "due now" depend on the wall clock in one place. v3 uses the browser's local time. The pilot needs a workspace time zone (default Asia/Tbilisi, 04-data-and-sync.md) that does not change when the laptop travels.
- No new dependency may be added while the owner is away, so kotlinx-datetime is not available.

## Decision

### Time types (domain, no platform code)

- `LocalDay` (year, month, day; ISO `yyyy-MM-dd`), `WallTime` (a day and a minute of the day; `yyyy-MM-ddTHH:mm`) and `Deadline` (a day and an optional minute) are plain domain types.
- A date-only deadline means the end of that day. It is never turned into an instant.
- "Now" is a `WallTime` in the workspace zone. The web layer reads it from the browser clock through `Intl.DateTimeFormat` with that zone (`WallClock`). An unknown zone falls back to Asia/Tbilisi, then to the device's local time.
- The domain compares wall times only. No time zone math happens in the domain.

### Documents

| Path | Fields | Notes |
|---|---|---|
| `users/{uid}/tasks/{taskId}` | `title` (1 to 500), `topicId` (or null), `done`, `completedOn` (day or null), `plannedDay` (day or null), `deadline` (`yyyy-MM-dd` or `yyyy-MM-ddTHH:mm`, or null), `inbox`, `createdAt`, `updatedAt`, `deletedAt` (optional), `discarded` (optional), `schemaVersion: 1` | Inbox is a flag on the Task, not a separate entry. |
| `users/{uid}/reminders/{reminderId}` | `at` (`yyyy-MM-ddTHH:mm`), `zone` (IANA name), `text` (1 to 500), `targetKind` (`task`, `note`, `topic`), `targetId`, `dismissedAt` (optional), `createdAt`, `updatedAt`, `deletedAt` (optional), `schemaVersion: 1` | A reminder is a wall time plus the zone it was set in. |
| `users/{uid}/prefs/main` | adds `keepCompleted` (bool, default true) and `timeZone` (IANA name, default Asia/Tbilisi) | Older documents without them still read. |

- Ids are client-made: `k-` for Tasks, `r-` for reminders.
- The rules accept only these shapes. Days and deadlines must be calendar strings, never timestamps. Nothing can be deleted.
- Edits write only the changed fields (ADR-009). Completing a Task on one device and renaming it on another both survive (emulator test).

### Behaviour

- **Today (D37):** each open Task shows once, in the first group it fits: Overdue (deadline passed), Today (planned for today or due today), Earlier planned (planned before today). A Task completed today stays, struck through, while "Keep completed tasks in Today" is on, and leaves the next day. `completedOn` is the workspace day of completion. D53 (ADR-018, part D) adds Upcoming and No date after Earlier planned, and every group opens and closes.
- **Add from Today (D38):** the Task is planned for today. Without a Topic it also waits in Inbox.
- **Inbox:** Tasks with `inbox = true`. "Link to Topic" sets `topicId` and clears the flag; "Keep standalone" clears the flag only. Nothing is copied or deleted. Taking a Topic away later does not put the Task back in Inbox.
- **Trash:** `deletedAt`. The Trash page arrives in Phase 9. Undo of a fresh capture or add writes `discarded = true`, so a Task that may already be on the server never comes back and is never hard-deleted.
- **Reminders:** a reminder is due from its minute until it is dismissed (`dismissedAt`). One that came due while Thread was closed shows as a banner when Thread opens. Thread cannot notify a closed tab (v1). A reminder whose target is gone does not show. Since D54 (ADR-018, part B) new reminders go on a Task or a Note, Topic reminders move onto Tasks, a reminder day counts as a date in Today, and a reminder on a completed Task shows no banner.
- **Clock:** the screens refresh "now" each minute, when the tab becomes visible again, and when the emulator test hook moves the clock (`threadTest.setClockOffset`).

## Consequences

- Planned days and deadlines never shift across a UTC boundary (web test `WallClockTest`, emulator test "offline task keeps calendar fields through sync").
- Reminders store their zone but the pilot compares every reminder in the one workspace zone. If the workspace zone ever changes, an old reminder keeps its wall time, not its instant.
- There is no Settings field for the time zone in the pilot. It is stored, defaults to Asia/Tbilisi and can be changed only in Firestore.
- Same as ADR-009: the same field edited on two devices keeps the last write to reach the server.

## Alternatives considered

| Option | Why not now |
|---|---|
| Store days and deadlines as Firestore timestamps | A date-only value would move days in other zones and near midnight. |
| kotlinx-datetime with the IANA database | A new dependency (and @js-joda for zones) needs the owner's approval. Can replace `WallClock` later without touching the domain. |
| A separate InboxEntry entity | Two documents to keep in step for one fact; triage would be two writes that can half-fail. |
| Reminders as instants | Simple for "due now", but a reminder set for 09:00 Tbilisi would no longer read 09:00 if the workspace zone changed. |
