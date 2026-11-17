package thread.domain.external

import thread.domain.topic.Topic

// The groups a pull request list can show besides repositories (owner change #25, D93): Mine by Topic, Review requested
// by author, either by review status. Every grouping keeps the order of [pullRequestsByRepository]: inside a group the
// newest update first, and groups by their newest pull request. The groups for the rest come last. None asks GitHub
// for anything: Topics are the workspace's links, and review status is what this device saved (Q4).

/** What one group of a pull request list holds. */
sealed interface PullRequestGroupKey {
    /** One repository, `owner/name` as its newest pull request spells it. */
    data class Repository(
        val repo: String,
    ) : PullRequestGroupKey

    /** The pull requests linked to this Topic and no other. */
    data class OneTopic(
        val topic: Topic,
    ) : PullRequestGroupKey

    /** The pull requests linked to two Topics or more, each shown once (Q5). */
    data object SeveralTopics : PullRequestGroupKey

    /** The pull requests linked to no Topic. */
    data object NoTopic : PullRequestGroupKey

    /** One author, as their newest pull request has the account. */
    data class Author(
        val person: GitHubPerson,
    ) : PullRequestGroupKey

    /** The pull requests GitHub sent without an account Thread can show. */
    data object NoAuthor : PullRequestGroupKey

    /** One review state, from the copy this device saved when the pull request was opened (Q4). */
    data class Review(
        val state: ReviewState,
    ) : PullRequestGroupKey

    /** The pull requests with no saved copy on this device, so no review state (Q4). */
    data object NotLoaded : PullRequestGroupKey
}

/** One group of a pull request list, its pull requests newest first. Never empty. */
data class PullRequestGroup(
    val key: PullRequestGroupKey,
    val pulls: List<PullRequestSummary>,
)

/** [pullRequestsByRepository] as [PullRequestGroup]s, so a list shows every grouping the same way. */
fun pullRequestGroupsByRepository(pulls: List<PullRequestSummary>): List<PullRequestGroup> =
    pullRequestsByRepository(pulls).map { PullRequestGroup(PullRequestGroupKey.Repository(it.repo), it.pulls) }

/**
 * [pulls] by their linked Topics (R1, Q5). A pull request linked to one Topic goes under it; one linked to two or more
 * goes once under Several Topics, after the Topics; one linked to none goes under No Topic, last. Topic groups come by
 * their newest pull request, then by name. [topicsOf] gives the Topics a pull request is linked to.
 */
fun pullRequestsByTopic(
    pulls: List<PullRequestSummary>,
    topicsOf: (PullRequestRef) -> List<Topic>,
): List<PullRequestGroup> {
    val linked = pulls.map { pull -> pull to topicsOf(pull.ref).distinctBy { it.id } }
    val oneTopic =
        linked
            .filter { (_, topics) -> topics.size == 1 }
            .groupBy({ (_, topics) -> topics.single().id }, { (pull, topics) -> topics.single() to pull })
            .values
            .map { group -> group.first().first to group.map { it.second }.sortedWith(NEWEST_FIRST) }
            .sortedWith(
                compareBy<Pair<Topic, List<PullRequestSummary>>, List<PullRequestSummary>>(newestGroupFirst()) { it.second }
                    .thenBy { it.first.name.lowercase() }
                    .thenBy { it.first.id.value },
            ).map { (topic, group) -> PullRequestGroup(PullRequestGroupKey.OneTopic(topic), group) }
    val several = linked.filter { (_, topics) -> topics.size > 1 }.map { it.first }
    val none = linked.filter { (_, topics) -> topics.isEmpty() }.map { it.first }
    return oneTopic + groupOrNone(PullRequestGroupKey.SeveralTopics, several) + groupOrNone(PullRequestGroupKey.NoTopic, none)
}

/**
 * [pulls] by author (R2). Logins ignore case, as on GitHub; a group has the account as its newest pull request has
 * it. Authors come by their newest pull request, then by login. A pull request without an author goes last.
 */
fun pullRequestsByAuthor(pulls: List<PullRequestSummary>): List<PullRequestGroup> {
    val people =
        pulls
            .mapNotNull { pull -> pull.author?.let { it.login.lowercase() to pull } }
            .groupBy({ it.first }, { it.second })
            .map { (login, group) -> login to group.sortedWith(NEWEST_FIRST) }
            .sortedWith(
                compareBy<Pair<String, List<PullRequestSummary>>, List<PullRequestSummary>>(newestGroupFirst()) { it.second }
                    .thenBy { it.first },
            ).mapNotNull { (_, group) -> group.first().author?.let { PullRequestGroup(PullRequestGroupKey.Author(it), group) } }
    return people + groupOrNone(PullRequestGroupKey.NoAuthor, pulls.filter { it.author == null })
}

/**
 * [pulls] by review status (R1, R2, Q4), in [ReviewState] order: Changes requested, Approved, Review requested, No
 * reviews yet. [reviewOf] gives the state this device saved for a pull request, or null when it has no saved copy:
 * those go under Not loaded yet, last. Nothing is loaded for this.
 */
fun pullRequestsByReview(
    pulls: List<PullRequestSummary>,
    reviewOf: (PullRequestRef) -> ReviewState?,
): List<PullRequestGroup> {
    val byState = pulls.groupBy { reviewOf(it.ref) }
    val states = ReviewState.entries.flatMap { state -> groupOrNone(PullRequestGroupKey.Review(state), byState[state].orEmpty()) }
    return states + groupOrNone(PullRequestGroupKey.NotLoaded, byState[null].orEmpty())
}

/** [pulls] as one group, newest first, or no group when there are none. */
private fun groupOrNone(
    key: PullRequestGroupKey,
    pulls: List<PullRequestSummary>,
): List<PullRequestGroup> = if (pulls.isEmpty()) emptyList() else listOf(PullRequestGroup(key, pulls.sortedWith(NEWEST_FIRST)))
