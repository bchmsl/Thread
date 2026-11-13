# Roadmap, decisions, and open questions

## Current state

Planning baseline documented on 2026-11-12.
On 2026-11-13 the owner approved the v3 prototype in design/claude-pilot-prototype-v3/ as the design reference for all pilot surfaces (D33).
No application code, deployment, or live integration has been completed. The approved design is a clickable prototype, not production UI.
Owner uses Claude Code for implementation and ChatGPT for architecture and prompts.
The visual design reference is the owner-approved v3 prototype, not ChatGPT.

## Confirmed decision register

| ID | Decision |
| --- | --- |
| D01 | Topic represents one meaningful finishable work objective, possibly spanning multiple Jira tickets. |
| D02 | Topics + Today + Inbox are the organizational model. |
| D03 | Web: Kotlin/JS + Compose HTML. Future iPhone: SwiftUI. |
| D04 | Clean Architecture with separate domain, data, presentation. |
| D05 | Shared Kotlin domain/data where practical; separate platform presentation. |
| D06 | Firebase for app-owned cloud data; instant local-first behavior is required. |
| D07 | Jira Cloud at your-site.atlassian.net. |
| D08 | Jira content via API plus local cache, not a Firestore content mirror. |
| D09 | User confirmed Jira local caching is permitted and work Notes have no storage restriction. |
| D10 | Jira/PR can link to multiple Topics. |
| D11 | Notes can be standalone or link to multiple Topics. |
| D12 | Tasks can be standalone or belong to one Topic; checkbox state only. |
| D13 | Planned day, deadline, and Reminder are distinct concepts. |
| D14 | Reminders attach to Task, Note, or Topic. |
| D15 | Rich-text visual Notes with autosave, not Markdown preview workflow. |
| D16 | Note checkboxes and app Tasks are distinct. |
| D17 | Custom global Topic statuses map to Planned/Active/Paused/Done categories. |
| D18 | Category colors: gray/blue/orange/green; used status deletion needs replacement. |
| D19 | Today includes planned/overdue work, Reminders, separate Review Requested section. |
| D20 | Topic Details is one page with collapsible sections and contextual external details. |
| D21 | Topics List and Board both in v1, drag status changes, remembered view. |
| D22 | Inbox triage does not delete content; standalone content is allowed. |
| D23 | Separate Jira and PR views; topic linking/creation from them. |
| D24 | Cmd+K local/global search and contextual quick creation. |
| D25 | Compact menu Pomodoro, default 25/5, editable durations, optional association and history. |
| D26 | External write actions deferred, including comments/status changes. |
| D27 | Closed-app notifications deferred; retain reminder definitions/in-app behavior. |
| D28 | Preserve conflicting Notes and support recoverable deletion. |
| D29 | Comfortable, airy UI first; Compact density later. |
| D30 | AI usage/session functionality deferred, session integration conditional on feasibility. |
| D31 | Dark mode only in v1. Additional themes and a theme switch are deferred. Confirmed by Bachana on 2026-11-12; see 02-ux-design.md. |
| D32 | **Superseded by D33 on 2026-11-13.** Was: Bachana approved keeping the v0.5 design for Today, Topics List/Board, and Topic Details on 2026-11-12. See design/v0.5/README.md for the scope and pending behavior. |
| D33 | 2026-11-13: Bachana chose the v3 prototype (design/claude-pilot-prototype-v3/) as the approved design reference for all pilot surfaces. Supersedes D32. Reason: owner review of the v3 prototype. Files: README.md, CLAUDE.md, 02-ux-design.md, 07-claude-prompts.md, design/PILOT-DESIGN-PLAN.md, the v3 README. |
| D34 | 2026-11-13: Pilot scope: basic read-only Jira and GitHub are in the pilot. The Focus timer (Pomodoro) comes after the pilot and stays in v1 (D25). Reason: owner accepted the proposed pilot boundary. Files: design/PILOT-DESIGN-PLAN.md. |
| D35 | 2026-11-13: Navigation is one bottom status line on every page, not a sidebar. It holds the views, quick capture (Task, Note or Topic), search, Focus, sync status, Trash and Settings. Reason: v3 proposal 1, accepted. Files: 02-ux-design.md, 01-product-spec.md. |
| D36 | 2026-11-13: A Topic's identity mark is a hex sticker with a two-letter monogram in the Topic's pastel color. Topic color is identity only, never status. Reason: v3 proposal 2, accepted. Files: 02-ux-design.md. |
| D37 | 2026-11-13: Today groups are Overdue, Today and Earlier planned, with a side column for Reminders and Review requested. Refines D19. Reason: v3 proposal 3, accepted. Files: 01-product-spec.md, 02-ux-design.md. |
| D38 | 2026-11-13: A Task added from Today without a Topic is planned for today and also waits in Inbox until it is filed. Reason: v3 proposal 4, accepted. Files: 01-product-spec.md. |
| D39 | 2026-11-13: A new Note made outside a Topic lands in Inbox as untriaged. A Note made inside a Topic is linked to that Topic. Reason: v3 proposal 5, accepted with the Inbox behavior already in 01-product-spec.md. Files: 01-product-spec.md, the v3 prototype. |
| D40 | 2026-11-13: ⌘K opens global search everywhere, including inside the Note editor. Adding a link in a Note uses the editor toolbar button. Refines D24. Reason: v3 proposal 6, accepted. Files: 02-ux-design.md. |
| D41 | 2026-11-13: In a Note conflict, the version you do not keep goes to Trash. "Keep both" saves it as a second Note with the same Topics. Refines D28. Reason: v3 proposal 7, accepted. Files: 01-product-spec.md. |
| D42 | 2026-11-13: Topics can be deleted in the pilot. Move to Trash is instant, with Undo. The Topic's Tasks and Reminders go to Trash with it. Notes are never deleted with a Topic: only the link is removed, and a Note linked only to that Topic becomes standalone, not Inbox. Jira and PR links are removed; external records are not touched. Restore brings back the Topic, its Tasks, Reminders and all its links. Delete forever asks first. Trash retention stays open. Reason: owner chose to allow Topic deletion. Files: 01-product-spec.md, the v3 prototype. |
| D43 | 2026-11-13: Move to Trash is instant with Undo, with no confirm dialog. Delete forever still asks. Reason: v3 proposal 9, accepted. Files: 01-product-spec.md. |
| D44 | 2026-11-13: No week-start setting in the pilot, because no pilot screen uses a week view. Reason: v3 proposal 10, accepted. Files: the v3 prototype. |
| D45 | 2026-11-13: The Focus timer opens as a small panel from the status line, with no dashboard block. It is delivered after the pilot (D34). Reason: v3 proposal 11, accepted. Files: 02-ux-design.md. |
| D46 | 2026-11-13: "Open in Jira" and "Open in GitHub" open the original page in a new tab. Reason: v3 proposal 12, accepted. Files: 02-ux-design.md. |

