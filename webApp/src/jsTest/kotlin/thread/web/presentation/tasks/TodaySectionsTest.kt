package thread.web.presentation.tasks

import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalView
import thread.domain.external.PullRequestListPage
import thread.domain.external.PullRequestRef
import thread.domain.external.PullRequestSummary
import thread.domain.external.Saved
import thread.domain.task.DueGroup
import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.task.dueGroupOf
import thread.domain.task.todayTasks
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.workspace.MeetingsLayout
import thread.domain.workspace.TodayHideableSection
import thread.domain.workspace.TodayHideableSection.Meetings
import thread.domain.workspace.TodayHideableSection.Reminders
import thread.domain.workspace.TodayHideableSection.ReviewRequested
import thread.domain.workspace.WorkspacePrefs
import thread.domain.workspace.todayHiddenWhenEmpty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D77, D86: which Today sections show for each mix of Settings "Hide empty sections" (Meetings, Reminders, Review
 * requested), empty task groups (always hidden, Q2) and the meetings (D78, D79). SYNTHETIC tasks and pull requests.
 */
class TodaySectionsTest {
    private val pull =
        PullRequestSummary(
            ref = PullRequestRef("synthetic-owner", "synthetic-repo", 1),
            title = "Synthetic pull request",
            author = null,
            isDraft = false,
            updatedAtMillis = null,
            webUrl = null,
        )

    /** Every mix of the three checkboxes, none checked first. */
    private val everyMix: List<Set<TodayHideableSection>> =
        (0 until 8).map { bits -> TodayHideableSection.entries.filterIndexed { index, _ -> bits and (1 shl index) != 0 }.toSet() }

    private fun page(vararg items: PullRequestSummary) = Saved(PullRequestListPage(items.toList(), items.size, isIncomplete = false), 0L)

    private fun sections(
        hidden: Set<TodayHideableSection>,
        hasTasks: Boolean = false,
        reminderRows: Int = 0,
        reviewContent: ReviewContent = ReviewContent.Nothing,
        meetingsShown: Boolean = true,
        meetingsContent: Boolean = false,
        layout: MeetingsLayout = MeetingsLayout.Vertical,
    ) = TodaySections(hidden, hasTasks, reminderRows, reviewContent, meetingsShown, meetingsContent, layout)

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
    fun withNothingCheckedEveryCardShowsButAnEmptyTaskGroupDoesNot() {
        val empty = sections(emptySet())
        assertTrue(empty.meetings)
        assertTrue(empty.reminders)
        assertTrue(empty.review)
        assertTrue(empty.rail)
        assertFalse(empty.main, "Q2: no task, no task group, Today's too")
        assertFalse(empty.nothing)
    }

    @Test
    fun eachCheckedSectionHidesOnlyWhileItIsEmpty() {
        assertEquals(8, everyMix.toSet().size)
        everyMix.forEach { hidden ->
            val empty = sections(hidden)
            assertEquals(Meetings !in hidden, empty.meetings, "Meetings with $hidden")
            assertEquals(Reminders !in hidden, empty.reminders, "Reminders with $hidden")
            assertEquals(ReviewRequested !in hidden, empty.review, "Review requested with $hidden")
            assertEquals(hidden.size < 3, empty.rail, "Side column with $hidden")
            assertFalse(empty.main)
            assertEquals(hidden.size == 3, empty.nothing, "Nothing for today with $hidden")

            val full = sections(hidden, reminderRows = 2, reviewContent = ReviewContent.Requests, meetingsContent = true)
            assertTrue(full.meetings && full.reminders && full.review, "Something to show keeps every card with $hidden")
            assertFalse(full.nothing)
        }
    }

    @Test
    fun aCheckedSectionComesBackAloneWhenSomethingArrives() {
        val all = setOf(Meetings, Reminders, ReviewRequested)
        val meeting = sections(all, meetingsContent = true)
        assertEquals(listOf(true, false, false), listOf(meeting.meetings, meeting.reminders, meeting.review))
        assertFalse(meeting.nothing)

        val reminder = sections(all, reminderRows = 1)
        assertEquals(listOf(false, true, false), listOf(reminder.meetings, reminder.reminders, reminder.review))

        val review = sections(all, reviewContent = ReviewContent.Requests)
        assertEquals(listOf(false, false, true), listOf(review.meetings, review.reminders, review.review))

        val problem = sections(all, reviewContent = ReviewContent.Problem)
        assertTrue(problem.review, "GitHub could not answer and nothing is saved: say so")
    }

