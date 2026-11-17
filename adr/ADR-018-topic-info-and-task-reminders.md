# ADR-018: Topic Additional information, Today sections and Task reminders

Status: Proposed (2026-11-14). Built in three parts, each its own commit: A (Additional information, D52), D (Today sections, D53) and B (reminders on Tasks, D54). The owner asked for all three and approved the plan and its defaults with "all approved. I am afk. Continue without my approvals please. Everything is approved from me" (relayed by the orchestrator session). The plan is `topic-details-plan.md` in the session notes. The details below are the worker's choices and are queued for review (owner-review-queue.md, Q86 onwards). Checked with unit tests, emulator rules tests and headless Chrome on the emulators, with SYNTHETIC data only.

This file holds parts A, D and B, in the order they were built.

## A. "Additional information" in place of "Pick up here" (D52)

The owner's words: "When opening topic I think we don't need "pick up here" block. I think instead it would be better to have additional information block. Same UI as it is for Pick up here but just one text box for additional information".

### Context

- A Topic had two short context fields, `leftOff` and `nextStep` (each up to 10,000 characters, ADR-009). Topic Details showed them as "Pick up here", two text boxes.
- `nextStep` also showed on Topics list rows, Board cards and Today's Active Topics ("Next: ..."), and search matched it. `leftOff` got "Created from KEY." when a Topic was made from a ticket or PR.
- The rules require both fields on every Topic document (`hasAll`), and older builds read them.

### Decision

**One optional field.** A Topic gets `info: String?`, the Additional information.

| `info` | Meaning | The box shows |
|---|---|---|
| absent or null | never saved | the old text, combined (below) |
| `""` | saved empty | nothing; it does not fall back |
| any text | saved | that text |

`topicInfo(topic)` (`TopicRules.kt`) returns `info` when it is not null. Otherwise it returns the old fields, non-empty parts only, joined by a blank line:

```
Where I left off: <leftOff>

Next step: <nextStep>
```

- **No migration.** Nothing is written until the owner types. The old text shows at once, and the first edit saves the whole box into `info`. There is no job to run twice.
- `editInfo(topic, text)` trims the text, refuses more than `TOPIC_INFO_MAX_LENGTH` (20,100) characters, and is Unchanged when the text equals `topicInfo(topic)`. A blur without typing therefore writes nothing, and the old text is not frozen into `info`.
- The limit is 20,100 so the two old fields at their limit, with their labels (31 characters), always fit.
- Undo restores `info` to its value before, null for a Topic never saved, so the old text shows again.
- **The old fields stay.** This build never changes `leftOff` and `nextStep`. A new Topic still writes them as `""`, because the rules require them and older builds read them. An older build keeps showing and editing them.
- "Created from KEY." now goes into `info`.

### Writes and rules

- Firestore: `info` is written only when it differs from the value before (field diffs, ADR-009). A new Topic writes it only when set (Created from KEY.). Undo writes `info: null`. Reads: a missing or null `info` is null.
- The outbox JSON (`WorkspaceWriteJson`) carries `info`. A change saved by an older build has no `info` and decodes as null.
- `schemaVersion` stays 1.

`validTopic` in firebase/firestore.rules:

```
-      return topic.keys().hasOnly(['name', 'color', 'statusId', 'goal', 'leftOff', 'nextStep', 'createdAt', 'updatedAt', 'deletedAt',
-          'schemaVersion'])
+      return topic.keys().hasOnly(['name', 'color', 'statusId', 'goal', 'leftOff', 'nextStep', 'info', 'createdAt', 'updatedAt',
+          'deletedAt', 'schemaVersion'])
...
         && validText(topic.goal) && validText(topic.leftOff) && validText(topic.nextStep)
+        && (topic.get('info', null) == null || (topic.info is string && topic.info.size() <= 20100))
```

- `hasAll` is unchanged, so every stored Topic stays valid.
- `info` may be null, unlike the plan (`!('info' in topic) || ...`): Undo of the first edit must write null, because a merge cannot remove a field without a delete sentinel, and a null keeps the write a plain merge.

### UI

