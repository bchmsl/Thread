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
import thread.domain.workspace.TodayCard
import thread.domain.workspace.TodayHideableSection
import thread.domain.workspace.TodayHideableSection.Meetings
import thread.domain.workspace.TodayHideableSection.Reminders
import thread.domain.workspace.TodayHideableSection.ReviewRequested
import thread.domain.workspace.TodayHideableSection.Tasks
import thread.domain.workspace.TodayHideableSection.Topics
import thread.domain.workspace.WorkspacePrefs
import thread.domain.workspace.todayHiddenWhenEmpty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D77, D86, D101: which Today sections show for each mix of the "Hide when empty" choices (Meetings, Tasks, Reminders,
 * Review requested, Topics), "Topics shown" (D89) and the meetings (D78, D79). SYNTHETIC tasks and pull requests.
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

    /** Every mix of the five checkboxes, none checked first. */
    private val everyMix: List<Set<TodayHideableSection>> =
        (0 until 32).map { bits -> TodayHideableSection.entries.filterIndexed { index, _ -> bits and (1 shl index) != 0 }.toSet() }

    /** The side column's cards. */
    private val railCards = setOf(Meetings, Reminders, ReviewRequested)

    /** What "Nothing for today" stands for: Tasks and the side column, all hidden. */
    private val nothingCards = railCards + Tasks

    private fun page(vararg items: PullRequestSummary) = Saved(PullRequestListPage(items.toList(), items.size, isIncomplete = false), 0L)

    private fun sections(
        hidden: Set<TodayHideableSection>,
        hasTasks: Boolean = false,
        reminderRows: Int = 0,
        reviewContent: ReviewContent = ReviewContent.Nothing,
        meetingsShown: Boolean = true,
        meetingsContent: Boolean = false,
        layout: MeetingsLayout = MeetingsLayout.Vertical,
        topicsShown: Boolean = true,
        topicsContent: Boolean = false,
    ) = TodaySections(hidden, hasTasks, reminderRows, reviewContent, meetingsShown, meetingsContent, layout, topicsShown, topicsContent)

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
    fun withNothingCheckedEveryCardShowsEvenEmpty() {
        val empty = sections(emptySet())
        assertTrue(empty.meetings)
        assertTrue(empty.reminders)
        assertTrue(empty.review)
        assertTrue(empty.rail)
        assertTrue(empty.main, "D101: Tasks with its hide choice off says it has no task")
        assertEquals(TodayCard.entries.toSet(), empty.drawn, "Topics with no Topic shows its empty state")
        assertFalse(empty.nothing)
    }

    @Test
    fun byDefaultOnlyTasksHidesWhenEmpty() {
        // D101: the look before D101 is kept until the owner changes a choice.
        val defaults = WorkspacePrefs().todayHiddenWhenEmpty
        assertEquals(setOf(Tasks), defaults)
        val empty = sections(defaults)
        assertFalse(empty.main, "Q2: no task, no Tasks card")
        assertEquals(setOf(TodayCard.Meetings, TodayCard.Reminders, TodayCard.ReviewRequested, TodayCard.Topics), empty.drawn)
        assertTrue(sections(defaults, hasTasks = true).main)
        assertTrue(TodayCard.Topics in sections(defaults, topicsContent = true).drawn)
    }

    @Test
    fun eachCheckedSectionHidesOnlyWhileItIsEmpty() {
        assertEquals(32, everyMix.toSet().size)
        everyMix.forEach { hidden ->
            val empty = sections(hidden)
            assertEquals(Meetings !in hidden, empty.meetings, "Meetings with $hidden")
            assertEquals(Tasks !in hidden, empty.main, "Tasks with $hidden")
            assertEquals(Reminders !in hidden, empty.reminders, "Reminders with $hidden")
            assertEquals(ReviewRequested !in hidden, empty.review, "Review requested with $hidden")
            assertEquals(Topics !in hidden, TodayCard.Topics in empty.drawn, "Topics with $hidden")
            assertEquals(railCards.any { it !in hidden }, empty.rail, "Side column with $hidden")
            assertEquals(hidden.containsAll(nothingCards), empty.nothing, "Nothing for today with $hidden")

            val full =
                sections(
                    hidden,
                    hasTasks = true,
                    reminderRows = 2,
                    reviewContent = ReviewContent.Requests,
                    meetingsContent = true,
                    topicsContent = true,
                )
            assertEquals(TodayCard.entries.toSet(), full.drawn, "Something to show keeps every card with $hidden")
            assertFalse(full.nothing)
        }
    }

    @Test
    fun aCheckedSectionComesBackAloneWhenSomethingArrives() {
        val all = TodayHideableSection.entries.toSet()
        assertEquals(emptySet(), sections(all).drawn)

        val meeting = sections(all, meetingsContent = true)
        assertEquals(listOf(true, false, false), listOf(meeting.meetings, meeting.reminders, meeting.review))
        assertFalse(meeting.nothing)

        val reminder = sections(all, reminderRows = 1)
        assertEquals(listOf(false, true, false), listOf(reminder.meetings, reminder.reminders, reminder.review))

        val review = sections(all, reviewContent = ReviewContent.Requests)
        assertEquals(listOf(false, false, true), listOf(review.meetings, review.reminders, review.review))

        val problem = sections(all, reviewContent = ReviewContent.Problem)
        assertTrue(problem.review, "GitHub could not answer and nothing is saved: say so")

        val task = sections(all, hasTasks = true)
        assertEquals(setOf(TodayCard.Tasks), task.drawn)
        assertFalse(task.nothing)

        val topic = sections(all, topicsContent = true)
        assertEquals(setOf(TodayCard.Topics), topic.drawn, "A Topic in a picked category brings the card back")
        assertTrue(topic.nothing, "Nothing for today, Topics aside")
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
        val onlyMeetingsLeft = sections(setOf(Tasks, Reminders, ReviewRequested), meetingsShown = false)
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
        val all = nothingCards
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
        assertFalse(empty.main, "D101: Tasks still hides when empty")
        assertFalse(empty.nothing)
        assertTrue(sections(WorkspacePrefs().todayHiddenWhenEmpty).reminders, "Off by default")
    }

    @Test
    fun theDrawnCardsAreTheSectionsThatShow() {
        // D95: the layout places these; Meetings is one card for the side card and the timeline alike.
        val defaults = setOf(Tasks)
        assertEquals(
            setOf(TodayCard.Meetings, TodayCard.Reminders, TodayCard.ReviewRequested, TodayCard.Topics),
            sections(defaults).drawn,
        )
        assertEquals(
            setOf(TodayCard.Meetings, TodayCard.Tasks, TodayCard.Reminders, TodayCard.ReviewRequested, TodayCard.Topics),
            sections(defaults, hasTasks = true, layout = MeetingsLayout.Horizontal).drawn,
        )
        val allHidden = nothingCards
        assertEquals(setOf(TodayCard.Topics), sections(allHidden).drawn, "Nothing for today, Topics aside")
        assertEquals(setOf(TodayCard.Tasks), sections(allHidden, hasTasks = true, meetingsShown = false).drawn - TodayCard.Topics)
        val noTopics = sections(allHidden, topicsShown = false, topicsContent = true)
        assertEquals(emptySet(), noTopics.drawn, "Topics shown picks no category (D89)")
        assertTrue(noTopics.nothing)
        assertEquals(emptySet(), sections(allHidden, topicsShown = false).drawn, "Not set up, whatever Hide when empty says")
        assertEquals(emptySet(), sections(allHidden + Topics).drawn, "Topics hides while no Topic is in a picked category")
    }

    @Test
    fun theSideColumnKeepsItsNameAndNamesOtherCards() {
        val before29 = listOf(TodayCard.Meetings, TodayCard.Reminders, TodayCard.ReviewRequested)
        assertEquals("Meetings, reminders and reviews", sideColumnLabel(before29))
        assertEquals("Meetings, reminders and reviews", sideColumnLabel(listOf(TodayCard.Meetings)))
        assertEquals("Reminders and reviews", sideColumnLabel(listOf(TodayCard.Reminders, TodayCard.ReviewRequested)))
        assertEquals("Reminders and reviews", sideColumnLabel(listOf(TodayCard.ReviewRequested)))
        assertEquals("Tasks", sideColumnLabel(listOf(TodayCard.Tasks)))
        assertEquals("Topics and meetings", sideColumnLabel(listOf(TodayCard.Topics, TodayCard.Meetings)))
        assertEquals(
            "Reviews, tasks, reminders and Topics",
            sideColumnLabel(listOf(TodayCard.ReviewRequested, TodayCard.Tasks, TodayCard.Reminders, TodayCard.Topics)),
        )
    }

    @Test
    fun editModeSaysWhyACardIsNotShown() {
        val all = TodayHideableSection.entries.toSet()
        val empty = sections(all, topicsShown = false)
        assertEquals(NotShown.Empty, empty.notShown(TodayCard.Meetings))
        assertEquals(NotShown.Empty, empty.notShown(TodayCard.Reminders))
        assertEquals(NotShown.Empty, empty.notShown(TodayCard.ReviewRequested))
        assertEquals(NotShown.Empty, empty.notShown(TodayCard.Tasks), "D101: Tasks hides because it is empty")
        assertEquals(NotShown.NoTopicsPicked, empty.notShown(TodayCard.Topics))
        assertEquals(NotShown.Empty, sections(all).notShown(TodayCard.Topics), "Picked, but no Topic is in those categories")
        assertNull(sections(all - Tasks).notShown(TodayCard.Tasks), "Hide when empty off: drawn with no task")
        assertNull(sections(all - Topics).notShown(TodayCard.Topics), "Hide when empty off: drawn with no Topic")

        val notSetUp = TodaySections(emptySet(), true, 0, ReviewContent.Nothing, meetingsShown = false, meetingsContent = false)
        assertEquals(NotShown.NotSetUp, notSetUp.notShown(TodayCard.Meetings), "Not set up hides it whatever Hide when empty says")
        (TodayCard.entries - TodayCard.Meetings).forEach { assertNull(notSetUp.notShown(it), "$it is drawn") }

        val horizontal = sections(all, meetingsContent = true, layout = MeetingsLayout.Horizontal)
        assertNull(horizontal.notShown(TodayCard.Meetings), "The timeline is the Meetings card")
        assertEquals(
            listOf("Not shown now: empty", "Not shown: meetings not set up", "Not shown: no Topics picked"),
            NotShown.entries.map { it.text },
        )
    }
}
