package thread.data.jira

import kotlinx.coroutines.test.runTest
import thread.data.CapturingTransport
import thread.data.NOW
import thread.data.answer
import thread.data.external.InMemoryKeyValueStore
import thread.data.external.KeyValueConnectionStore
import thread.data.http.HttpRequest
import thread.data.synthetic.SyntheticConnections
import thread.data.synthetic.SyntheticHttpTransport
import thread.data.synthetic.SyntheticScenario
import thread.data.synthetic.percentDecode
import thread.data.syntheticTransport
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalResult
import thread.domain.external.JiraFamily
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraSite
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

/** D49 S4. SYNTHETIC connection, tickets and answers only. */
class JiraFamilyRepositoryTest {
    private val meta = InMemoryKeyValueStore()
    private val connections = KeyValueConnectionStore(InMemoryKeyValueStore())
    private val topic = TopicId("t-synthetic")
    private val synthetic = SyntheticHttpTransport(scenario = { SyntheticScenario.Normal }, isOnline = { true }, nowMillis = { NOW })

    private suspend fun repository(
        transport: CapturingTransport,
        budget: RequestBudget = RequestBudget(10, 60_000),
    ): JiraFamilyRepository {
        connections.saveJira(SyntheticConnections.jira)
        return JiraFamilyRepository(connections, transport, meta, budget)
    }

    private fun keys(vararg values: String): List<JiraIssueKey> = values.map { requireNotNull(JiraIssueKey.parse(it)) }

    private fun ExternalResult<Refreshed<JiraFamily>>.family(): JiraFamily =
        assertIs<ExternalResult.Ok<Refreshed<JiraFamily>>>(this).value.saved.value

    private fun jql(request: HttpRequest): String = percentDecode(request.url.substringAfter("jql=").substringBefore('&'))

    /** Answers like the synthetic Jira, except [answerFor] the JQL that matches. */
    private fun transport(answerFor: (String) -> Int?): CapturingTransport =
        CapturingTransport { request ->
            answerFor(jql(request))?.let { answer(it, """{"errorMessages":["Synthetic"]}""") } ?: synthetic.get(request)
        }

    @Test
    fun threeSearchesFindTheLinkedTicketsTheirParentsAndTheirChildren() =
        runTest {
            val transport = syntheticTransport()
            val repository = repository(transport)

            val family = repository.refresh(topic, keys("SYN-103", "SYN-109"), NOW).family()

            assertEquals(
                listOf(
                    "key in (\"SYN-103\", \"SYN-109\")",
                    "key in (\"SYN-101\", \"SYN-110\")",
                    "parent in (\"SYN-103\", \"SYN-109\", \"SYN-101\", \"SYN-110\") ORDER BY updated DESC",
                ),
                transport.requests.map(::jql),
            )
            transport.requests.forEach { request ->
                assertTrue(request.url.endsWith("&fields=summary%2Cissuetype%2Cstatus%2Cupdated%2Cparent&maxResults=50"), request.url)
                assertEquals("GET /rest/api/3/search/jql", request.endpoint)
            }
            assertEquals(keys("SYN-103", "SYN-109"), family.keys)
            assertEquals(
                listOf("SYN-109" to "SYN-101", "SYN-103" to "SYN-110", "SYN-101" to "SYN-110", "SYN-110" to null, "SYN-105" to "SYN-110"),
                family.rows.map { it.key.value to it.parentKey?.value },
                "each ticket once, its first row kept",
            )

            val saved = assertNotNull(repository.readSaved(topic))
            assertEquals(family, saved.value)
            assertEquals(NOW, saved.savedAtMillis)
            val stored = assertNotNull(meta.get("family:t-synthetic"))
            assertTrue("\"parent\":{\"key\":\"SYN-101\"}" in stored, "only the parent's key is kept")
            assertFalse("iconUrl" in stored || "\"id\"" in stored)
        }

