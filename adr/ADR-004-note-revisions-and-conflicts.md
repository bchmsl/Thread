# ADR-004: Note revisions and conflicts

Status: Proposed (2026-11-13). Written in Phase 2 (M1-B), emulator only. Evidence: `adr/M1-RESULTS.md`. The owner accepted the revision model ("Writes" below) on 2026-11-13. The whole record is reviewed at Phase 4, the M1 review gate.

Phase 4 review (2026-11-13, owner away): checked for consistency with the other ADRs. Still Proposed; the owner review is queued. The Notes feature (Phase 8) builds on this record as written.

## Context

A Note can be edited on several devices, also offline. Sync must never overwrite text silently, never duplicate a write that is retried, and never bring a Note back from Trash because an old offline edit arrived late. Conflicts are resolved by the person with the v3 compare view (D41): keep this device's version, keep the other one, or keep both. The version not kept goes to Trash.

## Decision

### Firestore shapes

```
users/{uid}/notes/{noteId}
  title            string, the head's title
  headRevisionId   string
  updatedAt        server time
  schemaVersion    1
  deletedAt        server time or null; set = in Trash
  copiedFrom       {noteId, revisionId} on a Note made from one side of a conflict

users/{uid}/notes/{noteId}/revisions/{revisionId}
  parentRevisionId string or null (first version)
  title            string
  body             RichText v1 JSON string (ADR-003)
  schemaVersion    1
  deviceId         random id of the browser profile
  createdAt        server time
  discardedAt      server time, only set by a conflict resolution
```

- Revisions are immutable. The rules allow only `discardedAt` to be set, and `createdAt` to be written again when two devices write the same conflict copy (ADR-005). Nothing is ever deleted.
- The Note document is always written with merge. Only an explicit Trash or Restore names `deletedAt`, so an edit can never take a Note out of Trash (C3).

### Writes

- Each write is a new revision with an id made on the device (`crypto.randomUUID()`), so a retried or replayed write lands on the same document and never duplicates (C2).
- The parent is the device's base: the newest server-acknowledged revision its content came from. It is stored with the draft (`LocalDraft.baseRevisionId`, draft record version 2).
- A push writes the revision and the Note head in one batch.
- Timing: after 2 s without edits, and at least every 10 s while typing goes on, plus on page hide and when the Note closes. Only one revision waits for the server at a time; edits made meanwhile go into the next one.
- Acknowledgement: a revision is acknowledged when its snapshot has `hasPendingWrites` false. Only then does it become the base, and only then can the status say "Synced".
- A write the server refuses fails the batch, or disappears from the local copy after it was seen there. Either way sync stops with "Sync failed" and Retry sync. The text stays in the draft store (E3).

This replaces the plan's "one revision per editing session". A session revision would have to change after it is written, which breaks immutability and duplicate safety. The cost is more revisions: 10 edits with 5 reconnects made 5 revisions in C2, and steady typing makes one about every 10 s.

Owner decision (2026-11-13, "Accept all", relayed by the orchestrator session): one revision per sync push (2 s idle, at most 10 s) is accepted in place of one revision per editing session. Where the build-phase plan says "one revision per editing session", this record wins. `04-data-and-sync.md` does not name either model; it gets this one at the Phase 4 review.

### Current versions and conflicts

- A leaf is a revision that is not discarded and that no other revision uses as its parent. A discarded revision's children still count, so discarding never revives an older version.
- One leaf is the current version. More than one leaf is a conflict. Every device sees the same leaves, so every device shows the conflict.
- When the server has a newer version that already contains all of this device's content (equal to it or to one of its ancestors), the device takes it without asking.
- When the device has content the server lacks, it writes it as its own revision. If another device wrote from the same parent, that is a conflict, never an overwrite.
- Content that is not the person's work (a device with no stored draft) never competes: the server version replaces it, and it is written only when the server confirmed that the Note has no versions yet.
- A revision this app cannot read (another schema version or damaged) pauses writing for that Note, with "Sync paused for this note", so nothing is written over it.

### Resolution (D41)

One atomic batch per choice:

| Choice | Kept as the current version | Discarded | New Note |
|---|---|---|---|
| Keep this device's version | This device's revision | The other leaf | Copy of the other version, in Trash, id `conflict-<other revision id>` |
| Keep other device's version | The other leaf | Every other leaf (this device's) | Copy of this device's newest content, in Trash, random `conflict-` id |
| Keep both | This device's revision | The other leaf | Copy of the other version, not in Trash, title + " (other device copy)", id `conflict-<other revision id>` |

- Copies made from the other version use ids derived from it, so two devices that resolve the same way write the same copy, not two.
- The compare view is offered only when this device's newest write is in its local copy. Otherwise the person sees "This device's version is still on its way to the server. Try again in a moment."
- The other device takes the result on its next snapshot, with no prompt (C1).

### Trash

`setInTrash` writes only `deletedAt` (server time, or null to restore). The Note keeps all its revisions. A late offline edit adds a revision but leaves `deletedAt` alone, so the Note stays in Trash with the edit inside (C3).

## Consequences

- No Note text was lost in any check (unit, emulator and browser; `adr/M1-RESULTS.md`).
- The listener reads all revisions of an open Note. Fine for the spike; long-lived Notes need compaction or a "recent revisions only" query.
- Conflicts are by whole Note, not merged by paragraph. Matches D41.

## Known limits

- Resolving does not update the Note head's `title` to the kept version's title. It matters once a Notes list reads it.
- Copies do not carry Topics (there are no Topics yet). The v3 hint "with the same Topics" is left out until they exist.
- Devices have no names, so the compare view says "Other device". v3 shows a device name and edit time.
- The "Synced" status has no time, unlike v3.
- In the spike, a Note in Trash can still be edited.
- "Move to Trash" shows a toast without Undo (the toast has no actions yet); the Trash banner offers Restore.
- E3 uses a test hook: the write carries an extra field `rejectedForTesting`, which the rules refuse.

## Open points

- Revision history grows without limit: every push adds a revision and nothing is ever deleted. A cleanup or retention rule is needed later (owner note, 2026-11-13). It must keep the current versions, every version in an open conflict, and enough parents to detect a conflict with a device that was offline for a long time.

## Proposed document updates

- `04-data-and-sync.md`: the shapes above, leaves and conflicts, and the per-push revision model in place of "one revision per editing session".
- `ADR-003`: the draft record is now version 2 with `baseRevisionId` (a pointer is added there).
- Not changed until the owner accepts this record.
