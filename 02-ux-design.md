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
[workspace] [Today Inbox Topics Notes Jira PRs] [Task v | Capture a task | -> Inbox] [Search ⌘K] [Standup in 12 min] [Focus] [sync] [Trash] [Settings]
```

- Views: Today, Inbox, Topics, Notes, Jira, Pull Requests. Inbox shows its count: what Inbox shows, so Notes and completed Tasks hidden in Settings > Inbox are left out (Q8, D91).
- Capture: choose Task, Note or Topic, type a title, pick a destination. `C` focuses it from any page.
- Tools: global search (⌘K), Focus timer panel, sync status, Trash, Settings.
- Next meeting (D78): in the hour before a timed meeting that starts today, a teal chip names it ("Standup in 12 min"), then "Standup now" for 5 minutes after the start; "12m" or "Now" on a narrow line. It opens the Meeting card. It is not a live region; its name is a full sentence. All-day meetings never show.
- The line shrinks by width: full labels, then icons, then a compact capture field.
  Under 900 px the current view opens a views sheet. The v3 README lists the exact breakpoints.
- Notes index, Settings and Trash are approved as part of v3.
- A dedicated global Tasks page is not required by the confirmed scope.
- Idea only, not planned: a Settings toggle to show navigation in the status line or in a sidebar.

## Screen contracts

### Today

Main area: today's Tasks, due/overdue work, Reminders.
Separate Review Requested section.
Width (D87): with the Horizontal Meetings layout, while its timeline shows (loading included), Today's page is up to 1600 px wide; the Vertical layout and other pages stay at 1240 px, boards at 1520 px. The timeline takes the whole width (an hour of 10:00 to 19:00 is 165 px at 1600, 146 px in a 1440 px window, 125 px before D87); the side column keeps 340 px and the task column takes the rest. Narrower windows and phones are unchanged.
Groups (D90; before it Overdue, Today and Earlier planned, D37, then Upcoming and No date, D53): the main column groups today's Tasks as Settings > Today "Group tasks by" says; Reminders and Review requested sit in a side column.
- Which Tasks: open Tasks with a planned day, deadline or reminder (D54), open Tasks with none that do not wait in Inbox (D38), and, while "Show completed tasks" is on, the Tasks completed today. Each shows once.
- One order in every mode (the shared rule, `dueOrder` in the domain): the open Tasks for today (overdue by deadline, then planned, due or reminded today by deadline, then planned on an earlier day by planned day); then today's completed Tasks, oldest first; then later Tasks by first day, then the time of a timed deadline or reminder on that day, then creation; then the Tasks with no date, newest first.
- Due date, the default: Completed, Today, Tomorrow, Upcoming, in the owner's order. Completed holds today's completed Tasks; Today what were Overdue, Today and Earlier planned; Tomorrow the open Tasks whose first date is tomorrow; Upcoming the later ones under small day headings ("Fri 20 Nov"), then the ones with no date under "No date". Hints: "Done today. They leave when the day ends", "Overdue, planned, due or reminded today, or planned earlier", "Planned, due or reminded tomorrow", "Later dates, then no date". Rows keep their "Planned" and "Due" lines.
- Topic: a group per Topic with the Topic's sticker before its name in the heading. The Topic with the most urgent open Task comes first, then Topics with only completed Tasks, "No Topic" last; a Task whose Topic is in Trash counts as having none. Rows leave out the Topic tag.
- None: one group, "Tasks", hint "For today first, then done today, then later and with no date".
- In Topic and None, with no day headings, every row says when its Task is planned, today included.
- Today and Completed show every Task. Tomorrow and Upcoming show 10, then "Show all (N)" and "Show fewer". In Topic and None the Tasks for today and today's completed ones always show, then 10 more.
- Every group's heading is a button with a chevron (`aria-expanded`, `aria-controls`, the body `hidden` when closed), like a Topic Details section. The count stays in the heading when closed: open Tasks, or for Completed all of its Tasks. Today and Tomorrow start open; Completed (like Topic Details' Completed fold, A8) and Upcoming start closed; Topic groups and None start open. Each device remembers the choice per group; it is not synced.
- Days follow the workspace time zone (A12): at midnight there, Tomorrow's Tasks move to Today.
- The page header count ("N open tasks") counts the Today group only; "M done today" counts today's completed Tasks while they show.
- The Task sheet says where Today lists a Task: "Shows in Today under Tomorrow. Tomorrow it moves to Today.", "Shows in Today under its Topic because ...", or no group name with None.
- An empty task group is never shown, Today's too (D86, Q2): with no task at all the main column is not drawn (A15).
- Settings > Today, "Hide empty sections" (D77, one checkbox per section since D86, synced): Meetings, Reminders and Review requested, none checked by default; a workspace that only had the D77 switch on reads as Reminders and Review requested. A checked section is not shown while empty and comes back as soon as something arrives. Review requested counts as empty while GitHub is not connected, while its first answer loads and with no requests; it shows when GitHub could not answer and nothing is saved. Meetings counts as empty with no meeting left to show today (ended ones that "Show past meetings" leaves out count as gone), while its first answer loads and before an upload covered today; a problem keeps it: no upload for over an hour, a problem on the Mac, meetings that cannot load, or Thread needing an update. A column with nothing left is not drawn and the other takes the width. With no task and every other section empty and hidden, one line says "Nothing for today" ("No tasks, meetings, reminders or review requests. Empty sections are hidden.") with a link to Settings > Today; the header with "Add task for today" and Active and paused Topics stay.
Meetings (D78, ADR-020): a section card at the top of the side column, above Reminders, once meetings are set up (the Mac app has uploaded once).
- Rows: the time first (start over end, or "All day"), the title on its own line, then Now or Next with how long until it starts or ends, "Started yesterday" or "Ends tomorrow", "Tentative" or "Not answered", then the place and the linked Topics as tags. All-day first, then by start. Ended rows stay in order, greyed; the running one is tinted. Settings > Today "Show past meetings" (D86: All, the default, None, or 1 to 20) keeps only that many ended rows, the ones that ended last; all-day rows always stay. When it leaves no row, the card says "No more meetings today." and its count is what it shows.
- Each row is one button whose name is the whole sentence; it opens the Meeting card. Join sits beside it: "Join (Teams)" in a new tab, in the action style from 10 minutes before the start to the end. The join link is never shown as text: it may hold a passcode. A title or link the owner set on the Meeting card (D100) shows instead of the calendar's, here and everywhere else the meeting shows; a link of the owner's own from a service Thread does not know shows as "Join".
- One status line in the card: a problem on the Mac first (no calendar access, no calendar picked, or paused, each with what to do in the Mac app), then "Not updated since 08:10. Is your Mac awake and Thread Calendar running?" after an hour without an upload, then "No meetings today." only after an upload that covered today. The last upload's time closes the card ("Updated from your Mac at 10:42").
- While a page loads, or after a read error, it keeps its place as last seen on this device, so it neither flashes in nor disappears. A read error stays inside the card.
- Under 1100 px, where the side column's cards sit two to a row under the main column, the Meetings card takes the whole row.
- Settings > Today, "Meetings layout" (D79, synced): Vertical (default) is the card above. Horizontal takes the Meetings card out of the side column and puts it across the page under the heading, above both columns, as a timeline. The note, the footer and "Hide empty sections" work as above. "Show past meetings" is for the card only: the timeline keeps the whole day (D86, Q3).
  - Hours run left to right: 10:00 to 19:00 (D85), widened to whole hours for earlier or later meetings and for an hour after each flag's start; a meeting from yesterday starts at 00:00 and one into tomorrow ends at 24:00, with a square edge on that side. A line at now, with its time on the ruler, shows inside the hours.
  - All-day meetings sit as small cards ("All day", title) in a strip above the hours. Timed ones are compact blocks placed by start and end; an overlap takes the next row. A block's look comes from whether the start of its title fits (D85): its first 8 characters and an ellipsis, or a shorter title whole, counted at 7 px a character, never measured from the drawn text. When that fits across the block, the title lies flat on up to two 12 px lines, then Now or Next and the start on one line; a part of that line that does not fit whole is hidden, never cut. When it does not, the title runs along the block's diagonal on one line (D88), from the bottom-left corner to the top-right: each block gets its own angle, steep in a narrow block and flatter in a wider one, upright only in a block one line wide. The line touches the colored edge and the bottom where it starts and the right border and the top where it ends, 4 px inside the border above and below, and is cut with an ellipsis inside its block, with no time line; the tint or outline says Now or Next. A block whose 60 px are too short for those characters along its diagonal grows a pixel at a time until they fit (at most 79 px for a title longer than 8 characters); its row grows with it and the rows below move down, while other blocks keep 60 px at their row's top. Blocks have 3 px after the colored edge and 2 px before the right border. Ended ones are greyed, Now tinted, Next outlined, Tentative and Not answered dashed; the tooltip, the accessible name and the Meeting card have the whole title. Flat titles break only between words; a longer word is cut inside the word, with an ellipsis. Settings > Today "Narrow meeting titles" (D97) changes only the blocks that would run their title along the diagonal. Vertical turns it upright at 90°, the block 60 px tall or as tall as the line and 10 px. Dots with flags draws such a meeting as a flag; one that started yesterday keeps its block, its title on the diagonal. Increase height keeps the title flat and wraps it onto as many 15 px lines as its first 8 characters need, between words, or inside a word wider than the block; the rest is cut with an ellipsis, and the block is 15 px taller for each line past two. Its time line shows each part only while it fits whole. On a phone the four choices stand in two columns.
  - A meeting too narrow for one turned line (under 21 px: under 9 minutes in a 1440 px window and under 8 at 1600 px since D87, under 15 minutes on a phone) is a flag (D85), unless it started yesterday: a thin line up from the hours at its start, a dot at its foot, a 3 px bar along the hours for its real length, and its title on one line on a small flag at the top. Flags sit in a band between the ruler and the blocks; a day without them has no band, and a day with only flags has no empty block row. Latest start first, a flag takes the lowest band row with at least an hour free up to the next title or line, and its title gets up to 90 minutes of that; then a cut title moves up to a row where it fits whole if its line crosses no title. A busy hour reads as a staircase, earliest highest. Same phase styles as blocks (ended grey, Now strongest, Next outlined, Tentative and Not answered dashed line), no badge. The tooltip has the whole sentence, and the flag deepens on hover. Targets: 24 px tall, in 26 px rows, on every screen (WCAG 2.5.8).
  - Each block or flag is one button named by the row's sentence, Tab in time order after the all-day cards; it opens the Meeting card. Topic tags are not drawn on blocks; the name says them.
  - Join for the meeting now and the next one sits in the card's head as "Join <title>"; under 760 px on its own row under the title.
  - Each hour takes at least 88 px. A narrower card scrolls the hours inside itself, starting with now a third from the left; the page never scrolls sideways.
Today's Topics (D59, D89): Topics whose status is in a category checked under "Topics shown" in Settings > Today (Active and Paused by default), one group per status in Settings order, oldest first.
- Title: the checked categories in their fixed order (Planned, Active, Paused, Done), the first capitalised, "and" before the last, no comma before it: "Active and paused Topics" (default), "Paused Topics", "Planned, active and paused Topics"; all four is "All Topics". The layout switch is named "<title> layout".
- With none checked the section is not drawn, and nothing takes its place. It is not one of the "Hide empty sections": with a category checked and no Topic it shows its empty state, "No active or paused Topics" (the checked categories joined by "or"), with "Open Topics"; with all four, "No Topics".
- A Done Topic with nothing else to say reads "Finished." (D81).
- List / Board switch, List by default. The choice syncs and is also "Topics open as" in Settings > Today (D84); until it is first changed, a device shows the one it kept before.
- List: a heading per status (chevron, status swatch, name, count) over Topics list rows. A status with no Topic has no group. Groups start open, and each device remembers which are closed.
- Board: one read-only lane per status with the Today cards. A lane with no Topic says "No Topics", hidden on phones. At 760 px and wider, lanes that do not fit first widen the board (264 px a lane, up to the window less a 50 px gutter; the heading keeps the page width), then shrink evenly to 210 px, and only then scroll inside the section (D66). Below 760 px the lanes stack, as before.
- With only one status in the checked categories there is no heading, and the hint names it: "Statuses in the Paused category: Paused".
Edit mode (D98 and D99, owner change #29 L3 and L4):
- "Edit layout" (line button) sits in Today's header before "Add task for today". `#/today?edit=1` opens edit mode too (Settings > Today "Edit layout"); the address then drops the parameter. While editing, a bar takes the button's place under the header and stays at the top while the page scrolls: "Editing the layout" (or "Editing the narrow-window order"), a hint, "Reset to default" (or "Match desktop"; disabled while nothing is saved and nothing moved), Cancel and Done (primary). "Add task for today" and its form stay. Focus goes to the bar's title when it opens, and back to "Edit layout" after Cancel, Esc or Done.
- Places at their real widths: Top and Bottom across the page, Left and Right as Today's columns (Right 340 px at 1100 px and wider, both full width under it). Each is a named region with a dashed outline, its name and what it is ("The side column"). An empty place says "Drop a card here" (D99; "No cards here" in D98).
- A compact card: a grip, the card's name, a tag when Today does not draw it now (the card dimmed), "Move <card> to" (Top, Left, Right, Bottom; the card goes to the end of the new place), Move up and Move down inside its place (disabled at its ends), and its hide control: a "Hide when empty" switch for Meetings, Reminders and Review requested, the four "Topics shown" checkboxes for Topics, nothing for Tasks. In a narrow place the controls wrap under the name.
- Every change is said in the toast's live region: "Moved Meetings to Top, 1 of 1", "Moved Reminders up, 1 of 3", "Moved Topics first, 1 of 5", "Reminders will hide when empty", "Topics shown: Active and Paused", "Topics will be left out", "Layout back to default. Press Done to save."; Done says "Layout saved" (or "No changes"), Cancel "Changes undone". Focus stays on the control used; a card moved to another place keeps focus on its select there; a card reaching its place's end moves focus from the now disabled button to its select.
- Esc cancels, except inside an open card or dialog, the add form or an IME composition.
- Drag (D99, D-7): a card's head (grip, mark and name) is its handle, with a grab cursor. A mouse or pen drags after moving 4 px; a touch screen presses and holds the head for 500 ms, and a finger that moves 8 px first scrolls the page instead. While dragging, a copy of the head follows the pointer, the card stays in its place at 45% opacity, the place under the pointer lights up (a lighter outline and background), and a 3 px pink line shows where the card would go, before the card that would follow it or after the last; an empty place lights up with no line. Near the top or bottom of the area under the edit bar (and a due reminder's banner) and over the status line, the page scrolls, faster nearer the edge. Releasing over a place moves the card there, as one draft change said in the live region like the Move select ("Moved Topics to Right, 2 of 3"; in the phone order "Moved Topics to place 2 of 5"), with focus on the card's select. Releasing outside every place, Esc, a lost pointer, or the window losing focus calls off the drag and changes nothing; Esc while dragging does not close edit mode. The hint says how: "Drag a card by its name to any place, or use its Move menu and arrows." with a mouse, "Press and hold a card's name, then drag it to any place, or use its Move menu and arrows." on a touch screen (without "to any place" in the phone order). The Move select and the buttons stay as the keyboard path.
- At 759 px and narrower (phones and narrow windows, D-9) Today draws one column in the phone order, the wide layout read top, left, right, bottom until one is saved. Edit mode opened there shows one place, "Order" ("One column, top to bottom"); "Move <card> to" offers First and Last, up and down move along the column, and the reset is "Match desktop". Edit mode keeps the layout it opened on if the window is resized across 760 px.
Topic labels provide context.
A Task that qualifies for multiple sections should not create confusing duplicates.
Empty state should support quick capture, not just show an illustration.

