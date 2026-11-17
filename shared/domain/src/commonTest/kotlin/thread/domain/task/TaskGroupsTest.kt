package thread.domain.task

import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Every Task and Topic here is SYNTHETIC test data.
class TaskGroupsTest {
    private val today = LocalDay(2026, 11, 13)
    private val yesterday = today.plusDays(-1)
    private val tomorrow = today.plusDays(1)
    private val noon = WallTime.of(today, 12, 0)
    private val status = SEED_STATUSES[0].id
    private val alpha = Topic(TopicId("t-alpha"), "Synthetic Alpha", TopicColor.Green, status)
    private val beta = Topic(TopicId("t-beta"), "Synthetic Beta", TopicColor.Blue, status)
    private val gamma = Topic(TopicId("t-gamma"), "Synthetic Gamma", TopicColor.Orange, status)

    private fun task(
        id: String,
        createdAt: Long = id.hashCode().toLong(),
        topicId: TopicId? = alpha.id,
        done: Boolean = false,
        completedOn: LocalDay? = null,
        plannedDay: LocalDay? = null,
        deadline: Deadline? = null,
        inbox: Boolean = false,
        deleted: Boolean = false,
    ) = Task(TaskId(id), "Task $id", topicId, done, completedOn, plannedDay, deadline, inbox, createdAt, deleted)

    private fun ids(tasks: List<Task>) = tasks.map { it.id.value }

    private fun groupsOf(
        tasks: List<Task>,
        now: WallTime = noon,
        remindAt: Map<TaskId, WallTime> = emptyMap(),
    ) = groupByDue(tasks, now, remindAt).associate { it.group to ids(it.tasks) }

    @Test
    fun eachDateCaseLandsInExactlyOneOfTheFourGroups() {
        val cases =
            listOf(
                task("overdue-time", deadline = Deadline(today, 9 * 60)) to DueGroup.Today,
                task("overdue-day", deadline = Deadline(yesterday)) to DueGroup.Today,
                task("planned-today", plannedDay = today) to DueGroup.Today,
                task("due-later-today", deadline = Deadline(today, 17 * 60)) to DueGroup.Today,
                task("due-today", deadline = Deadline(today)) to DueGroup.Today,
                task("planned-earlier", plannedDay = yesterday.plusDays(-2)) to DueGroup.Today,
                task("planned-earlier-due-tomorrow", plannedDay = yesterday, deadline = Deadline(tomorrow)) to DueGroup.Today,
                task("planned-today-overdue", plannedDay = today, deadline = Deadline(today, 8 * 60)) to DueGroup.Today,
                task("planned-tomorrow", plannedDay = tomorrow) to DueGroup.Tomorrow,
                task("due-tomorrow-late", deadline = Deadline(tomorrow, 23 * 60 + 59)) to DueGroup.Tomorrow,
                task("planned-later-due-tomorrow", plannedDay = today.plusDays(5), deadline = Deadline(tomorrow)) to DueGroup.Tomorrow,
                task("inbox-planned-tomorrow", topicId = null, inbox = true, plannedDay = tomorrow) to DueGroup.Tomorrow,
                task("planned-in-two-days", plannedDay = today.plusDays(2)) to DueGroup.Upcoming,
                task("due-next-week", deadline = Deadline(today.plusDays(7), 10 * 60)) to DueGroup.Upcoming,
                task("no-date") to DueGroup.Upcoming,
                task("no-date-no-topic", topicId = null) to DueGroup.Upcoming,
                task("done-planned-today", done = true, completedOn = today, plannedDay = today) to DueGroup.Completed,
                task("done-overdue", done = true, completedOn = today, deadline = Deadline(yesterday)) to DueGroup.Completed,
                task("done-planned-later", done = true, completedOn = today, plannedDay = today.plusDays(3)) to DueGroup.Completed,
            )
        cases.forEach { (task, expected) -> assertEquals(expected, dueGroupOf(task, noon), task.id.value) }
        val groups = groupByDue(cases.map { it.first }, noon)
        val listed = groups.flatMap { it.tasks }
        assertEquals(cases.size, listed.size, "every Task once")
        assertEquals(cases.map { it.first }.toSet(), listed.toSet())
        groups.forEach { group -> group.tasks.forEach { assertEquals(group.group, dueGroupOf(it, noon), it.id.value) } }
        assertEquals(DueGroup.entries.toList(), groups.map { it.group }, "Completed, Today, Tomorrow, Upcoming: only these four")
    }

