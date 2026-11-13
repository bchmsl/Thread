# ADR-011: Notes in the workspace

Status: Proposed (2026-11-13). Written in Phase 8 while the owner was away. Built on ADR-004 (Note revisions and conflicts), ADR-009 (one document per entity, field-level merge writes, the Firestore persistent cache as outbox) and ADR-010 (Inbox as a flag). Checked with the domain tests (`NoteEditsTest`, `NotePreviewTest`), the emulator tests `FirestoreNoteWorkspaceEmulatorTest` and `FirestoreNoteRulesEmulatorTest`, and the Phase 8 browser checks. The owner review is queued (owner-review-queue.md, Q28 to Q34).

## Context

- Phase 8 adds the Notes view (v3 notes.html), Notes on Topic pages, Notes in Inbox, Note capture and Note reminders.
- A Note's text already syncs through its own revisions (ADR-004). The Notes index, Topic pages and Inbox need a light listing of every Note without reading every revision body.
- D39: a Note made outside a Topic waits in Inbox; a Note made in a Topic does not. A Note can belong to several Topics. Unlinking never deletes the Note.
- The listing and the text are written by two different parts of the app (the workspace store and the NoteSyncer). Neither may overwrite the other's fields.

## Decision

### Documents

| Path | Fields | Written by |
|---|---|---|
| `users/{uid}/notes/{noteId}` | `title`, `preview` (plain text, at most 280 characters; the rules allow 1000), `headRevisionId`, `deletedAt` (Trash), `copiedFrom` | NoteSyncer, with each pushed head (ADR-004) |
| same document | `inbox` (bool), `discarded` (optional), `createdAt` (set once), `updatedAt`, `schemaVersion: 1` | workspace store (filing) |
| `users/{uid}/topicNoteLinks/{topicId}_{noteId}` | `topicId`, `noteId`, `linked` (bool), `updatedAt`, `schemaVersion: 1` | workspace store |

- Both writers use merge writes of only their own fields, so a filing change and a text push to the same Note both survive (emulator test).
- The link id is made from the pair, so two devices linking the same Note to the same Topic write one document. Unlinking writes `linked: false`; nothing is deleted. The rules refuse any other id shape and refuse deletes.
- `createdAt` can be set once. A Note made before Phase 8 gets it on its first filing write.

### Behaviour

- **New note (index or Topic page):** the listing is written at once (`inbox = true` outside a Topic, plus a link inside one). The text is written only on the first edit. An untouched new note stays in the index as "Untitled note".
- **Capture a Note:** writes the listing, the link when a Topic is chosen, and a first revision with the typed title.
- **Change Topics:** one picker sets the full set of Topics. Linking any Topic clears `inbox`. Unlinking every Topic leaves the Note standalone, not back in Inbox. Undo restores the previous links and flag.
- **Inbox:** Notes with `inbox = true` show next to Inbox Tasks, newest first. "Link to Topic" and "Keep standalone" work on both kinds in one batch (`triageInbox`).
- **Trash:** Move to Trash sets `deletedAt` through the NoteSyncer. A trashed Note leaves the index, Topic pages and Inbox. Opened by its address it shows the Trash banner with Restore. The Trash page arrives in Phase 9.
- **Conflicts:** the kept version becomes the head and updates the listing. The other version becomes a copy Note ("… (other device copy)") with the same Topic links: in the index for Keep both, in Trash for Keep this or Keep other.
- **Save state in the index:** the open Note's row shows its own state (Offline, Sync failed, Conflict, Not saved). Other rows show a warning only while this device holds listing changes the server has not confirmed and the browser is offline. A Note's text state is known only while it is open.
- **Synced line:** "On this device and the server, HH:MM" uses the listing's last server change (with the date when it was not today).
- **Search in the index:** title and preview only, not the full body.

## Consequences

- The index, Topic pages and Inbox read one snapshot of the listing collection, never revision bodies.
- The preview is a copy of the head text. A Note edited only offline shows its old preview in other places until the push is acknowledged.
- A filing change bumps `updatedAt`, so "updated just now" in the index also covers linking and Inbox changes.
- Body text beyond the 280-character preview is not searchable in the pilot.
- Same as ADR-009: two devices changing the same filing field keep the last write to reach the server. Text conflicts keep the ADR-004 compare flow.

## Alternatives considered

| Option | Why not now |
|---|---|
| `topicIds` array on the Note | Two devices linking different Topics at once would overwrite each other; array unions need care in rules and offline. |
| Random link ids with a query to find the pair | Linking on two devices would make two documents for one fact. |
| A separate listing document per Note | Two documents to keep in step for one Note; the existing head document already holds the title. |
| Delete link documents on unlink | Deletes cannot be undone offline and the rules refuse deletes everywhere else. |
| Search over full bodies | Needs every body on every device or a server index; out of scope for the pilot. |
