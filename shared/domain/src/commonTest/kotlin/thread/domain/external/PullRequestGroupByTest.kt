package thread.domain.external

import thread.domain.topic.StatusId
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Owner change #25 (D93): the pull request lists grouped by Topic (R1, Q5), author (R2) or review status from saved
 * copies (Q4). SYNTHETIC repositories, logins and Topics only.
 */
class PullRequestGroupByTest {
    private val status = StatusId("s-synthetic")
    private val alpha = Topic(TopicId("t-alpha"), "Synthetic Alpha", TopicColor.Green, status)
    private val beta = Topic(TopicId("t-beta"), "Synthetic Beta", TopicColor.Blue, status)
    private val gamma = Topic(TopicId("t-gamma"), "synthetic gamma", TopicColor.Orange, status)

    private fun pull(
        id: String,
        updatedAtMillis: Long?,
        author: String? = "synthetic-me",
    ) = PullRequestSummary(
        requireNotNull(PullRequestRef.parse(id)),
        "SYNTHETIC $id",
        author?.let { GitHubPerson(it, null) },
        false,
        updatedAtMillis,
        null,
    )

    private fun List<PullRequestGroup>.shape() = map { group -> group.key to group.pulls.map { it.ref.id } }

    @Test
    fun oneTopicGoesUnderItSeveralGoOnceUnderSeveralTopicsAndNoneLast() {
        val api41 = pull("synthetic-org/synthetic-api#41", 500)
        val app12 = pull("synthetic-org/synthetic-app#12", 400)
        val app13 = pull("synthetic-org/synthetic-app#13", 300)
        val api38 = pull("synthetic-org/synthetic-api#38", 200)
        val tokens5 = pull("synthetic-labs/synthetic-design-tokens#5", 100)
        val links =
            mapOf(
                app12.ref to listOf(alpha),
                api38.ref to listOf(alpha),
                app13.ref to listOf(beta),
                // Two Topics, one of them twice: still two, and the pull request shows once.
                api41.ref to listOf(alpha, beta, alpha),
            )

        val groups = pullRequestsByTopic(listOf(api38, tokens5, app13, api41, app12)) { links[it].orEmpty() }

        assertEquals(
            listOf(
                PullRequestGroupKey.OneTopic(alpha) to listOf(app12.ref.id, api38.ref.id),
                PullRequestGroupKey.OneTopic(beta) to listOf(app13.ref.id),
                PullRequestGroupKey.SeveralTopics to listOf(api41.ref.id),
                PullRequestGroupKey.NoTopic to listOf(tokens5.ref.id),
            ),
            groups.shape(),
        )
        assertEquals(5, groups.sumOf { it.pulls.size }, "every pull request shows exactly once")
    }

    @Test
    fun topicGroupsTieByNameIgnoringCaseAndEmptyGroupsAreLeftOut() {
        // The same update time and number in three repositories: the newest pull requests tie, so the Topic name decides.
        val one = pull("synthetic-org/synthetic-app#1", 100)
        val two = pull("synthetic-org/synthetic-api#1", 100)
        val three = pull("synthetic-org/synthetic-lib#1", 100)
        val links = mapOf(one.ref to listOf(gamma), two.ref to listOf(beta), three.ref to listOf(alpha))

        val groups = pullRequestsByTopic(listOf(one, two, three)) { links[it].orEmpty() }

        assertEquals(
            listOf(
                PullRequestGroupKey.OneTopic(alpha) to listOf(three.ref.id),
                PullRequestGroupKey.OneTopic(beta) to listOf(two.ref.id),
                PullRequestGroupKey.OneTopic(gamma) to listOf(one.ref.id),
            ),
            groups.shape(),
        )
        assertEquals(emptyList(), pullRequestsByTopic(emptyList()) { emptyList() })
        assertEquals(
            listOf(PullRequestGroupKey.NoTopic to listOf(one.ref.id)),
            pullRequestsByTopic(listOf(one)) { emptyList() }.shape(),
            "with no links, only No Topic",
        )
    }

