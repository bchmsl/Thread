package thread.domain.external

import thread.domain.external.ReviewerState.Approved
import thread.domain.external.ReviewerState.ChangesRequested
import thread.domain.external.ReviewerState.Commented
import thread.domain.external.ReviewerState.Dismissed
import thread.domain.external.ReviewerState.Requested
import kotlin.test.Test
import kotlin.test.assertEquals

/** SYNTHETIC logins only. */
class PullRequestStatesTest {
    @Test
    fun aLaterCommentDoesNotUndoAnApproval() {
        val reviewers =
            PullRequestStates.reviewers(
                requested = emptyList(),
                reviews = listOf(SubmittedReview("synthetic-a", Approved), SubmittedReview("synthetic-a", Commented)),
            )

        assertEquals(listOf(Reviewer("synthetic-a", Approved)), reviewers)
    }

    @Test
    fun theNewestDecisionCounts() {
        val reviewers =
            PullRequestStates.reviewers(
                requested = emptyList(),
                reviews =
                    listOf(
                        SubmittedReview("synthetic-a", Commented),
                        SubmittedReview("synthetic-a", ChangesRequested),
                        SubmittedReview("synthetic-a", Approved),
                        SubmittedReview("synthetic-b", Approved),
                        SubmittedReview("synthetic-b", Dismissed),
                    ),
            )

        assertEquals(listOf(Reviewer("synthetic-a", Approved), Reviewer("synthetic-b", Dismissed)), reviewers)
    }

    @Test
    fun aReviewRequestedAgainShowsAsRequested() {
        val reviewers =
            PullRequestStates.reviewers(
                requested = listOf("synthetic-a", "synthetic-team"),
                reviews = listOf(SubmittedReview("synthetic-a", ChangesRequested)),
            )

        assertEquals(listOf(Reviewer("synthetic-a", Requested), Reviewer("synthetic-team", Requested)), reviewers)
    }

    @Test
    fun aRequestedTeamComesAfterThePeopleOnceAndIsMarkedAsATeam() {
        val reviewers =
            PullRequestStates.reviewers(
                requested = listOf("synthetic-b"),
                reviews = listOf(SubmittedReview("synthetic-a", Approved)),
                requestedTeams = listOf("synthetic-team", "synthetic-team", "synthetic-a"),
            )

        assertEquals(
            listOf(
                Reviewer("synthetic-a", Approved),
                Reviewer("synthetic-b", Requested),
                Reviewer("synthetic-team", Requested, isTeam = true),
            ),
            reviewers,
            "A team named like a reviewer is not listed twice",
        )
    }

    @Test
    fun reviewStateOrder() {
        fun state(vararg states: ReviewerState) = PullRequestStates.reviewState(states.mapIndexed { i, s -> Reviewer("synthetic-$i", s) })

        assertEquals(ReviewState.ChangesRequested, state(Approved, ChangesRequested, Requested))
        assertEquals(ReviewState.Approved, state(Approved, Requested, Commented))
        assertEquals(ReviewState.ReviewRequested, state(Requested, Commented))
        assertEquals(ReviewState.NoReviews, state(Commented, Dismissed))
        assertEquals(ReviewState.NoReviews, state())
    }

    @Test
    fun ciFailureWinsThenRunningThenSuccess() {
        fun ci(vararg outcomes: CheckOutcome): CiState {
            val summary = PullRequestStates.ci(outcomes.toList(), statusesReadable = true, checksReadable = true)
            return summary.state
        }

        assertEquals(CiState.Failing, ci(CheckOutcome.Success, CheckOutcome.Pending, CheckOutcome.Failure))
        assertEquals(CiState.Running, ci(CheckOutcome.Success, CheckOutcome.Pending, CheckOutcome.Neutral))
        assertEquals(CiState.Passing, ci(CheckOutcome.Success, CheckOutcome.Neutral))
        assertEquals(CiState.None, ci(CheckOutcome.Neutral))
        assertEquals(CiState.None, ci())
    }

    @Test
    fun ciKeepsWhatTheTokenCouldRead() {
        val ci = PullRequestStates.ci(listOf(CheckOutcome.Success), statusesReadable = true, checksReadable = false)

        assertEquals(CiSummary(CiState.Passing, statusesReadable = true, checksReadable = false), ci)
    }
}
