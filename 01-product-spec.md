# Product specification

Status: confirmed product baseline unless explicitly labeled otherwise.

## Product goal

A fast personal work environment, not a replacement for the team's Jira or GitHub.
A Topic represents one meaningful, finishable objective. It can span several external tickets and pull requests.

Example:
- Topic: Implement QR payments.
- Jira tickets: Build QR scanner; call backend; handle response.
- Personal Tasks: Clarify error codes tomorrow; test timeout handling today.
- Notes: Technical decisions, meeting notes, open questions.
- PRs: Implementation and follow-up fixes.
- Reminders: Follow up on the backend answer.
- Future: Associated Claude Code sessions.

A parallel architectural task is a separate Topic.

## Topic

- Name, optional goal/description, editable status.
- Additional information: one free text box for context, links and decisions (D52). It replaced "Where I left off" and "Next step"; a Topic saved before shows that older text in the box until it is edited.
- Associated Tasks, Notes, Jira tickets and PRs. Reminders sit on its Tasks (D54).
- A Topic can exist with only a name; do not force a lengthy creation form.
- Completion is independent of Jira completion.
- A completed Topic can be reopened.
- No nested Topics or workflow builder in v1.

### Editable workspace-wide statuses

Seed statuses: Planned, Active, Paused, Done.
Users can add, rename, reorder, and remove statuses.
Each status belongs to a stable semantic category:

| Category | Visual family | Example names |
| --- | --- | --- |
| Planned | Gray | Backlog, Planned |
| Active | Blue | In Progress, In Review |
| Paused | Orange | Blocked, Paused |
| Done | Green | Done, Cancelled |

Categories determine behavior; never infer behavior from display names or colors.
Statuses are shared across all Topics, not configured per Topic.
Deleting a used status requires choosing a replacement.
Category colors are the agreed baseline; arbitrary color overrides are not required for v1.

## Tasks

- Personal Task can be standalone or associated with one Topic.
- Simple incomplete/complete checkbox; no configurable Task status workflow in v1.
- Separate planned day, due date/time, and optional Reminder.
- Planned day means intended work day, not a deadline.
- Appearing in Today and Topic Details must reference the same Task.
- A completed personal Task does not change a Jira ticket.
- A checklist inside Note rich text is not automatically a Task.
- A checklist item needing Today scheduling or Reminders should become a separate Task; exact conversion interaction remains open.

## Notes

- Visual rich-text editing while typing, inspired by Apple Notes.
- Autosave; no Markdown source + preview as the primary experience.
- Initial formatting: headings, bold, italic, lists, checkboxes, links.
- Standalone Notes and Notes linked to multiple Topics are supported.
- One Note, many links; no duplicate content copies per Topic.
- Work Notes may be stored in Firebase, as confirmed by the owner.
- Tables, attachments, collaborative editing, and a full Notion-like block system are not confirmed v1 requirements.
- In a conflict, the version you do not keep goes to Trash. "Keep both" saves it as a second Note with the same Topics (D41).

## Relationships

- One Jira ticket or PR may be linked to multiple Topics.
- One Note may be linked to multiple Topics or none.
- Personal Tasks can be standalone or belong to one Topic.
- Reminders attach to a Task or a Note (D54, superseding D14 in part). Reminders an older build put on a Topic move onto a Task of their own in that Topic, with Undo.
- One meeting series may be linked to multiple Topics (D78). One link covers every occurrence of a repeating meeting.
- Removing a link must not delete the linked object.
- Removing an Inbox entry means triaged, not deleted.

## Today

Include:
- Tasks selected for the current day.
- Overdue unfinished work.
- Reminders.
- A separate Review Requested PR section.

Avoid duplicating the same Task just because it is both planned and overdue.
Always show Topic context when relevant.

Task groups (D90): Settings > Today "Group tasks by" (synced) picks how the main column groups today's Tasks. Due date, the default, has four groups in this order: Completed (only while "Show completed tasks" is on), Today, Tomorrow and Upcoming. Topic has one group per Topic, the one with the most urgent open Task first and "No Topic" last. None is one list. All three show the same Tasks in the same order; only the headings change.
Reminders and Review requested sit in a side column.