## Proposed milestone sequence

### M0: Baseline and designs
- Review these documents.
- Visual design for all pilot surfaces approved in the v3 prototype on 2026-11-13 (D33). This replaces the v0.5 approval.
- Design the rich-text editor. Done at prototype level in v3; the editor library is an M1 question.
- Define states and responsive behavior. Done at prototype level in v3; see its README.
Exit: reviewed visual direction and scope.

### M1: Technical feasibility slice
- Kotlin/JS Compose HTML build.
- One rich-text Note with durable local save/reload.
- Firebase Auth and one-user data isolation.
- Offline write, reconnect, second-device conflict experiment.
- Offline app-shell reopening.
- One real permitted Jira ticket with ADF/fields/comments and local cache.
- Verify auth/CORS and whether a small backend is required.
Exit: recorded results and ADRs for SDK, local store, editor, auth/proxy.

### M2: Personal workspace core
- Topics and editable status categories.
- Tasks and scheduling.
- Notes and many-to-many links.
- Inbox and Today.
- Sync status, conflict recovery, trash/restore.
Exit: app-owned workflow passes offline acceptance scenarios.

### M3: Read-only integrations
- Jira list/search/details and linking.
- GitHub My PRs / Review Requested / detail and linking.
- Local snapshots, errors, refresh states.
Exit: useful read-only workflow with real permissions.

### M4: Complete v1 UX
- Finish List/Board and responsive detail panel behavior.
- Cmd+K and quick capture.
- Compact Pomodoro/history.
- Accessibility, local search, failure states.
Exit: complete v1, no deferred features accidentally included.

### Later
Native SwiftUI app; Compact density; closed-app notifications; AI telemetry and session links; external writes; standup drafts; advanced automation.

Ideas the owner wants to keep (2026-11-13). These are ideas, not planned work:
- A Settings toggle to put navigation in the bottom status line or in a sidebar.
- An optional icon instead of the Topic monogram, if the user chooses one.
- The pilot scope may be revisited later.

## Open questions: do not block all design work on these

### Resolve in M0 or design
- Product name and UI language.
- Resolved 2026-11-13: dark palette and typeface, by the v3 prototype (D33). See 02-ux-design.md.
- Resolved 2026-11-13: Topic deletion behavior for the pilot (D42).
- Trash (recovery) retention period. Still open.
- Exact Note-to-Task conversion interaction.
- Date-only vs timed deadlines UI.
- Board defaults for completed Topics.

### Resolve in M1
- Compose HTML framework choice (plain setup vs compatible framework).
- Kotlin, Gradle, SDK versions.
- Firebase platform adapters vs third-party wrapper.
- Local store and sync ownership.
- Rich-text library, schema, and future iOS bridge.
- Firebase authentication provider.
- Jira auth method and browser CORS/service boundary.
- GitHub credential handling and detailed-content cache policy.
- Precise external metadata allowed in cloud references.
- Feasibility of guaranteed active-Topic offline download.

### Resolve before feature completion
- GitHub direct vs team review requests.
- Refresh cadence, cache limits and revoked-access cleanup.
- Reminder overdue/dismiss behavior.
- Pomodoro reload completion and cross-device timer ownership.
- Export/backups and trash retention.
- Exact server hosting/billing choices if a backend is introduced.

## Change process

Append dated decisions with reason and affected files.
If replacing an accepted decision, explicitly mark the old decision superseded.
Do not erase deferred features merely because they are not being built now.
Prompts should name one milestone/slice and its acceptance criteria.

