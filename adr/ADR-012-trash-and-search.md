# ADR-012: Trash, Delete forever and global search

Status: Proposed (2026-11-13). Written in Phase 9 while the owner was away. Built on ADR-004 (Note revisions), ADR-009 (one document per entity, merge writes, the Firestore persistent cache as outbox), ADR-010 (Tasks and Reminders) and ADR-011 (Notes in the workspace). Checked with the domain tests (`TrashTest`, `SearchTest`), the emulator tests `FirestoreTrashEmulatorTest` and `FirestoreTrashRulesEmulatorTest`, and the Phase 9 browser checks. The owner review is queued (owner-review-queue.md, Q35 to Q40).

## Context

- D42: a Topic can be moved to Trash. Its Tasks and Reminders go with it. Notes stay in Notes; only the link goes. Restore brings back the Topic, its Tasks, Reminders and all its links. Delete forever asks first. Retention is open.
- D43: Move to Trash is instant with Undo. Delete forever asks.
- CLAUDE.md: an old offline write must not bring deleted data back, and failed sync never discards content.
- 04-data-and-sync.md "Deletion": recoverable deletion, tombstones, no silent resurrection.
- D24 and D40: ⌘K global search over local content. Firestore has no full-text search.
- Tasks, Reminders and Notes already go to Trash on their own (Phases 7 and 8) through `deletedAt`.

## Decision

### A Topic in Trash is one field

- Move to Trash writes only the Topic: `deletedAt` (server time). Nothing is written to its Tasks, Reminders or Note links.
- Every view reads the workspace through `visibleWorkspace`: a Topic in Trash is left out, with its Tasks, the Reminders on it or on those Tasks, and its Note links. Notes stay. A Note whose only link was to that Topic shows as standalone (D42), not in Inbox.
- Restore and Undo write `deletedAt: null`. Everything comes back exactly, also what another device added or changed while the Topic was in Trash (emulator test: a Task added offline to the Topic comes back with it).
- A Topic restored after its status was deleted takes the first status of the same category, else the first status (v3).

### Restore of other items

- A Task whose Topic is in Trash or gone comes back standalone (v3 "Restore as standalone").
- A Note keeps all its links. A link to a Topic in Trash comes back when that Topic is restored.
- A Reminder comes back but stays hidden while what it is for is in Trash. The Trash page says so.

### Delete forever

- Delete forever replaces the document with a tombstone `{purgedAt, schemaVersion: 1}` (a full overwrite, not a merge). No content stays.
- The rules accept a tombstone only with exactly those two fields and `purgedAt == request.time`. After that, only another tombstone can be written (two devices deleting the same item). Any other write is refused: a stale offline edit, a restore, or a new create on the same id. Deletes stay refused everywhere except the case below.
- A Topic takes the Tasks and Reminders that went to Trash with it. A Task or a Note takes the Reminders still on it. Items moved to Trash on their own are separate entries. Notes, Jira tickets and pull requests linked to a Topic are not touched.
- The store skips tombstones when it decodes, and does not count them as unreadable. Purged Note ids are listed in `WorkspaceData.purgedNotes`.
- A Note's saved versions (`notes/{id}/revisions/*`) are deleted by a sweep in the app: for each purged Note, once per app start while online, read the versions from the server and delete them in batches of 400. The rules allow a revision delete only when the head is a tombstone, and refuse new revisions under a tombstone. A purged Note reads as `exists = false`.
- Delete forever of a Note also removes this device's local draft. Other devices keep theirs until a later phase cleans them (queued).
- Nothing in Trash is deleted automatically. Retention stays open (D42).

### Global search

- `searchWorkspace` matches Topics on name, next step or goal; Tasks on title (completed ones too); Notes on title or the 280-character preview. It ignores case, trims the query, and returns at most 6 per group (v3 `searchAll`).
- It searches what the views show: nothing in Trash, no Topic in Trash or what went with it. A Note filed out of Inbox stays searchable (acceptance example 6).
- The palette shows Topics, Tasks and Notes, then the views whose name matches under "Go to". Jira and pull requests join in Phases 10 and 11.

## Consequences

- Moving a Topic to Trash is one small write and cannot leave a half-moved bundle.
- A device that has not seen the Trash write still shows the Topic until it syncs. Its edits to the Topic or its Tasks are kept and come back on restore.
- After Delete forever, a stale edit from another device is refused by the server. That device shows "Sync failed" for the refused write until reload; the content is gone by the owner's choice.
- Tombstones keep their document ids forever. Each costs one small document; their count grows with purges.
- The version sweep costs one server read per purged Note per app start. Fine for the pilot; a later phase can remember swept ids on the device.
- Phase 10 and 11 Topic links to Jira tickets and pull requests must follow the same rule as Note links: hidden while the Topic is in Trash, back on restore, never touching the external record.

## Alternatives considered

| Option | Why not now |
|---|---|
| Write `deletedAt` to every Task, Reminder and link of the Topic | Many writes; a device offline during the move can add a Task that misses the bundle; Restore must tell bundle items from items trashed on their own. |
| Hard delete on Delete forever | A stale offline write would create the document again with old content. |
| Tombstone that keeps the title | Content would stay after "Delete forever". |
| Delete revisions in the same batch as the tombstone | The client cannot list them reliably offline; a batch over 500 writes fails. |
| Automatic purge after N days | Retention is an open owner decision. |
| Full-body Note search | Needs every body on every device or a server index. |