    @Test
    fun anEmptyGroupIsLeftOut() {
        val onlyTomorrow = listOf(task("a", plannedDay = tomorrow), task("b", deadline = Deadline(tomorrow)))
        assertEquals(listOf(DueGroup.Tomorrow), groupByDue(onlyTomorrow, noon).map { it.group })
        assertEquals(emptyList(), groupByDue(emptyList(), noon))
    }

    @Test
    fun tomorrowAndTodayFollowTheWorkspaceDayAtMidnight() {
        val plannedTomorrow = task("pt", plannedDay = tomorrow)
        val plannedInTwoDays = task("p2", plannedDay = today.plusDays(2))
        val plannedToday = task("p0", plannedDay = today)
        val dueTomorrowEarly = task("de", deadline = Deadline(tomorrow, 0))
        val all = listOf(plannedTomorrow, plannedInTwoDays, plannedToday, dueTomorrowEarly)

        val lastMinute = WallTime.of(today, 23, 59)
        assertEquals(DueGroup.Tomorrow, dueGroupOf(plannedTomorrow, lastMinute))
        assertEquals(DueGroup.Tomorrow, dueGroupOf(dueTomorrowEarly, lastMinute))
        assertEquals(DueGroup.Upcoming, dueGroupOf(plannedInTwoDays, lastMinute))
        assertEquals(DueGroup.Today, dueGroupOf(plannedToday, lastMinute))

        val midnight = WallTime.of(tomorrow, 0, 0)
        assertEquals(
            mapOf(DueGroup.Today to listOf("de", "pt", "p0"), DueGroup.Tomorrow to listOf("p2")),
            groupsOf(all, midnight),
            "at midnight Tomorrow joins Today, the next day becomes Tomorrow, and yesterday's plan stays in Today",
        )
        assertFalse(isOverdue(plannedToday, midnight), "a past planned day is not a deadline")
    }

    @Test
    fun aReminderDayCountsAsADate() {
        val remindedToday = task("rt", createdAt = 1)
        val remindedTomorrow = task("rm")
        val dueNotDismissed = task("rd", createdAt = 2)
        val remindedBeforeItsPlan = task("rp", plannedDay = today.plusDays(5))
        val inboxReminded = task("ir", topicId = null, inbox = true)
        val inboxNoReminder = task("in", topicId = null, inbox = true)
        val noReminder = task("nd")
        val remindAt =
            mapOf(
                remindedToday.id to WallTime.of(today, 18, 0),
                remindedTomorrow.id to WallTime.of(tomorrow, 9, 0),
                dueNotDismissed.id to WallTime.of(yesterday, 9, 0),
                remindedBeforeItsPlan.id to WallTime.of(today.plusDays(3), 9, 0),
                inboxReminded.id to WallTime.of(tomorrow, 8, 0),
            )
        val all =
            listOf(
                remindedToday,
                remindedTomorrow,
                dueNotDismissed,
                remindedBeforeItsPlan,
                inboxReminded,
                inboxNoReminder,
                noReminder,
            )
        val shown = todayTasks(all, noon, keepCompleted = true, remindAt = remindAt)
        assertTrue(inboxReminded in shown, "an Inbox Task with a reminder shows in Today (D54)")
        assertFalse(inboxNoReminder in shown, "one without any date stays in Inbox (D38)")
        assertEquals(
            mapOf(
                DueGroup.Today to listOf("rt", "rd"),
                DueGroup.Tomorrow to listOf("ir", "rm"),
                DueGroup.Upcoming to listOf("rp", "nd"),
            ),
            groupsOf(shown, remindAt = remindAt),
        )
        assertEquals(DueGroup.Upcoming, dueGroupOf(remindedToday, noon), "without its reminder the Task has no date")
        assertEquals(today.plusDays(3), firstDayOf(remindedBeforeItsPlan, remindAt[remindedBeforeItsPlan.id]))
    }

