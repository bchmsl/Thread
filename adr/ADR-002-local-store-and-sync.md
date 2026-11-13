# ADR-002: Local store and sync ownership

Status: Proposed (2026-11-13). Written in Phase 2 (M1-B), emulator only. Evidence: `adr/M1-RESULTS.md`.

## Context

Thread is local-first. A Note must be safe on the device before anything else happens, and the status line must never claim more than storage or the server confirmed (`02-ux-design.md`, "Quiet autosave indicator"). Phase 1 built the device side (`DraftAutosaver`, `IndexedDbDraftStore`, ADR-003). Phase 2 adds Firestore sync and must decide who owns which copy of a Note.

The Firebase JS SDK has its own offline copy (`persistentLocalCache`). Its write calls resolve only when the server acknowledges, and it offers no signal for "stored on this device". A refused write is also removed from its copy (E3).

## Decision

### Two device stores, two owners

| Store | Owner | Holds | Used for |
|---|---|---|---|
| IndexedDB draft store (`IndexedDbDraftStore`) | The app (`DraftAutosaver`) | The newest content of each open Note, and the server version it was edited from (`baseRevisionId`) | "Saved locally", and the text that survives a refused write, a failed sync or sign-out |
| Firestore local cache (`persistentLocalCache` + `persistentMultipleTabManager`) | The Firebase SDK | The device's copy of the server data, and writes still waiting for the server | Offline reads, the outbox, acknowledgement metadata |

- The draft store stays (plan item 8). Without it, "Saved locally" would have no durable signal behind it, and E3 would lose the refused text when the SDK drops the write.
- The app never reads or writes the Firestore cache directly. It goes through the SDK.
- Signed out, Notes use the database `thread-local` (Phase 1). Each signed-in account gets its own database, `thread-drafts-<uid>`, so two accounts on one browser profile never mix drafts.

### Layers

```
webApp (Compose HTML)                 Main.kt picks implementations
  NoteSession -> DraftAutosaver, NoteSyncer          (shared/domain)
                   |                |
            LocalDraftStore    NoteSyncRemote        (domain ports)
                   |                |
        IndexedDbDraftStore  FirestoreNoteSyncRemote (shared/data, jsMain)
                                    |
                             FirebaseBackend: app, Auth, Firestore, emulators
```

- `shared/domain` decides what to write and what the status is. It has no Firebase, DOM or SDK types.
- `shared/data` maps the ports to IndexedDB and Firestore with hand-written Kotlin externals (`firebase/app`, `firebase/auth`, `firebase/firestore`).
- `webApp` only composes and renders. `Main.kt` is the only place that picks implementations.

### Status truth

- `DraftAutosaver` owns the device part: Saving locally, Saved locally, or Needs attention.
- `NoteSyncer` owns the server part: synced, pushing, rejected, conflict, in Trash, unreadable.
- `noteSaveState` combines them in this order: LocalFailed, Saving, LocalOnly, Conflict, SyncFailed, Synced, Offline, Syncing. A device storage problem always wins, because until it is solved the text exists only on screen.
- "Synced" needs all of: no write in flight, the base revision acknowledged by the server (its snapshot has `hasPendingWrites` false), not discarded, and the same title and body as the local content.

### Network

- Online state comes from `navigator.onLine` and the `online` and `offline` events (`thread.web.platform.Network`). It only chooses between "Syncs when online" and "Syncing". It never makes anything count as synced.
- The SDK retries network failures itself. A failed `commit` is treated as a refusal.

### Only app-owned content

Firestore receives only Notes the person wrote: title, RichText body, revision metadata and a random device id. Future Jira and GitHub caches stay in their own device-local stores and never go through these classes.

## Consequences

- Two copies of the newest text exist on the device while a write waits. That is intended: the draft store is the safety copy, the Firestore cache is the outbox.
- A write that waited in the Firestore cache when the tab closed is sent by the next page load. The device recognises its own unacknowledged revision by `deviceId` and waits for it instead of writing again (E1, `writeStillWaitingAfterAReloadIsNotWrittenAgain`).
- The browser profile needs localStorage for a stable device id (`thread.deviceId`). Without it, the id lasts one page load, and a write that waited across a reload is written once more as a new revision. Sync stays correct.

## Known limits

- Two tabs of one browser profile editing the same Note share one device id and are not handled. Not tested.
- Signing out keeps the Firestore cache and the drafts on the device. Fine for a personal device; a shared device needs a "remove this device's data" action later.
- Drafts written while signed out do not move to the account on sign-in. They stay in `thread-local` and show again after sign-out. The signed-in Note opens from the server version. Whether to offer "add this device's notes to your account" is a later product decision.
- `navigator.storage.persist()` is still not requested (ADR-003).
- No offline app shell, so an offline reload cannot open the app (ADR-003). Solved in Phase 3 by ADR-008 (S1).

## Phase 4 review (2026-11-13)

- Checked against ADR-003 to ADR-008 for consistency. Still Proposed. The owner review is in the Phase 4 review queue.
- Proposed extension for Phase 6: Topics, statuses, Tasks, links, Reminders, Inbox entries and preferences use the Firestore persistent cache as their only device store and outbox. They get no second queue (`03-architecture.md`). Their status comes from snapshot metadata. That part is not tested yet; Phase 6 records it in its own ADR.

## Proposed document updates

- `03-architecture.md`: the two device stores and their owners, and the `LocalDraftStore` and `NoteSyncRemote` ports.
- `04-data-and-sync.md`: per-account draft databases, "Synced" only after server acknowledgement, and the status order above.
- Not changed until the owner accepts this record.
