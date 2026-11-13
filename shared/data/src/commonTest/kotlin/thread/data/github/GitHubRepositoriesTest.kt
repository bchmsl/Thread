package thread.data.github

import kotlinx.coroutines.test.runTest
import thread.data.CapturingTransport
import thread.data.NOW
import thread.data.answer
import thread.data.external.InMemoryKeyValueStore
import thread.data.external.KeyValueConnectionStore
import thread.data.synthetic.SyntheticConnections
import thread.data.syntheticTransport
import thread.domain.external.CiState
import thread.domain.external.CiSummary
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalResult
import thread.domain.external.PullRequestDetail
import thread.domain.external.PullRequestList
import thread.domain.external.PullRequestListPage
import thread.domain.external.PullRequestRef
import thread.domain.external.Refreshed
import thread.domain.external.ReviewState
import thread.domain.external.Reviewer
import thread.domain.external.ReviewerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** SYNTHETIC connection and answers only. */
class GitHubRepositoriesTest {
    private val connections = KeyValueConnectionStore(InMemoryKeyValueStore())
    private val store = InMemoryKeyValueStore()

    private suspend fun lists(transport: CapturingTransport): GitHubPullRequestLists {
        connections.saveGitHub(SyntheticConnections.gitHub)
        return GitHubPullRequestLists(connections, transport, store)
    }

    private suspend fun details(transport: CapturingTransport): GitHubPullRequestDetails {
        connections.saveGitHub(SyntheticConnections.gitHub)
        return GitHubPullRequestDetails(connections, transport, store)
    }

    private fun <T> ExternalResult<Refreshed<T>>.value(): T = assertIs<ExternalResult.Ok<Refreshed<T>>>(this).value.saved.value

    private fun ref(id: String) = requireNotNull(PullRequestRef.parse(id))

    @Test
    fun theTwoListsComeFromSearch() =
        runTest {
            val transport = syntheticTransport()
            val lists = lists(transport)

            val mine = lists.refresh(PullRequestList.Mine, NOW).value()
            val review = lists.refresh(PullRequestList.ReviewRequested, NOW).value()

            assertEquals(listOf("synthetic-org/synthetic-app#12", "synthetic-org/synthetic-app#13"), mine.items.map { it.ref.id })
            assertEquals(listOf(false, true), mine.items.map { it.isDraft })
            assertEquals(
                "https://github.com/synthetic-org/synthetic-app/pull/12",
                mine.items
                    .first()
                    .webUrl
                    ?.value,
            )
            assertEquals(listOf("synthetic-org/synthetic-lib#7"), review.items.map { it.ref.id })
            assertEquals(
                listOf(
                    "https://api.github.com/search/issues?q=is%3Apr%20is%3Aopen%20author%3A%40me%20archived%3Afalse&sort=updated&order=desc&per_page=50",
                    "https://api.github.com/search/issues?q=is%3Apr%20is%3Aopen%20review-requested%3A%40me%20archived%3Afalse" +
                        "&sort=updated&order=desc&per_page=50",
                ),
                transport.requests.map { it.url },
            )
            for (request in transport.requests) {
                assertEquals(
                    mapOf("Accept" to "application/vnd.github+json", "Authorization" to "Bearer synthetic-token-not-real"),
                    request.headers,
                )
            }
        }

    @Test
    fun aSavedListReadsBackTheSame() =
        runTest {
            val lists = lists(syntheticTransport())

            val fresh = lists.refresh(PullRequestList.Mine, NOW).value()

            assertEquals(fresh, assertNotNull(lists.readSaved(PullRequestList.Mine)).value)
            assertEquals(null, lists.readSaved(PullRequestList.ReviewRequested))
        }

    @Test
    fun detailCombinesReviewsStatusesAndChecks() =
        runTest {
            val transport = syntheticTransport()

            val detail = details(transport).refresh(ref("synthetic-org/synthetic-app#12"), NOW).value()

            assertEquals("SYNTHETIC: Add the offline shell", detail.title)
            assertEquals("feature/offline-shell", detail.headBranch)
            assertEquals("main", detail.baseBranch)
            // The author's own comment does not count; a re-requested reviewer shows as requested.
            assertEquals(
                listOf(
                    Reviewer("synthetic-reviewer-a", ReviewerState.Approved),
                    Reviewer("synthetic-reviewer-b", ReviewerState.Requested),
                    Reviewer("synthetic-team", ReviewerState.Requested),
                ),
                detail.reviewers,
            )
            assertEquals(ReviewState.Approved, detail.review)
            assertEquals(CiSummary(CiState.Running, statusesReadable = true, checksReadable = true), detail.ci)
            val sha = "1".repeat(40)
            assertEquals(
                listOf(
                    "https://api.github.com/repos/synthetic-org/synthetic-app/pulls/12",
                    "https://api.github.com/repos/synthetic-org/synthetic-app/pulls/12/reviews?per_page=100",
                    "https://api.github.com/repos/synthetic-org/synthetic-app/commits/$sha/status?per_page=100",
                    "https://api.github.com/repos/synthetic-org/synthetic-app/commits/$sha/check-runs?per_page=100",
                ),
                transport.requests.map { it.url },
            )
        }