    @Test
    fun completedHoldsOnlyTasksCompletedTodayWhileShowCompletedIsOn() {
        val doneToday = task("d1", done = true, completedOn = today, plannedDay = today, createdAt = 3)
        val doneUnplanned = task("d2", done = true, completedOn = today, createdAt = 1)
        val doneEarlierPlan = task("d3", done = true, completedOn = today, plannedDay = yesterday, createdAt = 2)
        val doneOverdue = task("d4", done = true, completedOn = today, deadline = Deadline(yesterday), createdAt = 4)
        val doneYesterday = task("d5", done = true, completedOn = yesterday, plannedDay = today)
        val doneInInbox = task("d6", topicId = null, inbox = true, done = true, completedOn = today)
        val open = task("o1", plannedDay = today)
        val all = listOf(doneToday, doneUnplanned, doneEarlierPlan, doneOverdue, doneYesterday, doneInInbox, open)

        val kept = groupsOf(todayTasks(all, noon, keepCompleted = true))
        assertEquals(listOf("d2", "d3", "d1", "d4"), kept[DueGroup.Completed], "today's completed Tasks, oldest first")
        assertEquals(listOf("o1"), kept[DueGroup.Today], "a completed Task never shows as overdue or under Today")

        val hidden = groupsOf(todayTasks(all, noon, keepCompleted = false))
        assertNull(hidden[DueGroup.Completed], "no Completed group with Show completed tasks off")
        assertEquals(listOf("o1"), hidden[DueGroup.Today])

        val nextDay = WallTime.of(tomorrow, 9, 0)
        assertNull(groupsOf(todayTasks(all, nextDay, keepCompleted = true))[DueGroup.Completed], "they leave when the day ends")
    }

    @Test
    fun noneListsEveryTaskInTheDueOrder() {
        val overdueLate = task("o1", deadline = Deadline(yesterday, 600), createdAt = 1)
        val overdueEarly = task("o2", deadline = Deadline(yesterday.plusDays(-1)), createdAt = 2)
        val plannedToday = task("t1", plannedDay = today, createdAt = 3)
        val dueAtOne = task("t2", deadline = Deadline(today, 13 * 60), createdAt = 4)
        val dueAtSix = task("t3", deadline = Deadline(today, 18 * 60), createdAt = 5)
        val plannedLongAgo = task("e1", plannedDay = yesterday.plusDays(-5), createdAt = 6)
        val plannedYesterday = task("e2", plannedDay = yesterday, createdAt = 7)
        val doneNew = task("c1", done = true, completedOn = today, createdAt = 9)
        val doneOld = task("c2", done = true, completedOn = today, plannedDay = today, createdAt = 8)
        val tomorrowNine = task("m1", deadline = Deadline(tomorrow, 9 * 60), createdAt = 10)
        val tomorrowPlain = task("m2", plannedDay = tomorrow, createdAt = 11)
        val inThreeDays = task("u1", plannedDay = today.plusDays(3), createdAt = 12)
        val inTwoDays = task("u2", plannedDay = today.plusDays(2), createdAt = 13)
        val noDateOld = task("n1", createdAt = 14)
        val noDateNew = task("n2", createdAt = 15)
        val all =
            listOf(
                noDateOld,
                inThreeDays,
                doneNew,
                tomorrowPlain,
                plannedYesterday,
                dueAtSix,
                overdueLate,
                noDateNew,
                plannedToday,
                tomorrowNine,
                doneOld,
                inTwoDays,
                plannedLongAgo,
                dueAtOne,
                overdueEarly,
            )
        assertEquals(
            listOf("o2", "o1", "t2", "t3", "t1", "e1", "e2", "c2", "c1", "m1", "m2", "u2", "u1", "n2", "n1"),
            ids(todayTasks(all, noon, keepCompleted = true)),
            "today's open Tasks in D37's order, then today's completed ones, then Tomorrow, Upcoming, and no date newest first",
        )
        assertEquals(ids(todayTasks(all, noon, keepCompleted = true)), ids(dueOrder(all, noon)))
    }

