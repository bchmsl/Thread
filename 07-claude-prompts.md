# Claude Code prompt pack

Use one prompt at a time. Run from the repository containing these documents.
These are initial handoff prompts. Later prompts should include approved visual artifacts and concrete repository context.

## Prompt 1: Understand and plan, no implementation

Read CLAUDE.md, README.md, and all numbered project documents.
Inspect the current repository without overwriting existing files.

Produce:
1. A concise product and architecture understanding.
2. Any contradictions or missing information that genuinely affect the next step.
3. A proposed module/source-set layout consistent with Kotlin/JS + Compose HTML and future SwiftUI.
4. An M1 feasibility plan covering rich-text editing, local persistence, Firebase sync/conflicts, and Jira read-only integration.
5. A small ordered implementation backlog with acceptance criteria.

Separate confirmed decisions, proposed choices, and open questions.
Do not re-ask answered product questions.
Do not implement, provision cloud resources, or invent final designs.
Wait for the next scoped task after presenting the plan.

## Prompt 2: Technical spike after plan approval

Implement only the M1 technical slice from the approved plan.
Read the project docs and preserve their constraints.

Build the smallest Kotlin/JS + Compose HTML experiment that validates:
- One visual rich-text Note.
- Durable local save and recovery after reload/offline.
- Firebase synchronization and truthful pending/synced/error state.
- A reproducible conflicting-edit scenario with no silent loss of writing.
- An offline-loadable application shell after initial setup.
- One permitted Jira ticket with cached-first detail rendering, description and comments.

First choose and document SDK/storage/editor candidates using current primary documentation.
If auth or CORS needs a small integration service, explain the concrete evidence and keep it minimal.
Use fixtures only where credentials are unavailable, label that limitation, and do not claim live validation.
Do not implement the full dashboard, external writes, AI sessions, or notifications.
Record actual verification results and proposed ADRs.

## Prompt 3: Implement approved visual design

Read the project documents and the approved design artifact: the v3 prototype in design/claude-pilot-prototype-v3/ (D33).
Implement only the named screens: [INSERT SCREEN LIST].
Use Kotlin/JS + Compose HTML and the agreed architecture.

Match the approved layout, typography, spacing, semantic colors, and states.
Use clearly labeled synthetic fixtures until repositories are connected.
Implement populated, empty, cached-refreshing, offline, and recoverable-error states.
Keep Pomodoro compact in the menu.
Use density tokens, but do not build the future Compact switch.
Preserve keyboard navigation, focus, semantic HTML, and responsive behavior.
Do not invent new dashboard widgets or change confirmed navigation without raising it.
Report visual verification at wide and narrow sizes.

Do not run this prompt until the screen scope is supplied.

## Prompt 4: Implement one vertical feature

Read the project documents and current architecture decisions.
Implement this feature only: [INSERT FEATURE].
Acceptance criteria: [INSERT OBSERVABLE CRITERIA].

Cover the required domain rule, repository/data behavior, and presentation.
Keep Firebase/network types out of domain.
Use existing local/sync contracts rather than creating a second synchronization system.
Keep external integrations read-only.
Add meaningful checks for the feature's failure modes.
Report what changed, tests run, unresolved issues, and any documentation changes.

## Prompt 5: Integration milestone

Implement the requested read-only integration slice: [JIRA OR GITHUB SCOPE].
Follow 05-integrations.md and approved authentication/storage decisions.

Use API authority with device-local detailed cache.
Render cached content immediately, then refresh.
Handle pagination, partial data, 401/403, rate limits, and expired credentials.
Store only approved minimal external references in cloud app data.
Support linking an external item to multiple Topics without content duplication.
Do not implement comments, transitions, reviews, or merges.
Verify with permitted real data when available and state exactly which paths used fixtures.

## Prompt 6: Release-readiness review

Review the implemented v1 against the numbered documents.
Do not expand scope to deferred features.

Verify:
- Confirmed features and relations.
- Per-user authorization and secret handling.
- Offline reload and reconnect.
- Note conflict/recovery behavior.
- External cache storage policy.
- Status rename/delete and semantic behavior.
- Today date/deadline behavior.
- List/Board and accessible status changes.
- Rich-text editor, shortcuts, narrow screens.
- Compact Pomodoro with reliable timestamp accounting.

Produce a prioritized defect list with reproduction steps.
Distinguish verified behavior from assumptions.
Do not claim a production-ready release if critical persistence or permission paths remain untested.

