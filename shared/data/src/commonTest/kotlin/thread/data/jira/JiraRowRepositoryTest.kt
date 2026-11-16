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
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraRowAnswer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Refresh on show (D67): ticket rows by key. SYNTHETIC connection, tickets and answers only. */
class JiraRowRepositoryTest {
    private val meta = InMemoryKeyValueStore()
    private val connections = KeyValueConnectionStore(InMemoryKeyValueStore())

    private suspend fun repository(
        transport: CapturingTransport,
        maxSearches: Int = JiraRowRepository.MAX_SEARCHES,
    ): JiraRowRepository {
        connections.saveJira(SyntheticConnections.jira)
        return JiraRowRepository(connections, transport, meta, maxSearches)
    }

    private fun keys(vararg values: String): List<JiraIssueKey> = values.map { requireNotNull(JiraIssueKey.parse(it)) }

    private fun jql(request: HttpRequest): String = percentDecode(request.url.substringAfter("jql=").substringBefore('&'))

    private fun JiraRowAnswer.rowKeys(): List<String> = rows.map { it.value.key.value }

    @Test
    fun oneSearchAsksForEveryShownKeyWithTheRowFieldsOnly() =
        runTest {
            val transport = syntheticTransport()

            val answer = repository(transport).refreshRows(keys("SYN-101", "SYN-103", "SYN-104"), emptySet(), NOW)

            assertEquals(listOf("key in (\"SYN-101\", \"SYN-103\", \"SYN-104\")"), transport.requests.map(::jql))
            val request = transport.requests.single()
            assertEquals("GET /rest/api/3/search/jql", request.endpoint)
            assertTrue(request.url.endsWith("&fields=summary%2Cissuetype%2Cstatus%2Cupdated&maxResults=50"), request.url)
            assertEquals(setOf("SYN-101", "SYN-103", "SYN-104"), answer.rowKeys().toSet())
            assertTrue(answer.rows.all { it.savedAtMillis == NOW })
            assertEquals(
                "To Do",
                answer.rows
                    .first { it.value.key.value == "SYN-103" }
                    .value.status
                    ?.name,
            )
            assertEquals(emptySet(), answer.refused)
            assertNull(answer.problem)
            assertTrue(answer.storedOnDevice)
        }

    @Test
    fun rowsAreSavedOnThisDeviceAndReadBackForTheConnectedSite() =
        runTest {
            val repository = repository(syntheticTransport())
            repository.refreshRows(keys("SYN-101", "SYN-103"), emptySet(), NOW)

            val saved = repository.allSavedRows()

            assertEquals(setOf("SYN-101", "SYN-103"), saved.map { it.value.key.value }.toSet())
            assertTrue(saved.all { it.savedAtMillis == NOW })
            assertNotNull(meta.get("row:SYN-103"))

            connections.clearJira()
            assertEquals(emptyList(), repository.allSavedRows(), "saved rows hide while Jira is not connected")
        }

    @Test
    fun aRefusedKeyIsFoundBySplittingTheSearchAndTheOtherRowsStillCome() =
        runTest {
            val transport = syntheticTransport()

            val answer = repository(transport).refreshRows(keys("SYN-101", "SYN-999", "SYN-103", "SYN-104"), emptySet(), NOW)

            assertEquals(
                listOf(
                    "key in (\"SYN-101\", \"SYN-999\", \"SYN-103\", \"SYN-104\")",
                    "key in (\"SYN-101\", \"SYN-999\")",
                    "key in (\"SYN-101\")",
                    "key in (\"SYN-999\")",
                    "key in (\"SYN-103\", \"SYN-104\")",
                ),
                transport.requests.map(::jql),
            )
            assertEquals(setOf("SYN-101", "SYN-103", "SYN-104"), answer.rowKeys().toSet())
            assertEquals(keys("SYN-999").toSet(), answer.refused)
            assertNull(answer.problem)
        }

