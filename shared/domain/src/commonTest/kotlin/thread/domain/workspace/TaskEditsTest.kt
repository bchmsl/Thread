package thread.domain.workspace

import thread.domain.reminder.Reminder
import thread.domain.reminder.ReminderId
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.task.Deadline
import thread.domain.task.TASK_TITLE_MAX_LENGTH
import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.time.DEFAULT_WORKSPACE_ZONE
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.TopicId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Every Task and Reminder here is SYNTHETIC test data.
class TaskEditsTest {
    private val today = LocalDay(2026, 11, 13)
    private val topic = TopicId("t-synthetic")
    private val loose = Task(TaskId("k1"), "Synthetic inbox task", inbox = true)

    private fun changed(result: EditResult): WorkspaceEdit = assertIs<EditResult.Changed>(result).edit

    @Test
    fun completingRecordsTheDayAndUndoReopens() {
        val edit = changed(toggleTask(loose, today))
        val done = assertIs<WorkspaceWrite.PutTask>(edit.writes.single())
        assertEquals(true, done.task.done)
        assertEquals(today, done.task.completedOn)
        assertEquals(loose, done.before)
        assertEquals(loose, assertIs<WorkspaceWrite.PutTask>(edit.undo.single()).task)
        val reopened = assertIs<WorkspaceWrite.PutTask>(changed(toggleTask(done.task, today)).writes.single()).task
        assertEquals(false, reopened.done)
        assertEquals(null, reopened.completedOn)
    }

    @Test
    fun pickingATopicFilesTheTaskOutOfInbox() {
        val filed = assertIs<WorkspaceWrite.PutTask>(changed(editTask(loose, topicId = topic)).writes.single()).task
        assertEquals(topic, filed.topicId)
        assertEquals(false, filed.inbox)
        val cleared = assertIs<WorkspaceWrite.PutTask>(changed(editTask(filed, topicId = null)).writes.single()).task
        assertEquals(null, cleared.topicId)
        assertEquals(false, cleared.inbox, "taking the Topic away does not put it back in Inbox")
    }

    @Test
    fun titlesAreTrimmedAndBlankOnesRefused() {
        val renamed = assertIs<WorkspaceWrite.PutTask>(changed(editTask(loose, title = "  New synthetic title ")).writes.single()).task
        assertEquals("New synthetic title", renamed.title)
        assertEquals(EditResult.Refused(EditProblem.BlankName), editTask(loose, title = "  "))
        assertEquals(EditResult.Refused(EditProblem.NameTooLong), editTask(loose, title = "x".repeat(TASK_TITLE_MAX_LENGTH + 1)))
        assertEquals(EditResult.Unchanged, editTask(loose, title = " ${loose.title} "))
    }

    @Test
    fun planningAndDeadlinesAreSeparate() {
        val planned = assertIs<WorkspaceWrite.PutTask>(changed(planTask(loose, today)).writes.single()).task
        assertEquals(today, planned.plannedDay)
        assertEquals(null, planned.deadline)
        val due = assertIs<WorkspaceWrite.PutTask>(changed(editTask(planned, deadline = Deadline(today.plusDays(3)))).writes.single()).task
        assertEquals(today, due.plannedDay, "a deadline never changes the planned day")
        assertEquals(EditResult.Unchanged, planTask(due, today))
    }

    @Test
    fun triageNeverDeletesAndUndoRestoresEachTask() {
        val other = Task(TaskId("k2"), "Second synthetic task", topicId = TopicId("t-old"), inbox = true)
        val linked = changed(triageTasks(listOf(loose, other), topic))
        val written = linked.writes.map { assertIs<WorkspaceWrite.PutTask>(it).task }
        assertTrue(written.all { it.topicId == topic && !it.inbox && !it.deleted })
        assertEquals(listOf(loose, other), linked.undo.map { assertIs<WorkspaceWrite.PutTask>(it).task })

        val kept = changed(triageTasks(listOf(loose, other), null)).writes.map { assertIs<WorkspaceWrite.PutTask>(it).task }
        assertEquals(listOf(null, TopicId("t-old")), kept.map { it.topicId }, "standalone keeps any Topic it had")
        assertTrue(kept.none { it.inbox })
        assertEquals(EditResult.Unchanged, triageTasks(listOf(loose.copy(inbox = false)), null))
    }

    @Test
    fun trashAndDiscardKeepTheDocument() {
        val trash = changed(trashTask(loose))
        assertEquals(true, assertIs<WorkspaceWrite.PutTask>(trash.writes.single()).task.deleted)
        assertEquals(loose, assertIs<WorkspaceWrite.PutTask>(trash.undo.single()).task)
        val discarded = assertIs<WorkspaceWrite.PutTask>(discardTask(loose).single()).task
        assertEquals(true, discarded.deleted)
        assertEquals(true, discarded.discarded)
    }

    @Test
    fun remindersDismissAndGoToTrash() {
        val reminder =
            Reminder(
                ReminderId("r1"),
                WallTime.of(today, 15, 0),
                DEFAULT_WORKSPACE_ZONE,
                "Synthetic reminder",
                ReminderTarget(TargetKind.Task, "k1"),
            )
        val dismissed = assertIs<WorkspaceWrite.PutReminder>(dismissReminder(reminder).single())
        assertEquals(true, dismissed.reminder.dismissed)
        assertEquals(reminder, dismissed.before)
        assertEquals(emptyList(), dismissReminder(dismissed.reminder))
        val trash = changed(trashReminder(reminder))
        assertEquals(true, assertIs<WorkspaceWrite.PutReminder>(trash.writes.single()).reminder.deleted)
        assertEquals(reminder, assertIs<WorkspaceWrite.PutReminder>(trash.undo.single()).reminder)
        assertEquals(EditResult.Unchanged, trashReminder(reminder.copy(deleted = true)))
    }

    @Test
    fun theFirstPreferencesSaveAlsoWritesTheNewFields() {
        val write = savePrefs(WorkspaceData(prefsStored = false), WorkspacePrefs(keepCompleted = false))!!
        assertEquals(null, write.before)
        assertEquals(false, write.prefs.keepCompleted)
        assertEquals(DEFAULT_WORKSPACE_ZONE, write.prefs.timeZone)
    }
}
