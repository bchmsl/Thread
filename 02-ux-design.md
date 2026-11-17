# UX and design brief

## Status and ownership

Confirmed: airy, simple, Apple Notes-inspired initial direction.
Future: Comfortable / Compact density switch.
This is a design brief, not a pixel-level design.
The approved visual design reference is the v3 prototype in [design/claude-pilot-prototype-v3/](design/claude-pilot-prototype-v3/README.md), chosen by the owner on 2026-11-13 (D33).
This brief stays the behavior contract. The prototype shows the approved look, layout and states.
Claude should implement the approved design rather than invent a competing direction.

## Experience principles

- Open into useful local content, not a full-screen network spinner.
- Writing and completing personal Tasks must feel immediate.
- Quiet, content-first interface with whitespace and readable type.
- Progressive disclosure for secondary details.
- Preserve Topic context when inspecting an external item.
- No decorative analytics cards, oversized Pomodoro card, or gamification.
- Clearly distinguish saved locally from synchronized remotely.

## Navigation (D35)

One bottom status line on every page replaces the sidebar:

```
[workspace] [Today Inbox Topics Notes Jira PRs] [Task v | Capture a task | -> Inbox] [Search ⌘K] [Focus] [sync] [Trash] [Settings]
```

- Views: Today, Inbox, Topics, Notes, Jira, Pull Requests. Inbox shows its count.
- Capture: choose Task, Note or Topic, type a title, pick a destination. `C` focuses it from any page.
- Tools: global search (⌘K), Focus timer panel, sync status, Trash, Settings.
- The line shrinks by width: full labels, then icons, then a compact capture field.
  Under 900 px the current view opens a views sheet. The v3 README lists the exact breakpoints.
- Notes index, Settings and Trash are approved as part of v3.
- A dedicated global Tasks page is not required by the confirmed scope.
- Idea only, not planned: a Settings toggle to show navigation in the status line or in a sidebar.

## Screen contracts

### Today

Main area: today's Tasks, due/overdue work, Reminders.
Separate Review Requested section.
Groups (D37): Overdue, Today, Earlier planned in the main column; Reminders and Review requested in a side column.
Upcoming and No date follow Earlier planned (D53). A Task is in the first group that fits, in that order.
- Upcoming: open Tasks with a planned day or deadline after today, under small day headings ("Tomorrow", "Mon 17 Nov"), earliest first; a timed deadline on that day sorts by its time. Rows keep their "Planned" and "Due" lines.
- No date: open Tasks with neither date, not waiting in Inbox, newest first, with the Topic tag.
- Each shows 10 rows, then "Show all (N)" and "Show fewer". Empty, they are not shown.
- Every group's heading is a button with a chevron (`aria-expanded`, `aria-controls`, the body `hidden` when closed), like a Topic Details section. The open count stays in the heading when closed. Overdue, Today and Earlier planned start open; Upcoming and No date start closed. Each device remembers the choice; it is not synced.
- Since D54 a reminder day counts as a date: a Task with a reminder today (or one due and not dismissed) is under Today, a later one under Upcoming, and No date means no planned day, deadline or reminder. The heading hints say so: "Planned, due or reminded today", "Planned, due or reminded after today", "No planned day, deadline or reminder".
- The page header count ("N open tasks") counts Overdue, Today and Earlier planned only.
Active and paused Topics (D59): Topics whose status is in the Active or Paused category, one group per status in Settings order, oldest first.
- List / Board switch, List by default; each device keeps the choice.
- List: a heading per status (chevron, status swatch, name, count) over Topics list rows. A status with no Topic has no group. Groups start open, and each device remembers which are closed.
- Board: one read-only lane per status with the Today cards. A lane with no Topic says "No Topics", hidden on phones. At 760 px and wider, lanes that do not fit first widen the board (264 px a lane, up to the window less a 50 px gutter; the heading keeps the page width), then shrink evenly to 210 px, and only then scroll inside the section (D66). Below 760 px the lanes stack, as before.
- With only one Active or Paused status there is no heading, and the hint names the status.
Topic labels provide context.
A Task that qualifies for multiple sections should not create confusing duplicates.
Empty state should support quick capture, not just show an illustration.