### Topics List

Rows: title, semantic status, next step, personal Task progress.
The next step is the Topic's first open Task, the oldest one, as Topic Details lists it without due-date grouping (D52, D92): "Next: <title>". Without one, the line shows the Topic's Additional information, else its goal (D81): plain text with no label, line breaks as spaces, in the line's own colour. Blank text counts as empty. Without any of them: "Finished" for a Done-category status, else "No open tasks". Board cards and Today's Active Topics use the same rule; a Board card with nothing to say shows no line. A list row keeps its one line with an ellipsis on a wide screen; cards, and rows on a phone, show at most two lines with an ellipsis.
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
Meetings (D78): a section card once meetings are set up on this device, or while the Topic has a link. One row per linked series: its next occurrence ("Now, until 11:30", "Tomorrow, 09:00", "Today, all day") or "None in the next 14 days", Join when that occurrence is today and not over, and Unlink with Undo. A row opens the next occurrence as a card and is marked current. A series gone from the window keeps the title saved with the link. "Link" opens a single-select picker of the series not linked yet: upcoming first by start, then ended ones by title, "repeats" on a series with more than one occurrence.
Additional information shows as text while viewing (D70). Its small Edit opens the same box, focused; Done or Esc closes it. The text saves as before.
The header's Edit opens the Edit Topic dialog (D71), the same from the page and from a Topic card. It is a small Topic card: its top edge, band and sticker take the color being picked, and the sticker shows the monogram of the name being typed. Name, Goal (optional; two lines, growing to six) and Sticker color: Chrome's nine colors as hex swatches (D72), one row on wider screens and five a row under 600 px, with the chosen color's Chrome name in the legend. Cancel and Save changes sit in a foot; nothing saves until Save changes, and Esc closes without saving.

