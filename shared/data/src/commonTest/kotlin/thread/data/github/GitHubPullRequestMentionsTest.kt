package thread.data.github

import kotlinx.coroutines.test.runTest
import thread.data.CapturingTransport
import thread.data.NOW
import thread.data.answer
import thread.data.external.InMemoryKeyValueStore
import thread.data.external.KeyValueConnectionStore
import thread.data.http.HttpRequest
import thread.data.synthetic.SyntheticConnections
import thread.data.synthetic.percentDecode
import thread.data.syntheticTransport
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalResult
import thread.domain.external.JiraIssueKey
import thread.domain.external.PullRequestMentions
import thread.domain.external.Refreshed
import thread.domain.external.RequestBudget
import thread.domain.topic.TopicId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D49 S5. SYNTHETIC connection, repositories, tickets and answers only. */
class GitHubPullRequestMentionsTest {
    private val connections = KeyValueConnectionStore(InMemoryKeyValueStore())
    private val store = InMemoryKeyValueStore()
    private val topic = TopicId("t-synthetic")
    private val app = "synthetic-org/synthetic-app"

    private suspend fun mentions(
        transport: CapturingTransport,
        budget: RequestBudget = RequestBudget(10, 60_000),
    ): GitHubPullRequestMentions {
        connections.saveGitHub(SyntheticConnections.gitHub)
        return GitHubPullRequestMentions(connections, transport, store, budget)
    }

    private fun keys(vararg values: String): List<JiraIssueKey> = values.map { requireNotNull(JiraIssueKey.parse(it)) }

    private fun ExternalResult<Refreshed<PullRequestMentions>>.value(): PullRequestMentions =
        assertIs<ExternalResult.Ok<Refreshed<PullRequestMentions>>>(this).value.saved.value

    private fun query(request: HttpRequest): String = percentDecode(request.url.substringAfter("q=").substringBefore('&'))

    private fun item(
        number: Int,
        repo: String,
        title: String,
        body: String? = null,
        pull: Boolean = true,
    ): String {
        val pullRequest = if (pull) ""","pull_request":{"url":"x"}""" else ""
        val bodyJson = body?.let { "\"$it\"" } ?: "null"
        return """{"number":$number,"title":"$title","body":$bodyJson,"state":"open","user":{"login":"synthetic-a","id":1},""" +
            """"repository_url":"https://api.github.com/repos/$repo","updated_at":"2026-11-12T10:00:00Z"$pullRequest}"""
    }

    @Test
    fun oneSearchPerRepositoryAndThreadChecksEachResultItself() =
        runTest {
            val transport = syntheticTransport()
            val repository = mentions(transport)

            val found = repository.refresh(topic, listOf(app), keys("SYN-101", "SYN-103", "SYN-105"), NOW).value()

            val request = transport.requests.single()
            assertEquals("is:pr repo:$app in:title,body (\"SYN-101\" OR \"SYN-103\" OR \"SYN-105\")", query(request))
            assertTrue(request.url.endsWith("&sort=updated&order=desc&per_page=20"), request.url)
            assertEquals("GET /search/issues (mentions)", request.endpoint)
            assertEquals(
                listOf("$app#12" to listOf("SYN-103", "SYN-105"), "$app#14" to listOf("SYN-101")),
                found.pulls.map { it.summary.ref.id to it.keys.map(JiraIssueKey::value) },
                "#15 names SYN-1010, which is not a linked ticket",
            )
            assertEquals(listOf(false, true), found.pulls.map { it.closed })
            assertEquals(listOf(app), found.repos)
            assertEquals(keys("SYN-101", "SYN-103", "SYN-105"), found.keys)

            val saved = assertNotNull(repository.readSaved(topic))
            assertEquals(found, saved.value)
            val stored = assertNotNull(store.get("mentions:t-synthetic"))
            assertFalse("body" in stored || "SYNTHETIC description" in stored, "descriptions are read, not kept")
            assertFalse("pull_request" in stored || "\"id\"" in stored)
        }