    @Test
    fun draftWithChangesRequestedAndFailingChecks() =
        runTest {
            val detail = details(syntheticTransport()).refresh(ref("synthetic-org/synthetic-app#13"), NOW).value()

            assertEquals(true, detail.isDraft)
            assertEquals("", detail.body)
            assertEquals(ReviewState.ChangesRequested, detail.review)
            assertEquals(CiState.Failing, detail.ci.state)
        }

    @Test
    fun statusesTheTokenCannotReadDoNotBlockTheDetail() =
        runTest {
            val detail = details(syntheticTransport()).refresh(ref("synthetic-org/synthetic-lib#7"), NOW).value()

            assertEquals(CiSummary(CiState.Passing, statusesReadable = false, checksReadable = true), detail.ci)
            assertEquals(ReviewState.ReviewRequested, detail.review)
        }

    @Test
    fun aSavedDetailReadsBackTheSame() =
        runTest {
            val details = details(syntheticTransport())
            val key = ref("synthetic-org/synthetic-app#12")

            val fresh: PullRequestDetail = details.refresh(key, NOW).value()

            assertEquals(fresh, assertNotNull(details.readSaved(key)).value)
            val stored = assertNotNull(store.get(key.id))
            assertFalse("\"name\":\"tests\"" in stored, "The saved copy keeps check names")
        }

    @Test
    fun rateLimitsAndOtherFailures() =
        runTest {
            val reset = NOW / 1_000 + 300
            val cases =
                listOf(
                    answer(403, headers = mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "$reset")) to
                        ExternalProblem.RateLimited(reset * 1_000),
                    answer(429, headers = mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "$reset")) to
                        ExternalProblem.RateLimited(reset * 1_000),
                    answer(403, headers = mapOf("retry-after" to "30")) to ExternalProblem.RateLimited(NOW + 30_000),
                    answer(403, body = """{"message":"You have exceeded a secondary rate limit."}""") to
                        ExternalProblem.RateLimited(NOW + 60_000),
                    answer(403, body = """{"message":"Resource not accessible by personal access token"}""") to ExternalProblem.Forbidden,
                    answer(401) to ExternalProblem.Unauthorized,
                    answer(404) to ExternalProblem.NotFound,
                    answer(422) to ExternalProblem.Failed("http-422"),
                    answer(200, body = "not json") to ExternalProblem.Failed("bad-response"),
                )
            for ((response, problem) in cases) {
                val result = lists(CapturingTransport { response }).refresh(PullRequestList.Mine, NOW)

                assertEquals(ExternalResult.Problem(problem), result, "For HTTP ${response.status} ${response.headers}")
            }
        }

    @Test
    fun aPullRequestWithoutAValidHeadShaIsABadResponse() =
        runTest {
            val transport = CapturingTransport { answer(200, """{"title":"x","head":{"ref":"a","sha":"../../etc"}}""") }

            val result = details(transport).refresh(ref("synthetic-org/synthetic-app#12"), NOW)

            assertEquals(ExternalResult.Problem(ExternalProblem.Failed("bad-response")), result)
            assertEquals(1, transport.requests.size)
        }

    @Test
    fun withoutAConnectionNothingIsSent() =
        runTest {
            val transport = syntheticTransport()
            val lists = GitHubPullRequestLists(connections, transport, store)

            assertEquals(ExternalResult.Problem(ExternalProblem.NotConnected), lists.refresh(PullRequestList.Mine, NOW))
            assertEquals(emptyList(), transport.requests)
        }

    @Test
    fun anEmptySearchIsAnEmptyList() =
        runTest {
            val page =
                lists(CapturingTransport { answer(200, """{"total_count":0,"incomplete_results":false,"items":[]}""") })
                    .refresh(PullRequestList.Mine, NOW)
                    .value()

            assertEquals(PullRequestListPage(emptyList(), 0, false), page)
        }
}