- Topic Details: the same `pickup` section with the Topic color, titled **Additional information**. One text box, 4 rows, labelled by the heading (`aria-labelledby`), placeholder "Context, links, decisions: anything worth keeping with this Topic". It saves as "Pick up here" did: on blur, a second after typing stops, and when the page leaves it. The section is one column at every width.
- Topics list rows, Board cards and Today's Active Topics show **"Next: <first open Task>"**: the Topic's oldest open Task, not in Trash, the first one Topic Details lists (`firstOpenTasks`). Without one, a list row shows "Finished" for a Done-category status, else "No open tasks"; a Board card shows nothing; an Active Topics card shows "No open tasks." The old `nextStep` text would freeze once nobody can edit it.
- Refined by D81 (2026-11-17): without an open Task, all of these show the Topic's Additional information (`topicInfo`, so the old text until it is edited), else its goal, as one line of plain text with no label, before the empty text above (`topicLine`).
- Search matches the Topic name, goal and `topicInfo(topic)`. Saved `info` hides the old text it replaced.
- The sync panel's refused-change text shows an "Additional information" line.
- Suggestions (ADR-017, S1) read `topicInfo(topic)` in place of the two old fields. The reason reads "Mentioned in Additional information".

### Consequences

- **The rules must be deployed before this build ships.** Until then the real project refuses every write that names `info`: an edit of the box, its Undo, and a Topic created from a ticket or PR. Those changes stay in the sync panel with Retry, Copy and Discard (Phase 12), so nothing is lost.
- An older build (an old tab, or the offline shell before it updates) that edits "Where I left off" or "Next step" after `info` exists writes text this build does not show. The text stays in the document. The window is short because the shell updates on reload.
- The Goal stays in the header, unchanged.
- The v3 prototype (D33) still shows "Pick up here". It is not edited; D52 records the difference.

### Evidence

