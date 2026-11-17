package thread.domain.external

import thread.domain.note.LinkHref
import thread.domain.topic.TopicId

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
    /** Who opened the pull request (owner change #13). Null when GitHub sent no account Thread can show. */
    val author: GitHubPerson?,
    val isDraft: Boolean,
    val updatedAtMillis: Long?,
    val webUrl: LinkHref?,
    /**
     * The pull request's GitHub assignees (owner change #8), in GitHub's order. Null when not known: a copy saved
     * before Thread kept them, or a row that did not come from a list.
     */
    val assignees: List<GitHubPerson>? = null,
)

/**
 * A GitHub account: an author, assignee or reviewer. [avatarUrl] is built by Thread from the account id, never taken
 * from an answer, and is null when the id is not known (a copy saved before Thread kept it).
 */
data class GitHubPerson(
    val login: String,
    val avatarUrl: LinkHref?,
) {
    /** GitHub logins ignore case. */
    fun isLogin(other: String?): Boolean = other != null && login.equals(other, ignoreCase = true)
}

/**
 * A list as GitHub's search returned it. [isIncomplete] is GitHub's own flag for a search that timed out. [viewer] is
 * the login of the token's own account, or null when Thread could not read it yet.
 */
data class PullRequestListPage(
    val items: List<PullRequestSummary>,
    val totalCount: Int,
    val isIncomplete: Boolean,
    val viewer: String? = null,
)

/** One repository's pull requests in a list (owner change #8). [repo] is `owner/name`. */
data class RepositoryPullRequests(
    val repo: String,
    val pulls: List<PullRequestSummary>,
)

/**
 * Groups [pulls] by repository (owner change #8). The repository with the most recent update comes first; inside a
 * group, the newest update first. A pull request without an update time goes last. Repository names ignore case, as
 * on GitHub; a group shows the name as its first pull request has it.
 */
fun pullRequestsByRepository(pulls: List<PullRequestSummary>): List<RepositoryPullRequests> =
    pulls
        .groupBy { it.repoKey() }
        .values
        .map { group -> group.sortedWith(NEWEST_FIRST) }
        .sortedWith(newestGroupFirst().thenBy { it.first().repoKey() })
        .map { group -> RepositoryPullRequests("${group.first().ref.owner}/${group.first().ref.repo}", group) }

/** The newest update first; a pull request without an update time last. Ties go by number, the higher first. */
internal val NEWEST_FIRST: Comparator<PullRequestSummary> =
    compareByDescending<PullRequestSummary, Long?>(nullsFirst()) { it.updatedAtMillis }
        .thenByDescending { it.ref.number }

/** Groups, each already [NEWEST_FIRST], by their newest pull request. */
internal fun newestGroupFirst(): Comparator<List<PullRequestSummary>> =
    compareBy<List<PullRequestSummary>, PullRequestSummary>(NEWEST_FIRST) { it.first() }

private fun PullRequestSummary.repoKey(): String = "${ref.owner}/${ref.repo}".lowercase()

/**
 * A pull request GitHub search found for Topic suggestions (D49 S5). [keys] are the searched Jira keys its title or
 * description names, as Thread read them itself. [closed] is true for a closed or merged pull request.
 */
data class PullRequestMention(
    val summary: PullRequestSummary,
    val closed: Boolean,
    val keys: List<JiraIssueKey>,
)

/** The pull requests in [repos] (`owner/repo`) that mention [keys]. Kept on this device per Topic; never synced. */
data class PullRequestMentions(
    val repos: List<String>,
    val keys: List<JiraIssueKey>,
    val pulls: List<PullRequestMention>,
)

/** GitHub search for [PullRequestMentions], and the last answer saved on this device per Topic. */
interface PullRequestMentionSource {
    /** The answer saved for [topicId], or null. */
    suspend fun readSaved(topicId: TopicId): Saved<PullRequestMentions>?

    /** Asks GitHub which pull requests in [repos] mention [keys], and saves the answer for [topicId]. */
    suspend fun refresh(
        topicId: TopicId,
        repos: List<String>,
        keys: List<JiraIssueKey>,
        nowMillis: Long,
    ): ExternalResult<Refreshed<PullRequestMentions>>
}

/** Pull requests: the provider's API and the copies saved on this device. */
interface PullRequestSource : ExternalRecordSource<PullRequestRef, PullRequestDetail> {
    /** Every pull request saved on this device, newest save first. Empty when GitHub is not connected. */
    suspend fun allSaved(): List<Saved<PullRequestDetail>>
}

data class PullRequestDetail(
    val ref: PullRequestRef,
    val title: String,
    val author: GitHubPerson?,
    val isDraft: Boolean,
    val headBranch: String,
    val baseBranch: String,
    /** The description as GitHub sent it: Markdown source. Used to find Jira keys (D49); the screen shows [description]. */
    val body: String,
    /** [body] parsed from GitHub Markdown into safe blocks (ADR-007, D57). Empty when there is no description. */
    val description: ExternalDocument,
    val updatedAtMillis: Long?,
    val webUrl: LinkHref?,
    val reviewers: List<Reviewer>,
    val review: ReviewState,
    val ci: CiSummary,
    /** The GitHub assignees (owner change #13). Null in a copy saved before Thread kept them. */
    val assignees: List<GitHubPerson>? = null,
    /** Commits and changed files and lines (owner change #13). Null in a copy saved before Thread kept them. */
    val changes: PullRequestChanges? = null,
)

/** What a pull request changes, as GitHub counts it for the whole pull request. */
data class PullRequestChanges(
    val commits: Int,
    val changedFiles: Int,
    val additions: Int,
    val deletions: Int,
)

/** The list row of a saved pull request. */
fun PullRequestDetail.summary(): PullRequestSummary = PullRequestSummary(ref, title, author, isDraft, updatedAtMillis, webUrl, assignees)

/**
 * A reviewer: a person, or a team whose review is requested ([isTeam]). [avatarUrl] is the person's, when Thread knows
 * the account id; a team has none.
 */
data class Reviewer(
    val name: String,
    val state: ReviewerState,
    val avatarUrl: LinkHref? = null,
    val isTeam: Boolean = false,
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
     * undo it. A person whose review is requested again shows as [ReviewerState.Requested]. [requestedTeams] are
     * listed by name after the people, marked as teams.
     */
    fun reviewers(
        requested: List<String>,
        reviews: List<SubmittedReview>,
        requestedTeams: List<String> = emptyList(),
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
        val people = states.map { (name, state) -> Reviewer(name, state) }
        val teams = requestedTeams.filter { it !in states }.distinct().map { Reviewer(it, ReviewerState.Requested, isTeam = true) }
        return people + teams
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
