# ADR-016: Encrypted sync of the Jira and GitHub connections

Status: Proposed (2026-11-14). The direction is the owner's choice (D47). The owner asked "How can we save credentials to remote? (jira token, clientid, github token)", then answered "Lets do A" (relayed by the orchestrator session). Option A: a passphrase in Settings, the browser encrypts the connections with a key made from it, and Firestore stores only the encrypted copy. The details below are the worker's choices and are queued for review (owner-review-queue.md, Q62 onwards). This ADR supersedes the "Never in Firestore" line of ADR-008 for the connection records only. Checked with unit tests, emulator rules tests and headless Chrome on the emulators, with SYNTHETIC tokens and passphrases only. The rules are not deployed yet.

## Context

- ADR-008 kept the Jira and GitHub connections in one browser profile (`thread-connections`). A token had to be typed again in every browser. Q8 proposed that scope.
- There is no app server (ADR-006, ADR-007). Firestore is the only shared store, and its rules are the whole server-side check (ADR-005).
- 03-architecture.md: no credentials in "ordinary Firestore records". 04-data-and-sync.md: integration credentials need "a dedicated secure auth mechanism" and "platform-appropriate protected handling".
- D08 keeps Jira content (descriptions, comments, fields) out of Firestore. Connection records are not Jira content, and saved copies stay on the device.

## Decision

### Crypto

| Part | Choice |
|---|---|
| API | WebCrypto (`crypto.subtle`) behind the `VaultCrypto` adapter in `shared/data` (`WebCryptoVault` in jsMain). The domain sees only `ConnectionSync` and `SecretText`. No new dependency. |
| Key | PBKDF2-HMAC-SHA-256 from the passphrase, a random 16-byte salt per record, 600,000 iterations (the OWASP Password Storage Cheat Sheet figure, checked 2026-11-14). The derived AES key is non-extractable, usages `encrypt` and `decrypt` only. |
| Cipher | AES-GCM 256, a fresh random 12-byte IV for every write, 128-bit tag. |
| Additional data | `thread/connections/v1/<uid>`. A record copied to another account, or a different payload version, does not open. |
| Payload | JSON `{payloadVersion: 1, jira?: {site, cloudId, email, token}, github?: {token}}`: the same fields as the device record. The plaintext bytes are zeroed after use. |
| Passphrase | At least 12 characters. Never stored, never sent. It lives only as `SecretText` while a form is submitted, and the fields are emptied on submit. |

### Firestore document

One document per account: `users/{uid}/vault/connections`.

| Field | Rule |
|---|---|
| `schemaVersion` | `1` |
| `kdf` | `"PBKDF2-SHA-256"` |
| `iterations` | int, 600,000 to 10,000,000 |
| `salt` | base64 of 16 bytes (24 characters) |
| `iv` | base64 of 12 bytes (16 characters) |
| `ciphertext` | base64, 24 to 8,192 characters |
| `updatedAt` | server time |

- Read and delete: the owner only (`signedInAs(uid)`), and only the id `connections`.
- Create and update: the owner only, the exact key set above (no other field, so no plaintext field can be added), the types and sizes above.
- Every other path under `users/{uid}/vault` is refused.
- Rules tests: `FirestoreVaultRulesEmulatorTest` (another account cannot read or write, wrong shapes are refused, a plaintext field is refused).

### This device

- IndexedDB database `thread-vault-{uid}`, store `keys`, record `device`: `{recordVersion, cryptoKey, kdf, iterations, salt, appliedIv, waiting}`. `cryptoKey` is the non-extractable `CryptoKey` itself, so the passphrase is typed once per browser and the key bytes never reach script.
- `appliedIv` is the IV of the last record this device wrote or applied. It marks which revision this device has.
- `waiting` means a change made here is not saved to the account yet.
- One database per account, so a second account on the same browser profile never sees the first account's key.
- `thread-external-cache` (saved Jira and PR copies) never syncs.

### Flows