    @Test
    fun anyTaskGroupKeepsTheMainColumn() {
        val today = LocalDay(2026, 11, 13)
        val noon = WallTime.of(today, 12, 0)
        val oneInEachGroup =
            listOf(
                Task(TaskId("synthetic-done"), "Synthetic done task", done = true, completedOn = today),
                Task(TaskId("synthetic-today"), "Synthetic task for today", plannedDay = today),
                Task(TaskId("synthetic-tomorrow"), "Synthetic task for tomorrow", plannedDay = today.plusDays(1)),
                Task(TaskId("synthetic-undated"), "Synthetic task with no date"),
            )
        assertEquals(DueGroup.entries.toList(), oneInEachGroup.map { dueGroupOf(it, noon) })
        oneInEachGroup.forEach { task ->
            val shown = todayTasks(listOf(task), noon, keepCompleted = true)
            everyMix.forEach { hidden ->
                val withTasks = sections(hidden, hasTasks = shown.isNotEmpty())
                assertTrue(withTasks.main, "${task.title} with $hidden")
                assertFalse(withTasks.nothing, "A task to show with $hidden")
            }
        }
    }

    @Test
    fun meetingsThatAreNotSetUpNeverShowWhateverIsChecked() {
        everyMix.forEach { hidden ->
            listOf(MeetingsLayout.Vertical, MeetingsLayout.Horizontal).forEach { layout ->
                val notSetUp = sections(hidden, meetingsShown = false, meetingsContent = true, layout = layout)
                assertFalse(notSetUp.meetings)
                assertFalse(notSetUp.timeline)
            }
        }
        val onlyMeetingsLeft = sections(setOf(Reminders, ReviewRequested), meetingsShown = false)
        assertFalse(onlyMeetingsLeft.rail)
        assertTrue(onlyMeetingsLeft.nothing)
    }

    @Test
    fun theHorizontalLayoutTakesTheMeetingsOutOfTheSideColumn() {
        // D79: the timeline runs above both columns; the side column keeps Reminders and Review requested.
        val all = sections(emptySet(), layout = MeetingsLayout.Horizontal)
        assertTrue(all.timeline)
        assertFalse(all.meetings, "No Meetings card in the side column")
        assertTrue(all.reminders)
        assertTrue(all.review)
        assertTrue(all.rail)

        val vertical = sections(emptySet(), layout = MeetingsLayout.Vertical)
        assertTrue(vertical.meetings)
        assertFalse(vertical.timeline)
    }

    @Test
    fun theTimelineHidesWhenEmptyAndComesBackLikeTheCard() {
        val all = setOf(Meetings, Reminders, ReviewRequested)
        val empty = sections(all, layout = MeetingsLayout.Horizontal)
        assertFalse(empty.timeline)
        assertTrue(empty.nothing)

        val withMeetings = sections(all, meetingsContent = true, layout = MeetingsLayout.Horizontal)
        assertTrue(withMeetings.timeline)
        assertFalse(withMeetings.rail, "The side column has nothing left and is left out")
        assertFalse(withMeetings.nothing, "Today shows the timeline instead of saying it has nothing")

        val kept = sections(setOf(Reminders, ReviewRequested), layout = MeetingsLayout.Horizontal)
        assertTrue(kept.timeline, "Meetings unchecked: the timeline stays on a day without meetings")
        assertFalse(kept.nothing)
    }

    @Test
    fun theOldSwitchHidesRemindersAndReviewButNotMeetings() {
        // Q2: a workspace that only has the D77 switch reads as Reminders and Review requested checked.
        val hidden = WorkspacePrefs(hideEmptySections = true).todayHiddenWhenEmpty
        val empty = sections(hidden)
        assertTrue(empty.meetings)
        assertFalse(empty.reminders)
        assertFalse(empty.review)
        assertFalse(empty.nothing)
        assertTrue(sections(WorkspacePrefs().todayHiddenWhenEmpty).reminders, "Off by default")
    }
}
