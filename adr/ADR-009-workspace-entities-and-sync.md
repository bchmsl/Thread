# ADR-009: Workspace entities and their sync (statuses, Topics, preferences)

Status: Proposed (2026-11-13). Written in Phase 6 while the owner was away. Built on ADR-002 (Firestore persistent cache as the device store) and ADR-005 (per-user rules). Checked with the emulator tests in `shared/data/src/jsTest/.../FirestoreWorkspace*EmulatorTest.kt` and the Phase 6 browser checks (96 of 96). The owner review is queued (owner-review-queue.md, Phase 6 items).

## Context

- Phase 6 adds Topics (D10, D42 later), custom statuses that map to four fixed categories (D11), and two workspace preferences from v3 Settings ("Topics opens as", "Capture starts as").
- These are app-owned content, so they live in Firestore under `users/{uid}` (CLAUDE.md, D08). They must work offline, show a truthful save state, and never lose a change silently.
- Notes already use per-document revisions (ADR-004). Topics, statuses and preferences are small records with no long text history, so they do not need revisions.

## Decision

### Documents

| Path | Fields | Notes |
|---|---|---|
| `users/{uid}/statuses/{statusId}` | `name` (1 to 24), `category` (`planned`, `active`, `paused`, `done`), `sortOrder` (int), `deletedAt` (timestamp or absent), `updatedAt`, `schemaVersion: 1` | Delete is a `deletedAt` write, so Undo works and nothing is removed. |
| `users/{uid}/topics/{topicId}` | `name` (1 to 200), `color` (5 ids), `statusId`, `goal`, `leftOff`, `nextStep` (each up to 10,000), `createdAt`, `updatedAt`, `schemaVersion: 1` | `createdAt` is set by the first write (`request.time`) and the rules keep it unchanged after that. |
| `users/{uid}/prefs/main` | `topicsView` (`list`, `board`), `captureType` (`task`, `note`, `topic`), `updatedAt`, `schemaVersion: 1` | One document per account. |

- Ids are made on the client: `t-` or `s-` plus a random id. A Topic shows at once, also offline.
- The seed statuses have fixed ids: `s-planned`, `s-active`, `s-paused`, `s-done`. Two devices that seed at the same time write the same four documents, not eight.
- The rules (`firebase/firestore.rules`) accept only these shapes, with every field present after the write. Nothing can be deleted.
- A document this app version cannot read (other `schemaVersion`, damaged) is left alone, not shown, and counted in the sync panel.

### Device store and outbox

- The Firestore persistent cache (`persistentMultipleTabManager`) is the device's only copy and outbox, as for Notes (ADR-002). There is no second queue.
- `WorkspaceStore.write` applies a list of writes as one batch, to the cache at once, and sends it when online. The screens only show what the store emits, so they always show what the device stored.
- Save state comes from snapshot metadata: a pending write is Syncing (online) or Saved locally, offline. All synced only after the server acknowledged every write.
- A write the server refuses is taken back out of the cache by the SDK. The store lists it in `refused`, the chip shows Sync failed, and the sync panel names the Topic, status or preferences with a link. The refused change is not kept anywhere else (queue item).

### Seeding

- A new account needs the four default statuses. Only the server can say an account has none: `needsSeed = !fromCache && statuses.isEmpty()`. The cache saying "empty" is not enough, because it may not have them yet.
- The screen model seeds once per sign-in on that answer.
- Status edits are refused with `NotReady` while no status is stored. An edit never writes the seed as a side effect. Capturing a Topic before statuses load is refused the same way.

### Field-level merge writes

- Each edit carries the value it was made from (`before`). The store writes only the fields that differ, as a merge.
- So two devices that change different fields of the same Topic or status both keep their change (emulator test `editsToDifferentFieldsOnTwoDevicesBothSurvive`).
- The same field changed on two devices: the last write to reach the server wins. No conflict is shown. These fields are short and v3 has no conflict UI for them.
- A new document (new Topic, added status, seed) writes every field.
- Preferences: until the account has a stored preferences document, the first save writes every field, because the rules accept the document only whole (`savePrefs`, `WorkspaceData.prefsStored`). Found by the Phase 6 browser checks; emulator test `theFirstPreferencesSaveIsAcceptedAndLaterSavesMerge`.

### Status semantics

- Category decides behavior (D11). The name is free text. A rename never changes where a Topic shows.
- Deleting a used status asks where its Topics go, and moves them in the same batch as the delete. Undo writes both back.
- A Topic whose status is missing or deleted shows under the first status, everywhere.
- Topic completion is not a checkbox; only Task completion is (CLAUDE.md).

## Consequences

- Works offline for every Topic, status and preferences change, and survives an offline reload (browser checks O1 to O7).
- Small, readable rules. No Cloud Functions, no server.
- Known race: two devices open a brand-new account at the same moment, both see no statuses, and both seed. If one renames a seed status before the other's seed reaches the server, the late seed resets that name. Not handled in the pilot (queue item).
- Last writer wins per field. If the same field is edited on two devices, one edit is lost without a notice.
- A refused write is lost after reload. In practice only a rules bug or a schema mismatch can cause it.

## Alternatives considered

| Option | Why not now |
|---|---|
| One workspace document with every status and Topic | Every edit rewrites the whole workspace, two devices clash on every change, and the 1 MiB document limit caps the workspace. |
| Revisions per Topic, like Notes | More code and UI for short fields. Can be added for `leftOff` and `nextStep` later if lost edits show up in the pilot. |
| Hard delete of statuses | No Undo, and other devices could keep Topics pointing at a missing status with no record of it. |
| Seeding from any empty snapshot | The cache can report empty before the server answers, which would write the seed again on every new device. |