    @Test
    fun laterTasksSortByFirstDayThenTimeThenCreation() {
        val plannedTomorrow = task("a", plannedDay = tomorrow, createdAt = 3)
        val dueTomorrowAtNine = task("b", deadline = Deadline(tomorrow, 9 * 60), createdAt = 5)
        val dueTomorrowAtEight = task("c", plannedDay = today.plusDays(3), deadline = Deadline(tomorrow, 8 * 60), createdAt = 6)
        val plannedTomorrowDueLater = task("d", plannedDay = tomorrow, deadline = Deadline(today.plusDays(2), 7 * 60), createdAt = 1)
        val inTwoDays = task("e", plannedDay = today.plusDays(2), createdAt = 0)
        val remindedTomorrowAtHalfEight = task("f", createdAt = 2)
        val remindAt = mapOf(remindedTomorrowAtHalfEight.id to WallTime.of(tomorrow, 8, 30))
        val all =
            listOf(plannedTomorrow, dueTomorrowAtNine, dueTomorrowAtEight, plannedTomorrowDueLater, inTwoDays, remindedTomorrowAtHalfEight)
        assertEquals(
            mapOf(DueGroup.Tomorrow to listOf("c", "f", "b", "d", "a"), DueGroup.Upcoming to listOf("e")),
            groupsOf(all, remindAt = remindAt),
        )
    }

    @Test
    fun topicsLeadWithTheMostUrgentOpenTaskAndNoTopicComesLast() {
        val alphaTomorrow = task("a1", topicId = alpha.id, plannedDay = tomorrow, createdAt = 1)
        val alphaNoDate = task("a2", topicId = alpha.id, createdAt = 2)
        val betaOverdue = task("b1", topicId = beta.id, deadline = Deadline(yesterday), createdAt = 3)
        val betaDone = task("b2", topicId = beta.id, done = true, completedOn = today, createdAt = 4)
        val gammaDone = task("g1", topicId = gamma.id, done = true, completedOn = today, createdAt = 5)
        val loosePlannedToday = task("l1", topicId = null, plannedDay = today, createdAt = 6)
        val unknownTopic = task("x1", topicId = TopicId("t-trashed"), plannedDay = today, createdAt = 7)
        val all = listOf(alphaTomorrow, alphaNoDate, betaOverdue, betaDone, gammaDone, loosePlannedToday, unknownTopic)

        val groups = groupByTopic(todayTasks(all, noon, keepCompleted = true), listOf(alpha, beta, gamma), noon)
        assertEquals(listOf(beta, alpha, gamma, null), groups.map { it.topic })
        assertEquals(
            listOf(listOf("b1", "b2"), listOf("a1", "a2"), listOf("g1"), listOf("l1", "x1")),
            groups.map { ids(it.tasks) },
            "inside a Topic the due order; a Topic with only completed Tasks after the ones with open Tasks; " +
                "a Task whose Topic is unknown goes with No Topic",
        )
        assertEquals(all.size, groups.sumOf { it.tasks.size }, "every Task once")

        val withoutCompleted = groupByTopic(todayTasks(all, noon, keepCompleted = false), listOf(alpha, beta, gamma), noon)
        assertEquals(listOf(beta, alpha, null), withoutCompleted.map { it.topic }, "a Topic with nothing left to show has no group")
    }

    @Test
    fun aTaskThatQualifiesTwiceShowsOnce() {
        val both = task("both", plannedDay = today, deadline = Deadline(today, 8 * 60))
        val plannedEarlierAndOverdue = task("old", plannedDay = yesterday, deadline = Deadline(yesterday))
        val plannedEarlierDueToday = task("edue", plannedDay = yesterday, deadline = Deadline(today))
        val plannedTomorrowRemindedToday = task("prt", plannedDay = tomorrow)
        val remindAt = mapOf(plannedTomorrowRemindedToday.id to WallTime.of(today, 20, 0))
        val all = listOf(both, plannedEarlierAndOverdue, plannedEarlierDueToday, plannedTomorrowRemindedToday)
        val listed = groupByDue(all, noon, remindAt).flatMap { it.tasks }
        assertEquals(all.size, listed.size)
        assertEquals(all.toSet(), listed.toSet())
        assertEquals(mapOf(DueGroup.Today to listOf("old", "both", "edue", "prt")), groupsOf(all, remindAt = remindAt))
    }

    @Test
    fun tasksInTrashAndInboxOnlyTasksStayOutOfToday() {
        val trashed = task("x", plannedDay = today, deleted = true)
        val captured = task("c", topicId = null, inbox = true)
        val capturedDone = task("cd", topicId = null, inbox = true, done = true, completedOn = today)
        val fromToday = task("ft", topicId = null, inbox = true, plannedDay = today)
        assertEquals(listOf(fromToday), todayTasks(listOf(trashed, captured, capturedDone, fromToday), noon, keepCompleted = true))
    }
}
