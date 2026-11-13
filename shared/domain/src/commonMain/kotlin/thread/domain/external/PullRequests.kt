package thread.domain.external

import thread.domain.note.LinkHref

/** One pull request: `owner/repo#number`. */
data class PullRequestRef(
    val owner: String,
    val repo: String,
    val number: Int,
) {
    init {
        require(NAME.matches(owner) && NAME.matches(repo)) { "Not a GitHub owner or repository name" }
        require(number > 0) { "A pull request number is positive" }
    }

    val id: String get() = "$owner/$repo#$number"

    override fun toString(): String = id

    companion object {
        private val NAME = Regex("^[A-Za-z0-9_.-]{1,100}$")

        /** Parses [id] in the `owner/repo#number` form, or returns null. */
        fun parse(id: String): PullRequestRef? {
            val match = Regex("^([^/#]+)/([^/#]+)#([0-9]{1,9})$").matchEntire(id) ?: return null
            val (owner, repo, number) = match.destructured
            if (!NAME.matches(owner) || !NAME.matches(repo)) return null
            return number.toIntOrNull()?.takeIf { it > 0 }?.let { PullRequestRef(owner, repo, it) }
        }
    }
}

/** The two v3 pull request lists. */
enum class PullRequestList {
    Mine,
    ReviewRequested,
}

data class PullRequestSummary(
    val ref: PullRequestRef,
    val title: String,
    val author: String?,
    val isDraft: Boolean,
    val updatedAtMillis: Long?,
    val webUrl: LinkHref?,
)

/** A list as GitHub's search returned it. [isIncomplete] is GitHub's own flag for a search that timed out. */
data class PullRequestListPage(
    val items: List<PullRequestSummary>,
    val totalCount: Int,
    val isIncomplete: Boolean,
)

data class PullRequestDetail(
    val ref: PullRequestRef,
    val title: String,
    val author: String?,
    val isDraft: Boolean,
    val headBranch: String,
    val baseBranch: String,
    /** The description as plain text. Thread does not render GitHub Markdown. */
    val body: String,
    val updatedAtMillis: Long?,
    val webUrl: LinkHref?,
    val reviewers: List<Reviewer>,
    val review: ReviewState,
    val ci: CiSummary,
)

data class Reviewer(
    val name: String,
    val state: ReviewerState,
)

enum class ReviewerState {
    Requested,
    Approved,
    ChangesRequested,
    Commented,
    Dismissed,
}

/** The pull request's review state, as the v3 review indicator shows it. */
enum class ReviewState {
    ChangesRequested,
    Approved,
    ReviewRequested,
    NoReviews,
}

/** A submitted review, in the order GitHub returns them (oldest first). */
data class SubmittedReview(
    val login: String,
    val state: ReviewerState,
)

/** The result of one commit status or one check run. */
enum class CheckOutcome {
    Success,
    Failure,
    Pending,
    Neutral,
}

/**
 * The CI state of the head commit. [statusesReadable] and [checksReadable] say whether the token could read commit
 * statuses and check runs. When one of them is not readable, the state comes from the other one only.
 */
data class CiSummary(
    val state: CiState,
    val statusesReadable: Boolean,
    val checksReadable: Boolean,
)

enum class CiState {
    Failing,
    Running,
    Passing,
    None,
}

/** How the v3 indicators are worked out from what GitHub returns. Pure rules, no I/O. */
object PullRequestStates {
    /**
     * One entry per person. A person's newest approval, change request or dismissal counts; a later comment does not
     * undo it. A person whose review is requested again shows as [ReviewerState.Requested]. Requested teams are
     * listed by name.
     */
    fun reviewers(
        requested: List<String>,
        reviews: List<SubmittedReview>,
    ): List<Reviewer> {
        val states = LinkedHashMap<String, ReviewerState>()
        for (review in reviews) {
            val current = states[review.login]
            val decisive = review.state != ReviewerState.Commented && review.state != ReviewerState.Requested
            if (decisive || current == null || current == ReviewerState.Commented) {
                if (review.state != ReviewerState.Requested) states[review.login] = review.state
            }
        }
        for (name in requested) states[name] = ReviewerState.Requested
        return states.map { (name, state) -> Reviewer(name, state) }
    }

    fun reviewState(reviewers: List<Reviewer>): ReviewState =
        when {
            reviewers.any { it.state == ReviewerState.ChangesRequested } -> ReviewState.ChangesRequested
            reviewers.any { it.state == ReviewerState.Approved } -> ReviewState.Approved
            reviewers.any { it.state == ReviewerState.Requested } -> ReviewState.ReviewRequested
            else -> ReviewState.NoReviews
        }

    /** Any failure wins, then anything still running, then success. Nothing at all is [CiState.None]. */
    fun ci(
        outcomes: List<CheckOutcome>,
        statusesReadable: Boolean,
        checksReadable: Boolean,
    ): CiSummary {
        val state =
            when {
                CheckOutcome.Failure in outcomes -> CiState.Failing
                CheckOutcome.Pending in outcomes -> CiState.Running
                CheckOutcome.Success in outcomes -> CiState.Passing
                else -> CiState.None
            }
        return CiSummary(state, statusesReadable, checksReadable)
    }
}