    @Test
    fun noLinkedTicketsSendsNothing() =
        runTest {
            val transport = syntheticTransport()
            val repository = repository(transport, RequestBudget(1, 60_000))
            val family = repository.refresh(topic, emptyList(), NOW).family()
            assertEquals(JiraFamily(emptyList(), emptyList()), family)
            assertTrue(transport.requests.isEmpty())
            val epic = repository.refresh(topic, keys("SYN-110"), NOW).family()
            assertEquals(listOf("SYN-110", "SYN-101", "SYN-103", "SYN-105"), epic.rows.map { it.key.value }, "the budget was not used")
        }

    @Test
    fun aRefusedSearchIsNoRowsAndIsSaved() =
        runTest {
            for (status in listOf(400, 403, 404)) {
                val transport = CapturingTransport { answer(status, """{"errorMessages":["Synthetic"]}""") }
                val family = repository(transport).refresh(topic, keys("SYN-101"), NOW).family()
                assertEquals(JiraFamily(keys("SYN-101"), emptyList()), family, "status $status")
                assertEquals(1, transport.requests.size, "with no linked row there is nothing more to ask")
                assertEquals(family, repository(transport).readSaved(topic)?.value)
            }
            val unreadable = CapturingTransport { answer(200, "[]") }
            assertEquals(emptyList(), repository(unreadable).refresh(topic, keys("SYN-101"), NOW).family().rows)
        }

    @Test
    fun aParentJiraRefusesIsLeftOutOfTheChildSearch() =
        runTest {
            val transport = transport { jql -> if (jql == "key in (\"SYN-110\")") 400 else null }

            val family = repository(transport).refresh(topic, keys("SYN-103"), NOW).family()

            assertEquals("parent in (\"SYN-103\") ORDER BY updated DESC", jql(transport.requests.last()))
            assertEquals(listOf("SYN-103"), family.rows.map { it.key.value })
        }

    @Test
    fun aProblemThatMayPassIsReturnedAndNothingIsSaved() =
        runTest {
            val expected =
                listOf(
                    401 to ExternalProblem.Unauthorized,
                    429 to ExternalProblem.RateLimited(NOW + 60_000),
                    500 to ExternalProblem.Failed("http-500"),
                )
            for ((status, problem) in expected) {
                val transport = CapturingTransport { answer(status) }
                val result = repository(transport).refresh(topic, keys("SYN-101"), NOW)
                assertEquals(problem, assertIs<ExternalResult.Problem>(result).problem)
                assertNull(meta.get("family:t-synthetic"))
            }
            // The child search fails after two that worked: nothing of the first two is saved either.
            val late = transport { jql -> if (jql.startsWith("parent in")) 503 else null }
            val result = repository(late).refresh(topic, keys("SYN-109"), NOW)
            assertEquals(ExternalProblem.Failed("http-503"), assertIs<ExternalResult.Problem>(result).problem)
            assertEquals(3, late.requests.size)
            assertNull(meta.get("family:t-synthetic"))
        }

    @Test
    fun pastTheBudgetARefreshFailsQuietlyWithoutARequest() =
        runTest {
            val transport = syntheticTransport()
            val repository = repository(transport, RequestBudget(1, 60_000))
            repository.refresh(topic, keys("SYN-101"), NOW).family()
            val sent = transport.requests.size

            val result = repository.refresh(topic, keys("SYN-101"), NOW + 1_000)

            assertEquals(JiraFamilyRepository.BUDGET_SPENT, assertIs<ExternalResult.Problem>(result).problem)
            assertEquals(sent, transport.requests.size)
            assertEquals(NOW, repository.readSaved(topic)?.savedAtMillis, "the saved answer stays")
        }

    @Test
    fun anotherSiteOrNoConnectionShowsNothing() =
        runTest {
            val repository = repository(syntheticTransport())
            repository.refresh(topic, keys("SYN-101"), NOW).family()

            connections.saveJira(SyntheticConnections.jira.copy(site = assertNotNull(JiraSite.parse("other-synthetic.atlassian.net"))))
            assertNull(repository.readSaved(topic))
            connections.clearJira()
            assertNull(repository.readSaved(topic))
            assertEquals(
                ExternalProblem.NotConnected,
                assertIs<ExternalResult.Problem>(repository.refresh(topic, keys("SYN-101"), NOW)).problem,
            )
        }
}
