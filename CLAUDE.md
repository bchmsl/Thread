# Claude Code project instructions

Read README.md and the numbered documents before proposing implementation.
These files are the planning baseline, not evidence of an existing codebase.

## Your role

Implement the owner's personal workspace.
ChatGPT supplies architecture and scoped prompts.
Follow the approved design reference: design/claude-pilot-prototype-v3/ (owner-approved 2026-11-13, D33). The UX brief alone is not a final mockup.
Do not silently change product requirements or the stack.

## Non-negotiable constraints

- Web in Kotlin/JS + Compose HTML.
- Do not substitute React, a TypeScript application, Compose canvas/Wasm UI, or a native-only client.
- A small JS editor/platform adapter is acceptable if justified and isolated.
- Clean dependency direction: presentation -> domain <- data.
- Domain has no Firebase, DOM, or SDK-specific models.
- Future iOS UI is SwiftUI. Preserve reusable domain/data boundaries.
- Rich-text Notes, not Markdown source/preview.
- Instant local-first behavior with truthful durable-save and sync status.
- Jira v1 and GitHub v1 are read-only.
- Jira descriptions/comments stay API + device-local cache.
- Firebase stores app-owned content and, as the one exception, calendar meetings the Mac app uploads; do not upload other external cache contents.
- Never hardcode or commit credentials.
- No large Pomodoro dashboard block.
- Custom Topic statuses map to stable categories; Task completion stays a checkbox.

## Decision handling

Confirmed decisions are in 01-product-spec.md and 06-roadmap-decisions.md.
Architecture/entity shapes marked proposed need technical validation.
Open questions must not become hidden assumptions.
Resolve routine implementation details sensibly and document them.
Ask before changing a confirmed product or architecture constraint.
Do not keep asking the owner to reconfirm decisions already documented.

## Before coding

Inspect the actual repository and existing instructions.
Report the requested slice, affected layers, assumptions, and acceptance checks.
Verify current official dependency/API documentation where needed.
Do not invent APIs, versions, existing files, or successful test results.
For planning-only prompts, do not modify source or provision cloud resources.

## Implementation discipline

Keep changes scoped to the requested milestone.
Avoid speculative abstractions, excess Gradle modules, or future-feature stubs.
Preserve user changes.
Platform SDKs and editor integrations belong behind boundaries.
Render untrusted content safely.
Use minimal auth scopes and per-user storage rules.
Differentiate pending local edits from server-acknowledged content.
Do not discard content on failed sync or authentication expiry.

## Verification

Prioritize real behavior:
- Offline reload persistence.
- Reconnect and duplicate-safe synchronization.
- Note conflict preservation.
- Linking without duplication and unlinking without deleting.
- Status category semantics.
- Planned day/deadline timezone behavior.
- Permission/rate-limit handling.
- Keyboard, editor, narrow-screen behavior.

Report tests actually run and unresolved limitations.
Do not claim working live integrations when using fixtures.
Use clearly labeled synthetic data for previews.

## Completion report

State what changed, how it was checked, and what remains.
Update relevant decision/progress documents when decisions are approved.
Do not mark deferred capabilities complete.
Never deploy, install privileged integrations, or introduce billing changes as an incidental part of a UI task.

