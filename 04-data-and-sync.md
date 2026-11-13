# Data model and synchronization

Entity shapes below are proposed implementation contracts for confirmed behavior.
Names are illustrative, not existing classes.

## Entity model

| Entity | Main fields / role |
| --- | --- |
| Topic | id, ownerId, title, goal, statusId, continuationNote, nextStep, timestamps |
| TopicStatus | id, ownerId, name, category, sortOrder |
| PersonalTask | id, ownerId, topicId nullable, title, completedAt nullable, plannedDate nullable, dueAt/date nullable, timestamps |
| Note | id, ownerId, title, versioned rich-text content, revision, timestamps |
| TopicNoteLink | topicId, noteId; unique pair |
| ExternalReference | id, provider, instance/repository identity, external stable ID/key, canonical URL |
| TopicExternalLink | topicId, externalReferenceId; unique pair |
| Reminder | id, ownerId, targetType, targetId, scheduledAt, timezone, state |
| InboxEntry | itemType, itemId, capturedAt, triagedAt nullable |
| FocusSession | id, ownerId, optional target, configured durations, start/pause/end information |
| Preferences | timezone, last Topics view, future density |
| ExternalSnapshot | device-local payload, field/completeness metadata, refreshedAt |
| SyncOperation | proposed only if custom outbox selected: operationId, target, baseRevision, payload, retry state |

## Relationship invariants

- Topic has many personal Tasks; Task has zero or one Topic.
- Topic <-> Note is many-to-many.
- Topic <-> ExternalReference is many-to-many.
- ExternalReference identifies an external object once, across Topics.
- Reminder has one typed target: Task, Note, or Topic.
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
| External identifiers and canonical links | Proposed minimal Firebase references | Yes |
| Jira descriptions, comments, detailed fields | Do not mirror into Firebase | API-derived cache |
| GitHub detailed payloads | Proposed: same API + local-cache model | Yes |
| Integration credentials | Dedicated secure auth mechanism, if needed | Platform-appropriate protected handling |

Jira API + local cache is explicitly confirmed.
GitHub cache policy and exact minimal cloud metadata fields are proposed; settle before implementation.
User confirmed local Jira caching and no restrictions on work Notes.
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

## Pomodoro persistence

Use timestamps and pause accounting, not a UI tick count as the source of truth.
Persist enough state to restore after reload.
No promise that a browser process executes while fully closed.
Closed-app notification delivery is deferred.
Cross-device simultaneous timers and timer takeover semantics remain open.

## Verification scenarios

- Offline create/edit -> reload -> content retained -> reconnect -> synced.
- Two devices edit one Note -> both versions remain recoverable.
- Repeated retry -> no duplicate Task, link, or completed session.
- Deleted object + stale offline update -> predictable recovery, no silent resurrection.
- Cache refresh fails -> old content remains with a visible stale/error state.
- Revoked external access -> stop retry loops; apply a defined cache cleanup policy.
- Local quota failure -> no false Saved indicator.

