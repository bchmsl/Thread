# ADR-018: Topic Additional information, Today sections and Task reminders

Status: Proposed (2026-11-14). Built in three parts, each its own commit: A (Additional information, D52), D (Today sections, D53) and B (reminders on Tasks, D54). The owner asked for all three and approved the plan and its defaults with "all approved. I am afk. Continue without my approvals please. Everything is approved from me" (relayed by the orchestrator session). The plan is `topic-details-plan.md` in the session notes. The details below are the worker's choices and are queued for review (owner-review-queue.md, Q86 onwards). Checked with unit tests, emulator rules tests and headless Chrome on the emulators, with SYNTHETIC data only.

This file holds part A. Parts D and B are added when they are built.

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

- The real Firebase project: the rules are not deployed.
- Safari and Firefox. Only headless Chrome ran the browser checks.
