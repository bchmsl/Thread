package thread.domain.trash

import thread.domain.note.NoteId
import thread.domain.note.NoteSummary
import thread.domain.note.NoteTopicLink
import thread.domain.reminder.Reminder
import thread.domain.reminder.ReminderId
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.time.DEFAULT_WORKSPACE_ZONE
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.StatusCategory
import thread.domain.topic.StatusId
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import thread.domain.topic.TopicStatus
import thread.domain.workspace.EditResult
import thread.domain.workspace.TrashKind
import thread.domain.workspace.WorkspaceData
import thread.domain.workspace.WorkspaceEdit
import thread.domain.workspace.WorkspaceWrite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Every Topic, Task, Note and Reminder here is SYNTHETIC test data.
class TrashTest {
    private val planned = SEED_STATUSES[0].id
    private val alpha = Topic(TopicId("t-alpha"), "Synthetic Alpha", TopicColor.Green, planned, goal = "Synthetic goal")
    private val beta = Topic(TopicId("t-beta"), "Synthetic Beta", TopicColor.Blue, planned)
    private val alphaTask = Task(TaskId("k-a"), "Synthetic Alpha task", topicId = alpha.id)
    private val alphaDone =
        Task(TaskId("k-d"), "Synthetic done task", topicId = alpha.id, done = true, completedOn = LocalDay(2026, 11, 12))
    private val alphaTrashed = Task(TaskId("k-t"), "Synthetic task trashed alone", topicId = alpha.id, deleted = true, deletedAtMillis = 5)
    private val loose = Task(TaskId("k-l"), "Synthetic standalone task")
    private val at = WallTime(LocalDay(2026, 11, 14), 9 * 60)
    private val onTopic = reminder("r-topic", TargetKind.Topic, alpha.id.value)
    private val onTask = reminder("r-task", TargetKind.Task, alphaTask.id.value)
    private val onLoose = reminder("r-loose", TargetKind.Task, loose.id.value)
    private val onNote = reminder("r-note", TargetKind.Note, "n-shared")
    private val shared = NoteSummary(NoteId("n-shared"), title = "Synthetic shared note")
    private val links =
        listOf(
            NoteTopicLink(alpha.id, shared.id),
            NoteTopicLink(beta.id, shared.id),
        )

    private fun reminder(
        id: String,
        kind: TargetKind,
        target: String,
    ) = Reminder(ReminderId(id), at, DEFAULT_WORKSPACE_ZONE, "Synthetic reminder $id", ReminderTarget(kind, target))

    private fun data(vararg topics: Topic) =
        WorkspaceData(
            statuses = SEED_STATUSES,
            topics = topics.toList(),
            tasks = listOf(alphaTask, alphaDone, alphaTrashed, loose),
            reminders = listOf(onTopic, onTask, onLoose, onNote),
            notes = listOf(shared),
            noteLinks = links,
        )

    private fun changed(result: EditResult): WorkspaceEdit = assertIs<EditResult.Changed>(result).edit

    private fun topicIn(edit: WorkspaceEdit) = assertIs<WorkspaceWrite.PutTopic>(edit.writes.single()).topic

    @Test
    fun aTopicGoesToTrashWithOneWriteAndUndoTakesItBack() {
        val edit = changed(trashTopic(alpha))
        val write = assertIs<WorkspaceWrite.PutTopic>(edit.writes.single())
        assertTrue(write.topic.deleted)
        assertEquals(alpha, write.before)
        assertEquals(alpha, assertIs<WorkspaceWrite.PutTopic>(edit.undo.single()).topic)
        assertEquals(EditResult.Unchanged, trashTopic(write.topic))
    }

    @Test
    fun aTopicInTrashHidesItsTasksRemindersAndNoteLinksButNotItsNotes() {
        val visible = visibleWorkspace(data(alpha.copy(deleted = true), beta))
        assertEquals(listOf(beta), visible.topics)
        assertEquals(listOf(loose), visible.tasks, "Alpha's tasks, also the one trashed alone, are hidden with it")
        assertEquals(listOf(onLoose, onNote), visible.reminders)
        assertEquals(listOf(NoteTopicLink(beta.id, shared.id)), visible.noteLinks)
        assertEquals(listOf(shared), visible.notes, "Notes stay in Notes")
    }

    @Test
    fun withoutATopicInTrashTheWorkspaceIsUnchanged() {
        val stored = data(alpha, beta)
        assertEquals(stored, visibleWorkspace(stored))
    }

    @Test
    fun restoringBringsEverythingBackBecauseNothingElseWasWritten() {
        val trashed = alpha.copy(deleted = true, deletedAtMillis = 10)
        val restored = topicIn(changed(restoreTopic(trashed, SEED_STATUSES)))
        assertEquals(alpha, restored)
        assertEquals(data(alpha, beta), visibleWorkspace(data(restored, beta)))
    }