    @Test
    fun theQueryStaysUnderGitHubsLimits() {
        val longKeys = (1..5).map { requireNotNull(JiraIssueKey.parse("A${"B".repeat(48)}-${1_000_000_000 + it}")) }
        val query = assertNotNull(GitHubClient.mentionQuery("o/r", longKeys))
        assertTrue(query.length <= GitHubClient.QUERY_MAX_LENGTH, "${query.length}")
        assertEquals(longKeys.take(3), GitHubClient.mentionKeys("o/r", longKeys), "keys from the end are dropped until it fits")
        val five = GitHubClient.mentionQuery(app, keys("SYN-1", "SYN-2", "SYN-3", "SYN-4", "SYN-5"))
        assertEquals(4, assertNotNull(five).split(" OR ").size - 1, "five keys need four ORs, under GitHub's five")
        assertNull(GitHubClient.mentionQuery("not a repo", keys("SYN-1")))
        assertNull(GitHubClient.mentionQuery("o/r\" OR is:issue", keys("SYN-1")))
        assertNull(GitHubClient.mentionQuery(app, emptyList()))
    }

    @Test
    fun issuesOtherRepositoriesAndTextThatDoesNotNameTheKeyAreLeftOut() =
        runTest {
            val items =
                listOf(
                    item(1, app, "Issue for SYN-101", pull = false),
                    item(2, "synthetic-org/other", "Other repository SYN-101"),
                    item(3, app, "Lowercase", body = "fixes syn-101"),
                    item(4, app, "Glued", body = "SYN-1010 and xSYN-101"),
                    item(5, "Synthetic-Org/Synthetic-App", "Names SYN-101 in GitHub's own letter case"),
                )
            val page = """{"total_count":5,"incomplete_results":false,"items":[${items.joinToString(",")}]}"""
            val transport = CapturingTransport { answer(200, page) }

            val found = mentions(transport).refresh(topic, listOf(app), keys("SYN-101"), NOW).value()

            assertEquals(listOf("Synthetic-Org/Synthetic-App#5"), found.pulls.map { it.summary.ref.id })
        }

    @Test
    fun aRepositoryGitHubRefusesGivesNothingAndTheOtherStillCounts() =
        runTest {
            val synthetic = syntheticTransport()
            for (refusal in listOf(answer(422, """{"message":"Validation Failed"}"""), answer(403), answer(404), answer(200, "[]"))) {
                val transport =
                    CapturingTransport { request ->
                        if ("repo:synthetic-org/private" in query(request)) refusal else synthetic.get(request)
                    }
                val repos = listOf("synthetic-org/private", app)
                val found = mentions(transport).refresh(topic, repos, keys("SYN-101"), NOW).value()
                assertEquals(2, transport.requests.size)
                assertEquals(listOf("$app#14"), found.pulls.map { it.summary.ref.id }, "status ${refusal.status}")
                assertEquals(found, mentions(transport).readSaved(topic)?.value)
            }
        }

    @Test
    fun aProblemThatMayPassIsReturnedAndNothingIsSaved() =
        runTest {
            val limitHeaders = mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "1800000120")
            val rateLimited = answer(403, """{"message":"API rate limit exceeded"}""", limitHeaders)
            val expected =
                listOf(
                    answer(401) to ExternalProblem.Unauthorized,
                    rateLimited to ExternalProblem.RateLimited(1_800_000_120_000),
                    answer(500) to ExternalProblem.Failed("http-500"),
                )
            for ((response, problem) in expected) {
                val transport = CapturingTransport { response }
                val result = mentions(transport).refresh(topic, listOf(app), keys("SYN-101"), NOW)
                assertEquals(problem, assertIs<ExternalResult.Problem>(result).problem)
                assertNull(store.get("mentions:t-synthetic"))
            }
        }

    @Test
    fun atMostTwoRepositoriesAndTheBudgetHoldsTheRest() =
        runTest {
            val transport = syntheticTransport()
            val repository = mentions(transport, RequestBudget(1, 60_000))

            val none = repository.refresh(topic, emptyList(), keys("SYN-101"), NOW).value()
            assertEquals(PullRequestMentions(emptyList(), keys("SYN-101"), emptyList()), none)
            assertTrue(transport.requests.isEmpty(), "no repository, no search, and the budget is not used")

            val three = listOf(app, "synthetic-org/synthetic-lib", "synthetic-org/third")
            val found = repository.refresh(topic, three, keys("SYN-101"), NOW).value()
            assertEquals(listOf(app, "synthetic-org/synthetic-lib"), found.repos)
            assertEquals(2, transport.requests.size)

            val spent = repository.refresh(topic, listOf(app), keys("SYN-101"), NOW + 1_000)
            assertEquals(GitHubPullRequestMentions.BUDGET_SPENT, assertIs<ExternalResult.Problem>(spent).problem)
            assertEquals(2, transport.requests.size)
            assertEquals(NOW, repository.readSaved(topic)?.savedAtMillis)
        }
}
