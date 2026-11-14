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
        assertEquals(listOf(fromToday), todayTasks(listOf(fromToday), noon, keepCompleted = true).today)
        assertEquals(listOf(fromToday), inboxTasks(listOf(fromToday)))
    }

    @Test
    fun todayGroupsFollowD37() {
        val overdue = task("over", deadline = Deadline(today, 9 * 60))
        val plannedToday = task("plan", plannedDay = today)
        val dueLater = task("later", deadline = Deadline(today, 17 * 60))
        val dueToday = task("dateonly", deadline = Deadline(today))
        val earlier = task("earlier", plannedDay = yesterday.plusDays(-2))
        val future = task("future", plannedDay = tomorrow)
        val nothing = task("none")
        val groups = todayTasks(listOf(overdue, plannedToday, dueLater, dueToday, earlier, future, nothing), noon, true)
        assertEquals(listOf(overdue), groups.overdue)
        assertEquals(setOf(plannedToday, dueLater, dueToday), groups.today.toSet())
        assertEquals(listOf(earlier), groups.earlier)
        assertEquals(listOf(future), groups.upcoming)
        assertEquals(listOf(nothing), groups.noDate)
        assertEquals(5, groups.openCount, "Upcoming and No date are not counted (D53)")
    }

    @Test
    fun d53UpcomingAndNoDateHoldTheOpenTasksTheOtherGroupsLeave() {
        val plannedTomorrow = task("p1", plannedDay = tomorrow)
        val dueNextWeek = task("d1", deadline = Deadline(today.plusDays(7), 10 * 60))
        val plannedLaterDueTomorrow = task("pd", plannedDay = today.plusDays(5), deadline = Deadline(tomorrow))
        val plannedEarlierDueLater = task("ed", plannedDay = yesterday, deadline = Deadline(tomorrow))
        val inboxPlannedTomorrow = task("ip", topicId = null, inbox = true, plannedDay = tomorrow)
        val inTopicNoDate = task("n1")
        val standaloneNoDate = task("n2", topicId = null)
        val inboxNoDate = task("in", topicId = null, inbox = true)
        val doneFuture = task("df", done = true, completedOn = yesterday, plannedDay = tomorrow)
        val doneNoDateToday = task("dn", done = true, completedOn = today)
        val trashedNoDate = task("tn", deleted = true)
        val all =
            listOf(
                plannedTomorrow,
                dueNextWeek,
                plannedLaterDueTomorrow,
                plannedEarlierDueLater,
                inboxPlannedTomorrow,
                inTopicNoDate,
                standaloneNoDate,
                inboxNoDate,
                doneFuture,
                doneNoDateToday,
                trashedNoDate,
            )
        val groups = todayTasks(all, noon, keepCompleted = true)
        assertEquals(setOf(plannedTomorrow, dueNextWeek, plannedLaterDueTomorrow, inboxPlannedTomorrow), groups.upcoming.toSet())
        assertEquals(setOf(inTopicNoDate, standaloneNoDate), groups.noDate.toSet())
        assertEquals(listOf(plannedEarlierDueLater), groups.earlier, "Earlier planned comes before Upcoming")
        assertEquals(listOf(doneNoDateToday), groups.today, "a completed Task stays under Today for the day, as before")
        val shown = groups.overdue + groups.today + groups.earlier + groups.upcoming + groups.noDate
        assertEquals(shown.toSet().size, shown.size, "no Task twice")
        assertFalse(inboxNoDate in shown, "a Task in Inbox without dates stays in Inbox only (D38)")
        assertFalse(doneFuture in shown)
        assertEquals(1, groups.openCount, "only Earlier planned is open for today here")
        assertEquals(1, groups.doneCount)

        assertEquals(TodayGroup.Upcoming, todayGroupOf(plannedLaterDueTomorrow, noon))
        assertEquals(TodayGroup.NoDate, todayGroupOf(standaloneNoDate, noon))
        assertNull(todayGroupOf(inboxNoDate, noon))
        assertEquals(tomorrow, firstDayOf(plannedLaterDueTomorrow))
        assertNull(firstDayOf(inTopicNoDate))

        val nextDay = todayTasks(all, WallTime.of(tomorrow, 0, 0), keepCompleted = true)
        assertTrue(plannedTomorrow in nextDay.today, "an Upcoming Task moves to Today on its day")
        assertTrue(plannedLaterDueTomorrow in nextDay.today)
    }

    @Test
    fun upcomingSortsByFirstDayThenTimeThenCreation() {
        val plannedTomorrow = task("a", plannedDay = tomorrow, createdAt = 3)
        val dueTomorrowAtNine = task("b", deadline = Deadline(tomorrow, 9 * 60), createdAt = 5)
        val dueTomorrowAtEight = task("c", plannedDay = today.plusDays(3), deadline = Deadline(tomorrow, 8 * 60), createdAt = 6)
        val plannedTomorrowDueLater = task("d", plannedDay = tomorrow, deadline = Deadline(today.plusDays(2), 7 * 60), createdAt = 1)
        val inTwoDays = task("e", plannedDay = today.plusDays(2), createdAt = 0)
        val all = listOf(plannedTomorrow, dueTomorrowAtNine, dueTomorrowAtEight, plannedTomorrowDueLater, inTwoDays)
        assertEquals(listOf("c", "b", "d", "a", "e"), todayTasks(all, noon, true).upcoming.map { it.id.value })
    }

    @Test
    fun noDateListsNewestFirst() {
        val old = task("old", createdAt = 100)
        val new = task("new", createdAt = 200)
        val pendingTime = task("pending", createdAt = Long.MAX_VALUE)
        assertEquals(listOf(pendingTime, new, old), todayTasks(listOf(old, pendingTime, new), noon, true).noDate)
    }

    @Test
    fun aTaskThatQualifiesTwiceShowsOnce() {
        val both = task("both", plannedDay = today, deadline = Deadline(today, 8 * 60))
        val plannedEarlierAndOverdue = task("old", plannedDay = yesterday, deadline = Deadline(yesterday))
        val plannedEarlierDueToday = task("edue", plannedDay = yesterday, deadline = Deadline(today))
        val groups = todayTasks(listOf(both, plannedEarlierAndOverdue, plannedEarlierDueToday), noon, true)
        val all = groups.overdue + groups.today + groups.earlier
        assertEquals(3, all.size)
        assertEquals(all.toSet().size, all.size, "no Task twice")
        assertEquals(listOf(both.id, plannedEarlierAndOverdue.id).toSet(), groups.overdue.map { it.id }.toSet())
        assertEquals(listOf(plannedEarlierDueToday), groups.today)
    }

    @Test
    fun todayFollowsTheWorkspaceDayAtMidnight() {
        val planned = task("p", plannedDay = today)
        val lastMinute = WallTime.of(today, 23, 59)
        val nextDay = WallTime.of(tomorrow, 0, 0)
        assertEquals(listOf(planned), todayTasks(listOf(planned), lastMinute, true).today)
        val after = todayTasks(listOf(planned), nextDay, true)
        assertEquals(emptyList(), after.today)
        assertEquals(listOf(planned), after.earlier, "yesterday's plan moves to Earlier planned, not Overdue")
        assertEquals(emptyList(), after.overdue)
    }

    @Test
    fun completedTasksStayForTheDayWhenTheSettingIsOn() {
        val doneToday = task("d1", done = true, completedOn = today, plannedDay = today)
        val doneUnplanned = task("d2", done = true, completedOn = today)
        val doneEarlier = task("d3", done = true, completedOn = today, plannedDay = yesterday)
        val doneOverdue = task("d4", done = true, completedOn = today, deadline = Deadline(yesterday))
        val doneYesterday = task("d5", done = true, completedOn = yesterday, plannedDay = today)
        val all = listOf(doneToday, doneUnplanned, doneEarlier, doneOverdue, doneYesterday)
        val kept = todayTasks(all, noon, keepCompleted = true)
        assertEquals(setOf(doneToday, doneUnplanned, doneOverdue), kept.today.toSet())
        assertEquals(listOf(doneEarlier), kept.earlier)
        assertEquals(emptyList(), kept.overdue, "a completed Task is never overdue")
        assertEquals(4, kept.doneCount)
        assertEquals(0, kept.openCount)
        val hidden = todayTasks(all, noon, keepCompleted = false)
        assertEquals(emptyList(), hidden.today + hidden.earlier + hidden.overdue)
    }

    @Test
    fun inboxOnlyTasksWithoutDatesStayOutOfToday() {
        val captured = task("c", topicId = null, inbox = true)
        val capturedDone = task("cd", topicId = null, inbox = true, done = true, completedOn = today)
        assertEquals(emptyList(), todayTasks(listOf(captured, capturedDone), noon, true).today)
    }

    @Test
    fun todaySortsOpenFirstThenByDeadline() {
        val a = task("a", plannedDay = today, createdAt = 1)
        val b = task("b", deadline = Deadline(today, 13 * 60), createdAt = 2)
        val c = task("c", plannedDay = today, done = true, completedOn = today, createdAt = 0)
        val d = task("d", deadline = Deadline(today, 18 * 60), createdAt = 3)
        assertEquals(listOf(b, d, a, c), todayTasks(listOf(a, b, c, d), noon, true).today)
        val o1 = task("o1", deadline = Deadline(yesterday, 600))
        val o2 = task("o2", deadline = Deadline(yesterday.plusDays(-1)))
        assertEquals(listOf(o2, o1), todayTasks(listOf(o1, o2), noon, true).overdue)
    }

    @Test
    fun tasksInTrashShowNowhere() {
        val trashed = task("x", plannedDay = today, inbox = true, deleted = true)
        assertEquals(emptyList(), todayTasks(listOf(trashed), noon, true).today)
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
