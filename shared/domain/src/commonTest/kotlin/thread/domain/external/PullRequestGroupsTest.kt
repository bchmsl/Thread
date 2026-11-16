package thread.domain.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Owner change #8: the pull request lists grouped by repository. SYNTHETIC repositories and logins only. */
class PullRequestGroupsTest {
    private fun pull(
        id: String,
        updatedAtMillis: Long?,
    ) = PullRequestSummary(
        requireNotNull(PullRequestRef.parse(id)),
        "SYNTHETIC $id",
        GitHubPerson("synthetic-me", null),
        false,
        updatedAtMillis,
        null,
    )

    private fun List<RepositoryPullRequests>.shape() = map { group -> group.repo to group.pulls.map { it.ref.number } }

    @Test
    fun theRepositoryWithTheNewestUpdateComesFirstAndItsNewestPullRequestFirst() {
        val groups =
            pullRequestsByRepository(
                listOf(
                    pull("synthetic-org/synthetic-app#12", 300),
                    pull("synthetic-org/synthetic-api#41", 500),
                    pull("synthetic-org/synthetic-app#13", 400),
                    pull("synthetic-org/synthetic-api#38", 100),
                    pull("synthetic-labs/synthetic-design-tokens#5", 200),
                ),
            )

        assertEquals(
            listOf(
                "synthetic-org/synthetic-api" to listOf(41, 38),
                "synthetic-org/synthetic-app" to listOf(13, 12),
                "synthetic-labs/synthetic-design-tokens" to listOf(5),
            ),
            groups.shape(),
        )
    }

    @Test
    fun repositoryNamesIgnoreCaseAndTheGroupShowsItsNewestPullRequestsSpelling() {
        val groups =
            pullRequestsByRepository(
                listOf(
                    pull("synthetic-org/synthetic-app#12", 100),
                    pull("Synthetic-Org/Synthetic-App#13", 200),
                ),
            )

        assertEquals(listOf("Synthetic-Org/Synthetic-App" to listOf(13, 12)), groups.shape())
    }

    @Test
    fun withoutAnUpdateTimeAPullRequestGoesLastAndTiesGoByNumberThenName() {
        val groups =
            pullRequestsByRepository(
                listOf(
                    pull("synthetic-org/synthetic-lib#9", null),
                    pull("synthetic-org/synthetic-lib#7", 100),
                    pull("synthetic-org/synthetic-zeta#3", 100),
                    pull("synthetic-org/synthetic-beta#2", 100),
                    pull("synthetic-org/synthetic-beta#3", 100),
                    pull("synthetic-org/synthetic-none#4", null),
                ),
            )

        assertEquals(
            listOf(
                "synthetic-org/synthetic-lib" to listOf(7, 9),
                "synthetic-org/synthetic-beta" to listOf(3, 2),
                "synthetic-org/synthetic-zeta" to listOf(3),
                "synthetic-org/synthetic-none" to listOf(4),
            ),
            groups.shape(),
        )
    }

    @Test
    fun anEmptyListHasNoGroups() {
        assertEquals(emptyList(), pullRequestsByRepository(emptyList()))
    }

    @Test
    fun aLoginMatchesTheViewerIgnoringCase() {
        val person = GitHubPerson("Synthetic-Me", null)

        assertTrue(person.isLogin("synthetic-me"))
        assertFalse(person.isLogin("synthetic-teammate"))
        assertFalse(person.isLogin(null))
    }
}
