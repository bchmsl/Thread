package thread.domain.task

import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.TopicId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Every Task here is SYNTHETIC test data.
class TaskRulesTest {
    private val today = LocalDay(2026, 11, 13)
    private val yesterday = today.plusDays(-1)
    private val tomorrow = today.plusDays(1)
    private val noon = WallTime.of(today, 12, 0)
    private val topic = TopicId("t-synthetic")

    private fun task(
        id: String,
        createdAt: Long = id.hashCode().toLong(),
        topicId: TopicId? = topic,
        done: Boolean = false,
        completedOn: LocalDay? = null,
        plannedDay: LocalDay? = null,
        deadline: Deadline? = null,
        inbox: Boolean = false,
        deleted: Boolean = false,
    ) = Task(TaskId(id), "Task $id", topicId, done, completedOn, plannedDay, deadline, inbox, createdAt, deleted)

    @Test
    fun deadlinesParseAndPrint() {
        assertEquals(Deadline(today), Deadline.parse("2026-11-13"))
        assertEquals(Deadline(today, 18 * 60), Deadline.parse("2026-11-13T18:00"))
        assertEquals("2026-11-13", Deadline(today).toString())
        assertEquals("2026-11-13T07:30", Deadline(today, 450).toString())
        assertNull(Deadline.parse("2026-11-13T"))
        assertNull(Deadline.parse("13/11/2026"))
    }

    @Test
    fun aDateOnlyDeadlineLastsTheWholeDayAndNeverShifts() {
        val due = Deadline.parse("2026-11-13")!!
        assertFalse(due.isPassed(WallTime.of(today, 0, 0)))
        assertFalse(due.isPassed(WallTime.of(today, 23, 59)), "still due at the last minute of its day")
        assertTrue(due.isPassed(WallTime.of(tomorrow, 0, 0)), "passed when the next day starts")
        // Written and read back as the same calendar day: no instant, no zone, so no UTC shift.
        assertEquals("2026-11-13", Deadline.parse(due.toString()).toString())
    }

    @Test
    fun aTimedDeadlinePassesAfterItsMinute() {
        val due = Deadline(today, 15 * 60)
        assertFalse(due.isPassed(WallTime.of(today, 14, 59)))
        assertFalse(due.isPassed(WallTime.of(today, 15, 0)), "not passed at the exact minute (v3 compares with <)")
        assertTrue(due.isPassed(WallTime.of(today, 15, 1)))
    }

    @Test
    fun deadlinesSortWithDateOnlyAtTheEndOfTheirDay() {
        val list = listOf(Deadline(today), Deadline(today, 600), Deadline(yesterday), Deadline(today, 1439))
        assertEquals(listOf(Deadline(yesterday), Deadline(today, 600), Deadline(today, 1439), Deadline(today)), list.sorted())
    }

    @Test
    fun aNewTaskWithoutATopicWaitsInInbox() {
        val loose = newTask(TaskId("k1"), "  Synthetic call  ", topicId = null)!!
        assertEquals("Synthetic call", loose.title)
        assertTrue(loose.inbox)
        assertFalse(loose.done)
        val filed = newTask(TaskId("k2"), "Synthetic review", topic)!!
        assertFalse(filed.inbox)
        assertNull(newTask(TaskId("k3"), "   ", null))
        assertNull(newTask(TaskId("k4"), "x".repeat(TASK_TITLE_MAX_LENGTH + 1), null))
    }

    @Test
    fun d38ATaskAddedFromTodayIsPlannedForTodayAndAlsoInInbox() {
        val fromToday = newTask(TaskId("k1"), "Synthetic today task", topicId = null, plannedDay = today)!!
        assertEquals(today, fromToday.plannedDay)
        assertTrue(fromToday.inbox)
        assertEquals(listOf(fromToday), todayTasks(listOf(fromToday), noon, keepCompleted = true))
        assertEquals(DueGroup.Today, dueGroupOf(fromToday, noon))
        assertEquals(listOf(fromToday), inboxTasks(listOf(fromToday)))
    }

    @Test
    fun tasksInTrashShowNowhere() {
        val trashed = task("x", plannedDay = today, inbox = true, deleted = true)
        assertEquals(emptyList(), todayTasks(listOf(trashed), noon, true))
        assertEquals(emptyList(), inboxTasks(listOf(trashed)))
        assertEquals(TaskProgress(0, 0), progressOf(topic, listOf(trashed)))
    }

    @Test
    fun progressCountsTheTopicsPersonalTasks() {
        val other = TopicId("t-other")
        val tasks =
            listOf(
                task("1", done = true, completedOn = today),
                task("2"),
                task("3", done = true, completedOn = yesterday),
                task("4", topicId = other),
                task("5", topicId = null),
            )
        assertEquals(TaskProgress(done = 2, total = 3), progressOf(topic, tasks))
        assertEquals(TaskProgress(done = 0, total = 1), progressOf(other, tasks))
    }

    @Test
    fun inboxListsNewestFirst() {
        val old = task("old", topicId = null, inbox = true, createdAt = 100)
        val new = task("new", topicId = null, inbox = true, createdAt = 200)
        val pendingTime = task("pending", topicId = null, inbox = true, createdAt = Long.MAX_VALUE)
        val filed = task("filed", inbox = false, createdAt = 300)
        assertEquals(listOf(pendingTime, new, old), inboxTasks(listOf(old, new, filed, pendingTime)))
    }

    @Test
    fun overdueNeedsAnOpenTaskAndAPassedDeadline() {
        assertTrue(isOverdue(task("a", deadline = Deadline(yesterday)), noon))
        assertFalse(isOverdue(task("b", deadline = Deadline(yesterday), done = true), noon))
        assertFalse(isOverdue(task("c", deadline = Deadline(today)), noon))
        assertFalse(isOverdue(task("d"), noon))
    }

    @Test
    fun theFirstOpenTaskOfEachTopicIsTheOldestOpenOne() {
        val other = TopicId("t-other")
        val tasks =
            listOf(
                task("done-first", createdAt = 1, done = true),
                task("trashed", createdAt = 2, deleted = true),
                task("second", createdAt = 4),
                task("first", createdAt = 3),
                task("loose", createdAt = 0, topicId = null),
                task("other", createdAt = 5, topicId = other),
            )
        val first = firstOpenTasks(tasks)
        assertEquals(mapOf(topic to "first", other to "other"), first.mapValues { it.value.id.value })
        assertEquals(emptyMap(), firstOpenTasks(listOf(task("all-done", done = true))))
    }
}
