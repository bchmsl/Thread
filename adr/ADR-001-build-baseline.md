# ADR-001: Build baseline

Status: Proposed (Phase 0, 2026-11-13). The owner approved the phase plan and Phase 0 on 2026-11-13 ("Approve, start P0"). The choices below become Accepted only after the owner reviews this record (Phase 4).

Phase 4 review (2026-11-13, owner away): checked for consistency with ADR-002 to ADR-008. Still Proposed; the owner review is queued. Later additions: Firebase JS 13.3.0 (ADR-002), Tiptap 3.33.1 (ADR-003), kotlinx-serialization 1.13.0, see `gradle/libs.versions.toml`.

## Context

Thread is a Kotlin/JS + Compose HTML web app with shared Kotlin domain and data code, built so a SwiftUI app can reuse them later (D03–D06, `03-architecture.md`). Phase 0 creates the repository and a minimal build. It contains no feature code.

The repository lives in iCloud Drive, next to the planning documents. Build output and Gradle state need special handling there (see "iCloud Drive").

## Decision

### Versions

All versions were checked on 2026-11-13.

| Item | Version | Why | Source |
|---|---|---|---|
| Gradle | 9.7.0 | The highest Gradle version the Kotlin Gradle plugin 2.4.21 documents (7.6.3–9.7.0). 9.9.1 is the latest Gradle, but Kotlin 2.4.21 does not list it. Distribution SHA-256 is pinned in `gradle-wrapper.properties`. | kotlinlang.org/docs/gradle-configure-project.html; services.gradle.org |
| Gradle wrapper jar | from 9.7.0 | SHA-256 `7a9ce74c…262c5d` matches the checksum Gradle publishes for 9.7.0 | services.gradle.org/distributions/gradle-9.7.0-wrapper.jar.sha256 |
| Kotlin | 2.4.21 | Latest stable. 2.5.0 is an RC. | Maven Central metadata; JetBrains/kotlin releases |
| Compose runtime, `html-core` | 1.12.2 | Latest stable. 1.13.0-beta02 is a pre-release. | Maven Central metadata |
| kotlinx-coroutines | 1.11.0 | Latest. `domain` exposes it as `api`, so later contracts can use `Flow`. It also proves the library resolves for the iOS targets. | Maven Central metadata |
| ktlint Gradle plugin | 14.2.0 | Latest on the Gradle Plugin Portal. It runs ktlint engine 1.5.0 by default (seen in the resolved `ktlint` configuration). A newer engine was not checked. | plugins.gradle.org metadata |