Meetings (D78, ADR-020) head the side column once meetings are set up:
- Today's work meetings from Apple Calendar on the owner's Mac, all-day ones first, then by start. Ended ones stay, greyed; Settings > Today "Show past meetings" (D86, synced) can keep only the latest few in this card, or none.
- Each row shows the time, the title, Now or Next, "Tentative" or "Not answered", the place, the linked Topics, and Join for a Teams, Zoom, Google Meet or Webex link, or for the owner's own join link (D100).
- Declined and cancelled meetings are not shown.
- The card says when the Mac last uploaded. After an hour without an upload it asks whether the Mac is awake. "No meetings today." shows only after an upload that covered today.
- With Meetings checked under "Hide empty sections" in Settings > Today (D86), it is hidden while it has no meeting left to show today. A problem keeps it: no upload for over an hour, a problem on the Mac, meetings that cannot load, or Thread needing an update.
- Settings > Today, "Meetings layout" (D79, synced): Vertical, the default, is the card described here. Horizontal moves the same card across the page under the heading, above both columns, as a timeline of today's hours, 10:00 to 19:00 unless a meeting falls outside them: all-day meetings above, timed ones as compact blocks by start and end, overlaps in rows, a block too narrow to show the start of its title flat running that title along its diagonal, from the bottom-left corner to the top-right at the block's own angle (and growing taller when needed, D88), a meeting too narrow even for one such line as a flag on a thin line at its start, in a band above the blocks (D85), a line at now, and Join for the meeting now and the next one in the card's head. On wide screens Today's page is then wider than the other pages (up to 1600 px, D87), so the hours have more room and more titles fit; the Vertical layout keeps the usual width. Settings > Today "Narrow meeting titles" (D97, synced, right after "Meetings layout") picks how a block too narrow for the start of its title shows it: Diagonal, the default, as above; Vertical, the title turned upright and the block as tall as the line needs; Dots with flags, a flag as for the shortest meetings; or Increase height, the title flat and wrapped onto more lines only until its start shows, the block and its row taller. In every choice a title that fits stays flat and a meeting too narrow for one turned line is a flag.
- A row opens the Meeting card: when, where, your answer, Join, and its Topics with Link to Topic and Unlink. The calendar itself stays read-only.
- Alternate title and join link (D100, owner change #31): the Meeting card's Edit sets the owner's own title and join link for the meeting, both optional. When set, each wins over the calendar's wherever the meeting shows: Today's rows and timeline, Join, the status line, ⌘K, Topic Details and the Link picker. For a repeating meeting they apply to the whole series. The link must be a full https link; a Teams, Zoom, Google Meet or Webex link keeps its label ("Join (Zoom)"), any other shows as "Join". Clearing a field brings the calendar's value back. The card still shows the calendar's title and link as facts while an alternate is set. ⌘K finds the meeting by either title; join links are never searched. The alternates sync to every device; the calendar and the Mac app are not changed.
- The status line names the next meeting in the hour before it starts ("Standup in 12 min") and opens its card.

The due-date groups (D90; before it Overdue, Today, Earlier planned, Upcoming and No date, D37 and D53). A Task's first date is the earliest of its planned day, deadline and next reminder (D54):
- Completed: Tasks completed today, while "Show completed tasks" is on. It starts closed.
- Today: open Tasks that are overdue, planned, due or reminded today, or planned on an earlier day.
- Tomorrow: open Tasks whose first date is tomorrow.
- Upcoming: open Tasks with a later first date, by day, then the ones with no date. A Task waiting in Inbox without dates stays in Inbox only. It starts closed.

Days follow the workspace time zone. An empty group is not shown. Every group opens and closes from its heading, and each device remembers the choice.

With no task at all, the Tasks card follows its "Hide when empty" choice (D101, synced), in Settings > Today "Hide empty sections" and in edit mode. On, the default, it is not shown, as before D101. Off, it shows as one Tasks card that says "No tasks for today.".

Below the Tasks, Today shows the Topics whose status is in a category checked under "Topics shown" in Settings > Today (D89, synced): Active and Paused by default, and any mix of Planned, Active, Paused and Done. They are grouped by status in Settings order (D59). The section's title names the checked categories: "Active and paused Topics" by default, "Planned and done Topics", "All Topics" for all four. With none checked, Today leaves its Topics out. Topics also has a "Hide when empty" choice (D101, synced, off by default): on, the card is not shown while no Topic is in a checked category; off, it shows its empty state. A List / Board switch picks the layout; List is the default. The choice syncs and is also in Settings > Today (D84); until it is first changed, a device keeps the layout it showed before. On wide screens the Board, like the Topics Board, uses more width and then narrower lanes before it scrolls sideways (D66).

A Task added from Today without a Topic is planned for today and also waits in Inbox until it is filed (D38).

Layout and edit mode (owner change #29: D95, D96, D98, D99). Today's cards are Meetings, Tasks (every task group as one card), Reminders, Review requested and Topics. Each sits in one of four places: Top and Bottom run across the page, Left is the main column and Right the side column. Not saved, the layout is the one described above. Each card takes a form that fits its place (D96).
- "Edit layout" in Today's header, or in Settings > Today "Today layout", opens edit mode. Each card shows as a compact card in its place, with "Move to" (a place), Move up and Move down inside the place, and the hide choices Settings > Today has for it: "Hide when empty" for every card (Tasks and Topics since D101), and "Topics shown" for Topics too. It is another place for the same choices, not new ones.
- In edit mode a card can also be dragged by its name to any place, or to another position in its place, and takes that place's width; an empty place takes a drop. With a mouse or pen the drag starts when the pointer moves; on a touch screen the card's name is pressed and held first, so a swipe still scrolls the page (D-7). A drop is a change like "Move to": nothing is saved until Done. The Move menu and the up and down buttons stay, as the keyboard path and the fallback.
- A card Today does not draw now still shows in edit mode, with why: "Not shown now: empty", "Not shown: no Topics picked" or "Not shown: meetings not set up". Tasks with no task and its "Hide when empty" on says "Not shown now: empty" (D101; "Shows when there are tasks" before).
- Nothing is saved until Done, which saves only what changed in edit mode. Cancel or Esc drops the changes. Leaving Today while editing saves, like Done; a reload drops the changes. "Reset to default" puts the layout back to the one above (saved on Done).
- Phones and windows 759 px wide or narrower have their own order, one column (D-9). It follows the wide layout until it is changed there. Edit mode opened in such a window edits that order (First, Last, up and down), and its reset is "Match desktop". The window's width when edit mode opens decides which one it edits.

## Inbox

Quick-captured Tasks and Notes without a selected Topic arrive here.
A new Note made outside a Topic, for example from the Notes page, also arrives here. A Note made inside a Topic is linked to that Topic (D39).
They can later be linked to a Topic or kept standalone and marked triaged.
A standalone object is not necessarily an untriaged Inbox object.

Inbox preferences (D91, synced), in Settings > Inbox:
- Show completed tasks (on by default): a completed Task still waiting in Inbox shows struck through until it is filed. Off hides it.
- Group by (None by default): None is one list, newest first. Type is Tasks, then Notes. Due date is Today's groups (Completed, Today, Tomorrow and Upcoming, D90), then Notes. Empty groups hide. Completed holds every completed Task still in Inbox, not only today's.
- Show notes (on by default): off hides Inbox Notes here only. They stay in Inbox, untriaged, and show on the Notes page.

The Inbox count is what Inbox shows (Q8): hidden Notes and hidden completed Tasks are left out. Select all, the selection and triage act only on what shows. When only hidden items wait, Inbox says it is clear, says what is hidden and links to Settings > Inbox.

## Trash

- Tasks, Notes, Reminders and Topics can be moved to Trash.
- Move to Trash is instant, with Undo. There is no confirm dialog (D43).
- Restore puts an item back with its Topic links. A link to a Topic that no longer exists cannot come back.
- Delete forever asks first (D43).
- Jira tickets and pull requests never go to Trash. Unlinking them only removes the link.
- Trash retention is open.

### Deleting a Topic (D42)

- The Topic's Tasks and Reminders, including Reminders on those Tasks, go to Trash with it.
- Notes are never deleted with a Topic. Only the link is removed. A Note linked only to that Topic becomes standalone. It does not go to Inbox.
- Jira and PR links to the Topic are removed. The external records are not touched.
- Restore brings back the Topic, its Tasks and Reminders, and all its links to Notes, Jira tickets and PRs.

## Topics views

- Both List and Board in v1.
- Default List; remember the last selected view.
- List shows status, the next open Task (D52), and personal Task progress. A Topic with no open Task shows its Additional information instead, else its goal (D81); Board cards and Today's Topics do the same.
- Board columns represent editable statuses, not just the four categories.
- Drag a Topic to a column to change status.
- Also provide a non-drag status-change action.

## Topic Details

One page with collapsible sections, not a primary tab layout:
- Header: name, status, goal.
- Main: Additional information (D52), Tasks.
- Notes.
- Related Jira tickets and PRs. Task rows show their next reminder (D54); "Topic reminders" lists only Topic reminders not moved yet or kept by Undo. Linked Jira tickets nest under their linked Jira parent (D64).
- Tasks, Notes, Jira tickets and pull requests open as cards on top of Topic Details, which stays as it was (D70).
- Meetings (D78): one row per linked meeting series with its next occurrence, or "None in the next 14 days". Join when that occurrence is today, and Unlink. "Link" picks a series. Shown once meetings are set up on this device, or while the Topic has a link.
- Suggested Jira tickets and PRs (D49, ADR-017): under the linked rows of the Jira and Pull requests sections. Found by fixed rules, not AI: keys and PR links written in the Topic (name, goal, Additional information), its Tasks and Note previews; Jira issue links of linked tickets; Jira keys in linked PRs. Each row says why. Link is one click with Undo. "Don't suggest" hides the item in that Topic until it is restored from "Not suggested here"; Unlink does the same. Nothing is linked without a click. The "Suggested" heading folds the group away; each device remembers it per Topic (D65).

Topic Details preferences (D92, synced), in Settings > Topic Details. They apply on the Topic page and in the Topic card:
- Show completed tasks (on by default): completed Tasks show under "Completed", closed at first; each device remembers whether it is open, for every Topic. Off hides them; the "N open" count stays.
- Hide empty sections (none checked by default): Notes, Jira, Pull requests, Meetings and Additional information. A checked section shows only while it holds something. Jira and Pull requests also show for a suggestion (Q7). Tasks never hide. While Additional information is hidden, Edit Topic has a field for it; text saved there brings the section back.
- Group tasks by due date (off by default): its own switch. On, the Tasks show under Today's groups (Completed while shown, Today, Tomorrow and Upcoming, D90), with Completed first and holding every completed Task of the Topic. Empty groups hide. Off, the open Tasks come oldest first, then Completed, as before.

## Integrations in v1

- Jira page: assigned tickets, search for other accessible tickets, link to existing Topic or create Topic.
- Pull Requests page: My PRs and Review Requested, with CI and review state. Each list is grouped as Settings > Pull requests says (D93, synced): Mine by Repository (default), Topic or Review status; Review requested by Repository (default), Author or Review status. By Topic, a pull request linked to one Topic shows under it, one linked to two or more shows once under "Several Topics", and one linked to none under "No Topic" (Q5). Review status comes from the copy this device saved when the pull request was opened; the rest show under "Not loaded yet", and grouping asks GitHub for nothing (Q4). Each row shows its GitHub assignees (avatar and name), with "You" marked when the owner is one (D62).
- Show external details even if not linked to a Topic.
- Read-only: opening originals is supported.
- Posting comments, changing status, merging, and other external mutations are deferred.
- Calendar (D78, ADR-020): the owner's work calendar (Outlook, synced into Apple Calendar on the Mac). The "Thread Calendar" menu bar app reads one calendar and uploads its meetings to Firebase, so every device shows them, also while the Mac sleeps, as of its last upload. Read-only: neither app changes a meeting. Window: yesterday to 14 days ahead. Attendees, organizer and meeting notes are not uploaded. Microsoft Graph is not used: it needs admin consent.
- Calendar page (D102, owner change #31): a seventh view after Pull requests. It lists the uploaded meetings from yesterday to the last day the Mac uploaded, by day in week cards, with a two-week overview to jump to a day (a strip of day chips on narrow screens), and opens at a day with `?day=`. Read-only, from the same meetings as Today; rows open the Meeting card, and Join shows on today's rows only. Today's Meetings card and the Meeting card link to it. It adds no data, rules, Mac app change or setting; a longer range and a Day view are not planned.

## Search and quick capture

- Cmd+K global search: Topics, Tasks, Notes, downloaded Jira/PR information, and meetings from yesterday to 14 days ahead by title or place (D78). Picking a meeting opens its card.
- Create a Task, Note or Topic from the capture field in the status line (D35). It replaces the earlier plus button.
- Inside a Topic, creation defaults to that Topic.
- Elsewhere, Task/Note defaults to Inbox with optional Topic selection.
- Global local search does not promise a search of all remote Jira/GitHub content.
- Jira's explicit remote search is a separate operation.

## Pomodoro

- Compact menu control, not a large dashboard widget.
- Default 25-minute work / 5-minute break; editable durations.
- Attach to Task or Topic, or run standalone.
- Start, pause, stop, and remaining time.
- Continue across navigation; save completed sessions to history.
- No dependency on closed-app notification delivery in v1.
- Decided 2026-11-16 (D56): the timer keeps running across a reload or a closed tab, and a period that ended meanwhile counts at its real end time. Each device has its own timer; the durations and the history sync. The panel shows the last 4 sessions; all are kept. A focus period stopped after 1 minute or more is saved as stopped.

## Acceptance examples

1. QR Topic can link three Jira tickets and two PRs without duplicating external records.
2. A Note linked to two Topics reflects the same edit from either Topic.
3. A Task planned for today appears in Today and its Topic as the same object.
4. A Topic can be Done while a linked Jira ticket remains open.
5. Renaming Active to Building does not change semantic behavior.
6. A triaged standalone Note disappears from Inbox but remains searchable.
7. Offline edits to downloaded Notes survive a reload.
8. Cached Jira details appear before a background refresh completes.
9. Sync failure never silently discards user writing.
10. Pomodoro does not occupy a large Today or Topic card.

## Deferred

Native iPhone app; Compact density; AI usage; optional Claude session linking/control; closed-app notifications; Jira/GitHub write actions; automated standup drafts and advanced automations.

