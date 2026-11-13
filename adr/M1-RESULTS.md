# M1 results

Real output of the M1 experiments. Every run used synthetic data only.

| Part | Where |
|---|---|
| M1-A (Phase 1): editor, paste, IME, local drafts, Phase 1 E1, E2, E4 | `ADR-003`, "Verification" |
| M1-B (Phase 2): Firebase sync and conflicts, E1–E4, C1–C3 | This file |
| S1–S2, J1–J4, G1–G3 (Jira, GitHub) | Not run yet (Phase 3) |

## M1-B, 2026-11-13

### Environment

| Item | Version |
|---|---|
| Firebase JS SDK | `firebase` 13.3.0 |
| Rules test library | `@firebase/rules-unit-testing` 6.0.0 (tests only) |
| Emulators | `firebase-tools` 15.36.0 through pinned `npx`; Firestore emulator JAR v1.22.0 (cached, accepted by the owner) |
| Java, Node | OpenJDK 21.0.12.1, Node v25.6.1 |
| Browser | Chrome 157.0.8103.79, headless |
| Project | `demo-thread` (no cloud project), Auth on 127.0.0.1:9099, Firestore on 127.0.0.1:8085 |

No `firebase login`, no real project, no deploy, no billing. No JDK or system trust setting was changed.

### Method

- The production bundle was served from localhost. A script drove headless Chrome over the DevTools protocol.
- Each device is its own browser context, with its own IndexedDB, Firestore cache and device id. This is the same as a separate browser profile.
- Offline is DevTools network emulation (`Network.emulateNetworkConditions`). It also fires the page's `offline` and `online` events.
- Sign-in used the spike's test hook, "Sign in as synthetic-user-a" or "-b". It signs in to the Auth emulator with synthetic claims, without the popup.
- The script read the emulator data over the emulator's REST API with the emulator's admin token. The data was cleared once before the run, so the last check sees everything the run wrote.
- Each check waited for the status line's state, not for fixed times. Waits of 2.3 to 3 s were used only to let the 2 s push debounce pass.
- Evidence: the script log, `results.json` and 10 screenshots, kept outside the repository in the session scratchpad.

### Browser checks: 13 of 13 passed

| Check | Steps | Result |
|---|---|---|
| Sign-in, first sync | Open signed out, then sign in | Signed out: "Saved locally / Local only, no sync", banner "Local only". After sign-in: Synced. Server: 1 Note, 1 revision with the sample text. No page errors. |
| E1 offline write, reopen | Sign in, go offline, type, wait past the debounce, close the tab while offline, reopen online in the same profile | While offline: "Saved locally / Syncs when online", banner "Offline", states Saving then Offline, never Synced. The server did not have the edit. Reopened: Synced, the text is there, the server's current version has the edit, 2 revisions. |
| E2 close right after typing | Type, close the tab 0 ms after the last key, reopen | Status at close: Saving. Reopened: Synced, the text is there, 1 current version on the server. |
| E3 server refuses a pending write | Turn on "Make the server refuse writes", go offline, type, go online | "Needs attention / Saved locally, sync failed", banner "Sync failed". States Saving, Offline, Syncing, SyncFailed, never Synced. The text stayed in the editor. The refused write is not on the server. Hook off, Retry sync: Synced, and the server has the text. |
| E4 local save fails | Turn on "Make local saves fail", type, wait 3 s | "Needs attention / Not saved on this device". States Saving, LocalFailed, never Synced. Hook off, Retry save: Synced, text kept. |
| C1 two devices edit offline, keep this device | Two devices of one account, both offline, both type, both online. Device A: Compare versions, Keep this device's version | Both devices: "Changed on two devices". The compare view shows A's and B's text; focus on its heading. Toast: "Kept this device's version. The other one is in Trash." B takes A's version with no prompt. B's version is a Note in Trash with B's text. One current version on the server. |
| C1b keep the other device | Same, then Keep other device's version | Toast: "Kept the other device's version. Yours is in Trash." A shows B's text. A's version is a Note in Trash. |
| C1c keep both | Same, then Keep both | Toast: "Kept both. The other device's copy is a second note." Both devices show A's text. B's version is a second Note, "Synthetic note for the editor spike (other device copy)", not in Trash. |
| Narrow conflict view | C1 at 390 × 844 | Columns stacked, no sideways scroll. The spike header steps aside while comparing (as in v3, the compare view takes the pane) and comes back after resolving. |
| C2 repeated reconnects | 5 rounds: type online, go offline, type, go online | 10 edits made 5 new revisions. No two live revisions share a parent (no duplicates). One current version with the last edit. |
| C3 Trash plus a stale offline edit | A goes offline and types. B moves the Note to Trash. A goes online | The Note stays in Trash on both devices. A's edit is kept inside it as the current version. Restore on B takes it out of Trash on A too. |
| Account isolation | A third device signs in as synthetic-user-b | User B sees only their own Note. The server holds 2 accounts. |
| Emulator holds only app content | Read all emulator data after the run | Root collections: `users` only. 6 Notes, 24 revisions. No field outside the ADR-004 shapes. Every revision text has the synthetic sample's or a check's synthetic wording (0 without). |