Settings > Topic Details (D92), the same on the page and in the Topic card:
- **Completed:** the "Completed N" toggle under the open Tasks, closed at first; each device remembers it for every Topic. Its rows do not say "Planned today". "Show completed tasks" off removes it; the heading still counts open Tasks.
- **Hidden sections:** a checked section that holds nothing is not drawn, its actions with it. Holding something: a linked Note; for Jira and Pull requests a linked item or a suggestion while connected (Q7); a linked meeting series; Additional information with text. Additional information stays while its text box is open; Done then hides it. When a section hides while focus was in it (its last Note unlinked, say), focus goes to the header's Edit. With every side section hidden or absent, the side column goes away and the main column takes the width.
- **Edit Topic field (A9):** while Additional information is hidden, the dialog opens with an "Additional information" field (optional, 3 rows, the section's placeholder) between Goal and Sticker color. Saved text brings the section back; a blank field changes nothing.
- **Group tasks by due date:** the Tasks section lists Completed (while shown), Today, Tomorrow and Upcoming, in the shared order (D90). Each date group is a labelled group with a small heading: Today's mark, name and count, like a "Suggested" heading. Date groups do not fold (the Tasks section folds as a whole); Completed keeps its fold, with a chevron heading like theirs, first as in the shared rule. Empty groups hide. The rows look the same as in the plain list.

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
The Jira "Assigned to me" tab has a "Current sprint only" checkbox above the list (D50). Off by default, hidden when the site has no sprints. The choice syncs and is also in Settings > Jira (D84); until it is first changed, a device uses the one it kept before. The label is the 40 px tap target. With it on and nothing in the sprint, the empty state says "Nothing assigned to you in the current sprint".
Unsupported rich content should degrade to an understandable placeholder/link, not disappear silently.
Jira text (D74) shows as in Jira: headings 1 to 6, quotes, rules, strikethrough, code with its language name, checklists, decisions (an arrow, "Decision:" for screen readers), tables with header cells, spans and row numbers, panels in Thread's tone colors with an icon (info, note, success, warning, error; a custom panel has none), expands as native disclosure rows (closed at first, open by click or Enter, "Details" when untitled) and inline status lozenges in Jira's six colors. Inline lozenges are uppercase like Jira's, unlike the ticket status lozenge (D50); each color pair is at least 7.4:1. A wide table scrolls sideways in its own box, so the page never does. Jira's text and cell colors are dropped. Pictures and files inside the text stay placeholders named by their file.
A ticket with attachments shows an Attachments card after Description (D75): a picture or document icon, the file name, and "PNG image · 240 KB · Thu 12 Nov 16:40". The name opens the file on the Jira site in a new tab ("opens in Jira" for screen readers); Thread does not download or preview files. A Sub-tasks card lists each sub-task with its type icon, key, title and status, the status kept on the first line on a phone. Details adds Due (a calendar day, "Fri 20 Nov", with the year when it is not this year) and Fix versions.
A Jira ticket and a pull request open as cards (D68, D69): a head card in the kind's band (key or state, title, people, actions), then each part as its own card. Jira: Description, Comments and Related issues on the left; Details and Topics on the right. A pull request is laid out like GitHub: the head card has the state, repository, title, the author (avatar and login, "You" for the viewer), the branches, CI and review, and three counts (commits, files changed, lines added and removed with GitHub's five blocks); Description on the left; Reviewers (avatar, login and review state, teams marked), Assignees and Topics on the right. The two columns switch on the width the detail has, not the window's, so the same layout fits the page and an 860 px card (D70). On one column the order is head, Reviewers, Description, Assignees, Topics. The pull request's own "Updated" field and the "CI sources" field are gone; the "Updated 09:41" bar above keeps when the copy was downloaded, and a muted line still says when the token could not read a CI part.

