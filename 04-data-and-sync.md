# Data model and synchronization

Entity shapes below are proposed implementation contracts for confirmed behavior.
Names are illustrative, not existing classes.

## Entity model

| Entity | Main fields / role |
| --- | --- |
| Topic | id, ownerId, title, goal, statusId, continuationNote, nextStep, info (Additional information, D52), timestamps |
| TopicStatus | id, ownerId, name, category, sortOrder |
| PersonalTask | id, ownerId, topicId nullable, title, completedAt nullable, plannedDate nullable, dueAt/date nullable, timestamps |
| Note | id, ownerId, title, versioned rich-text content, revision, timestamps |
| TopicNoteLink | topicId, noteId; unique pair |
| ExternalReference | id, provider, instance/repository identity, external stable ID/key, canonical URL |
| TopicExternalLink | topicId, externalReferenceId; unique pair |
| Reminder | id, ownerId, targetType, targetId, scheduledAt, timezone, state |
| InboxEntry | itemType, itemId, capturedAt, triagedAt nullable |
| FocusSession | id `f-<runId>`, startedAt, endedAt, time focused, planned minutes, outcome (completed or stopped), optional target (D56) |
| Preferences | timezone, last Topics view, focus and break minutes (D56), future density |
| ExternalSnapshot | device-local payload, field/completeness metadata, refreshedAt |
| Meeting (D78) | id, seriesKey, title, all-day or timed span, response, location, joinUrl; written by the Mac app only (ADR-020) |
| MeetingSource (D78) | the Mac app's last pass: state, calendar name, days covered, zone, uploader, app version, lastSuccessAt |
| TopicMeetingLink (D78) | topicId, seriesKey, linked, title snapshot; unique pair |
| SyncOperation | proposed only if custom outbox selected: operationId, target, baseRevision, payload, retry state |

## Relationship invariants

- Topic has many personal Tasks; Task has zero or one Topic.
- Topic <-> Note is many-to-many.
- Topic <-> ExternalReference is many-to-many.
- Topic <-> meeting series is many-to-many (D78). A link names the series, so it covers every occurrence and survives the meeting leaving the window.
- ExternalReference identifies an external object once, across Topics.
- Reminder has one typed target: Task or Note (D54). `topic` is still read for reminders older builds made; those move onto a Task of their own (ADR-018 part B).
- FocusSession has at most one direct Task/Topic association; a Task can resolve its Topic.
- Inbox triage is separate from whether an object has a Topic.
- Link deletion does not delete source content.
- Topic deletion must not silently cascade to shared Notes or external objects.
- Topic deletion/restore for the pilot is decided in D42 (06-roadmap-decisions.md, 2026-11-13): Tasks and Reminders go to Trash with the Topic; Notes and external objects are only unlinked; Restore brings back the Topic and all its links. Trash retention remains open.

## Ownership and storage boundary

| Data | Cloud | Local |
| --- | --- | --- |
| Topics, Tasks, Notes, statuses, links | Firebase | Durable working copy/cache |
| Reminder definitions, focus history, preferences | Firebase | Local usable copy |
| The running Focus timer (D56) | Never | This device only: IndexedDB `thread-focus-<uid>`, shared by its tabs |
| External identifiers and canonical links | Proposed minimal Firebase references | Yes |
| Jira descriptions, comments, detailed fields | Do not mirror into Firebase | API-derived cache |
| GitHub detailed payloads | Proposed: same API + local-cache model | Yes |
| Work meetings and the Mac app's source record (D78) | Yes, written only by the Mac app: yesterday to 14 days ahead, no attendees, organizer or notes | Firestore cache |
| Topic links to meeting series (D78) | Firebase | Durable working copy/cache |
| Integration credentials | Optional: one encrypted record, `users/{uid}/vault/connections`, sealed in the browser with the owner's passphrase (ADR-016); never plain text | Per browser profile (`thread-connections`, ADR-008); the sync key in `thread-vault-{uid}` |