    @Test
    fun aTopicWhoseStatusWasDeletedComesBackInAStatusOfTheSameCategory() {
        val custom = TopicStatus(StatusId("s-review"), "Synthetic review", StatusCategory.Active, 4, deleted = true)
        val stored = SEED_STATUSES + custom
        val trashed = alpha.copy(statusId = custom.id, deleted = true)
        assertEquals(StatusId("s-active"), topicIn(changed(restoreTopic(trashed, stored))).statusId)
        val unknown = alpha.copy(statusId = StatusId("s-unknown"), deleted = true)
        assertEquals(planned, topicIn(changed(restoreTopic(unknown, stored))).statusId, "no category known: the first status")
    }

    @Test
    fun trashListsTopicsWithWhatWentWithThemAndSeparateEntries() {
        val trashedAlpha = alpha.copy(deleted = true, deletedAtMillis = 20)
        val trashedNote = NoteSummary(NoteId("n-gone"), inTrash = true, deletedAtMillis = 30, fromConflict = true)
        val trashedReminder = onLoose.copy(deleted = true, deletedAtMillis = 1)
        val discarded = Task(TaskId("k-x"), "Synthetic undone capture", deleted = true, discarded = true)
        val stored =
            data(trashedAlpha, beta).copy(
                tasks = data().tasks + discarded,
                notes = listOf(shared, trashedNote),
                reminders = listOf(onTopic, onTask, trashedReminder, onNote),
            )
        val entries = trashEntries(stored)
        assertEquals(listOf("n-gone", "t-alpha", "k-t", "r-loose"), entries.map { it.id }, "newest deletion first, no discarded item")
        val topic = assertIs<TrashEntry.TopicEntry>(entries[1])
        assertEquals(listOf(alphaTask, alphaDone), topic.tasks, "the Task trashed alone is its own entry")
        assertEquals(listOf(onTopic, onTask), topic.reminders)
        assertEquals(listOf(shared), topic.notes)
        val task = assertIs<TrashEntry.TaskEntry>(entries[2])
        assertEquals(TopicState.InTrash, task.topic?.state)
        val note = assertIs<TrashEntry.NoteEntry>(entries[0])
        assertEquals("Untitled note", note.title)
        assertTrue(note.note.fromConflict)
        val reminder = assertIs<TrashEntry.ReminderEntry>(entries[3])
        assertEquals(ReminderFor(TargetKind.Task, loose.title, inTrash = false), reminder.target)
    }

    @Test
    fun aTaskWhoseTopicIsInTrashOrGoneIsRestoredStandalone() {
        val inTrash = data(alpha.copy(deleted = true), beta)
        assertNull(assertIs<WorkspaceWrite.PutTask>(changed(restoreTask(alphaTrashed, inTrash)).writes.single()).task.topicId)
        val gone = data(beta)
        assertNull(assertIs<WorkspaceWrite.PutTask>(changed(restoreTask(alphaTrashed, gone)).writes.single()).task.topicId)
        val live = assertIs<WorkspaceWrite.PutTask>(changed(restoreTask(alphaTrashed, data(alpha, beta))).writes.single()).task
        assertEquals(alpha.id, live.topicId)
        assertEquals(false, live.deleted)
    }

    @Test
    fun aRestoredReminderIsNoLongerDeleted() {
        val restored = assertIs<WorkspaceWrite.PutReminder>(changed(restoreReminder(onLoose.copy(deleted = true))).writes.single())
        assertEquals(false, restored.reminder.deleted)
        assertEquals(EditResult.Unchanged, restoreReminder(onLoose))
    }

    @Test
    fun deletingATopicForeverTakesWhatWentWithItAndLeavesNotesAlone() {
        val stored = data(alpha.copy(deleted = true), beta)
        val topic = trashEntries(stored).filterIsInstance<TrashEntry.TopicEntry>().single()
        val writes = deleteForeverWrites(topic, stored).map { assertIs<WorkspaceWrite.DeleteForever>(it) }
        assertEquals(
            listOf(
                TrashKind.Topic to "t-alpha",
                TrashKind.Task to "k-a",
                TrashKind.Task to "k-d",
                TrashKind.Reminder to "r-topic",
                TrashKind.Reminder to "r-task",
            ),
            writes.map { it.kind to it.id },
        )
    }

    @Test
    fun deletingATaskOrNoteForeverTakesItsLiveReminders() {
        val stored =
            data(alpha, beta).copy(
                tasks = listOf(loose.copy(deleted = true)),
                notes = listOf(shared.copy(inTrash = true)),
                reminders = listOf(onLoose, onNote, onNote.copy(id = ReminderId("r-note-trashed"), deleted = true)),
            )
        val entries = trashEntries(stored)
        val task = entries.filterIsInstance<TrashEntry.TaskEntry>().single()
        assertEquals(listOf("k-l", "r-loose"), deleteForeverWrites(task, stored).map { (it as WorkspaceWrite.DeleteForever).id })
        val note = entries.filterIsInstance<TrashEntry.NoteEntry>().single()
        assertEquals(listOf("n-shared", "r-note"), deleteForeverWrites(note, stored).map { (it as WorkspaceWrite.DeleteForever).id })
        assertEquals(TopicState.Live, note.topics.first().state)
    }
}
