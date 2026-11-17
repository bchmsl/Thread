package thread.web.presentation.tasks

import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalView
import thread.domain.external.PullRequestListPage
import thread.domain.task.TodayTasks
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
 * Which of Today's sections show (D77). Overdue, Earlier planned, Upcoming and No date never show empty (D53). With
 * "Hide empty sections in Today" on, Today, Reminders and Review requested do the same, and each comes back as soon as
 * something arrives. The rule names these sections only: Active and paused Topics always shows, and so does the
 * Meetings card once meetings are set up (D78), even on a day without meetings.
 *
 * [reminderRows]: today's reminders that have a row to show. [meetings]: the Meetings card shows
 * ([thread.web.presentation.calendar.MeetingsModel.cardShown]).
 */
internal class TodaySections(
    hideEmpty: Boolean,
    tasks: TodayTasks,
    reminderRows: Int,
    reviewContent: ReviewContent,
    val meetings: Boolean,
) {
    val today: Boolean = !hideEmpty || tasks.today.isNotEmpty()
    val reminders: Boolean = !hideEmpty || reminderRows > 0
    val review: Boolean = !hideEmpty || reviewContent != ReviewContent.Nothing

    /** The main column has a group to show. */
    val main: Boolean =
        today || listOf(tasks.overdue, tasks.earlier, tasks.upcoming, tasks.noDate).any { it.isNotEmpty() }

    /** The side column has a card to show. */
    val rail: Boolean = meetings || reminders || review

    /** Every section the rule names is empty and hidden, so Today says so in their place. */
    val nothing: Boolean = !main && !rail
}
