package thread.web.presentation.tasks

import thread.domain.task.Deadline
import thread.domain.task.DueGroup
import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.task.groupByDue
import thread.domain.task.todayTasks
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.TopicId
import thread.domain.workspace.TodayGroupBy
import thread.web.platform.WallClock
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertEquals

// D90: Today's task groups in a real time zone, and what the Task sheet says for each "Group tasks by". Every Task here
// is SYNTHETIC.
class TodayGroupsTest {
    private val today = LocalDay(2026, 11, 13)
    private val tomorrow = today.plusDays(1)
    private val noon = WallTime.of(today, 12, 0)
    private val inTopic = Task(TaskId("k-synthetic"), "Synthetic task", TopicId("t-synthetic"))

    private fun groups(
        now: WallTime,
        tasks: List<Task>,
    ) = groupByDue(todayTasks(tasks, now, keepCompleted = true), now).associate { group -> group.group to group.tasks.map { it.id.value } }

    @Test
    fun tomorrowBecomesTodayAtMidnightInTheWorkspaceZoneNotInUtc() {
        val plannedTomorrow = inTopic.copy(id = TaskId("k-tomorrow"), plannedDay = tomorrow)
        val plannedInTwoDays = inTopic.copy(id = TaskId("k-later"), plannedDay = today.plusDays(2))
        val tasks = listOf(plannedTomorrow, plannedInTwoDays)
        // Asia/Tbilisi is UTC+4: its midnight is 20:00 UTC, while the UTC day is still the 13th.
        val lastMinute = WallClock.wallTimeAt(Date.parse("2026-11-13T19:59:00Z"), "Asia/Tbilisi")
        val midnight = WallClock.wallTimeAt(Date.parse("2026-11-13T20:00:00Z"), "Asia/Tbilisi")
        assertEquals(WallTime.of(today, 23, 59), lastMinute)
        assertEquals(mapOf(DueGroup.Tomorrow to listOf("k-tomorrow"), DueGroup.Upcoming to listOf("k-later")), groups(lastMinute, tasks))
        assertEquals(mapOf(DueGroup.Today to listOf("k-tomorrow"), DueGroup.Tomorrow to listOf("k-later")), groups(midnight, tasks))
        val utc = WallClock.wallTimeAt(Date.parse("2026-11-13T20:00:00Z"), "UTC")
        assertEquals(mapOf(DueGroup.Tomorrow to listOf("k-tomorrow"), DueGroup.Upcoming to listOf("k-later")), groups(utc, tasks))
    }

    @Test
    fun theTaskSheetNamesTheGroupTodayListsTheTaskUnder() {
        val overdue = inTopic.copy(deadline = Deadline(today.plusDays(-1)))
        val earlier = inTopic.copy(plannedDay = today.plusDays(-2))
        val plannedTomorrow = inTopic.copy(plannedDay = tomorrow)
        val doneToday = inTopic.copy(plannedDay = today, done = true, completedOn = today)
        val loose = inTopic.copy(topicId = null)

        fun effect(
            task: Task,
            groupBy: TodayGroupBy,
        ) = todayEffect(task, noon, keepCompleted = true, groupBy = groupBy)

        assertEquals("Shows in Today under Today because the deadline passed.", effect(overdue, TodayGroupBy.DueDate))
        assertEquals("Shows in Today under its Topic because the deadline passed.", effect(overdue, TodayGroupBy.Topic))
        assertEquals("Shows in Today because the deadline passed.", effect(overdue, TodayGroupBy.None))
        assertEquals(
            "Shows in Today under Today because it was planned for an earlier day. It is not overdue; nothing moves it automatically.",
            effect(earlier, TodayGroupBy.DueDate),
        )
        assertEquals("Shows in Today under Tomorrow. Tomorrow it moves to Today.", effect(plannedTomorrow, TodayGroupBy.DueDate))
        assertEquals("Shows in Today under its Topic. Its first date is tomorrow.", effect(plannedTomorrow, TodayGroupBy.Topic))
        assertEquals("Completed. It stays in Today under Completed until tomorrow, then leaves.", effect(doneToday, TodayGroupBy.DueDate))
        assertEquals("Completed. It stays in Today until tomorrow, then leaves.", effect(doneToday, TodayGroupBy.None))
        assertEquals("Completed. It does not show in Today.", todayEffect(doneToday, noon, keepCompleted = false))
        assertEquals(
            "Shows in Today under No Topic. It has no date: plan a day, set a deadline or add a reminder to give it one.",
            effect(loose, TodayGroupBy.Topic),
        )
        assertEquals(
            "Not in Today while it waits in Inbox. Plan a day, set a deadline or add a reminder to see it there.",
            effect(loose.copy(inbox = true), TodayGroupBy.None),
        )
    }
}
