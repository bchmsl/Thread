package thread.domain.workspace

import thread.domain.task.Deadline
import thread.domain.task.DueGroup
import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.task.groupByDue
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.TopicId
import kotlin.test.Test
import kotlin.test.assertEquals

// Every Task here is SYNTHETIC test data.
class TopicDetailsViewTest {
    private val today = LocalDay(2026, 11, 13)
    private val tomorrow = today.plusDays(1)
    private val noon = WallTime.of(today, 12, 0)
    private val topicId = TopicId("topic-a")

    private fun task(
        id: String,
        createdAt: Long,
        done: Boolean = false,
        completedOn: LocalDay? = null,
        plannedDay: LocalDay? = null,
        deadline: Deadline? = null,
    ) = Task(TaskId(id), "Task $id", topicId, done, completedOn, plannedDay, deadline, createdAtMillis = createdAt)

    // Oldest first, as `topicTasks` gives them.
    private val doneLongAgo = task("done-long-ago", 100, done = true, completedOn = today.plusDays(-20))
    private val noDate = task("no-date", 200)
    private val plannedLater = task("planned-later", 300, plannedDay = today.plusDays(4))
    private val overdue = task("overdue", 400, deadline = Deadline(today.plusDays(-1)))
    private val doneToday = task("done-today", 500, done = true, completedOn = today, plannedDay = today)
    private val plannedTomorrow = task("planned-tomorrow", 600, plannedDay = tomorrow)
    private val reminded = task("reminded-tomorrow", 700)
    private val tasks = listOf(doneLongAgo, noDate, plannedLater, overdue, doneToday, plannedTomorrow, reminded)
    private val remindAt = mapOf(reminded.id to WallTime.of(tomorrow, 9, 30))

    private fun parts(
        showCompleted: Boolean,
        groupByDue: Boolean,
        list: List<Task> = tasks,
    ) = topicTaskParts(list, showCompleted, groupByDue, noon, remindAt).map { part -> part.due to part.tasks.map { it.id.value } }

    @Test
    fun withoutGroupingTheOpenTasksComeFirstThenCompletedAsBefore() {
        assertEquals(
            listOf(
                null to listOf("no-date", "planned-later", "overdue", "planned-tomorrow", "reminded-tomorrow"),
                DueGroup.Completed to listOf("done-long-ago", "done-today"),
            ),
            parts(showCompleted = true, groupByDue = false),
        )
    }

    @Test
    fun showCompletedOffLeavesCompletedOutInBothModes() {
        assertEquals(
            listOf(null to listOf("no-date", "planned-later", "overdue", "planned-tomorrow", "reminded-tomorrow")),
            parts(showCompleted = false, groupByDue = false),
        )
        val grouped = parts(showCompleted = false, groupByDue = true)
        assertEquals(listOf(DueGroup.Today, DueGroup.Tomorrow, DueGroup.Upcoming), grouped.map { it.first })
        assertEquals(false, grouped.flatMap { it.second }.any { it.startsWith("done") })
    }

    @Test
    fun groupingByDueDateUsesTheSharedRuleWithEveryCompletedTaskOfTheTopic() {
        assertEquals(
            listOf(
                // Not only today's: Completed in a Topic holds all its completed Tasks, oldest first (the shared rule).
                DueGroup.Completed to listOf("done-long-ago", "done-today"),
                DueGroup.Today to listOf("overdue"),
                // A reminder day counts as a date (D54); a timed one comes before a day without a time.
                DueGroup.Tomorrow to listOf("reminded-tomorrow", "planned-tomorrow"),
                // Dated first, then no date.
                DueGroup.Upcoming to listOf("planned-later", "no-date"),
            ),
            parts(showCompleted = true, groupByDue = true),
        )
        // The same groups and order as the shared rule gives (D90): no second copy of it.
        assertEquals(
            groupByDue(tasks, noon, remindAt).map { it.group to it.tasks },
            topicTaskParts(tasks, showCompleted = true, groupByDue = true, now = noon, remindAt = remindAt).map { it.due to it.tasks },
        )
    }

    @Test
    fun emptyPartsAreLeftOut() {
        assertEquals(emptyList(), parts(showCompleted = true, groupByDue = false, list = emptyList()))
        assertEquals(emptyList(), parts(showCompleted = true, groupByDue = true, list = emptyList()))
        val allDone = listOf(doneLongAgo, doneToday)
        assertEquals(listOf(DueGroup.Completed to listOf("done-long-ago", "done-today")), parts(true, false, allDone))
        assertEquals(listOf(DueGroup.Completed to listOf("done-long-ago", "done-today")), parts(true, true, allDone))
        assertEquals(emptyList(), parts(showCompleted = false, groupByDue = true, list = allDone))
        assertEquals(listOf(DueGroup.Today to listOf("overdue")), parts(showCompleted = true, groupByDue = true, list = listOf(overdue)))
    }

    @Test
    fun aCheckedSectionHidesOnlyWhileItHoldsNothing() {
        val all = TopicHideableSection.entries.toSet()
        assertEquals(all, hiddenTopicSections(all, TopicSectionItems()), "everything checked and empty")
        assertEquals(emptySet(), hiddenTopicSections(emptySet(), TopicSectionItems()), "nothing checked: every section shows")
        val cases =
            listOf(
                TopicHideableSection.Notes to TopicSectionItems(notes = 1),
                TopicHideableSection.Jira to TopicSectionItems(jira = 1),
                TopicHideableSection.PullRequests to TopicSectionItems(prs = 2),
                TopicHideableSection.Meetings to TopicSectionItems(meetings = 1),
                TopicHideableSection.Info to TopicSectionItems(info = "Context"),
            )
        cases.forEach { (section, items) ->
            assertEquals(all - section, hiddenTopicSections(all, items), "$section holds something")
            assertEquals(setOf(section), hiddenTopicSections(setOf(section), TopicSectionItems()), "$section alone, empty")
            assertEquals(emptySet(), hiddenTopicSections(setOf(section), items), "$section alone, not empty")
        }
        // Something in an unchecked section never hides another one.
        assertEquals(setOf(TopicHideableSection.Notes), hiddenTopicSections(setOf(TopicHideableSection.Notes), TopicSectionItems(jira = 3)))
    }

    @Test
    fun suggestionsKeepJiraAndPullRequestsShowing() {
        val both = setOf(TopicHideableSection.Jira, TopicHideableSection.PullRequests)
        // Q7: nothing linked, but a suggestion shows.
        assertEquals(setOf(TopicHideableSection.PullRequests), hiddenTopicSections(both, TopicSectionItems(jiraSuggested = 2)))
        assertEquals(setOf(TopicHideableSection.Jira), hiddenTopicSections(both, TopicSectionItems(prsSuggested = 1)))
        assertEquals(emptySet(), hiddenTopicSections(both, TopicSectionItems(jiraSuggested = 1, prsSuggested = 1)))
        assertEquals(both, hiddenTopicSections(both, TopicSectionItems(notes = 4, meetings = 2, info = "Text")))
    }

    @Test
    fun blankAdditionalInformationIsEmpty() {
        val info = setOf(TopicHideableSection.Info)
        assertEquals(info, hiddenTopicSections(info, TopicSectionItems(info = "  \n ")))
        assertEquals(emptySet(), hiddenTopicSections(info, TopicSectionItems(info = "Where I left off: Halfway")))
    }
}
