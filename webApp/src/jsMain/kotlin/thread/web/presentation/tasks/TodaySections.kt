package thread.web.presentation.tasks

import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalView
import thread.domain.external.PullRequestListPage
import thread.domain.workspace.MeetingsLayout
import thread.domain.workspace.TodayCard
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
 * hides. [meetingsLayout]: where (D79). [topicsShown]: "Topics shown" picks a category (D89).
 */
internal class TodaySections(
    hidden: Set<TodayHideableSection>,
    hasTasks: Boolean,
    reminderRows: Int,
    reviewContent: ReviewContent,
    meetingsShown: Boolean,
    meetingsContent: Boolean,
    meetingsLayout: MeetingsLayout = MeetingsLayout.Vertical,
    topicsShown: Boolean = true,
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

    /** The cards Today draws now (D95). The layout places them; a place left with none takes no space. */
    val drawn: Set<TodayCard> =
        buildSet {
            if (meetings || timeline) add(TodayCard.Meetings)
            if (main) add(TodayCard.Tasks)
            if (reminders) add(TodayCard.Reminders)
            if (review) add(TodayCard.ReviewRequested)
            if (topicsShown) add(TodayCard.Topics)
        }
}

/**
 * The side column's name for screen readers. With only the cards it held before #29 it keeps its D77 name; another card
 * the layout puts there is named too (D95), in the column's order.
 */
internal fun sideColumnLabel(cards: List<TodayCard>): String {
    if (cards.all { it in RAIL_CARDS }) {
        return if (TodayCard.Meetings in cards) "Meetings, reminders and reviews" else "Reminders and reviews"
    }
    val names = cards.map { CARD_NAMES.getValue(it) }
    val text = if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " and " + names.last()
    return text.replaceFirstChar { it.uppercaseChar() }
}

private val RAIL_CARDS = setOf(TodayCard.Meetings, TodayCard.Reminders, TodayCard.ReviewRequested)

private val CARD_NAMES =
    mapOf(
        TodayCard.Meetings to "meetings",
        TodayCard.Tasks to "tasks",
        TodayCard.Reminders to "reminders",
        TodayCard.ReviewRequested to "reviews",
        TodayCard.Topics to "Topics",
    )