    @Test
    fun aKeyRefusedBeforeIsAskedAloneSoTheSearchDoesNotSplitAgain() =
        runTest {
            val transport = syntheticTransport()

            val answer = repository(transport).refreshRows(keys("SYN-101", "SYN-999", "SYN-103"), keys("SYN-999").toSet(), NOW)

            assertEquals(listOf("key in (\"SYN-101\", \"SYN-103\")", "key in (\"SYN-999\")"), transport.requests.map(::jql))
            assertEquals(keys("SYN-999").toSet(), answer.refused)
            assertEquals(setOf("SYN-101", "SYN-103"), answer.rowKeys().toSet())
        }

    @Test
    fun aSavedRowStaysWhenItsKeyIsRefused() =
        runTest {
            val repository = repository(syntheticTransport())
            repository.refreshRows(keys("SYN-103"), emptySet(), NOW)

            // Later Jira refuses the key: a search cannot tell a deleted ticket from a hidden one.
            val refusing = CapturingTransport { answer(400, """{"errorMessages":["Synthetic"]}""") }
            val answer = JiraRowRepository(connections, refusing, meta).refreshRows(keys("SYN-103"), emptySet(), NOW + 1)

            assertEquals(keys("SYN-103").toSet(), answer.refused)
            assertEquals(listOf("SYN-103"), repository.allSavedRows().map { it.value.key.value })
        }

    @Test
    fun a401StopsTheRefreshAndKeepsTheSavedRows() =
        runTest {
            repository(syntheticTransport()).refreshRows(keys("SYN-101"), emptySet(), NOW)
            val transport = syntheticTransport(SyntheticScenario.Unauthorized)
            val repository = repository(transport)

            val answer = repository.refreshRows(keys("SYN-101", "SYN-103"), keys("SYN-999").toSet(), NOW + 1)

            assertEquals(1, transport.requests.size, "nothing more after the 401")
            assertEquals(ExternalProblem.Unauthorized, answer.problem)
            assertEquals(emptyList(), answer.rows)
            assertEquals(NOW, repository.allSavedRows().single().savedAtMillis)
        }

    @Test
    fun aRateLimitStopsTheRefresh() =
        runTest {
            val transport = syntheticTransport(SyntheticScenario.RateLimited)

            val answer = repository(transport).refreshRows(keys("SYN-101"), keys("SYN-999").toSet(), NOW)

            assertEquals(1, transport.requests.size)
            assertIs<ExternalProblem.RateLimited>(answer.problem)
        }

    @Test
    fun theSplitStopsAtTheMostSearches() =
        runTest {
            val transport = syntheticTransport()

            val answer =
                repository(
                    transport,
                    maxSearches = 3,
                ).refreshRows(keys("SYN-101", "SYN-103", "SYN-104", "SYN-999"), emptySet(), NOW)

            assertEquals(3, transport.requests.size)
            assertEquals(setOf("SYN-101", "SYN-103"), answer.rowKeys().toSet(), "rows found before the stop are kept")
            assertEquals(emptySet(), answer.refused, "the refused key was not isolated yet")
        }

    @Test
    fun withoutAConnectionNothingIsAsked() =
        runTest {
            val transport = syntheticTransport()

            val answer = JiraRowRepository(connections, transport, meta).refreshRows(keys("SYN-101"), emptySet(), NOW)

            assertEquals(ExternalProblem.NotConnected, answer.problem)
            assertEquals(0, transport.requests.size)
        }

    @Test
    fun theSyntheticFixturesChangeBetweenTwoCallsAtRevisionOne() =
        runTest {
            var revision = 0
            val synthetic =
                SyntheticHttpTransport(
                    scenario = { SyntheticScenario.Normal },
                    isOnline = { true },
                    nowMillis = { NOW },
                    revision = { revision },
                )
            val repository = repository(CapturingTransport { synthetic.get(it) })

            val before = repository.refreshRows(keys("SYN-103"), emptySet(), NOW)
            revision = 1
            val after = repository.refreshRows(keys("SYN-103"), emptySet(), NOW + 1)

            assertEquals(
                "To Do",
                before.rows
                    .single()
                    .value.status
                    ?.name,
            )
            assertEquals(
                "In Progress",
                after.rows
                    .single()
                    .value.status
                    ?.name,
            )
        }
}
