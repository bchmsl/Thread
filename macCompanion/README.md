# Thread Calendar (Mac app)

A menu bar app that reads one calendar on this Mac and uploads its meetings to the owner's Thread Firestore, so Thread shows them on every device, also when Thread is closed. Decision D78, [ADR-020](../adr/ADR-020-calendar-meetings-sync.md).

- **Reads:** one calendar you pick, through EventKit full access (the only level that can read events). It never changes the calendar: the build fails if an EventKit write call appears.
- **Uploads** to `users/{uid}/meetings`, from yesterday to 14 days ahead: title, start and end (or all-day days), your response, location, one allowlisted `https` join link and a series key. Declined and cancelled meetings are not uploaded. Plus one status document, `users/{uid}/meetingSource/main`.
- **Never leaves the Mac:** attendees, organizer, notes, e-mail addresses, the signed-in e-mail and uid, tokens.
- It is the only writer of `meetings` and `meetingSource`. Thread web only reads them.

## Requirements

- macOS 14 or later, Swift 6 (Xcode or the Command Line Tools).
- An **Apple Development** certificate in the login keychain, and Apple's **WWDR G3** intermediate certificate. Without the intermediate, `codesign` fails with `errSecInternalComponent`. On a new Mac: sign in to Xcode › Settings › Accounts with your Apple ID and create the certificate, then install "Worldwide Developer Relations - G3" from [Apple's certificate page](https://www.apple.com/certificateauthority/). The stable signature keeps the calendar permission and the Keychain item across rebuilds.

## Config (never committed)

| File | Keys | From |
|---|---|---|
| `firebase/web-config.properties` | `apiKey`, `projectId` | The web app's config (see `firebase/web-config.example.properties`) |
| `macCompanion/oauth-desktop.properties` | `clientId`, `clientSecret` | Google Cloud console of the Thread project › APIs & Services › Credentials › Create credentials › OAuth client ID › **Desktop app** |

Copy `oauth-desktop.example.properties` to `oauth-desktop.properties` and fill it in. Both files are git-ignored. Never paste the values into chat, an issue or a commit. The build script reads them without printing and writes them only into `Config.plist` inside the app bundle in `~/Library/Caches/ThreadCalendar-build`.

All four values set: cloud mode. None set: the build makes the emulator variant instead. Anything in between stops the build and names the missing key.

## Build and install

```bash
macCompanion/scripts/build-app.sh --install
```

- Builds in `~/Library/Caches/ThreadCalendar-build` (never in iCloud Drive) and installs `~/Applications/Thread Calendar.app`.
- A running copy is quit and reopened. A first install is not opened.
- `--emulator` builds "Thread Calendar Emulator" (bundle id `com.bachana.ThreadCalendar.emulator`) for the local emulators (`demo-thread`, `127.0.0.1:9099` and `:8085`). It has its own Keychain item and diagnostics folder.

## First run

1. Open **Thread Calendar** from `~/Applications`. A calendar icon appears in the menu bar.
2. **Allow calendar access…** and allow full access.
3. **Calendar ▸** pick the work calendar.
4. **Sign in to Thread…** and finish in the browser with the Google account you use for Thread.
5. Optional check: the menu's "Signed in as …" submenu shows the User UID. Compare it on screen with Firebase console › Authentication › Users.
6. Turn on **Start at login**.

If sign-in fails, the alert shows a code and the fix:

| Code | Fix |
|---|---|
| `INVALID_IDP_RESPONSE` | Firebase console › Authentication › Sign-in method › Google › safelist the Desktop client ID, then sign in again |
| `API_KEY_…` | Google Cloud console › Credentials › the Web API key: let it call the Identity Toolkit, Token Service and Cloud Firestore APIs from apps without a website (relax the website restriction), then sign in again |
| `oauth_access_denied` | The browser sign-in was cancelled. Try again |
| `timeout` | The browser step took over 5 minutes. Try again |

## Menu

| Item | What it does |
|---|---|
| Status line | "Up to date, 10:42, 18 meetings", or the problem (see below) |
| Calendar ▸ | The calendars on this Mac, by account. The pick is kept by identifier, with the title as a fallback |
| Signed in as … ▸ User UID | Shown on this Mac only |
| Sync now | Runs a pass now, also after a refused upload or a backoff |
| Start at login | Login item (`SMAppService`) |
| Stop syncing and remove meetings… | Deletes every uploaded meeting and the status document, then signs out on this Mac. Your calendar does not change; Topic links stay |
| Open Thread | Opens Thread web |

## When it syncs

- 30 s after the calendar changes (`EKEventStoreChanged`, debounced), every 15 minutes, on wake, at day change, when the network comes back, and from Sync now.
- A pass compares the calendar with a local index and writes only what changed, in commits of up to 250 writes, the status document last. A pass with no changes writes nothing. The status document is rewritten at most every 30 minutes as a heartbeat.
- Once a day, after sign-in, or when the local index is missing, it also lists the remote ids and deletes strays.

| Status | Meaning |
|---|---|
| Not signed in / Sign in again | No account, or the token could not be renewed. Nothing is deleted |
| No calendar access / Pick a calendar | The status document says so; the uploaded meetings stay |
| Offline | Syncs when the network is back |
| Sync failed (code), retrying at HH:mm | Waits 1, 5, then 15 minutes |
| Upload refused: update Thread Calendar | The rules refused a write. No retry until Sync now |
| Another Mac is uploading meetings | Another Mac wrote in the last 24 hours. Take over only if it has stopped |

## Diagnostics

`~/Library/Application Support/ThreadCalendar/` (`ThreadCalendar-Emulator/` for the emulator variant):

- `results.json`: the last pass's counts and codes (writes, upserts, deletes, detached counters, outcome).
- `events.log` (rotated at 512 KB to `events.1.log`): one line per event with counts and codes.
- `index.json`: meeting id to content hash, used to find changes.

Keys and values are restricted to `A-Z a-z 0-9 _ . : -`. No titles, times, links, e-mail, uid or tokens are written.

## Tests

```bash
swift test --package-path macCompanion --scratch-path ~/Library/Caches/ThreadCalendar-build/test
```

The emulator suites (Auth REST and the upload pass against the committed `firebase/firestore.rules`) run only with `THREAD_CALENDAR_EMULATOR_TESTS=1`:

```bash
THREAD_CALENDAR_EMULATOR_TESTS=1 firebase/emulators.sh exec "swift test --package-path macCompanion --scratch-path $HOME/Library/Caches/ThreadCalendar-build/test"
```

All test data is SYNTHETIC.

## Uninstall

1. Menu › **Stop syncing and remove meetings…** (removes the uploaded meetings and signs out).
2. Turn off Start at login, quit, and delete `~/Applications/Thread Calendar.app`.
3. Optional: delete `~/Library/Application Support/ThreadCalendar/`.

## Layout

| Path | What |
|---|---|
| `Sources/ThreadCalendarKit/` | Ids, mapper, join links, differ, Firestore and Auth REST, sign-in (loopback + PKCE), Keychain, sync engine, diagnostics |
| `Sources/ThreadCalendar/` | The app: menu, EventKit reader, triggers |
| `Tests/ThreadCalendarKitTests/` | Unit tests and the emulator-gated suites |
| `scripts/build-app.sh` | Build, sign, install |
