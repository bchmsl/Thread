# Roadmap, decisions, and open questions

## Current state

Planning baseline documented on 2026-11-12.
On 2026-11-13 the owner approved the v3 prototype in design/claude-pilot-prototype-v3/ as the design reference for all pilot surfaces (D33).
Update 2026-11-15: the pilot is built and live. Phases 0 to 12 of the pilot plan and D47 to D54 are on `master` and deployed to GitHub Pages (https://bchmsl.github.io/Thread/), on the owner's real Firebase project. The owner connects Jira and GitHub with their own tokens in Settings; the automated checks used the Firebase emulators and SYNTHETIC fixtures only. Phase 13, the pilot, is closed with no fixes (D55, pilot/PILOT-RESULTS.md). Update 2026-11-16: Phase 14 builds the Focus timer (D25, D34, D45, D56, adr/ADR-019-focus-timer.md). It is committed and pushed (2b269a5); its Firestore rules were deployed first. Pull request descriptions now show as formatted text from their GitHub Markdown (D57), and Thread has an app icon: the hex mark on the ink tile (D58). Both are committed and deployed (0bd5bfd, 72faf6b) and needed no rules deploy. Today now shows Topics whose status is in the Active or Paused category, grouped by their own status, with a List / Board switch that defaults to List (D59, owner change #1). It needs no rules deploy. Connection sync now updates open devices within seconds through a listener on the encrypted copy, a connection change made while sync is on is no longer lost when Settings is left, and after Unlock the Jira and GitHub views show the connections without a reload (D60, owner change #7). Built, not committed; it needs no rules deploy.
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
| D14 | **Superseded in part by D54 on 2026-11-14.** Was: Reminders attach to Task, Note, or Topic. Now they attach to a Task or a Note. |
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
| D49 | 2026-11-14: A Topic suggests related Jira tickets and pull requests by fixed rules, not AI, under the linked rows of its Jira and Pull requests sections. Phase 1 finds keys and PR links written in the Topic, its Tasks and Note previews; Jira issue links of linked tickets; and Jira keys in linked PRs. Only items Jira or GitHub confirmed show, each with its reason. Link is one click with Undo. "Don't suggest" hides the item in that Topic until it is restored from "Not suggested here", with Undo; Unlink does the same. Dismissals are an optional `dismissed` field on the existing link document, so they sync and follow the Topic into Trash and back. Only keys and PR ids go to Jira and GitHub. Needs a rules deploy before the build ships. Phase 2 is built (2026-11-14, under the relayed "all approved. I am afk. Continue without my approvals please. Everything is approved from me"): S4 suggests the parents, children and siblings of the linked tickets from up to three Jira GET searches, and S5 suggests PRs that name a linked ticket from one GitHub GET search per linked repository (at most 2). Both send only keys and repository names, are saved on the device per Topic for 10 minutes, have a per-minute budget and stay quiet on refusals. Their Jira and GitHub API assumptions are [inference, not checked live]; details queued (Q100 to Q103). Owner's words: "when I open a topic, it would be good to catch keywords, tickets and even if I attach PR or Jira to a topic, it would be good to suggest to add another Jiras and PRs which are related to it. It would also be good to remove specific suggestion if it is not relatable and it won't suggest in that topic anymore.", then "all approved. I will be AFK, continue". Details queued (Q77 to Q85). Files: adr/ADR-017-topic-suggestions.md, JiraFamilyRepository.kt, GitHubRepositories.kt (GitHubPullRequestMentions), 01-product-spec.md, 02-ux-design.md, 04-data-and-sync.md, 05-integrations.md, firebase/firestore.rules, TopicSuggestions.kt. |
| D50 | 2026-11-14: Jira "Assigned to me" lists every open item assigned to the owner, of every issue type: Bug, Task, Story, Sub-task, Epic, custom types and items with no type. Nothing filtered by type before; the list stopped at 2 pages (100 rows). It now reads up to 10 pages (500 rows) and says "Showing the latest N" when Jira has more. Done-category items stay out. A "Current sprint only" checkbox adds `sprint in openSprints()`: off by default, kept on this device, hidden when the site has no Sprint field, and each mode is saved apart on the device. Jira statuses show as lozenges colored by status category, like Jira: To Do gray, In Progress blue, Done green, with Jira's status name kept and AA contrast. They show on the Jira list and search rows, the ticket header, a Topic's Jira section and ⌘K results, and later on suggestion rows. Owner's words: "Get the bugs as well, not only tasks. Get all jira items assigned to me. also, add a checkbox to show current sprint only [...] also add jira status coloring as it is currently on jira". Details queued (Q73, Q74, Q76). Files: 05-integrations.md, 02-ux-design.md, JiraLists.kt, JiraSprintField.kt, JiraScreen.kt, ExternalParts.kt, app.css. |
| D51 | 2026-11-14: Thread downloads Jira "Assigned to me", My PRs and Review requested in the background. "Background" means while Thread is open in a tab: there is no service worker or server job, so nothing refreshes while it is closed. A refresh runs on start, every 5 minutes while signed in, connected, online and visible, and at once on becoming visible or coming back online when the list is older than that. It never runs signed out, offline, hidden, with the SYNTHETIC fixtures, inside a rate limit, or after a 401 until the connection is set up again. Failed refreshes in a row double the wait, up to 30 minutes. The pages and the background share one list each, so a list is never asked twice at once, and a page asks again on open only when the last request is over a minute old. Owner's words: "auto download my PRs and my jiras in the background". Details queued (Q75). Files: 05-integrations.md, BackgroundRefresh.kt, BackgroundRefresher.kt, ExternalListFeed.kt. |
| D52 | 2026-11-14: Topic Details shows "Additional information", one text box, in place of "Pick up here" (Where I left off, Next step). Same section and save behaviour, labelled by its heading. It is one optional field, `info`, on the Topic. Until it is first saved, the box shows the old text combined ("Where I left off: ...", "Next step: ..."); nothing is migrated or written until the owner types, and the old fields are never cleared, so an older build still shows them. A blur without typing writes nothing; Undo of the first edit shows the old text again. Up to 20,100 characters. "Created from KEY." goes into it. Search and suggestions read it. Topics list rows, Board cards and Today's Active Topics now show "Next: <the Topic's first open Task>" (else "Finished" for Done, else "No open tasks"). Needs a rules deploy before the build ships. Owner's words: "When opening topic I think we don't need "pick up here" block. I think instead it would be better to have additional information block. Same UI as it is for Pick up here but just one text box for additional information", then "all approved. I am afk. Continue without my approvals please. Everything is approved from me". Details queued (Q86 to Q90). Files: adr/ADR-018-topic-info-and-task-reminders.md, 01-product-spec.md, 02-ux-design.md, 04-data-and-sync.md, firebase/firestore.rules, TopicRules.kt, TopicScreen.kt. |
| D53 | 2026-11-14: Today adds two groups after Earlier planned: Upcoming (open Tasks whose planned day or deadline is after today, under day headings such as "Tomorrow" and "Mon 17 Nov", earliest first) and No date (open Tasks with no planned day and no deadline, with or without a Topic, newest first). A Task that waits in Inbox without dates shows in Inbox only (D38). Each Task still shows once, in the first group that fits: Overdue, Today, Earlier planned, Upcoming, No date. Each new group shows 10 rows, then "Show all (N)". All five groups open and close from their heading; Upcoming and No date start closed, and each device remembers the choice (not synced). The page header count still counts only Overdue, Today and Earlier planned. Completed Tasks stay under Today or Earlier planned for the day, as before. No data or rules change. Refines D37 and D19. Owner's words: "in today view it would be better another collapsible sections: upcoming tasks (which has time, but not today) and tasks which does not have a time at all", then "all approved. I am afk. Continue without my approvals please. Everything is approved from me". Details queued (Q91 to Q94). Files: adr/ADR-018-topic-info-and-task-reminders.md, 01-product-spec.md, 02-ux-design.md, TaskRules.kt, TodayScreen.kt. |
| D54 | 2026-11-14: Reminders belong to a Task or a Note; supersedes D14 in part. Each reminder stays its own document (several per Task are fine), and the Task checkbox is "done". Task rows show a bell with the next reminder that is not dismissed, plus "+N" when there are more; the Task sheet field is "Remind me". Topic Details has no Reminders section and no "+ Reminder"; it lists "Topic reminders" only while the Topic still has some that are not dismissed (not moved yet, or kept by Undo). A reminder day counts as a date in Today: a reminder today, or one due and not dismissed, puts the Task under Today, a later one under Upcoming, and an Inbox Task with a reminder shows. A reminder on a completed Task shows no banner. **Conversion:** after the workspace loads, with server data, online and nothing of this device waiting, each open Topic reminder (not dismissed, not in Trash, its Topic stored, also in Trash) moves onto a Task `k-from-<reminder id>` in that Topic, titled with its text, no dates, not in Inbox: one batch per reminder (create the Task, retarget the reminder). A local copy (IndexedDB `thread-backup-<uid>`) is stored first; if that fails nothing is written. The ids make it idempotent across devices, re-runs and reloads; a refused duplicate from another device is dropped when the server shows it settled. A toast "Moved N Topic reminders into Tasks" offers Undo: the reminders go back to their Topic and the Tasks are discarded, which also stops any device from converting them again. No rules change; older builds read a moved reminder as a Task reminder, and a Topic reminder they add later moves on the next load. Owner's words: "also I think tasks and reminders can be merged. Task can have a reminder option as well", then "all approved. I am afk. Continue without my approvals please. Everything is approved from me". Details queued (Q95 to Q99). Files: adr/ADR-018-topic-info-and-task-reminders.md, 01-product-spec.md, 02-ux-design.md, 04-data-and-sync.md, ReminderConversion.kt, KeyValueConversionBackup.kt, TaskRules.kt, Reminder.kt, TopicSections.kt. |
| D55 | 2026-11-15: Phase 13, the real workday pilot, is closed with no fixes. The owner tested the pilot on the live app and reported no problems, so the fix list (must fix, should fix, later) is empty. No pilot log was kept, and which of the four pilot-goal actions were done was not recorded. The Focus timer (Pomodoro, D25, D34, D45) is the next v1 feature; its phase plan needs the owner's approval before any code. Owner's words: "I already tested it. What is next?", then, asked what problems the test hit, "none. Continue". Files: pilot/PILOT-RESULTS.md, README.md. |
| D56 | 2026-11-16: The Focus timer (Pomodoro, D25, D34, D45) is built as Phase 14 of focus-timer-plan.md. **F1 A:** a running timer keeps its end time across a reload or a closed tab; a period that ended meanwhile counts at its real end time, and Thread says so on return. **F2 A:** each device has its own timer, kept on the device only (IndexedDB `thread-focus-<uid>`), never in the account; the lengths and the history sync. **F3 A:** the panel shows the last 4 sessions; every session is kept. The plan's defaults FD1 to FD9 stand as written. Sessions are account documents `users/{uid}/focusSessions/f-<runId>`, written once (a repeat of the same write is accepted, a change is not, no delete); the lengths are `focusMin` and `breakMin` in the preferences. The new Firestore rules must be deployed before the build: until then the server refuses its sessions and any preferences save that stores a length, which stay in the sync panel with Retry. Resolves the open question "Pomodoro reload completion and cross-device timer ownership". Owner's words: "Approve, all A". Details queued (Q104 onwards). Files: adr/ADR-019-focus-timer.md, 02-ux-design.md, 04-data-and-sync.md, firebase/firestore.rules, Focus.kt, FocusRules.kt, FocusTimer.kt, IndexedDbFocusRunStore.kt, FocusModel.kt, FocusPanel.kt. |
| D57 | 2026-11-16: Pull request descriptions show as formatted text, as on GitHub. Thread parses the description's GitHub Markdown in shared Kotlin into the read-only document model that Jira text already uses, and builds it as DOM elements. It does not ask GitHub for HTML and inserts none. This is option A of the owner change queue, chosen by the orchestrator. Shown: headings, bold, italic, strikethrough, inline code, code blocks, bullet and numbered lists, task lists (read-only checkboxes), quotes, links, tables, rules, bare links, `#123` and `@name` links, and line breaks as GitHub keeps them. Images show as a link "Image: <alt>" that opens the image, because the CSP does not load pictures from other sites; the owner has not asked for inline images. Raw HTML stays text, except that comments are hidden, `<br>` breaks the line and `<img>` becomes an image link. Every link passes the Notes link rule or stays text. Size and nesting are capped (65,536 characters, 2,000 blocks, tables of 100 rows by 20 columns, 16 levels), and the screen says what it did not show. No data, rules or cache change: the device keeps the Markdown source and parses it on each read, and the Jira key search (D49) still reads the source. Replaces ADR-007's "plain text" description rule. Owner's words: "Also pull requests should be rendered as formatted text as it is on Github pull request". Details queued (Q116 to Q121). Files: adr/ADR-007-github-access-and-cache.md, adr/ADR-014-github-in-the-workspace.md, adr/ADR-015-pilot-hardening.md, GitHubMarkdown.kt, ExternalDocument.kt, PullRequests.kt, GitHubJson.kt, ExternalParts.kt, PullRequestView.kt, thread.css. |
| D58 | 2026-11-16: Thread has a favicon and app icon: the hex brand mark, light (`#e9edf7`) on the ink tile (`#0e1322`, the app background), variant B of three the owner compared at 16, 32 and 180 px on light and dark tab bars. Shipped as `favicon.svg` (no light or dark rule needed: the tile carries its own background), `favicon.ico` (16, 32, 48) and `favicon-32.png` for browsers without SVG icons, `apple-touch-icon.png` (180 px, solid to the edge; iOS rounds it), and `manifest.webmanifest` with 192 and 512 px icons and a maskable 512 px icon whose mark stays inside the safe circle. `theme-color` and the manifest colors are `#0e1322`. Paths are relative, so the same build works at `/` and at `/Thread/`. The icons are processed resources, so the offline shell caches them and they count in the build id (ADR-008); the CSP is unchanged (`img-src 'self'`, `manifest-src 'self'`, ADR-015). The PNGs were rendered once with headless Chrome and the ICO packed with the Python standard library: no new build dependency. The manifest sets no `display`, so Thread still opens in a browser tab when added to a home screen (owner-review-queue Q122). Owner's words: "Also please add a favicon and some icon or logo for this project. Currently the hexagon serves as a logo and I like it, maybe we can use it for favicon as well, but also consider the background and the color of it for favicon"; the pick: "B". Files: `webApp/src/jsMain/resources/` (`index.html`, `favicon.svg`, `favicon.ico`, `favicon-32.png`, `apple-touch-icon.png`, `manifest.webmanifest`, `icons/`), ADR-008, ADR-015. |
| D59 | 2026-11-16: Today's Topic section, "Active and paused Topics", shows Topics whose status is in the Active or Paused category, grouped by status, with a List / Board switch; List is the default. Before, it showed Active-category Topics only, as one card grid. Planned and Done Topics stay off Today. There is one group per status, named by the owner's own status names (for example Analysis, In Progress, QA, Hold), in Settings order; inside a group the oldest Topic comes first, as before. **List:** each status has a heading like Today's other groups (chevron, status swatch, name, count), with rows like the Topics list. A status with no Topic has no group. Every group starts open, and each device remembers which are closed (`thread.today.open.at-<statusId>`). **Board:** one read-only lane per status, holding the Today cards, with no drag. A lane with no Topic says "No Topics" on wide screens and is hidden on phones. Four lanes scroll inside the section, never the page. Each device keeps the chosen layout (`thread.today.activeLayout`); it is not synced. With only one Active or Paused status there is no heading: plain rows or the card grid, and the hint names the status. A Paused status keeps the Paused swatch. Rows and cards keep D52's "Next:" line. No data or rules change. Refines D19 and D37; categories decide, not names (D17). Owner's words: "on Today view, I think we need topics groupped by status, maybe try if it will look good with board/list view", then "Keep only active ones as is, but I might have multiple "Active" status and group them", then, with the statuses To-Do, Analysis, In Progress, QA, Hold and Done, "I want to show Active and pause grouped by my own values: Analysis In Progress QA Hold". The pick: "Both, default List". Details queued (Q123 to Q125). Files: 01-product-spec.md, 02-ux-design.md, TopicRules.kt, TodayScreen.kt, thread.css. |
| D60 | 2026-11-16: Connection sync (D47, ADR-016) now updates open devices live. One Firestore document listener per signed-in workspace watches the encrypted copy (`users/{uid}/vault/connections`) and only starts the existing check, which reads the server and decides; the listener never decrypts or applies anything. Snapshots from the offline cache are skipped, and only the record appearing, going, or getting a new salt or IV counts. So within seconds and without a reload: a device without the key goes Locked when sync is turned on elsewhere; a device with the key applies a connect, new token or disconnect, and its Jira and GitHub views load again; a new passphrase or turning sync off shows too. A listener error stops only the listener, with no message; the checks on sign-in, on a network change and on opening Settings stay, and it starts again on the next network change. A check asked for while another check or an action runs now runs once after it instead of being dropped. Three bug fixes come with it. A connect, new token or disconnect made while sync is on reaches the encrypted copy even when the form or Settings is left meanwhile (saving here, starting the sync and reloading the views is one step that leaving cannot cut; the save to the account runs in the workspace; the form's toggle and Cancel wait while it saves). After Unlock, the Jira and GitHub views always load again and the "Unlocked" toast shows: before, the Unlock form closed as soon as the state turned On, which could cut the reload short and leave GitHub "Not connected" until a reload. The two-device browser check caught this once in two runs; it is a likely cause of the owner's "still needed hard reload". The views now load again in the workspace. And a server read that fails while online shows "Could not check sync", not "Offline". The Locked texts stay as they are: no Unlock hints on the Jira and GitHub rows. A forced page reload was not chosen: it needs the same listener, then loses open forms and typing. No data or rules change; the rules already let the owner read the record. Cost: one listener per open tab and one server read per change. Owner's report: "Critical bug: 1. I opened app in mobile 2. I opened app in web/desktop 3. Jira is not attached in any, github is not attached in any, passphrase is off 4. I attached Jira, github and turned on passphrase on web 5. on mobile, it did not update. After hard reloading page, only passphrase was on, jira and github still showed unattached", then "Oh, it was connected after unlocking, however it still needed hard reload. If it is impossible to live update, can we force page to hard reload?". The picks: "C" (update in place, not a forced reload) and, for the Locked state, "Keep as is (A)". Refines D47 and ADR-016's Check flow; Q64 updated. Files: adr/ADR-016-encrypted-connection-sync.md, ConnectionSync.kt, VaultParts.kt, VaultConnectionSync.kt, FirestoreVaultRemote.kt, ConnectionSyncModel.kt, ConnectionsBlock.kt, App.kt. |

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
- Resolved 2026-11-16: Pomodoro reload completion and cross-device timer ownership (D56). See adr/ADR-019-focus-timer.md.
- Export/backups and trash retention.
- Exact server hosting/billing choices if a backend is introduced.

## Change process

Append dated decisions with reason and affected files.
If replacing an accepted decision, explicitly mark the old decision superseded.
Do not erase deferred features merely because they are not being built now.
Prompts should name one milestone/slice and its acceptance criteria.

