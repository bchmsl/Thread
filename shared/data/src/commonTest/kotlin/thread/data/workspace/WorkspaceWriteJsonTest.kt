package thread.data.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import thread.domain.note.LinkHref
import thread.domain.note.NoteId
import thread.domain.note.NoteSummary
import thread.domain.note.NoteTopicLink
import thread.domain.reminder.Reminder
import thread.domain.reminder.ReminderId
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.task.Deadline
import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.StatusCategory
import thread.domain.topic.StatusId
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import thread.domain.topic.TopicStatus
import thread.domain.workspace.CaptureKind
import thread.domain.workspace.ExternalProvider
import thread.domain.workspace.ExternalReference
import thread.domain.workspace.TopicExternalLink
import thread.domain.workspace.TopicsView
import thread.domain.workspace.TrashKind
import thread.domain.workspace.WorkspacePrefs
import thread.domain.workspace.WorkspaceWrite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Every Topic, Task, Reminder, Note and link here is SYNTHETIC test data.
class WorkspaceWriteJsonTest {
    private val topic =
        Topic(
            TopicId("t-1"),
            "Synthetic topic",
            TopicColor.entries.last(),
            StatusId("s-active"),
            goal = "Synthetic goal",
            leftOff = "Synthetic left off",
            nextStep = "Synthetic next step",
            createdAtMillis = 1_700_000_000_000,
        )
    private val task =
        Task(
            TaskId("k-1"),
            "Synthetic task",
            topic.id,
            done = true,
            completedOn = LocalDay(2026, 11, 12),
            plannedDay = LocalDay(2026, 11, 13),
            deadline = Deadline(LocalDay(2026, 11, 14), minuteOfDay = 17 * 60 + 30),
            createdAtMillis = 1_700_000_000_001,
        )
    private val reminder =
        Reminder(
            ReminderId("r-1"),
            WallTime(LocalDay(2026, 11, 13), 9 * 60),
            "Asia/Tashkent",
            "Synthetic reminder",
            ReminderTarget(TargetKind.Task, task.id.value),
            dismissed = true,
            deletedAtMillis = 1_700_000_000_002,
        )
    private val note = NoteSummary(NoteId("n-1"), "Synthetic note", "Synthetic preview", inbox = true, updatedAtMillis = 5)
    private val reference =
        ExternalReference(ExternalProvider.GitHub, "synthetic/repo", "7", LinkHref.parse("https://github.com/synthetic/repo/pull/7")!!)

    private val everyWrite =
        listOf(
            WorkspaceWrite.PutTopic(topic, isNew = true),
            WorkspaceWrite.PutTopic(topic.copy(name = "Renamed", deleted = true, deletedAtMillis = 9), before = topic),
            WorkspaceWrite.PutStatus(
                TopicStatus(StatusId("s-1"), "Building", StatusCategory.Active, 1),
                before = TopicStatus(StatusId("s-1"), "Active", StatusCategory.Active, 1),
            ),
            WorkspaceWrite.PutPrefs(WorkspacePrefs(TopicsView.Board, CaptureKind.Note, keepCompleted = false, timeZone = "Asia/Tbilisi")),
            WorkspaceWrite.PutTask(task, isNew = true),
            WorkspaceWrite.PutTask(Task(TaskId("k-2"), "Date-only deadline", deadline = Deadline(LocalDay(2026, 1, 2))), before = task),
            WorkspaceWrite.PutReminder(reminder, isNew = true, before = reminder.copy(text = "Old")),
            WorkspaceWrite.PutNote(note, before = note.copy(inbox = false)),
            WorkspaceWrite.PutNoteLink(NoteTopicLink(topic.id, note.id, linked = false)),
            WorkspaceWrite.PutExternalRef(reference),
            WorkspaceWrite.PutExternalLink(TopicExternalLink(topic.id, reference.id)),
            WorkspaceWrite.DeleteForever(TrashKind.Reminder, reminder.id.value, "Synthetic reminder"),
        )

    private fun roundTrip(writes: List<WorkspaceWrite>): List<WorkspaceWrite>? {
        val stored = WorkspaceWriteJson.encode(writes).toString()
        return WorkspaceWriteJson.decode(Json.parseToJsonElement(stored).jsonArray)
    }

    @Test
    fun everyKindOfWriteComesBackUnchanged() {
        assertEquals(everyWrite, roundTrip(everyWrite))
    }

    @Test
    fun aWriteThisVersionCannotReadDropsTheWholeChange() {
        val good = WorkspaceWriteJson.encode(everyWrite.take(1)).single()
        val unknown = Json.parseToJsonElement("""{"type":"later","value":{}}""")
        val badDeadline =
            Json.parseToJsonElement(
                WorkspaceWriteJson
                    .encode(listOf(WorkspaceWrite.PutTask(task)))
                    .single()
                    .toString()
                    .replace("2026-11-14", "not a day"),
            )

        assertNull(WorkspaceWriteJson.decode(JsonArray(listOf(good, unknown))))
        assertNull(WorkspaceWriteJson.decode(JsonArray(listOf(good, badDeadline))))
        assertNull(WorkspaceWriteJson.decode(JsonArray(emptyList())), "A change always has a write")
    }
}