- Domain unit tests: `WorkspaceEditsTest.additionalInformationIsTrimmedAndOnlyAnEditStoresIt`, `TopicRulesTest.additionalInformationFallsBackToTheOldPickUpText`, `TaskRulesTest.theFirstOpenTaskOfEachTopicIsTheOldestOpenOne`, `SearchTest` (info matches, replaced text does not), `TopicSuggestionsTest` (the Info field), `ExternalLinksTest` (Created from).
- Data tests: `WorkspaceWriteJsonTest` (info round trip, Undo to null, a change without `info` from an older build), `FirestoreWorkspaceRulesEmulatorTest.aTopicMayCarryAdditionalInformationAndNothingElse` (the old shape, text, null, empty, 20,100 and a new Topic accepted; 20,101, a number, a bool, an unknown field and another account refused), `FirestoreWorkspaceEmulatorTest` (an offline edit merges with another device's status move, writes only `info`, and its Undo writes null), `FirestoreTrashEmulatorTest` (a stale `info` edit cannot bring back a Topic deleted forever).
- webApp test: `SuggestionReasonTextTest`.
- Headless Chrome on the emulator build, a scratch profile and SYNTHETIC data (see the commit message for the list).

### Not verified

- The real Firebase project: the rules were deployed before `b8cfeb6` was pushed (2026-11-14), but the checks above ran on the emulators only.
- Safari and Firefox. Only headless Chrome ran the browser checks.

## D. Today: Upcoming and No date (D53)

The owner's words: "in today view it would be better another collapsible sections: upcoming tasks (which has time, but not today) and tasks which does not have a time at all".

### Context

- Today's main column had three groups (D37): Overdue, Today and Earlier planned. `todayGroupOf` put each Task in the first one that fits, so it showed once. Every other Task did not show in Today.
- The groups were plain headings and could not close.

### Decision

**Two more groups, checked last.** `todayGroupOf` (`TaskRules.kt`) checks in this order, and the first match wins:

| Group | An open Task that |
|---|---|
| Overdue | has a deadline that passed |
| Today | is planned for today or due today |
| Earlier planned | was planned before today |
| **Upcoming** (new) | has a planned day or deadline, all after today |
| **No date** (new) | has neither, and is not waiting in Inbox |

- A Task waiting in Inbox with no dates has no group: Inbox lists it (D38). One in Inbox with a date is in Upcoming.
- `firstDayOf(task)` is the earlier of the planned day and the deadline day.
- Upcoming is sorted by first day, then a deadline with a time on that day by its time, then creation. No date is newest first.
- Completed Tasks are unchanged: with "Keep completed tasks in Today" on, a Task completed today stays under Today, or Earlier planned when it was planned before today, until tomorrow. Upcoming and No date hold open Tasks only.
- `TodayTasks.openCount`, the page header's "N open tasks", still counts Overdue, Today and Earlier planned only.
- Since part B (D54), the day of a Task's next reminder counts as a date too. See B.

### UI

- Upcoming lists its Tasks under small day headings (`h3`): "Tomorrow", then "Mon 17 Nov". Each row keeps its "Planned" and "Due" lines, so it says which date it is.
- Upcoming and No date show 10 rows, then "Show all (N)", which becomes "Show fewer". An empty group is not shown; Today keeps "Nothing planned for today yet." (Until D86: since then an empty Today group is not shown either.)
- **Every group opens and closes.** The heading is a button with a chevron, `aria-expanded` and `aria-controls`, and the body is `hidden` and not drawn when closed, like a Topic Details section. The open count stays in the heading. Overdue, Today and Earlier planned start open; Upcoming and No date start closed.
- **Remembered per device, not synced.** `thread.today.open.<group>` in `localStorage` holds `1` or `0`. Nothing stored, a value it does not know, or blocked storage falls back to the default (`readDeviceText`, which already catches storage errors).
- The Task sheet's Today line names the new groups: "Shows in Today under Upcoming", "under No date", or "Not in Today while it waits in Inbox".

### Consequences

- No data, outbox or rules change. Nothing to deploy.
- A Task with only a later date, or none, now shows in Today. The Inbox rule (D38) is unchanged.
- The v3 prototype (D33) has three groups that do not close. It is not edited; D53 records the difference.

### Evidence

- Domain unit tests: `TaskRulesTest.d53UpcomingAndNoDateHoldTheOpenTasksTheOtherGroupsLeave` (the five groups, each Task once, Inbox left out, completed Tasks, the header count, a Task moving to Today on its day), `upcomingSortsByFirstDayThenTimeThenCreation`, `noDateListsNewestFirst`, and `todayGroupsFollowD37` (the new groups).
- webApp test: `TodayGroupStateTest` (defaults, the stored choice per group, an unknown value).
- Headless Chrome on the emulator build, a scratch profile and SYNTHETIC data (see the commit message for the list).

### Not verified

- Safari and Firefox. Only headless Chrome ran the browser checks.

## B. Reminders on Tasks (D54)

The owner's words: "also I think tasks and reminders can be merged. Task can have a reminder option as well". This changes D14, which the owner approved explicitly with the plan.

### Context

- A reminder was its own document with one target: a Task, a Note or a Topic (D14, ADR-010). Topic Details had a Reminders section with "+ Reminder" for the Topic, listing the Topic's, its Tasks' and its Notes' reminders.
- The Task sheet already had a reminder field, and a Task could have several reminders.
- The rules let an update change `targetKind` and `targetId`. A Task create needs `createdAt == request.time`, and an update keeps `createdAt`.

### Decision

**Reminders belong to a Task or a Note (OB1).** The documents stay as they are; "merged" is what the owner sees:

- Task rows (Topic, Today, Inbox) show a bell with the next reminder that is not dismissed, plus "+N" when there are more. The Task sheet field is "Remind me". Done is the Task checkbox.
- Topic Details has no Reminders section and no "+ Reminder". It lists "Topic reminders" only while the Topic still has some that are not dismissed: ones not moved yet, or kept by Undo. That list has no Add.
- Note reminders are unchanged (OB2): the Note, Today and banners show them, Topic Details no longer does.
- No snooze and no repeat (OB4). A reminder on a completed Task shows no banner (OB5): `dueReminders(..., doneTaskIds)`.
- **A reminder day is a date in Today (OD1).** `taskReminderTimes` gives each Task its earliest reminder that is not dismissed and not in Trash. `todayGroupOf(task, now, remindAt)` counts its day like a planned day: today puts the Task under Today, and so does one already due and not dismissed; a later day puts it under Upcoming. An Inbox Task with a reminder shows. Upcoming sorts a reminder time on the first day like a deadline time. The Today side column is unchanged (OB6).
- The plan and the deadline stay separate from the reminder (D13).

**Topic reminders move onto Tasks.** `topicReminderConversions` and `TopicReminderConverter` (`ReminderConversion.kt`, domain):

| Step | What happens |
|---|---|
| Which | Every reminder with target `topic` that is not dismissed, not in Trash, and whose Topic is stored (also a Topic in Trash). Dismissed ones stay as history; one in Trash moves once it is restored. |
| Task | `k-from-<reminder id>`: in that Topic, titled with the reminder text, open, no dates, not in Inbox. Stored already: only the reminder moves. Stored and discarded: the move was undone, so the reminder stays. |
| Writes | One batch per reminder: create the Task (when new) and change the reminder's `targetKind` and `targetId` only. A refusal affects that reminder only. |
| When | On each workspace snapshot, only with the server's data (every listener's `fromCache` false, the new `WorkspaceData.fromServer`), online, and nothing of this device waiting to sync. One run at a time. |
| Backup | Before any write, one record goes to this account's IndexedDB database `thread-backup-<uid>`, store `conversions`, written durably: `{recordVersion: 1, kind: "topic-reminders", savedAt, reminders, writes, undo}` (`KeyValueConversionBackup`). If it fails, nothing is written and the next snapshot tries again. After the backup the data is read again, and only conversions that are in the backup and still needed are written. Thread never deletes these records, and they are never synced. |
| Toast | "Moved N Topic reminders into Tasks" ("Moved 1 Topic reminder into a Task") with Undo (OB7). |
| Undo | One batch: each reminder goes back to its Topic exactly as it was, and each Task is discarded (deleted and hidden everywhere, Trash included). A discarded `k-from-` Task tells every device to leave that reminder on its Topic. |