| Flow | What happens |
|---|---|
| Turn on | Online only. Passphrase twice, at least 12 characters. A new salt and key. The record is created in a transaction that requires no document to exist. |
| Check | On sign-in, on every offline or online change, and on opening Settings. Reads the server (`getDocFromServer`). No record: Off, and a key here is deleted. A record but no key here: Locked. A key for another salt: Locked, "the passphrase changed on another device", and the old key is deleted. A newer IV with the same key: the record is applied here (last write wins), and the Jira and GitHub views load again. A change waiting here: it is written. |
| Unlock | Online only. Derives the key from the record's salt. A wrong passphrase changes nothing. The right one replaces this browser's connections with the saved ones. |
| Connect, new token, disconnect | Saved here first, as before. Then the whole record is written again. Offline, it is marked `waiting` and written on the next check. |
| Change passphrase | Online only. Needs the current passphrase. A newer record from another device is applied first. A new salt, a new key, a new record. Other devices go Locked once. |
| Turn off | Online only, with a confirmation dialog. Deletes the record and the key here. Every browser keeps the connections it has. |
| Forgotten passphrase | No recovery, and the UI says so: turn sync off, connect again, turn on with a new passphrase. |
| Fixture mode | Not available. The row says so and offers no action. |

- Every write is a Firestore transaction that expects the salt this device's key belongs to. A device with an old key can never undo a new passphrase, and it cannot bring back a record that another device turned off.
- A write that finds a newer record retries once after a check, then reports "changed on another device meanwhile".

### Errors and secrets

- `ConnectionSyncProblem`: TooShort, WrongPassphrase, Offline, ChangedElsewhere, Damaged, Refused, Failed, Device. Each has a fixed text in Settings. No message carries a token, a passphrase or a record.
- A record that does not decode, has a bad IV or salt, fewer iterations than this build uses, or more than 10,000,000 reads as Damaged. A device with the key shows "The saved copy cannot be opened" and offers "Replace saved copy". Connections here never change because of a damaged record.
- `SecretText` rules from ADR-008 still apply: no token or passphrase in logs, the console, URLs, errors or the service worker cache. The browser checks search the console, storage, the URL and the worker cache for every synthetic secret.

## Consequences

- A token is typed once per account, not once per browser. A new browser needs sign-in and the passphrase once.
- Anyone with the passphrase and access to the account can read the tokens. Without the passphrase, Firestore, Google and anyone with the database see only ciphertext. The passphrase is the only thing that protects it: a weak one can be guessed offline from the record.
- Last write wins for the whole record. A disconnect on one device disconnects every device on its next check. A change made offline on a device whose passphrase was changed elsewhere is lost on that device's next check (it goes Locked and asks for the new passphrase).
- AES-GCM cannot tell a wrong passphrase from a changed record. On a device without the key, a damaged record reads as a wrong passphrase.
- The page origin is shared with the public `bchmsl.github.io` site. The non-extractable key can still be used by any script on that origin while the page is open, as with the tokens in ADR-008.
- The Firestore SDK's own local cache keeps a copy of the encrypted document.
- Raising the iteration count later needs a step that re-seals old records. Until then a new device reads them as Damaged.
- Until the rules in `firebase/firestore.rules` are deployed, the real project refuses the vault document, and the row says "The server refused to read the saved copy". Nothing else changes.

## Evidence

- Unit tests: `VaultConnectionSyncTest` (12), `WebCryptoVaultTest` (3), `IndexedDbDeviceVaultStoreTest` (2).
- Emulator tests: `FirestoreVaultRulesEmulatorTest` (3), `FirestoreVaultEmulatorTest` (2, with the real WebCrypto and 600,000 iterations on two emulated devices).
- Headless Chrome on the emulator build, three scratch browser contexts (devices), SYNTHETIC tokens: turn on, a short and a mismatched passphrase, the record shape and its absence of plaintext, the non-extractable key, unlock on a second device, a wrong passphrase, a change on one device reaching another, change passphrase, a tampered record (no data loss, repair), offline waiting and upload, 390 px width, turn off, fixture mode, and no secret in the console, storage, URL or worker cache.

## Not verified

- The real Firebase project: the rules are not deployed.
- Safari and Firefox. Only headless Chrome ran the browser checks.
- Two devices writing in the same second: covered by the transaction and the unit tests, not by a live race.
