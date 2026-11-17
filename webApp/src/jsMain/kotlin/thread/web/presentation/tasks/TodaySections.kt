package thread.web.presentation.tasks

import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalView
import thread.domain.external.PullRequestListPage
import thread.domain.workspace.MeetingsLayout
import thread.domain.workspace.TodayHideableSection
import thread.web.presentation.external.savedOrNull

/** What Today's Review requested card has to show (D77). */
internal enum class ReviewContent {
    /** Nothing to act on: GitHub not checked yet or not connected, the first answer still on its way, or no requests. */
    Nothing,

    /** Nothing saved on this device and GitHub could not answer: offline, a refused token, a rate limit. */
    Problem,

    /** At least one pull request asks for a review. */
    Requests,
}

/** [connected]: GitHub is checked and connected on this device. [view]: the Review requested list, when it is asked. */
internal fun reviewContent(
    connected: Boolean,
    view: ExternalView<PullRequestListPage>?,
): ReviewContent {
    if (!connected || view == null) return ReviewContent.Nothing
    val items = view.savedOrNull?.value?.items
    return when {
        items != null -> if (items.isEmpty()) ReviewContent.Nothing else ReviewContent.Requests
        // A connection cleared on another tab counts as not connected, like the check above.
        view is ExternalView.Missing -> if (view.problem == ExternalProblem.NotConnected) ReviewContent.Nothing else ReviewContent.Problem
        else -> ReviewContent.Nothing
    }
}

/**
 * Which of Today's sections show (D77, D86). A task group never shows empty (D53; Today too since D86, Q2): [hasTasks]
 * says whether Today has a Task to show in any group (D90). Settings "Hide empty sections" names Meetings, Reminders
 * and Review requested ([hidden], from [thread.domain.workspace.todayHiddenWhenEmpty]): each one named there shows
 * only while it has something, and comes back as soon as something arrives. The Topics card is not one of these: it
 * shows unless "Topics shown" picks no category (D89).
 *
 * [reminderRows]: today's reminders that have a row to show. [meetingsShown]: the meetings are set up
 * ([thread.web.presentation.calendar.MeetingsModel.cardShown]). [meetingsContent]: they have something to show
 * ([thread.web.presentation.calendar.TodayMeetingsView.hasContent]): a meeting left today, or a problem that never
 * hides. [meetingsLayout]: where (D79).
 */
internal class TodaySections(
    hidden: Set<TodayHideableSection>,
    hasTasks: Boolean,
    reminderRows: Int,
    reviewContent: ReviewContent,
    meetingsShown: Boolean,
    meetingsContent: Boolean,
    meetingsLayout: MeetingsLayout = MeetingsLayout.Vertical,
) {
    private val meetingsAny = meetingsShown && (TodayHideableSection.Meetings !in hidden || meetingsContent)

    /** The Meetings card leads the side column (Vertical). */
    val meetings: Boolean = meetingsAny && meetingsLayout == MeetingsLayout.Vertical

    /** The meetings timeline runs across the page above both columns (Horizontal). */
    val timeline: Boolean = meetingsAny && meetingsLayout == MeetingsLayout.Horizontal

    val reminders: Boolean = TodayHideableSection.Reminders !in hidden || reminderRows > 0
    val review: Boolean = TodayHideableSection.ReviewRequested !in hidden || reviewContent != ReviewContent.Nothing

    /** The main column has a task group to show. */
    val main: Boolean = hasTasks

    /** The side column has a card to show. */
    val rail: Boolean = meetings || reminders || review

    /** Every section the rule names is empty and hidden, so Today says so in their place. */
    val nothing: Boolean = !main && !rail && !timeline
}