### Topics List

Rows: title, semantic status, next step, personal Task progress.
The next step is the Topic's first open Task, the oldest one, as Topic Details lists it (D52): "Next: <title>". Without one: "Finished" for a Done-category status, else "No open tasks". Board cards and Today's Active Topics use the same Task; a Board card without one shows no line.
Progress counts personal Tasks only; do not imply Jira completion.
List / Board toggle preserves selected mode.
Filtering and sorting controls should remain unobtrusive; exact options are open.

### Topics Board

One column per custom status, ordered by the workspace status order.
Small, readable cards. No giant nested previews.
Drag-and-drop with keyboard/menu equivalent.
Reflect the status change locally and persist it.
Preserve an intentional accessible horizontal-scroll experience on narrow displays.
Width (D66), at 760 px and wider: when the lanes fit the page, nothing changes. When they do not, the board grows past the page, centered, only as far as the lanes need (256 px a lane, gaps included), up to the window less a 50 px gutter or the safe area. The page heading row keeps the page width. If the lanes still do not fit, they shrink evenly to a 210 px floor, and only then does the board scroll sideways inside itself. At the floor a card keeps its hex, its wrapped name, its next line and its status select. Six lanes fit from a 1424 px window, seven from 1672 px. Below 760 px the board scrolls inside itself with 240 px lanes, as before.

### Topic Details

Header with title/status and short goal.
Main flow: Additional information, personal Tasks, Notes.
Additional information (D52) replaces v3's "Pick up here": the same tinted section, one text box (4 rows, behind Edit since D70) labelled by its heading, placeholder "Context, links, decisions: anything worth keeping with this Topic". It saves on blur, a second after typing stops, and when the page leaves. A Topic saved before shows its "Where I left off" and "Next step" text in the box until it is edited.
Secondary region: Jira, PRs. Since D54 there is no Reminders section: Task rows show a bell with the next reminder, and "Topic reminders" appears only while the Topic has some left from an older build.
Linked Jira tickets nest under their linked parent (D64), by Jira's `parent` field only; issue links stay flat. Nested lists, 25 px a level (16 px below 760 px) with a thin guide line, no deeper indent after 3 levels. A ticket whose parent is not linked shows a muted "in <KEY>" after its title; if an older ancestor is linked, it nests under the closest one and keeps the hint.
Sections collapse; exact default expansion behavior to be designed.
Tasks, Notes, Jira tickets and pull requests open as cards on top of the page (D70, see Item cards); the page stays as it was under the card. There is no side panel and no routed detail screen.
Additional information shows as text while viewing (D70). Its small Edit opens the same box, focused; Done or Esc closes it. The text saves as before.
The header's Edit opens the Edit Topic dialog (D71), the same from the page and from a Topic card. It is a small Topic card: its top edge, band and sticker take the color being picked, and the sticker shows the monogram of the name being typed. Name, Goal (optional; two lines, growing to six) and Sticker color: Chrome's nine colors as hex swatches (D72), one row on wider screens and five a row under 600 px, with the chosen color's Chrome name in the legend. Cancel and Save changes sit in a foot; nothing saves until Save changes, and Esc closes without saving.

Suggestions (D49, ADR-017). Not in the v3 prototype; placement queued as Q77.
- A "Suggested" group under the linked rows of the Jira and Pull requests sections. Hidden when not connected or when there is nothing to suggest.
- The "Suggested N" heading is a button with a chevron that folds the group away and opens it again, like Today's groups (D65). Folded, the heading and the count stay. Each device remembers it per Topic and section; it starts open and is not synced.
- Rows reuse the external row with a dashed icon frame and a muted title. The snippet line gives the best two reasons ("Mentioned in Additional information", "Mentioned in pull request #12", "SYN-101 is blocked by this"). "Also in" tags show other Topics that link the item.
- Phase 2 reasons: "Parent of SYN-103", "Child of SYN-101", "Same parent as SYN-103" (Jira) and "Mentions SYN-101" (pull requests). A closed or merged PR found this way ranks below open ones. No new controls.
- Each row has a small **Link** text button and a **Don't suggest** icon button ("Don't suggest SYN-104 in this Topic"). Both show a toast with Undo.
- 3 rows, then "Show N more", up to 10. "Not suggested here (N)" opens a dialog with **Restore** per row (title from the device, else "Not on this device") and **Done**.
- Focus: after Link or Don't suggest, the next suggestion, else the section heading. In the dialog, the next Restore, else Done; closing returns to "Not suggested here".
- Narrow screens: title and reasons wrap; the two buttons stay on the right (checked at 390 px).
- Offline: the rows already on the device, no error.

