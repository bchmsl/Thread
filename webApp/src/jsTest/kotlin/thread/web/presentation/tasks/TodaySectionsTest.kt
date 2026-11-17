package thread.web.presentation.tasks

import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalView
import thread.domain.external.PullRequestListPage
import thread.domain.external.PullRequestRef
import thread.domain.external.PullRequestSummary
import thread.domain.external.Saved
import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.task.TodayTasks
import thread.domain.workspace.MeetingsLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D77: which Today sections show with "Hide empty sections in Today" off and on, and the Meetings card (D78). SYNTHETIC
 * tasks and pull requests.
 */
class TodaySectionsTest {
    private val task = Task(TaskId("synthetic-task"), "Synthetic task")
    private val noTasks = TodayTasks(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    private val pull =
        PullRequestSummary(
            ref = PullRequestRef("synthetic-owner", "synthetic-repo", 1),
            title = "Synthetic pull request",
            author = null,
            isDraft = false,
            updatedAtMillis = null,
            webUrl = null,
        )

    private fun page(vararg items: PullRequestSummary) = Saved(PullRequestListPage(items.toList(), items.size, isIncomplete = false), 0L)

    @Test
    fun reviewRequestsCountOnlyWhenThereIsSomethingToActOn() {
        val some = page(pull)
        val none = page()
        assertEquals(ReviewContent.Nothing, reviewContent(connected = false, view = ExternalView.Fresh(some, storedOnDevice = true)))
        assertEquals(ReviewContent.Nothing, reviewContent(connected = true, view = null))
        assertEquals(ReviewContent.Nothing, reviewContent(connected = true, view = ExternalView.Loading))
        assertEquals(ReviewContent.Nothing, reviewContent(connected = true, view = ExternalView.Fresh(none, storedOnDevice = true)))
        assertEquals(ReviewContent.Nothing, reviewContent(connected = true, view = ExternalView.SavedCopy(none, ExternalProblem.Offline)))
        assertEquals(ReviewContent.Nothing, reviewContent(connected = true, view = ExternalView.Missing(ExternalProblem.NotConnected)))
        assertEquals(ReviewContent.Requests, reviewContent(connected = true, view = ExternalView.Fresh(some, storedOnDevice = true)))
        assertEquals(ReviewContent.Requests, reviewContent(connected = true, view = ExternalView.Refreshing(some)))
        assertEquals(ReviewContent.Requests, reviewContent(connected = true, view = ExternalView.SavedCopy(some, ExternalProblem.Offline)))
        assertEquals(ReviewContent.Problem, reviewContent(connected = true, view = ExternalView.Missing(ExternalProblem.Offline)))
        assertEquals(ReviewContent.Problem, reviewContent(connected = true, view = ExternalView.Missing(ExternalProblem.Unauthorized)))
    }

    @Test
    fun withTheSettingOffEverySectionShowsAsBefore() {
        val sections =
            TodaySections(
                hideEmpty = false,
                tasks = noTasks,
                reminderRows = 0,
                reviewContent = ReviewContent.Nothing,
                meetingsShown = false,
            )
        assertTrue(sections.today)
        assertTrue(sections.reminders)
        assertTrue(sections.review)
        assertTrue(sections.main)
        assertTrue(sections.rail)
        assertFalse(sections.nothing)
    }

    @Test
    fun withTheSettingOnEachNamedSectionShowsOnlyWithSomething() {
        val empty =
            TodaySections(hideEmpty = true, tasks = noTasks, reminderRows = 0, reviewContent = ReviewContent.Nothing, meetingsShown = false)
        assertFalse(empty.today)
        assertFalse(empty.reminders)
        assertFalse(empty.review)
        assertFalse(empty.main)
        assertFalse(empty.rail)
        assertTrue(empty.nothing, "Today says it has nothing instead of an empty page")

        val withToday = noTasks.copy(today = listOf(task))
        val full =
            TodaySections(
                hideEmpty = true,
                tasks = withToday,
                reminderRows = 1,
                reviewContent = ReviewContent.Requests,
                meetingsShown = false,
            )
        assertTrue(full.today)
        assertTrue(full.reminders)
        assertTrue(full.review)
        assertFalse(full.nothing)

        val problem =
            TodaySections(hideEmpty = true, tasks = noTasks, reminderRows = 0, reviewContent = ReviewContent.Problem, meetingsShown = false)
        assertTrue(problem.review, "GitHub could not answer and nothing is saved: say so")
    }

    @Test
    fun anOtherDateGroupKeepsTheMainColumnWhileTodayIsHidden() {
        listOf(
            noTasks.copy(overdue = listOf(task)),
            noTasks.copy(earlier = listOf(task)),
            noTasks.copy(upcoming = listOf(task)),
            noTasks.copy(noDate = listOf(task)),
        ).forEach { tasks ->
            val sections =
                TodaySections(
                    hideEmpty = true,
                    tasks = tasks,
                    reminderRows = 0,
                    reviewContent = ReviewContent.Nothing,
                    meetingsShown = false,
                )
            assertFalse(sections.today)
            assertTrue(sections.main)
            assertFalse(sections.rail)
            assertFalse(sections.nothing)
        }
    }

    @Test
    fun oneRailCardKeepsTheRailWhileTheMainColumnIsHidden() {
        val reminders =
            TodaySections(hideEmpty = true, tasks = noTasks, reminderRows = 2, reviewContent = ReviewContent.Nothing, meetingsShown = false)
        assertFalse(reminders.main)
        assertTrue(reminders.rail)
        assertTrue(reminders.reminders)
        assertFalse(reminders.review)
        assertFalse(reminders.nothing)

        val review =
            TodaySections(
                hideEmpty = true,
                tasks = noTasks,
                reminderRows = 0,
                reviewContent = ReviewContent.Requests,
                meetingsShown = false,
            )
        assertTrue(review.rail)
        assertFalse(review.reminders)
        assertTrue(review.review)
    }

    @Test
    fun theMeetingsCardStaysWithTheSettingOnEvenWithNoMeetingsToday() {
        // D77 with D78: once meetings are set up, the card shows, so "No meetings today" is said, not hidden.
        val meetings =
            TodaySections(hideEmpty = true, tasks = noTasks, reminderRows = 0, reviewContent = ReviewContent.Nothing, meetingsShown = true)
        assertTrue(meetings.meetings)
        assertTrue(meetings.rail)
        assertFalse(meetings.reminders)
        assertFalse(meetings.review)
        assertFalse(meetings.main)
        assertFalse(meetings.nothing, "Today shows the Meetings card instead of saying it has nothing")

        val notSetUp =
            TodaySections(hideEmpty = true, tasks = noTasks, reminderRows = 0, reviewContent = ReviewContent.Nothing, meetingsShown = false)
        assertFalse(notSetUp.rail)
        assertTrue(notSetUp.nothing)
    }

    @Test
    fun theHorizontalLayoutTakesTheMeetingsOutOfTheSideColumn() {
        // D79: the timeline runs above both columns; the side column keeps Reminders and Review requested.
        val all =
            TodaySections(
                hideEmpty = false,
                tasks = noTasks,
                reminderRows = 0,
                reviewContent = ReviewContent.Nothing,
                meetingsShown = true,
                meetingsLayout = MeetingsLayout.Horizontal,
            )
        assertTrue(all.timeline)
        assertFalse(all.meetings, "No Meetings card in the side column")
        assertTrue(all.reminders)
        assertTrue(all.review)
        assertTrue(all.rail)

        val vertical =
            TodaySections(
                hideEmpty = false,
                tasks = noTasks,
                reminderRows = 0,
                reviewContent = ReviewContent.Nothing,
                meetingsShown = true,
                meetingsLayout = MeetingsLayout.Vertical,
            )
        assertTrue(vertical.meetings)
        assertFalse(vertical.timeline)
    }

    @Test
    fun theTimelineStaysWithTheSettingOnEvenWithNothingElse() {
        val timelineOnly =
            TodaySections(
                hideEmpty = true,
                tasks = noTasks,
                reminderRows = 0,
                reviewContent = ReviewContent.Nothing,
                meetingsShown = true,
                meetingsLayout = MeetingsLayout.Horizontal,
            )
        assertTrue(timelineOnly.timeline)
        assertFalse(timelineOnly.main)
        assertFalse(timelineOnly.rail, "The side column has nothing left and is left out")
        assertFalse(timelineOnly.nothing, "Today shows the timeline instead of saying it has nothing")

        val notSetUp =
            TodaySections(
                hideEmpty = true,
                tasks = noTasks,
                reminderRows = 0,
                reviewContent = ReviewContent.Nothing,
                meetingsShown = false,
                meetingsLayout = MeetingsLayout.Horizontal,
            )
        assertFalse(notSetUp.timeline)
        assertTrue(notSetUp.nothing)
    }
}