- **Idempotent.** The Task id comes from the reminder id. A second run, another device or a reload finds nothing to do. Both writes are one batch, so a run is never cut between them. If a `k-from-` Task is ever stored while its reminder is still on the Topic, only the move is written.
- **Two devices at once.** Both write the same Task id. The server takes one batch; the other is refused whole, because its create is now an update with a different `createdAt`. Each converter remembers what it wrote, and the refusal is dropped without showing once the server data shows the Task stored or the reminder moved (`isSettledConversion`). Any other refusal stays in the sync panel, and that reminder is not tried again while it is listed.
- **Older builds.** A moved reminder reads as a Task reminder, which older builds already show. A Topic reminder an older build adds later moves on the next load of this build. Field-level merges keep an older build's dismiss. No rules change.
- **D42.** A converted Task belongs to its Topic, so it goes to Trash and comes back with the Topic, and Delete forever takes it with its reminders.

### UI

- Toast with Undo after a run that moved something.
- Task rows: `meta--rem` with the bell and time ("15:00" today, else the day and time), "+N" and a `title` such as "Next of 3 reminders".
- Task sheet: "Remind me"; the Today line says "because it has a reminder today", and the No date and Inbox lines mention adding a reminder.
- Today's group hints: "Planned, due or reminded today", "Planned, due or reminded after today", "No planned day, deadline or reminder".
- Today's empty side column: "No reminders today. Add one from a Task or Note."

### Consequences

- No rules change, nothing to deploy beyond the web build. The first open of this build on an account with open Topic reminders writes to the owner's data, once.
- A new IndexedDB database per account on each device that converts.
- `remindersForTopic` is gone; nothing used it after the Topic section changed.
- When two devices convert at once, both may show the toast. Undo on either one puts the reminders back for all devices.
- A converted Task is titled with the reminder text, so its banner shows the same words twice ("Reminder for task" and the title).
- The v3 prototype (D33) still shows a Topic Reminders section. It is not edited; D54 records the difference.

### Evidence

- Domain unit tests: `ReminderConversionTest` (13 tests: which reminders count, the writes, a second run, a cut run, Undo and later runs, settled refusals, the gate, backup first and one batch per reminder, a failed backup, re-reading after the backup, unsettled refusals, one Undo batch), `TaskRulesTest.d54AReminderDayCountsAsADateInToday` and `d54UpcomingSortsByAReminderTimeOnTheFirstDay`, `ReminderTest.d54ADoneTasksReminderShowsNoBanner` and `d54EachTaskGetsItsNextOpenReminder`.
- Data test: `KeyValueConversionBackupTest` (the record and its way back, nothing to save, a storage failure).
- Emulator tests, `FirestoreReminderConversionEmulatorTest`, with the current rules: two devices converting at once make one Task per reminder and drop their refusals; a second run and another device find nothing; offline nothing converts and back online it does; a device offline during the conversion adds nothing when it returns; Undo puts each reminder back and no device converts it again; a Topic reminder written later the old way is converted; the backup is stored before any write and a failed one writes nothing.
- webApp test: `ReminderTextTest` (the Task sheet's Today line, the toast).
- Headless Chrome on the emulator build, a scratch profile and SYNTHETIC data, with Topic reminders seeded in the old shape: conversion, Undo and a reload after it, the backup record and its timing, Today, banners, Trash and Restore, two browsers at once and Undo after the race, 390 px (see the commit message for the list).
- The build before part B (5cc0447, built from `git archive`) on the same emulator data: it shows the converted Tasks, their reminders and banners with no console errors, and a Topic reminder it adds while this build is offline is converted once this build is back online.

### Not verified

- Safari and Firefox. Only headless Chrome ran the browser checks.
- Builds older than 5cc0447 against converted data. They share its reminder read path, so this is an inference from the code, not a run.
- The real Firebase project. Nothing in this part needs a deploy besides the web build.
