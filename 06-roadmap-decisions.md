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
| D47 | 2026-11-14: The Jira and GitHub connections (Jira site, Cloud ID, e-mail and token; GitHub token) can sync through the account, encrypted in the browser with a key made from a passphrase the owner sets in Settings. Firestore stores only the encrypted copy. On a new device: sign in and enter the passphrase once. Saved Jira and PR copies stay on the device. Owner's words: "How can we save credentials to remote? (jira token, clientid, github token)", then "Lets do A". Supersedes the "Never in Firestore" line of ADR-008 for the connection records. The details are queued (Q62 to Q72). Files: adr/ADR-016-encrypted-connection-sync.md, adr/ADR-008-offline-shell-and-token-storage.md, 03-architecture.md, 04-data-and-sync.md. |
| D48 | 2026-11-14: No page zoom. The web app blocks every zoom a page can control: pinch and double-tap on touch screens, a trackpad pinch, Ctrl or ⌘ with the wheel, and Ctrl or ⌘ with + = - _ 0. iOS no longer zooms in on a focused field. Page code cannot block the browser's menu zoom, the operating system's accessibility zoom, or iOS Safari's "ignore zoom restrictions" setting. A trade-off against text resizing (WCAG 1.4.4). Owner's words: "Restrict zoom completely". Files: 02-ux-design.md, webApp index.html, app.css, ZoomGuard.kt. |
| D49 | 2026-11-14: A Topic suggests related Jira tickets and pull requests by fixed rules, not AI, under the linked rows of its Jira and Pull requests sections. Phase 1 finds keys and PR links written in the Topic, its Tasks and Note previews; Jira issue links of linked tickets; and Jira keys in linked PRs. Only items Jira or GitHub confirmed show, each with its reason. Link is one click with Undo. "Don't suggest" hides the item in that Topic until it is restored from "Not suggested here", with Undo; Unlink does the same. Dismissals are an optional `dismissed` field on the existing link document, so they sync and follow the Topic into Trash and back. Only keys and PR ids go to Jira and GitHub. Needs a rules deploy before the build ships. Phase 2 (parents, children, PR search, a suggestion cache) is not built. Owner's words: "when I open a topic, it would be good to catch keywords, tickets and even if I attach PR or Jira to a topic, it would be good to suggest to add another Jiras and PRs which are related to it. It would also be good to remove specific suggestion if it is not relatable and it won't suggest in that topic anymore.", then "all approved. I will be AFK, continue". Details queued (Q77 to Q85). Files: adr/ADR-017-topic-suggestions.md, 01-product-spec.md, 02-ux-design.md, 04-data-and-sync.md, 05-integrations.md, firebase/firestore.rules, TopicSuggestions.kt. |
| D50 | 2026-11-14: Jira "Assigned to me" lists every open item assigned to the owner, of every issue type: Bug, Task, Story, Sub-task, Epic, custom types and items with no type. Nothing filtered by type before; the list stopped at 2 pages (100 rows). It now reads up to 10 pages (500 rows) and says "Showing the latest N" when Jira has more. Done-category items stay out. A "Current sprint only" checkbox adds `sprint in openSprints()`: off by default, kept on this device, hidden when the site has no Sprint field, and each mode is saved apart on the device. Jira statuses show as lozenges colored by status category, like Jira: To Do gray, In Progress blue, Done green, with Jira's status name kept and AA contrast. They show on the Jira list and search rows, the ticket header, a Topic's Jira section and ⌘K results, and later on suggestion rows. Owner's words: "Get the bugs as well, not only tasks. Get all jira items assigned to me. also, add a checkbox to show current sprint only [...] also add jira status coloring as it is currently on jira". Details queued (Q73, Q74, Q76). Files: 05-integrations.md, 02-ux-design.md, JiraLists.kt, JiraSprintField.kt, JiraScreen.kt, ExternalParts.kt, app.css. |
| D51 | 2026-11-14: Thread downloads Jira "Assigned to me", My PRs and Review requested in the background. "Background" means while Thread is open in a tab: there is no service worker or server job, so nothing refreshes while it is closed. A refresh runs on start, every 5 minutes while signed in, connected, online and visible, and at once on becoming visible or coming back online when the list is older than that. It never runs signed out, offline, hidden, with the SYNTHETIC fixtures, inside a rate limit, or after a 401 until the connection is set up again. Failed refreshes in a row double the wait, up to 30 minutes. The pages and the background share one list each, so a list is never asked twice at once, and a page asks again on open only when the last request is over a minute old. Owner's words: "auto download my PRs and my jiras in the background". Details queued (Q75). Files: 05-integrations.md, BackgroundRefresh.kt, BackgroundRefresher.kt, ExternalListFeed.kt. |
| D52 | 2026-11-14: Topic Details shows "Additional information", one text box, in place of "Pick up here" (Where I left off, Next step). Same section and save behaviour, labelled by its heading. It is one optional field, `info`, on the Topic. Until it is first saved, the box shows the old text combined ("Where I left off: ...", "Next step: ..."); nothing is migrated or written until the owner types, and the old fields are never cleared, so an older build still shows them. A blur without typing writes nothing; Undo of the first edit shows the old text again. Up to 20,100 characters. "Created from KEY." goes into it. Search and suggestions read it. Topics list rows, Board cards and Today's Active Topics now show "Next: <the Topic's first open Task>" (else "Finished" for Done, else "No open tasks"). Needs a rules deploy before the build ships. Owner's words: "When opening topic I think we don't need "pick up here" block. I think instead it would be better to have additional information block. Same UI as it is for Pick up here but just one text box for additional information", then "all approved. I am afk. Continue without my approvals please. Everything is approved from me". Details queued (Q86 to Q90). Files: adr/ADR-018-topic-info-and-task-reminders.md, 01-product-spec.md, 02-ux-design.md, 04-data-and-sync.md, firebase/firestore.rules, TopicRules.kt, TopicScreen.kt. |

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
Proposed answers for each item: `adr/M1-RESULTS.md`, "M1 exit review" (Phase 4, 2026-11-13). They are not decisions until the owner reviews the ADRs; no D47+ row is added before that.
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
- Refresh cadence, cache limits and revoked-access cleanup. List refresh cadence: D51 (2026-11-14).
- Reminder overdue/dismiss behavior.
- Pomodoro reload completion and cross-device timer ownership.
- Export/backups and trash retention.
- Exact server hosting/billing choices if a backend is introduced.

## Change process

Append dated decisions with reason and affected files.
If replacing an accepted decision, explicitly mark the old decision superseded.
Do not erase deferred features merely because they are not being built now.
Prompts should name one milestone/slice and its acceptance criteria.