Jira API + local cache is explicitly confirmed.
GitHub cache policy and exact minimal cloud metadata fields are proposed; settle before implementation.
User confirmed local Jira caching and no restrictions on work Notes.
Meetings are the one external content kept in Firebase (D78, ADR-020, owner change #19): the owner asked for the calendar to be "synced across devices thoroughly". Only the fields and the window above go up.
This approval does not instruct storing secrets or all external payloads in Firestore.

## Read path

1. Read available local data immediately.
2. Observe local changes for screen updates.
3. Refresh eligible remote data in the background.
4. Store refreshed content locally; UI updates from the same observable path.
5. Display last-refresh time and stale/offline state where useful.

For Jira detail opening, show cached data first and request revalidation online.
Deduplicate overlapping requests and respect rate limits.
No cache plus offline means an honest unavailable state, not fabricated content.

## Write path for app-owned content

Required outcome:
1. Capture the user's edit.
2. Commit locally durably before claiming it is saved locally.
3. Reflect the edit without waiting for the network.
4. Synchronize in the background.
5. Mark Synced only after remote acknowledgment.
6. Retry transient failures without losing work or duplicating effects.

SDK persistence may provide some steps. A custom outbox is not automatically required.
If implementing one, use stable operation IDs and define idempotency explicitly.
Authentication/permission errors are not infinite silent retry cases.

## Notes conflicts

Silent last-write-wins replacement of unsynchronized writing is unacceptable.
Proposed strategy: revision-aware writes; retain conflicting versions and let the user choose or recover both.
Do not promise automatic rich-text merge or collaboration.
Where conflict detection needs a server comparison, leave the offline edit pending until online reconciliation.
Keep local drafts durable during debouncing.
Exact retention duration, resolution UI, and history scope remain open.

## Deletion

Confirmed: recoverable deletion and preservation of conflicting writing.
Proposed: tombstones plus a restore flow, with cleanup only after a defined retention period.
Prevent an old offline write from silently resurrecting deleted data.
Define conflict behavior for delete-vs-edit; preserve recoverable content.
Retention period is not yet selected.

## Offline scope

Target: active Topics and their personal content available offline.
Also retain downloaded external details associated with those Topics.
This is a desired availability contract, not something Firebase caching guarantees automatically.
Define download completeness, quota handling, and explicit not-yet-downloaded indicators in the spike.
A newly added device needs network access to download content.
Remote Jira searches, first-time external content, and future Claude remote control require internet.

Web app shell must also be available offline after initial setup; data cache alone does not make a reload work.
Browser storage can be evicted or unavailable. Surface failures honestly and use cloud sync/exports as recovery paths.
Never promise first-ever launch offline.

## Dates and time

Proposed:
- plannedDate is a local calendar date, not an instant.
- Store actual timestamps in UTC, retain intended timezone for reminders.
- Workspace timezone defaults to Asia/Tbilisi for this user, with configuration possible.
- Deadline-only dates should not be accidentally shifted by UTC conversion.
- Define Today/overdue against the configured timezone.
- No recurrence requirement in v1.

## Search

Index app-owned local content and downloaded external snapshots.
Search must not upload cached Jira payloads merely to power full-text search.
Firestore must not be assumed to supply arbitrary full-text search.
Remote Jira search has explicit online behavior and separate permissions.
Meetings (D78) are searched on the device by title and place, over the meetings the Firestore cache holds (yesterday to 14 days ahead). The join link is never searched.

## Pomodoro persistence

Use timestamps and pause accounting, not a UI tick count as the source of truth.
Persist enough state to restore after reload.
No promise that a browser process executes while fully closed.
Closed-app notification delivery is deferred.
Resolved 2026-11-16 (D56): a running timer keeps its end time across a reload or a closed tab, and a period that ended meanwhile counts at its real end time (F1 A). Each device has its own timer, so there is no takeover; the lengths and the history sync (F2 A). See "Focus timer" below and ADR-019.

## Verification scenarios

- Offline create/edit -> reload -> content retained -> reconnect -> synced.
- Two devices edit one Note -> both versions remain recoverable.
- Repeated retry -> no duplicate Task, link, or completed session.
- Deleted object + stale offline update -> predictable recovery, no silent resurrection.
- Cache refresh fails -> old content remains with a visible stale/error state.
- Revoked external access -> stop retry loops; apply a defined cache cleanup policy.
- Local quota failure -> no false Saved indicator.

## M1 outcome (proposed, 2026-11-13)

Status: **provisional**. Written in Phase 4 while the owner was away. Details and evidence: ADR-002, ADR-004, ADR-006 to ADR-008 and `adr/M1-RESULTS.md`. Only the per-push revision model below has the owner's words ("Accept all", ADR-004).

- Note shapes: `users/{uid}/notes/{noteId}` with `headRevisionId` and `deletedAt`, and immutable `revisions/{revisionId}` with `parentRevisionId` (ADR-004).
- One revision per sync push (after 2 s idle, at least every 10 s), not one per editing session. Owner-accepted on 2026-11-13.
- More than one current version (leaf) is a conflict. D41 resolution writes in one batch. Trash is a `deletedAt` field, so a late edit never takes a Note out of Trash.
- Status order: Needs attention (device), Saving, Local only, Conflict, Sync failed, Synced, Offline, Syncing. "Synced" only after server acknowledgement.
- Drafts: `thread-local` when signed out, `thread-drafts-<uid>` per signed-in account (ADR-002).
- External data: Jira and GitHub copies in the IndexedDB database `thread-external-cache`, per browser profile, never in Firestore. Tokens in `thread-connections`, per browser profile (ADR-008).
- Cloud references for Jira and PR links: proposed default is provider, instance, key or number, and canonical URL, with no title (owner decision O11 open).
- Dates (proposed for Phase 7): `plannedDate` and a date-only deadline are calendar dates in the workspace time zone, never instants.


## Phase 6 outcome (proposed, 2026-11-13)

Status: **provisional**. Written in Phase 6 while the owner was away. Details: ADR-009 (Proposed).

- Statuses, Topics and preferences are one document each under `users/{uid}`: `statuses/{id}`, `topics/{id}`, `prefs/main`. Client-made ids. Seed statuses have fixed ids (`s-planned`, `s-active`, `s-paused`, `s-done`).
- Field names in the pilot: Topic `name`, `goal`, `leftOff` (the "continuationNote" above), `nextStep`, `color`, `statusId`, `createdAt`. Preferences hold `topicsView` and `captureType`; the time zone joins in Phase 7.
- Status delete is a `deletedAt` write that moves its Topics in the same batch. Nothing is hard-deleted.
- Edits write only the changed fields. Different fields edited on two devices both survive. The same field: the last write to reach the server wins.
- The Firestore persistent cache is the outbox. Save state comes from snapshot metadata. A refused write shows Sync failed and is named in the sync panel.

## Phase 7 outcome (proposed, 2026-11-13)

Status: **provisional**. Written in Phase 7 while the owner was away. Details: ADR-010 (Proposed).

- Tasks are `users/{uid}/tasks/{id}` (`k-` ids) with `title`, `topicId`, `done`, `completedOn`, `plannedDay`, `deadline`, `inbox`, `createdAt`, optional `deletedAt` and `discarded`. Reminders are `users/{uid}/reminders/{id}` (`r-` ids) with `at`, `zone`, `text`, `targetKind`, `targetId`, optional `dismissedAt` and `deletedAt`.
- `plannedDay`, `completedOn` and a date-only `deadline` are calendar strings (`yyyy-MM-dd`), never timestamps. A deadline time is `yyyy-MM-ddTHH:mm`. A reminder is a wall time plus its zone.
- Today, Overdue and "due now" compare wall times in the workspace zone (`prefs.timeZone`, default Asia/Tbilisi, no Settings field yet). The web layer reads the clock through `Intl.DateTimeFormat`; the domain has its own date types and no platform code.
- Inbox is a flag on the Task. Triage clears it, and sets the Topic when linking. Nothing is copied or deleted.
- Trash is `deletedAt`; Undo of a fresh capture writes `discarded`. Nothing is hard-deleted.
- `prefs/main` gains `keepCompleted` (default true) and `timeZone`.

## Phase 8 outcome (proposed, 2026-11-13)

Status: **provisional**. Written in Phase 8 while the owner was away. Details: ADR-011 (Proposed).

- The Note head document `users/{uid}/notes/{id}` is also the Notes listing. The NoteSyncer writes `title`, `preview` (280 characters of plain text), `headRevisionId` and `deletedAt`. The workspace store writes `inbox`, `discarded`, `createdAt` and `updatedAt`. Both use merge writes of their own fields only.
- Topic links are `users/{uid}/topicNoteLinks/{topicId}_{noteId}` with `linked` true or false. The id comes from the pair, so linking twice is one document. Unlinking never deletes the Note or the link document.
- D39: a Note made outside a Topic has `inbox = true`. Linking any Topic clears it. Unlinking every Topic leaves the Note standalone.
- Conflict copies get the same Topic links as the Note they came from.
- The Notes index searches the title and the preview only.

## Phase 9 outcome (proposed, 2026-11-13)

Status: **provisional**. Written in Phase 9 while the owner was away. Details: ADR-012 (Proposed).

- A Topic in Trash is one field, `deletedAt`, on the Topic. Its Tasks, Reminders and Note links are not written; every view hides them while the Topic is in Trash. Restore and Undo clear the field, so everything comes back, also what another device changed meanwhile.
- Delete forever replaces the document with a tombstone `{purgedAt, schemaVersion: 1}`. The rules refuse every later write except another tombstone, so an old offline write cannot bring the item back. Document deletes stay refused, except the versions of a Note deleted forever.
- A Topic deleted forever takes the Tasks and Reminders that went to Trash with it. A Task or Note takes the Reminders still on it. Notes and external records are never touched.
- The versions of a Note deleted forever are deleted by the app once per app start while online. Its local draft goes on this device at once; other devices keep theirs for now.
- Nothing purges automatically. The retention period is still open.
- Global search covers Topics (name, goal, Additional information; next step before D52), Tasks (title) and Notes (title and preview) on this device, at most 6 per group, nothing in Trash. No Jira payload is uploaded for search.

## Phase 10 outcome (proposed, 2026-11-13)

Status: **provisional**. Written in Phase 10 while the owner was away. Details: ADR-013 (Proposed). Checked on SYNTHETIC fixtures and the emulators only; nothing was checked against real Jira.

- Firestore holds Jira references as ids only: `externalRefs/{provider~instance~key}` with `{provider, instance, key, url, updatedAt, schemaVersion}`, and `topicExternalLinks/{topicId}_{refId}` with `{topicId, refId, linked, updatedAt, schemaVersion}`. One ticket is stored once, however many Topics link it. Unlink writes `linked: false`; nothing is deleted.
- Titles, statuses, descriptions and comments stay in IndexedDB on this device (`jiraIssues`, and the Assigned list in `jiraMeta`). Search results stay in memory.
- A Topic in Trash hides its Jira links; Restore brings them back. Delete forever leaves the id-only refs and links.
- Offline or rate limited shows the saved copy with its age and makes no request. Expired access keeps copies readable. Not found forgets the saved copy.
- Copies refresh on open and every 5 minutes while visible and online.
- Since D67, rows that show a ticket's or pull request's status also refresh when they show. Jira rows are saved on the device under `row:<KEY>` in `jiraMeta`; nothing reaches Firestore.

## Phase 11 outcome (proposed, 2026-11-13)

Status: **provisional**. Written in Phase 11 while the owner was away. Details: ADR-014 (Proposed). Checked on SYNTHETIC fixtures and the emulators only; nothing was checked against real GitHub.

- Pull request references use the Phase 10 collections as ids only: `externalRefs/github~owner~repo~number` with `instance: "owner/repo"` and `key: "number"`, and `topicExternalLinks`. No rules change. One pull request is stored once, however many Topics link it. Unlink writes `linked: false`; nothing is deleted.
- Titles, authors, branches, descriptions, reviews and CI state stay in IndexedDB on this device (`pullRequests`, and the two lists in `prLists`).
- A Topic in Trash hides its pull request links; Restore brings them back.
- Offline or rate limited shows the saved copy with its age and makes no request. Expired access keeps copies readable. Not found forgets the saved copy.
- Thread sends at most 10 search requests a minute, inside GitHub's 30 for the token.

## Phase 12 outcome (proposed, 2026-11-13)

Status: **provisional**. Written in Phase 12 while the owner was away. Details: ADR-015 (Proposed). Checked on the production build against the emulators with SYNTHETIC data only; nothing was checked against real Firebase, Jira or GitHub.

- No change to the Firestore data model or the rules.
- A change the server refuses is a failed sync and is never dropped (owner-review-queue Q14, ADR-009). The workspace store keeps each change in a device-only IndexedDB record (`thread-outbox-<uid>`, never uploaded) until the server answers. A refused change stays in the sync panel after a reload, with Retry, Copy and Discard (with Undo). A change whose answer was lost in a reload is compared with the stored data after `waitForPendingWrites`, field by field, and listed as "Could not confirm" when the data does not show it.
- Revoked or expired access (401): saved copies stay readable and the page says "access expired" with Reconnect. The five-minute automatic refresh stops for that provider. Opening another item, pressing Refresh or saving a token loads once; there is no retry loop. Cache policy: copies stay until "Delete saved copies", Disconnect hides them, a 404 forgets one copy.
- The Jira and Pull requests pages show the banner of the view that has a problem, so a list that got a 401 still says why.

Verification scenarios, as checked on the production build (browser checks of Phases 7 to 12, run again in Phase 12):

| Scenario | Checked by |
|---|---|
| Offline create/edit, reload, reconnect, synced | Phase 7 O1 to O6 (Tasks), Phase 8 O1 to O9 (Notes) |
| Two devices edit one Note | Phase 8 X1 to X18: both versions stay, the other one becomes a Note copy |
| Repeated retry, no duplicate | Phase 8 O9 and Q6, Phase 7 O6, and the emulator tests (`-Pthread.emulatorTests`) |
| Deleted object plus stale offline update | Phase 9 D1 to D10 and W1 to W3: tombstones, nothing comes back |
| Cache refresh fails | Phase 10 O1, A1 to A5, Phase 11 the same for GitHub, Phase 12 J3 (server error keeps the saved ticket), G0 and G1 (rate limit with and without a saved copy) |
| Revoked external access | Phase 10 A1 and Phase 12 A0 to A5 (no automatic refresh after a 401, Refresh asks once, Jira and GitHub) |
| Local quota failure | Phase 8 E6: a failed local save says Needs attention, never Saved |
| Server refuses a change (rules), reload | Phase 12 Q14 R1 to R14 (kept after a reload, Copy, Discard and Undo, Retry) and B1 to B10 (answer lost in a reload), and the emulator tests |

## Topic suggestions (D49, proposed, 2026-11-14)

Details: ADR-017 (Proposed). Checked with the emulator tests and headless Chrome on the emulators, with SYNTHETIC data only.

- `topicExternalLinks/{topicId}_{refId}` gets one optional field: `{topicId, refId, linked, dismissed?, updatedAt, schemaVersion: 1}`. An item is dismissed in a Topic only when `dismissed` is true and `linked` is false: linked wins. Documents without the field read as not dismissed. `schemaVersion` stays 1.
- Writes are merges. Dismiss: the `externalRefs` document (ids and URL only) and `linked: false, dismissed: true`. Restore: `linked: false, dismissed: false`. Link writes only `linked: true` and leaves `dismissed` as stored (Q84). Unlink, and removing an item in the Link picker: `linked: false, dismissed: true` (Q79). Every one has Undo.
- Rules (`validExternalLink`): `hasOnly` adds `dismissed`, and `dismissed`, when present, must be a bool. `linked` and `dismissed` may both be true, so an older build's Link is accepted.
- **Deploy the rules before the build ships.** Until then the server refuses Unlink, Don't suggest and Restore. They stay in the sync panel (Phase 12); nothing is lost. Link is unaffected.
- Trash: a Topic in Trash hides its links, so its dismissals leave with it and come back on Restore (D42).
- Device only, never uploaded: the tickets and PRs that suggestion checks load are saved in `thread-external-cache` like opened ones; keys Jira did not show are remembered for a day in browser storage (`thread.jira.notTickets`).
- Phase 2, device only: S4 family rows in the external cache's meta store under `family:<topicId>` (marked with the Jira site) and S5 PR rows in its lists store under `mentions:<topicId>` (no PR descriptions). Each is fresh for 10 minutes and cleared with the connection, like the lists. No Firestore field and no rules change.

## Topic Additional information (D52, proposed, 2026-11-14)

Details: ADR-018 part A (Proposed). Checked with the emulator tests and headless Chrome on the emulators, with SYNTHETIC data only.

- `topics/{topicId}` gets one optional field, `info`: absent or null means never saved, `""` means saved empty. Up to 20,100 characters. `schemaVersion` stays 1.
- Until `info` is saved, the app shows `leftOff` and `nextStep` combined ("Where I left off: ...", "Next step: ..."). Nothing is migrated; the first edit writes `info` only. This build never changes `leftOff` and `nextStep`; a new Topic writes them as `""` because the rules require them.
- Writes are field diffs, as before. Undo of the first edit writes `info: null`.
- Rules (`validTopic`): `hasOnly` adds `info`; `info`, when present and not null, is a string of at most 20,100 characters. `hasAll` is unchanged.
- **Deploy the rules before the build ships.** Until then the server refuses edits of the box, their Undo and Topics created from a ticket or PR. They stay in the sync panel (Phase 12); nothing is lost.
- The outbox JSON carries `info`; changes saved by older builds have none and read as null.
- Search matches `info`, or the old text while `info` is not saved.

## Focus timer (D56, proposed, 2026-11-16)

Details: ADR-019 (Proposed). Checked with unit tests, the emulator tests and headless Chrome on the emulators, with SYNTHETIC data only.

- **This device's timer** stays on the device: one JSON record (`v` 1) in IndexedDB `thread-focus-<uid>`, store `timer`, key `device`. It holds the run (id, phase, end time while running, time left while paused, planned lengths, start, target) and the last picked Task or Topic. Each action is one read-write transaction (read, settle, act, write), and other tabs hear of a change on `BroadcastChannel("thread-focus-<uid>")`. It never goes to Firestore.
- **Settle** applies every end that passed, each at its real time: the focus end records a Completed session with `endedAt` = the end time, and the break starts then. A hidden page does not settle; it does on return, with the real times in the message.
- **Sessions:** `users/{uid}/focusSessions/f-<runId>` with `startedAt`, `endedAt`, `focusedMs`, `plannedMin`, `outcome` (`completed` or `stopped`), `targetKind`, `targetId` and `schemaVersion` 1. One per focus period; a Stop under 1 minute and a Stop in the break save none. The times come from the device, not the server, so a repeat of the same write changes nothing.
- Sessions go through `WorkspaceStore.write` (`PutFocusSession`), so the outbox, the sync status and the refused-change list cover them. A Retry or a second tab writes the same document again, which is accepted and stays one session.
- **Read:** one listener, the newest 50 by `endedAt`. The panel shows the newest 4.
- **Lengths:** `users/{uid}/prefs/main` gets `focusMin` (1 to 90) and `breakMin` (1 to 30). Absent reads as 25 and 5.
- **Rules:** `validPrefs` allows the two fields. `focusSessions` can be created with a valid shape and an id matching `f-[A-Za-z0-9_-]{1,64}`, updated only when nothing changes, and never deleted.
- **Deploy the rules before the build ships.** Until then the server refuses every session, and any preferences save that stores a Focus or Break length (a changed length, or the first preferences save of a new account). They stay in the sync panel with Retry; nothing is lost.

## Hide empty Today sections (D77, 2026-11-17)

- **Setting:** `users/{uid}/prefs/main` gets `hideEmptySections` (bool). Absent reads as false, so documents and clients from before D77 keep working.
- It saves like the other preferences: only a changed value is written, through the outbox, and a refused save stays in the sync panel with Retry.
- **Rules:** `validPrefs` allows the key; when present it must be a bool.
- **Deploy the rules before the build ships.** Until then the server refuses a preferences save that stores the setting (turning it on or off, or the first preferences save of a new account).

## Meetings layout (D79, 2026-11-17)

- **Setting:** `users/{uid}/prefs/main` gets `meetingsLayout` (`vertical` or `horizontal`). Absent, or a value this build does not know, reads as `vertical`, so documents and clients from before D79 keep working.
- It saves like the other preferences: only a changed value is written, through the outbox, and a refused save stays in the sync panel with Retry.
- **Rules:** `validPrefs` allows the key; when present it must be one of the two strings.
- **Deploy the rules before the build ships.** Until then the server refuses a preferences save that stores the layout (changing it, or the first preferences save of a new account).
- The timeline itself stores nothing: it is drawn from the meetings already on the device (D78).

## Calendar meetings (D78, 2026-11-17)

Details: ADR-020 (Accepted). Plan: `calendar-plan.md` v2.2 in the session notes.

- **Collections** under `users/{uid}`, owner only: `meetings/{32 hex}` and `meetingSource/main`, written by the Mac app over Firestore REST; `topicMeetingLinks/{topicId}_{seriesKey}`, written by the web. The ids are SHA-256 hashes of the calendar's ids, so no raw calendar id is stored.
- **Writer:** the Mac app diffs what it read against a local index on the Mac and writes only changes, at most 250 a commit, with `meetingSource/main` last. A quiet pass writes nothing; the source is rewritten at most every 30 minutes as a heartbeat. The web never writes meetings or the source.
- **Retention:** the Mac app deletes what leaves the window (yesterday to 14 days ahead, in the workspace zone), and declined and cancelled meetings. The rules refuse a timed meeting outside the window. No TTL policy: it needs billing. "Stop syncing" in the Mac app deletes every meeting and the source; Topic links stay.
- **Deletes:** meetings and the source are the first content collections the owner may delete (ADR-005). Topic links are never deleted: unlinking writes `linked: false`.
- **Read:** `FirestoreMeetingStore` listens to both and maps times to wall-clock times in the workspace zone. The Firestore cache keeps the last copy offline; the card says when the Mac last uploaded.
- **Links** go through the workspace store (`PutMeetingLink`, ADR-009): the outbox, the sync status and the refused-change list cover them. Linking writes the series title as it is then, so a Topic can name a series that left the window.
- **Device only:** whether this browser has seen meetings set up (`thread.meetings.seen`, so the card does not flash in or out on load), and on the Mac the index, the keychain item and the diagnostics (counts and codes only).
- **Rules** were deployed by the owner before the clients shipped ("Deployed"). Until then the Mac app's writes and the web's listeners were refused, and the web kept the error inside the Meetings card.