### Note editor

A Note opens for viewing, in the Notes tab and in a card (D70): no caret, no toolbar, the title as a heading. Checklist items still tick and links open. Edit (or E) switches to writing; Done (or Esc) goes back. A new Note opens for writing. A Note the editor cannot read keeps Edit off.
Visual formatting: headings, emphasis, lists, checkboxes, links.
Quiet autosave indicator: Saving locally / Saved locally / Syncing / Synced / Needs attention.
Do not show Synced before remote acknowledgment.
Handle paste, text selection, undo/redo, IME and Georgian text, links, and mobile keyboard.
No request per keystroke; local durability and reasonable batching are required.
Preserve unsynchronized drafts on navigation or recover them on next launch.

### External details

Title, key/number, status, assignee/author, metadata, rich description, comments.
Show last refreshed time and whether the content is cached.
Loading more comments should not block already available content.
Include Open in Jira/GitHub. It opens the original page in a new tab (D46).
Jira status shows as a small lozenge colored by status category, like Jira (D50): To Do gray, In Progress blue, Done green. The text is Jira's status name as written, not uppercased. Each color pair meets WCAG AA on the dark theme. Used on Jira list and search rows, the ticket header, a Topic's Jira section, ⌘K results and suggestion rows.
Jira issue types show as Thread's own icons (D63): a white glyph on a colored tile for Epic, Story, Task, Subtask, Bug and Defect, and a gray one for any other type. Rows show the icon only, with the type name in its label and hover title; the ticket header shows the icon and the name. A ticket whose type is not on the device keeps the plain Jira mark, and so do places that mean Jira itself (nav, Settings, empty states, ⌘K "Search Jira", the "Link a Jira ticket" picker).
The Jira "Assigned to me" tab has a "Current sprint only" checkbox above the list (D50). Off by default, remembered on this device, hidden when the site has no sprints. The label is the 40 px tap target. With it on and nothing in the sprint, the empty state says "Nothing assigned to you in the current sprint".
Unsupported rich content should degrade to an understandable placeholder/link, not disappear silently.
Jira text (D74) shows as in Jira: headings 1 to 6, quotes, rules, strikethrough, code with its language name, checklists, decisions (an arrow, "Decision:" for screen readers), tables with header cells, spans and row numbers, panels in Thread's tone colors with an icon (info, note, success, warning, error; a custom panel has none), expands as native disclosure rows (closed at first, open by click or Enter, "Details" when untitled) and inline status lozenges in Jira's six colors. Inline lozenges are uppercase like Jira's, unlike the ticket status lozenge (D50); each color pair is at least 7.4:1. A wide table scrolls sideways in its own box, so the page never does. Jira's text and cell colors are dropped. Pictures and files inside the text stay placeholders named by their file.
A ticket with attachments shows an Attachments card after Description (D75): a picture or document icon, the file name, and "PNG image · 240 KB · Thu 12 Nov 16:40". The name opens the file on the Jira site in a new tab ("opens in Jira" for screen readers); Thread does not download or preview files. A Sub-tasks card lists each sub-task with its type icon, key, title and status, the status kept on the first line on a phone. Details adds Due (a calendar day, "Fri 20 Nov", with the year when it is not this year) and Fix versions.
A Jira ticket and a pull request open as cards (D68, D69): a head card in the kind's band (key or state, title, people, actions), then each part as its own card. Jira: Description, Comments and Related issues on the left; Details and Topics on the right. A pull request is laid out like GitHub: the head card has the state, repository, title, the author (avatar and login, "You" for the viewer), the branches, CI and review, and three counts (commits, files changed, lines added and removed with GitHub's five blocks); Description on the left; Reviewers (avatar, login and review state, teams marked), Assignees and Topics on the right. The two columns switch on the width the detail has, not the window's, so the same layout fits the page and an 860 px card (D70). On one column the order is head, Reviewers, Description, Assignees, Topics. The pull request's own "Updated" field and the "CI sources" field are gone; the "Updated 09:41" bar above keeps when the copy was downloaded, and a muted line still says when the token could not read a CI part.

