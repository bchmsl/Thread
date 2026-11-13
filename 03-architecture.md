# Architecture

## Confirmed constraints

- Kotlin web application using Kotlin/JS + Compose HTML, not a canvas-based shared Compose UI.
- Future iPhone UI in SwiftUI.
- Clean Architecture: data / domain / presentation.
- Firebase for application-owned cloud storage.
- Fast local-first behavior.
- Share domain/data Kotlin code with iOS where practical; platform UI stays separate.

## Proposed dependency structure

presentation -> domain <- data
composition root -> presentation + domain + data

Domain must not import Firebase, browser APIs, DOM, SwiftUI, or remote DTOs.
Presentation consumes domain contracts and presents platform-specific state.
Data implements repository contracts and maps persistence/network structures into domain models.
Composition roots wire implementations.

## Proposed repository layout

shared/
  domain/                 # Kotlin Multiplatform module
    topic/
    task/
    note/
    reminder/
    integration/
    focus/
  data/                   # Kotlin Multiplatform module
    repository/
    local/
    remote/
    mapper/
    sync/
webApp/                   # Kotlin/JS + Compose HTML
  presentation/
  platform/
  di/
iosApp/                   # future SwiftUI application
integrationService/       # optional Kotlin/Ktor, only when justified
docs/                     # these documents if paths are updated

Feature names inside modules are initially packages.
Avoid a Gradle module for every screen or entity.
Physical layout is proposed; verify source-set and dependency compatibility before scaffolding.

## Domain

Examples:
- Topic, TopicStatus, StatusCategory, PersonalTask, Note.
- ExternalReference and TopicExternalLink.
- Reminder and FocusSession.
- Repository interfaces and focused use cases.

Meaningful use cases include:
- LinkExternalItemToTopic.
- CompleteTopic.
- PlanTaskForDay.
- TriageInboxItem.
- ReplaceAndDeleteTopicStatus.

Do not create a pass-through use case for every method just to satisfy a pattern.
Business validation belongs here; network retry mechanics do not.

## Presentation

Web: Compose HTML screens, screen state holders, actions, navigation, editor adapter.
iOS: SwiftUI views and native presentation state.
Do not force shared ViewModels or promise direct frictionless Flow consumption from Swift.
Choose the Kotlin-to-Swift boundary explicitly during the later iOS spike.
Shared code can expose stable use cases and observation contracts with a small platform bridge.

## Data and platform boundaries

- Repository implementations coordinate local data and cloud synchronization.
- Platform adapters handle IndexedDB or another validated browser store, native storage, SDK access, credentials, and rich-text rendering integration.
- Firebase SDK details and DTOs remain in data/platform code.
- Repositories expose observable app data independently of request lifecycle.
- A screen's loading state must not dictate whether data is durable.

### Firebase SDK decision is open

Options include a compatible third-party Kotlin Multiplatform wrapper or platform Firebase SDK adapters.
No wrapper, target combination, or version is considered verified by this document.
Validate Auth, Firestore persistence, reload behavior, pending writes, and future Apple compatibility before selecting.

### Local storage decision is open

Evaluate Firestore's built-in persistence against the required guarantees first.
A dedicated local store/outbox may be needed for downloaded external content, search, explicit offline availability, and conflict-safe Notes.
Do not build two competing sync queues for the same data.
If custom storage/outbox is selected, document the authority and acknowledgment model in an ADR.

## Firebase responsibilities

- Authentication.
- Firestore for app-owned records and associations.
- Security Rules enforcing per-user ownership.
- Hosting for the compiled web application.
- A server-side component only for integration requirements that cannot safely/reliably live in the client.

Firebase is a backend; it does not eliminate backend concerns.

## Optional integration service

A small Kotlin/Ktor service is proposed if required for OAuth secret handling, token refresh, API proxying, or future webhooks/background integration work.
Cloud Run is a possible deployment target, not a confirmed provisioned service.
Do not select Firebase Cloud Functions as a Kotlin runtime by assumption.
For v1, verify Jira/GitHub auth and browser CORS before deciding whether this service is needed.
No credentials in source code, client bundles, logs, Notes, or ordinary Firestore records.

## Rich-text editor boundary

Compose HTML is the application UI.
A JavaScript rich-text editor embedded through Kotlin/JS interoperability is acceptable as a proposed adapter, not an excuse to rewrite the app in React/TypeScript.
Editor library and serialized document format are open.
Select an exportable, versioned format with a credible SwiftUI/native rendering/editing path.
Do not assume arbitrary editor JSON can be consumed by iOS without an adapter.
Sanitize imported/rendered HTML and validate links.

## Quality strategy

Test rules and failure modes, not architecture scaffolding:
- Status categories and replacement.
- Many-to-many links and unlink-vs-delete.
- Planned day vs deadline and timezone behavior.
- Durable offline edits and reload recovery.
- Repeated sync and conflict preservation.
- Per-user authorization.
- External pagination, rate limits, permission errors.
- Keyboard/editor interactions and responsive layouts.

Use a minimal end-to-end vertical slice before implementing all features.
No dependency versions, successful builds, or API compatibility have yet been verified.