    @Test
    fun authorsIgnoreCaseComeByTheirNewestPullRequestAndNoAuthorGoesLast() {
        val api44 = pull("synthetic-org/synthetic-api#44", 600, "synthetic-reviewer-a")
        val lib7 = pull("synthetic-org/synthetic-lib#7", 500, "synthetic-teammate")
        val long101 = pull("synthetic-labs/synthetic-long#101", 400, "Synthetic-Reviewer-A")
        val lib9 = pull("synthetic-org/synthetic-lib#9", 300, null)
        val lib3 = pull("synthetic-org/synthetic-lib#3", null, "synthetic-teammate")

        val groups = pullRequestsByAuthor(listOf(lib9, long101, lib3, lib7, api44))

        assertEquals(
            listOf(
                PullRequestGroupKey.Author(GitHubPerson("synthetic-reviewer-a", null)) to listOf(api44.ref.id, long101.ref.id),
                PullRequestGroupKey.Author(GitHubPerson("synthetic-teammate", null)) to listOf(lib7.ref.id, lib3.ref.id),
                PullRequestGroupKey.NoAuthor to listOf(lib9.ref.id),
            ),
            groups.shape(),
        )
        // The group has the account as its newest pull request spells it.
        val newestSpelling = pullRequestsByAuthor(listOf(api44.copy(updatedAtMillis = 100), long101)).single().key
        assertEquals(PullRequestGroupKey.Author(GitHubPerson("Synthetic-Reviewer-A", null)), newestSpelling)
    }

    @Test
    fun reviewStatusComesFromSavedCopiesInAFixedOrderWithTheRestUnderNotLoadedYet() {
        val api41 = pull("synthetic-org/synthetic-api#41", 500)
        val app12 = pull("synthetic-org/synthetic-app#12", 400)
        val app13 = pull("synthetic-org/synthetic-app#13", 300)
        val api38 = pull("synthetic-org/synthetic-api#38", 200)
        val tokens5 = pull("synthetic-labs/synthetic-design-tokens#5", 100)
        val lib1 = pull("synthetic-org/synthetic-lib#1", null)
        val saved =
            mapOf(
                app12.ref to ReviewState.ChangesRequested,
                api38.ref to ReviewState.Approved,
                app13.ref to ReviewState.ChangesRequested,
                lib1.ref to ReviewState.NoReviews,
            )
        val asked = mutableListOf<PullRequestRef>()

        val groups =
            pullRequestsByReview(listOf(tokens5, api38, lib1, app13, api41, app12)) { ref ->
                asked += ref
                saved[ref]
            }

        assertEquals(
            listOf(
                PullRequestGroupKey.Review(ReviewState.ChangesRequested) to listOf(app12.ref.id, app13.ref.id),
                PullRequestGroupKey.Review(ReviewState.Approved) to listOf(api38.ref.id),
                PullRequestGroupKey.Review(ReviewState.NoReviews) to listOf(lib1.ref.id),
                PullRequestGroupKey.NotLoaded to listOf(api41.ref.id, tokens5.ref.id),
            ),
            groups.shape(),
            "Review requested has no pull request, so it is left out",
        )
        // Only the saved state is read, once per pull request: nothing else is asked for.
        assertEquals(6, asked.size)
        assertEquals(
            listOf(PullRequestGroupKey.NotLoaded to listOf(api41.ref.id, tokens5.ref.id)),
            pullRequestsByReview(listOf(tokens5, api41)) { null }.shape(),
            "nothing opened on this device: all Not loaded yet",
        )
    }

    @Test
    fun theRepositoryGroupsAreTheSameAsBefore() {
        val pulls =
            listOf(
                pull("synthetic-org/synthetic-app#12", 300),
                pull("synthetic-org/synthetic-api#41", 500),
                pull("synthetic-org/synthetic-app#13", 400),
            )

        assertEquals(
            pullRequestsByRepository(pulls).map { PullRequestGroupKey.Repository(it.repo) to it.pulls },
            pullRequestGroupsByRepository(pulls).map { it.key to it.pulls },
        )
    }
}