### Item cards (D70)

The rule: an item opened from its own tab opens there (a Note from the Notes list, a ticket from the Jira tab, a pull request from the Pull requests tab, a Topic from the Topics tab or its page). From anywhere else (Topic Details, Today, Inbox, reminders, toasts, Trash, the sync panel, ⌘K, another card) it opens as a card on top, and the page under it does not change. Views always navigate.

- **Cards:** Task 560 px; Note 860 px at a fixed height (1100 px while Compare versions is open); Jira ticket and pull request 860 px, read-only; Topic 1100 px with Topic Details as on the page, its two columns from 900 px of card width (a container query). On wide screens a card hangs from the top, so it grows downward and the band stays put. Under 900 px it fills the screen.
- **Band:** lane 3's kind band (D68) with Back (named after the card under it, when there is one), Edit or Done (Task and Note), "Open full page" (the item in its own tab; not for a Task) and Close. "Editing" shows next to the kind while editing. Under 600 px the button labels hide; each keeps its accessible name.
- **One card at a time:** an item opened from a card replaces it, and Back returns. The browser's Back does the same, and a card's address (`?task=`, `?note=`, `?jira=`, `?pr=`, `?topic=`) survives a reload. A link to an item the page already shows shows the page. A link to a gone item shows a toast.
- **View mode (#3):** Tasks and Notes open for viewing. Viewing opens with focus on Close. E edits (not while typing in a field); Esc while editing goes back to viewing and a second Esc closes. No Cancel: edits save as they are made. New items open for editing.
- **Task card:** viewing shows the title, Complete task, what Today will do, and Topic, Planned day (with Today and Tomorrow chips that still plan it), Deadline and Remind me (add and delete still work). Editing shows the title box, Topic, planned day, deadline and reminders. The foot has "Open Topic" and Move to Trash.
- **Topic card:** Topic Details on the page color, with its own Edit dialog and Additional information's Edit and Done; the band has no Edit. The foot has Move to Trash with Undo.
- **Closing a Note** whose latest changes are not saved on this device (Close, Esc, a click outside) asks "Close this note?" with "Close anyway". The browser's Back does not ask.
- Closing the last card returns focus to the row that opened the first one. Ids inside a card carry their own prefix.
- **The page stays still (D76):** while a card or any other dialog is open, the page under it never scrolls: not at a card's top or bottom, not on a card that does not scroll, not over the frame around a card, not by its own scrollbar. The card and the boxes inside it (wide tables, code blocks) scroll as usual. Closing the card leaves the page where it was. The page always keeps its scrollbar's room, so nothing shifts sideways when the page grows or a dialog opens.
- A row that is a link, and a Jira card's related ticket, keeps the item's own page as its address: a middle click or a click with a modifier key opens that page in a new tab instead of a card.

### Quick capture

Minimal initial fields. Capture lives in the status line (D35).
From Topic: default association.
Elsewhere: Inbox default with optional Topic.
Do not require scheduling, priority, or a Topic just to capture an idea.

### Pomodoro

Focus button in the status line; show remaining time while active.
It opens a small panel for configuration and actions, with no dashboard block (D45).
Delivered after the pilot; still in v1 (D34, D25). Built in Phase 14 (D56, ADR-019).
Task/Topic context visible within the popover.
Use persisted timestamps, not only a decrementing UI counter, to survive background throttling.
Built as v3 (D56): the tool shows "Focus" when Ready and the time left while a period runs or is paused, with v3's filled running style; its screen reader name carries the time. The panel (`?focus=1` on any page) has the time and phase (Ready, Focusing, Paused, Break, Break paused), "For:", Start focus, Pause or Resume, Stop, Focus min and Break min (editable only while Ready), "Attach to (optional)", the last 4 sessions (F3 A) and v3's timestamp note. It drops v3's concept badge and demo note.
End messages are in-app toasts (D27): "Focus session done. Break: 5 min." and "Break over.". An end noticed late (a hidden tab, a closed Thread) gives one message with the real times, such as "Focus session done at 14:25. Break ended at 14:30."
Each device has its own timer (F2 A); the lengths and the history are the same everywhere.

## Required interface states

For key screens design populated, empty, loading-first-use, cached-refreshing, offline, recoverable error, and permission/reauthentication states.
Do not blank useful cached data while refreshing.
Toast messages alone are insufficient for a persistent sync problem.

## Design system preparation

Proposed token groups:
- Semantic colors: surface, text, border, status categories, success/warning/error.
- Typography and spacing.
- Radius and elevation.
- Row/control dimensions.
- Density tokens.

Comfortable is the only initial density.
Compact will change spacing and row dimensions, not business features.
Dark mode is the only theme in v1. Additional themes and a theme switch are deferred. Keep color roles semantic so other themes can be added later.
Palette, typeface and breakpoints follow the v3 prototype (D33). App language remains open.
The production icon library is open; v3 uses its own inline SVG set.
Do not turn tentative visual defaults into hard product requirements.

### v3 visual system (approved 2026-11-13)

Source of truth: `design/claude-pilot-prototype-v3/assets/tokens.css`, in three layers (primitive, semantic, component).

| Area | v3 choice |
| --- | --- |
| Shell | Ink-navy surfaces (app `#0e1322`, surface `#131a2b`, raised `#192135`), fog text (`#e9edf7`, `#b2bbd2`, `#8792ae`). |
| Action | One magenta action color (`#f2559e`). One magenta pill per surface marks the main action. |
| Status categories | Gray `#a7b0c6`, blue `#82b6ff`, orange `#f5a65b`, green `#7bd49f`, each with a pattern swatch: dashed outline = Planned, solid = Active, stripes = Paused, check = Done. Meaning never depends on color alone. |
| Topic identity | Hex sticker with a two-letter monogram (D36) in one of Chrome's nine tab group colors, named and ordered as in Chrome so a Topic can match its tab group (D72): Grey `#dfe2e9`, Blue `#a8bcff`, Red `#ff928b`, Yellow `#ffdd7a`, Green `#87eb84`, Pink `#ff96de`, Purple `#cb93ff`, Cyan `#88e3eb`, Orange `#ffb379`, Chrome's dark-theme values. Topic colors are identity only, never status. Idea only, not planned: an optional icon instead of the monogram. |
| Type | Futura for display, system UI for text. Scale 12, 13, 14, 15, 17, 20, 24, 30, 38 px. Futura ships with Apple platforms; other systems use the listed fallbacks (Century Gothic, URW Gothic, Trebuchet MS). |
| Space and shape | 4 px spacing base. Radius 10 px for controls and rows, 14 px for surfaces, pills for primary actions. Controls 40 px, primary 44 px, rows 48 px minimum, status line 56 px. |
| Notes | The editor is a notebook page on a faint dot grid. |
| Today | Overdue, Today, Earlier planned, plus a side column for Reminders and Review requested (D37). Upcoming and No date added after v3 (D53). A Task's reminder day counts as a date (D54). |
| Motion | Short transitions (120 and 220 ms) that drop to zero with reduced motion. |
| Scrollbars (D73) | Every scroller, the page included, has a 10 px bar with no track, so the surface shows through, and a 6 px rounded thumb in fog `#5b6681`, brighter (`#8792ae`) under the pointer. Only where the main pointer is a mouse or trackpad; touch screens keep their own overlay scrollbars. Chromium and Safari draw it with `::-webkit-scrollbar`; Firefox gets the thin standard bar in the same colors, without the hover change. |
| Cards (D68) | Every section is a card with a tinted head band; every item is a card shaped like its object. See "Card language" below. |

### Card language (D68, D69)

Direction D, "Band + Object", picked by the orchestrator while the owner was away (D68). Source: `webApp/src/jsMain/resources/css/cards.css` and `Cards.kt`.

- **Section card:** every section and block (Topic sections, Today's sections, Pull requests' repository groups, a detail's parts, Trash's preview) is a card. Its head band is tinted in the kind's accent and carries the kind's mark, the title and the count; Notes, Jira and Topics add a pattern Thread draws in the accent, fading toward the actions.
- **Item card:** every Task, Note, Jira item, pull request, Topic and reminder is a card shaped like its object, in lists, Today, Inbox, Notes, Jira, Pull requests, Trash and ⌘K results.

| Kind | Accent | Mark and shape | Band pattern |
| --- | --- | --- | --- |
| Task | Neutral fog `#c3cce0` | Its checkbox on a plain card | None |
| Note | Paper yellow `#e8c46a` | A page with a folded corner | Dot grid |
| Jira item | Jira blue family `#6aa8ff` | A ticket with a stub and a perforated edge | A tear line of dots under the band, a notch at each end |
| Pull request | GitHub violet family `#b48cff` | A commit on a rail; cards in one list share the rail | None |
| Topic | The Topic's own color (D72) | The hex sticker; a colored top edge | Hexagons |
| Reminder | Fog `#ced5e6` | The time leads, like a clock face | None |

- Marks are Thread-drawn, in the provider colors. No official Jira or GitHub logos (Q5).
- Each accent is at least 6.7:1 on the surface color. Kind never depends on color alone: each has its own mark, shape and label.
- A suggestion is the same card with a dashed edge and a quieter title. The "Suggested N" heading still folds them away (D65).
- A Jira card's stub holds the issue type icon (D63) in place of the Jira mark, so a ticket shows one mark. The stub, the tear line and the blue still say Jira, and the stub keeps the Jira mark when the type is not known. The type shows on hover, and screen readers hear it in the card's link. A Topic's tickets under a linked parent (D64) are cards in the nested lists, one card gap apart.
- ⌘K results are small cards of their kind. Settings' Jira and GitHub connections use the kinds' marks and accents.
- An item card (D70, an item opened on top of another screen) starts with the kind's band: the mark and kind name, a back button on the left and the actions on the right. Under 900 px the card fills the screen, and under 600 px the button labels hide. A Topic's card sits on the page color, like the Topic page.
- The Edit Topic dialog (D71) is a small Topic card: the colored top edge, and the band with its hexagons and sticker, preview the color being picked before it is saved.

## Accessibility and interaction quality

Use semantic HTML, keyboard access, visible focus, sufficient contrast, and meaningful labels.
Status meaning must not depend on color alone.
Support reduced motion.
Cmd+K must coexist with editor shortcuts. ⌘K opens global search in the editor too; adding a link uses the toolbar button (D40).
No page zoom (D48). The page blocks every zoom it can: the viewport meta (`maximum-scale=1, user-scalable=no`, which also stops iOS zooming in on a focused field), `touch-action: pan-x pan-y` (no pinch or double-tap zoom, scrolling stays), Ctrl or ⌘ with + = - _ 0, Ctrl or ⌘ with the wheel or a trackpad pinch, and Safari's pinch gestures. Page code cannot block the browser's own menu zoom, the operating system's accessibility zoom, or a setting that forces zoom on (iOS Safari's "ignore zoom restrictions"). This is a deliberate trade-off against text resizing (WCAG 1.4.4), chosen by the owner.
Retain sensible browser navigation and deep links.

## Next visual deliverables

1. Today desktop.
2. Topics List and Board.
3. Topic Details with external detail panel.
4. Note editor states.
5. Narrow-screen adaptations and core empty/offline states.

All five are covered by the v3 prototype, approved on 2026-11-13 (D33).
New screens or states beyond it should follow its visual system.

