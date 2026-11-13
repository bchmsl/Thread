# ADR-005: Auth and security rules

Status: Proposed (2026-11-13). Written in Phase 2 (M1-B), emulator only. Evidence: `adr/M1-RESULTS.md`.

## Context

The web app talks to Firestore straight from the browser. There is no app server, so Firebase Auth and the Firestore security rules are the whole security boundary. The owner chose Google sign-in (O3, relayed by the orchestrator session). Phase 2 runs only against the local emulators with the `demo-thread` project: no `firebase login`, no real project, no billing.

## Decision

### Sign-in

- Firebase Auth, Google provider only, through `signInWithPopup`. No other provider and no extra scopes. Against the Auth emulator, the popup is the emulator's account picker.
- `FirebaseAccountSession` maps Auth to the domain port `AccountSession`. The account id is the Firebase `uid` (`AccountId`). The label (email or display name) is shown in the account bar only and never logged.
- Closing the popup is not an error (`auth/popup-closed-by-user`, `auth/cancelled-popup-request`, `auth/user-cancelled`). Other failures carry only the Firebase error code, for example "Sign-in failed (auth/network-request-failed)". Messages and user details are never logged.
- Signing out keeps the drafts and the Firestore cache on the device (ADR-002).

### Security rules (`firebase/firestore.rules`)

| Path | Read | Create | Update | Delete |
|---|---|---|---|---|
| `users/{uid}/notes/{noteId}` | Owner | Owner, valid Note | Owner, valid Note | Never |
| `users/{uid}/notes/{noteId}/revisions/{revisionId}` | Owner | Owner, valid revision, `createdAt` = server time, no `discardedAt` | Owner, only `discardedAt` or `createdAt` change, both to server time | Never |
| Anything else | Never | Never | Never | Never |

- Owner means `request.auth.uid == uid`. Signed-out visitors and other accounts get nothing.
- A valid Note has only the known keys, `schemaVersion` 1 and `updatedAt` equal to server time. Optional fields are type-checked.
- A valid revision has all required keys and no others, `schemaVersion` 1, a string body and a non-empty device id.
- An unknown field is refused. E3 uses this: the test hook adds `rejectedForTesting` to a write (ADR-004).
- The rules do not check that `parentRevisionId` exists, or that `headRevisionId` points to a real revision. The client handles both, and a wrong pointer can only confuse the owner's own data.

### Emulator setup

- `firebase.json` and `.firebaserc`: project `demo-thread`, Auth on `127.0.0.1:9099`, Firestore on `127.0.0.1:8085`, emulator UI off, `singleProjectMode`. A `demo-` project has no cloud resources, so nothing can reach a real project.
- `firebase/emulators.sh start | exec "<command>"` runs `firebase-tools@15.36.0` through pinned `npx`. Nothing is installed globally.
- `FirebaseBackend` connects to the emulators with the placeholder key `demo-api-key-for-emulators-only`. It is not a secret: the emulators accept any value.
- Emulator tests are named `*Emulator*`. They run only with `-Pthread.emulatorTests` (`shared/data/build.gradle.kts`), inside `emulators.sh exec`. A plain `./gradlew build` skips them.
- `shared/data/karma.config.d/mocha-timeout.js` raises the mocha timeout to 30 s for the emulator tests.
- `@firebase/rules-unit-testing` 6.0.0 is a test-only dependency. It reads `process.env`, which a browser does not have, so the rules test defines an empty `process.env` first.

### Test hooks in app code

These are for the spike and the automated checks. They must be removed or kept out of a build that talks to a real project:

| Hook | Where | Used for |
|---|---|---|
| `signInToEmulatorForTesting` | `FirebaseAccountSession` | "Sign in as synthetic-user-a/b" on the spike page: signs in to the Auth emulator with unsigned synthetic claims, without the popup |
| `rejectWritesForTesting` | `FirestoreNoteSyncRemote` | E3: "Make the server refuse writes" |
| `goOfflineForTesting`, `goOnlineForTesting` | `FirebaseBackend` | Emulator tests |
| `failWritesForTesting` | `IndexedDbDraftStore` | E4 (Phase 1) |

## Evidence

Rules tests, `FirestoreRulesEmulatorTest`, 8 passed against the Firestore emulator:

- `ownerCanWriteAndReadTheirNotes`
- `otherAccountsAndSignedOutVisitorsCannotReadOrWrite`
- `nothingCanBeDeleted`
- `revisionContentNeverChangesButCanBeDiscarded`
- `theSameCopyOfAVersionCanBeWrittenAgain`
- `unknownShapesAreRefused`
- `pathsOutsideNotesAreDenied`
- `aMergeWriteThatDoesNotNameDeletedAtKeepsTheNoteInTrash`

Isolation is also checked through the real client: `FirestoreNoteSyncEmulatorTest.anotherAccountCannotListenOrWrite`, and the browser check `isolation-other-account` (two accounts, each sees only its own Note).

## Consequences

- No credential exists in the repository, the config, the logs or the emulator data.
- The rules are the only server-side check. Every new collection needs its own rules and rules tests before the client writes to it.

## Known limits

- The real Google popup was not run. Only the emulator's account picker and the synthetic sign-in hook were used.
- Auth emulator tokens are unsigned. The rules were tested only with emulator tokens.
- No App Check. Not needed for a personal app on the free plan; can be added later.

## Before a real project (O4)

1. The owner creates the project, runs `firebase login` and enables Google sign-in. Not part of any phase so far.
2. The real web config goes into a git-ignored local file, with a committed `.example` file. No service-account keys anywhere.
3. Remove the test hooks above, or keep them out of that build.
4. Deploying the rules is its own approved action. Rules tests run against the emulator first.

## Proposed document updates

- `03-architecture.md`: Google-only Firebase Auth, the rules table above, and "rules plus rules tests before any new collection".
- Not changed until the owner accepts this record.