### Item cards (D70)

The rule: an item opened from its own tab opens there (a Note from the Notes list, a ticket from the Jira tab, a pull request from the Pull requests tab, a Topic from the Topics tab or its page). From anywhere else (Topic Details, Today, Inbox, reminders, toasts, Trash, the sync panel, ⌘K, another card) it opens as a card on top, and the page under it does not change. Views always navigate.

- **Cards:** Task and Meeting 560 px; Note 860 px at a fixed height (1100 px while Compare versions is open); Jira ticket and pull request 860 px, read-only; Topic 1100 px with Topic Details as on the page, its two columns from 900 px of card width (a container query). On wide screens a card hangs from the top, so it grows downward and the band stays put. Under 900 px it fills the screen.
- **Band:** lane 3's kind band (D68) with Back (named after the card under it, when there is one), Edit or Done (Task and Note), "Open full page" (the item in its own tab; not for a Task) and Close. "Editing" shows next to the kind while editing. Under 600 px the button labels hide; each keeps its accessible name.
- **One card at a time:** an item opened from a card replaces it, and Back returns. The browser's Back does the same, and a card's address (`?task=`, `?note=`, `?jira=`, `?pr=`, `?topic=`, `?meeting=`) survives a reload. A link to an item the page already shows shows the page. A link to a gone item shows a toast.
- **View mode (#3):** Tasks and Notes open for viewing. Viewing opens with focus on Close. E edits (not while typing in a field); Esc while editing goes back to viewing and a second Esc closes. No Cancel: edits save as they are made. New items open for editing.
- **Task card:** viewing shows the title, Complete task, what Today will do, and Topic, Planned day (with Today and Tomorrow chips that still plan it), Deadline and Remind me (add and delete still work). Editing shows the title box, Topic, planned day, deadline and reminders. The foot has "Open Topic" and Move to Trash.
- **Meeting card (D78, D100):** no "Open full page". Edit (or E) opens two optional fields under when, as in the other cards (D70): "Title" (placeholder: the calendar's title; hint "Shown instead of the calendar's title. Leave empty to use it.") and "Join link" (placeholder `https://`; hint "Any https link. Shown instead of the calendar's link. Leave empty to use it."), then a line with the calendar icon: "Applies to every meeting in this series. Your calendar does not change." for a repeating meeting, else "Shows only in Thread. Your calendar does not change." Each field saves when it is left or on Enter; Done (or Esc) saves the field in use first. A link that is not a full https link stays in its field with "Use a full link that starts with https://, or leave it empty." under it, marked invalid, and nothing is saved; the first Done or Esc then goes to the field, the second leaves Edit and keeps the saved link. Emptying a field brings the calendar's value back. While an alternate is set the facts add "Calendar title" and "Calendar link" (the calendar's own Join, or "No join link") first. The title, when, then facts: where ("No place given"), your answer, and Topics: the linked Topics as chips with Unlink, "Not linked to a Topic yet", and "Link to Topic", a multi-select picker ("Save links"). Every link change has Undo. Join when the meeting has a link. The foot says "From Apple Calendar on your Mac. Thread does not change it." A meeting that is gone shows "That meeting is no longer in Thread." A link waits for the first read.
- **Topic card:** Topic Details on the page color, with its own Edit dialog and Additional information's Edit and Done; the band has no Edit. The foot has Move to Trash with Undo.
- **Closing a Note** whose latest changes are not saved on this device (Close, Esc, a click outside) asks "Close this note?" with "Close anyway". The browser's Back does not ask.
- Closing the last card returns focus to the row that opened the first one. Ids inside a card carry their own prefix.
- **The page stays still (D76):** while a card or any other dialog is open, the page under it never scrolls: not at a card's top or bottom, not on a card that does not scroll, not over the frame around a card, not by its own scrollbar. The card and the boxes inside it (wide tables, code blocks) scroll as usual. Closing the card leaves the page where it was. The page always keeps its scrollbar's room, also in Safari (D94), so nothing shifts sideways when the page grows or a dialog opens.
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

### Settings (D84)

One page. Under the header a row of links (chips) jumps to each block and moves focus to its heading; `#/settings/<block>` opens a block directly, and the old `#/settings/prefs` opens Today.
Blocks, in order: Connections, Calendar from your Mac, Topic statuses, Today, Inbox, Topics, Topic Details, Jira, Pull requests, General, Appearance, Account.
- Today, in the owner's order (D86, D89, D90, D97): Topics open as (List / Board), Show completed tasks, Hide empty sections (Meetings, Reminders and Review requested checkboxes in a labelled group), Meetings layout, Narrow meeting titles (Diagonal / Vertical / Dots with flags / Increase height; Diagonal by default; shown with either layout, its hint names the Horizontal timeline), Group tasks by (None / Topic / Due date; Due date by default), Show past meetings (a select: All, None, 1 to 20), Topics shown (Planned, Active, Paused and Done checkboxes in a labelled group; Active and Paused by default), Today layout (D98: "Edit layout" opens Today in edit mode; "Reset to default", or "Match desktop" at 759 px and narrower, resets the layout this window uses at once, with Undo in the toast, and is disabled while nothing is saved). A row of choices wraps under its label when the label would get too narrow beside it (at 390 px: Meetings layout, Narrow meeting titles and Group tasks by). More than three choices stand in two columns across the row where the row is under 440 px (Narrow meeting titles on a 390 px phone and at 480 px).
- Inbox, in the owner's order (D91): Show completed tasks (on), Group by (None / Type / Due date; None by default), Show notes (on).
- Topics: Topics open as (List / Board), the same choice as the Topics page switch (D21).
- Topic Details, in the owner's order (D92): Show completed tasks (on), Hide empty sections (Notes, Jira, Pull requests, Meetings and Additional information checkboxes in a labelled group; none checked), Group tasks by due date (off). The owner listed the last one under Hide empty sections; it is its own switch (A2).
- Jira: Current sprint only, the same choice as the Jira page checkbox. Disabled, with a hint, while Jira is not connected or the site has no sprints.
- Pull requests, in the owner's order (D93): Mine: group by (Repository / Topic / Review status), Review requested: group by (Repository / Author / Review status); Repository by default. The hints say that a pull request linked to more than one Topic shows once, under Several Topics, and that review status comes from pull requests opened on this device, the rest showing under Not loaded yet.
- General: Capture starts as.
Every preference syncs with the account; open or closed groups stay per device.

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
| Today | Overdue, Today, Earlier planned, plus a side column for Reminders and Review requested (D37). Upcoming and No date added after v3 (D53). A Task's reminder day counts as a date (D54). Since D90 the groups follow "Group tasks by": Completed, Today, Tomorrow and Upcoming by default, or by Topic, or none. |
| Inbox | One list of captures, newest first, with Select all and a selection bar (v3). Since D91 Settings > Inbox can group it by Type (Tasks, then Notes) or by Due date (Today's groups, then Notes), as section cards that do not fold, and can hide completed Tasks and Notes. A completed Task shows struck through. When only hidden items wait: "Inbox is clear", what is hidden, and a "Change in Settings" link. |
| Pull requests | Each list in repository groups (D62). Since D93 Settings > Pull requests picks each list's grouping: Mine by Repository, Topic or Review status; Review requested by Repository, Author or Review status. Groups come by their newest pull request, then by name; "Several Topics", "No Topic", "Unknown author" and "Not loaded yet" come last, and review groups keep a fixed order (Changes requested, Approved, Review requested, No reviews yet). A Topic heading has the Topic's sticker (No Topic an empty one), an author heading the avatar and login. A row leaves out what its heading says: its repository shows unless grouped by repository, the author hides in an author group, the Topics hide under one Topic or No Topic. |
| Motion | Short transitions (120 and 220 ms) that drop to zero with reduced motion. |
| Scrollbars (D73) | Every scroller, the page included, has a 10 px bar with no track, so the surface shows through, and a 6 px rounded thumb in fog `#5b6681`, brighter (`#8792ae`) under the pointer. Only where the main pointer is a mouse or trackpad; touch screens keep their own overlay scrollbars. Chromium and Safari draw it with `::-webkit-scrollbar`; Firefox gets the thin standard bar in the same colors, without the hover change. |
| Cards (D68) | Every section is a card with a tinted head band; every item is a card shaped like its object. See "Card language" below. |

### Card language (D68, D69)

Direction D, "Band + Object", picked by the orchestrator while the owner was away (D68). Source: `webApp/src/jsMain/resources/css/cards.css` and `Cards.kt`.

- **Section card:** every section and block (Topic sections, Today's sections, Pull requests' groups, a detail's parts, Trash's preview) is a card. Its head band is tinted in the kind's accent and carries the kind's mark, the title and the count; Notes, Jira and Topics add a pattern Thread draws in the accent, fading toward the actions.
- **Item card:** every Task, Note, Jira item, pull request, Topic and reminder is a card shaped like its object, in lists, Today, Inbox, Notes, Jira, Pull requests, Trash and ⌘K results.

| Kind | Accent | Mark and shape | Band pattern |
| --- | --- | --- | --- |
| Task | Neutral fog `#c3cce0` | Its checkbox on a plain card | None |
| Note | Paper yellow `#e8c46a` | A page with a folded corner | Dot grid |
| Jira item | Jira blue family `#6aa8ff` | A ticket with a stub and a perforated edge | A tear line of dots under the band, a notch at each end |
| Pull request | GitHub violet family `#b48cff` | A commit on a rail; cards in one list share the rail | None |
| Topic | The Topic's own color (D72) | The hex sticker; a colored top edge | Hexagons |
| Reminder | Fog `#ced5e6` | The time leads, like a clock face | None |
| Meeting (D78) | Calendar teal `#5cc8b8` | A calendar mark; the time leads and a teal left edge marks the row | A calendar's grid |

- Marks are Thread-drawn, in the provider colors. No official Jira or GitHub logos (Q5).
- Each accent is at least 6.7:1 on the surface color. Kind never depends on color alone: each has its own mark, shape and label.
- A suggestion is the same card with a dashed edge and a quieter title. The "Suggested N" heading still folds them away (D65).
- A Jira card's stub holds the issue type icon (D63) in place of the Jira mark, so a ticket shows one mark. The stub, the tear line and the blue still say Jira, and the stub keeps the Jira mark when the type is not known. The type shows on hover, and screen readers hear it in the card's link. A Topic's tickets under a linked parent (D64) are cards in the nested lists, one card gap apart.
- ⌘K results are small cards of their kind. Settings' Jira and GitHub connections use the kinds' marks and accents.
- Meetings in ⌘K (D78): a "Meetings" group after Notes, matched by title or place over the meetings on this device (yesterday to 14 days ahead), never by the join link. A meeting with an alternate title (D100) is matched by it and by the calendar's title, and shows the alternate. A row has the title, then when ("Today, 14:00", "Tomorrow, all day") and the linked Topics. Upcoming and running first, then ended ones; three per series and six in all. Click or Enter opens the Meeting card.
- Settings, "Calendar from your Mac" (D78): the state (up to date, waiting for the first upload, not updated since a time, no access, no calendar, paused, not set up, or update Thread), the calendar's name, the last upload and the app version. No buttons: syncing starts, pauses and stops in the Mac app, and the block points to `macCompanion/README.md`.
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