The build ran on Temurin JDK 21.0.12.1 (the machine's `JAVA_HOME`). No JVM toolchain is pinned, because no module compiles JVM code.

### Framework and plugins

- Plain Compose HTML. No Kobweb: it brings its own project model, routing and conventions, which the approved design does not need.
- Only two Gradle plugins per module: Kotlin Multiplatform, and the Kotlin Compose compiler plugin in `webApp`. The `org.jetbrains.compose` Gradle plugin is not applied. The build uses explicit library coordinates, and Compose HTML compiled and rendered without it.
- `google()` is in the repositories because `org.jetbrains.compose.runtime:runtime-js:1.12.2` depends on `androidx.compose.runtime:runtime-js:1.12.1`, which is published on Google Maven (seen in its POM).

### Modules

```text
:shared:domain  KMP. js(IR, nodejs) + iosArm64 + iosSimulatorArm64. commonMain + commonTest.
:shared:data    KMP. js(IR, browser) only. api(:shared:domain).
:webApp         Kotlin/JS browser executable, Compose HTML. Output file thread.js.
```

- Dependency direction: `webApp -> data -> domain`. `domain` depends on nothing in the project.
- The iOS targets on `domain` are compile-only. They make a JS-only API in common code fail the build early. No framework is built, and there is no `iosApp` yet. `data` gets iOS targets with the iOS spike.
- `DomainLayer`, `DataLayer` and the build page in `webApp` are temporary Phase 0 markers. Phase 1 deletes them when real code lands.

### Version control

- `git init` on `main`. No commit and no remote. The owner commits.
- `.gitignore` covers build output and tools (`build`, `.gradle`, `.kotlin` with no trailing slash, so the symlinks below are covered, and `node_modules/`), local config and secrets (`local.properties`, `.env*` except `.env.example`), Firebase logs, IDE files, `.DS_Store`, and the handoff zips in the root (`/*.zip`).
- Tracked: the documents, `design/`, the Gradle wrapper (jar included) and `kotlin-js-store/yarn.lock`. The lock file pins the npm packages that the Kotlin Gradle plugin installs.

### iCloud Drive

This follows the setup DevBoard uses and documents (`../DevBoard/README.md`, "iCloud Drive"). Each item is marked as observed or as a documented risk.

| Path | Setup |
|---|---|
| `build` (root, `shared/domain`, `shared/data`, `webApp`) | Symlinks into `~/Library/Caches/Thread/`, which mirrors the project layout |
| `.kotlin` (root) | Symlink into the same cache folder |
| `.gradle` | A real folder. DevBoard saw Gradle treat every output as stale when `.gradle` was a symlink. |
| All of the above | Extended attribute `com.apple.fileprovider.ignore#P`. Dropbox documents this attribute. Apple does not document it for iCloud, so it is an undocumented measure that worked for DevBoard. |

Risks that remain:
- `.git` is inside iCloud. Apple does not document what iCloud does to a Git repository. Third-party reports mention renamed `.git` folders and numbered copies of refs. Build and commit on one Mac only. The safer option is a clone outside iCloud with a remote, but that changes the agreed setup, so it is the owner's call.
- A new module needs its own `build` symlink before its first build, or its output lands in iCloud.
- `kotlin-js-store/yarn.lock` is tracked and lives in iCloud. Two Macs that change it could leave a `yarn 2.lock` copy.

## Verification (2026-11-13, real output)

| Check | Result |
|---|---|
| `./gradlew build` | Passed (75 tasks), after the LLVM fix below. It covers `compileKotlinJs` for the three modules, the production `thread.js` bundle (`jsBrowserDistribution`, 514 KiB minified), both iOS compiles and the ktlint checks. There are no tests yet, so the test tasks were skipped (`iosSimulatorArm64Test` SKIPPED, `linkDebugTestIosSimulatorArm64` NO-SOURCE). |
| `./gradlew ktlintCheck` | Passed |
| `:shared:domain:compileKotlinIosArm64`, `:shared:domain:compileKotlinIosSimulatorArm64` | Passed, after the LLVM fix below. Output is in `shared/domain/build/classes/kotlin/iosArm64` and `.../iosSimulatorArm64`. |
| Kotlin/Native LLVM download (first attempt) | Failed. Kotlin/Native could not download `llvm-21-aarch64-macos-essentials-97.tar.gz` (151,150,049 bytes) from download.jetbrains.com. The machine's network inspects TLS for that host with a company certificate authority. macOS trusts that CA, but the Gradle JDK does not, so Java reported `PKIX path building failed`. Maven Central and services.gradle.org are not inspected and worked. The Kotlin/Native 2.4.21 compiler itself was already in `~/.konan`. |
| LLVM fix | With the owner's approval ("Yes, download it"), the same file was downloaded from the same URL with `curl`, which uses the macOS trust store, into `~/.konan/dependencies/cache/`. Size matched the server's `content-length`, and `tar -tzf` read all 303 entries. SHA-256 `62fd78f0…21b408a`. JetBrains publishes no checksum for it that was found. Kotlin/Native then extracted it from the cache and needed no other download. No JDK or system trust setting was changed. |
| `:webApp:jsBrowserDevelopmentRun` | Passed. The dev server ran at `http://localhost:8080/`. `GET /` returned 200 with `index.html`, and `GET /thread.js` returned 200. Headless Chrome rendered `<h1>Thread</h1><p>Build baseline: webApp → data → domain</p>` in `#root`. The server was stopped afterwards. |
| Ignore rules | `git check-ignore` matches the zips to `/*.zip`. Git would track 158 files (this ADR included), none of them zips or build output. |
| Build output outside iCloud | No real `build`, `.kotlin` or `node_modules` folder inside the project. No `name 2` copies. |
| Existing documents | SHA-256 of all 148 files that existed before Phase 0 (`.DS_Store` excluded) is unchanged |
| Secrets | No key-format matches (private keys, Google, GitHub, Atlassian or Slack token formats, `client_secret`) in any file git would track |

## Open

- On this network, any future Kotlin/Native download from download.jetbrains.com (for example after a Kotlin upgrade) fails the same way. Fixes: the same manual download into `~/.konan/dependencies/cache/`, a build off the company network, or the owner making the Gradle JDK trust the company CA.
- Not checked: a newer ktlint engine than 1.5.0; behavior on a second Mac; an iOS framework or link step (not built until the iOS spike).