### Gradle

Command, run once after the last code change:

```bash
sh firebase/emulators.sh exec "./gradlew build :shared:domain:iosSimulatorArm64Test -Pthread.emulatorTests --continue"
```

Result: `BUILD SUCCESSFUL in 48s`, 102 tasks, exit code 0. ktlint ran as part of `build` and passed.

| Test task | Passed | Contents |
|---|---|---|
| `:shared:domain:iosSimulatorArm64Test` | 64 | NoteSyncer 18, DraftAutosaver 18, RichTextJson 16, RevisionHistory 5, NoteSaveState 4, LinkHref 3 |
| `:shared:domain:jsNodeTest` | 64 | Same common tests. UP-TO-DATE in this run, from an earlier passing run of the same inputs. |
| `:shared:data:jsBrowserTest` | 23 | IndexedDbDraftStore 9, FirestoreRulesEmulator 8, FirestoreNoteSyncEmulator 6 |
| `:webApp:jsBrowserTest` | 16 | RichTextEditorJson 8, NoteEditor 7, SearchShortcut 1 |

New in Phase 2:

- NoteSyncer: first sync, content that is not the person's waits for or yields to the server, Synced only after acknowledgement, max wait, one write in flight, a newer version replaces unchanged content, edits on two devices become a conflict, each D41 choice, devices that discarded each other's versions, refused write keeps the text, a write still waiting after a reload is not written again, unreadable versions pause writing, Trash keeps the content.
- RevisionHistory: leaves, newest first, discarded revisions, missing parents and cycles.
- NoteSaveState: status order and Synced only after acknowledgement.
- DraftAutosaver: 3 rebase tests. IndexedDbDraftStore: the base revision is stored, a Phase 1 record is read without one. NoteEditor: replacing the content is not an edit.
- FirestoreNoteSyncEmulator, through the real SDK and emulator: a push is acknowledged only by the server; a refused write fails and disappears from the local copy; another account cannot listen or write; a resolution discards and copies in one write; a later edit does not take a Note out of Trash; two devices editing offline get a conflict and resolving it loses no text.
- FirestoreRulesEmulator: the 8 rules tests listed in ADR-005.

Kotlin/Native printed "could not open … cache" linker warnings during the iOS test link. They did not fail the build.

### Other checks

| Check | Result |
|---|---|
| Licences | `firebase` and `@firebase/rules-unit-testing` with all their dependencies: 128 npm packages. Apache-2.0 88, MIT 24, BSD-3-Clause 10, ISC 5, 0BSD 1. All permissive. |
| Bundle | `thread.js` 2.16 MiB minified, up from 1.25 MiB in Phase 1. Mostly the Firebase SDK (app, auth, firestore). |
| Secrets | A key-format search (private keys, Google, GitHub, Atlassian, Slack, `client_secret`) over the sources, config and ADRs found nothing. The only key is the emulator placeholder `demo-api-key-for-emulators-only`, which is not a secret. `firestore-debug.log` is git-ignored. |

## Observations

- **Long-offline device.** In an earlier run, a script error left an offline device page open. Its queued write reached the server minutes later, when the script disconnected. The result was a conflict (a second current version), not an overwrite, so the person would have been asked. Not planned as a check, but it is the behaviour ADR-004 intends.
- **E4 and sync.** While local saves failed, sync still pushed the on-screen text to the server. The status kept saying "Needs attention / Not saved on this device", which is true for this device. The text was safer than the status says. Proposal: keep it, because the device storage problem is the one the person must fix.
- **E2 and the draft store.** E2 passed with both stores. It does not show that Firestore alone would pass, and that was not tested. The draft store stays for the reasons in ADR-002: the SDK gives no "stored on this device" signal, and it drops a refused write (E3).

## Not verified

- Safari and Firefox. Only headless Chrome 157.
- Real network faults: slow, flaky or half-open connections. Offline was DevTools emulation only.
- An offline reload. There is no offline app shell yet, so E1 closes offline and reopens online.
- Two tabs of one profile editing the same Note.
- The real Google popup, and any real Firebase project.
- Crash or OS kill right after typing.
- Long-lived Notes with many revisions (listener size, ADR-004).
- C2 for Tasks and links: they do not exist yet. Only Note revisions were checked.
