# Screen contract v0.5

All new choices below are Proposed unless explicitly described as an existing Confirmed requirement.

## Shared shell

Desktop: 166px quiet sidebar, content-led main area, 58px minimum breadcrumb bar. Comfortable spacing. Dark-only v1 is Confirmed. Additional themes are Deferred. Exact dark colors and English UI labels are Proposed. Sidebar Today and Topics are functional; Inbox, Notes, Jira and Pull Requests are disabled placeholders for navigation context, not implemented screens. Focus control is at the sidebar foot and previews its compact panel. Search, Settings and global create are not designed here; do not infer removal from v1.

## Today

Today shows parallel active Topics with equal visual weight and each Topic’s next step. The prototype uses the fixture status Active; production uses the semantic Active category, including custom status names. When none qualify, omit this section. The section’s placement and ordering remain Proposed. Initial Today content is embedded in the HTML.

Desktop places the Tasks card in the wider column and stacks Reminders and Review requested in a narrower column. At preview widths of 740px or less the cards stack in reading order. Task groups remain inside one card. The open count counts incomplete displayed tasks and updates when a checkbox changes.

Order: Overdue → Planned today → Earlier planned → Reminders → Review requested.
Proposed production deduplication: incomplete tasks with expired deadlines go to Overdue first; other tasks planned today go to Planned today; remaining tasks with earlier planned dates go to Earlier planned. Each task appears once. A task scheduled for a future day with an expired deadline still belongs in Overdue. Reminder targeting a visible task should preserve that context; exact reminder deduplication/dismiss behavior remains Open.

Earlier planned is a proposal: no silent rescheduling and no implication that a missed planned date is a missed deadline. Review requested is visually separate and lower on the page so personal work remains primary.

Prototype groups are fixed synthetic examples, not computed date logic. Completing a task strikes it through and updates Topic progress. Keeping completed rows visible until navigation/refresh is an interaction to review, not a finalized retention policy.

Add task: minimal title. In this Today prototype it creates a standalone Inbox task with planned day Today. Automatic Today planning is Proposed, not a change already approved in root quick-capture rules. Production capture must also offer optional Topic selection; this preview omits that picker.

## Topic color

Proposed: each Topic has a pastel color independent of its status. Today Topic cards, List/Board and Topic Details show this color. A linked Task inherits the color in its Topic label. Related reminders use the same label. Standalone Tasks have neutral labels. Keep Topic names visible. Use separate semantic status indicators, and allow palette colors to repeat.

The prototype has fixed assignments. Color picker, default assignment and editing rules are Open.

## Content components

- **Tasks:** checkbox rows with planning or deadline metadata; completion changes the same personal task everywhere.
- **Reminders:** a time tile beside the reminder text and its linked Topic.
- **Notes:** a warm document surface with title and text excerpt in Topic Details.
- **Pull requests:** a compact GitHub-inspired row shared by Today and Topic Details. A green Open pill communicates lifecycle; the title opens details, the number is right aligned, and repository, author, CI and cache metadata are below. Lifecycle, CI and review state are distinct.

## Topics List

Columns: Topic, status, next step, personal Task completion count. Counts are personal tasks only. Title opens the matching Topic. List/Board toggle shares the same records and retains choice across preview navigation. Completed Topics are currently visible; final default/filter is Open. Sorting/filtering/create-topic interactions are not designed yet.

## Topics Board

Columns use the four initial statuses in workspace order. Cards show title, next step, task count. Drag-and-drop changes status. Native select offers a keyboard-accessible equivalent. Production must support custom statuses; four fixed columns are fixture scope only. No complex card previews.

Desktop preview uses four columns, then two and one at smaller embedded widths. This reflow is a prototype limitation: confirmed production narrow Board requires intentional horizontal scroll; do not implement wrapping columns as the final mobile behavior without approval.

## Topic Details

Header: title/status/goal. Continuation context and next step remain prominent above Tasks. One flow of collapsible sections, not tabs. Initial Tasks, Notes, Jira and PR sections open; Reminders closed. Defaults are Proposed. Shared task changes reflect across Today and Topic Details.

QR Topic is the full example. Other Topics open their own titles/context and show their actual fixture contents. Note preview is read-only; rich-text editor design is a later artifact. Do not infer that read-only Notes satisfy v1.

Click a linked Jira or PR to show one contextual detail panel. Switching replaces the panel rather than stacking sidebars. Closing restores the same Topic. Panel shows source identity, title, status, metadata, cached time, description, comment and disabled source button because fixtures have no real source URL.

On smaller preview widths panel stacks below content. This is NOT the approved narrow-screen route behavior; production requires a separate routed detail screen with a clear return path. Browser back/deep links are not implemented in this prototype.

## Fixture inventory

Date fixed at 2026-11-12 (Asia/Tbilisi context), not live current time.
Topics: QR payments (Active), bill payment errors (Active), recurring API (Paused), mapper tests (Planned), receipt (Done).
QR tasks: timeout test, error-code confirmation, camera fallback, completed scanner connection. Bill task: review error copy. QR has one sample Note, Jira PAY-142 and PR #248. Recurring API has one reminder at 16:30.
No identifiers represent verified real Jira/GitHub records.

## Later states, still required

Empty, first-use loading, cached-refreshing, offline, recoverable error, reauthentication, quota failure, saved locally vs synced, Notes conflict recovery and trash/restore. The cache labels shown here are illustrative only. Do not interpret them as validated persistence.
