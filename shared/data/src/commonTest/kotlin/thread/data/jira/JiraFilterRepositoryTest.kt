package thread.data.jira

import kotlinx.coroutines.test.runTest
import thread.data.CapturingTransport
import thread.data.NOW
import thread.data.answer
import thread.data.external.InMemoryKeyValueStore
import thread.data.external.KeyValueConnectionStore
import thread.data.synthetic.SyntheticConnections
import thread.data.synthetic.SyntheticJiraSearch
import thread.data.synthetic.SyntheticScenario
import thread.data.syntheticTransport
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalResult
import thread.domain.external.JiraFilter
import thread.domain.external.JiraFilterId
import thread.domain.external.JiraIssueList
import thread.domain.external.JiraSite
import thread.domain.external.JiraStarredFilters
import thread.domain.external.Refreshed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SYNTHETIC connection and answers only (D103). */
class JiraFilterRepositoryTest {
    private val meta = InMemoryKeyValueStore()
    private val connections = KeyValueConnectionStore(InMemoryKeyValueStore())
    private val base = "https://api.atlassian.com/ex/jira/00000000-0000-4000-8000-000000000000/rest/api/3"

    private fun id(raw: String): JiraFilterId = assertNotNull(JiraFilterId.parse(raw))

    private suspend fun starred(transport: CapturingTransport): JiraStarredFilterRepository {
        connections.saveJira(SyntheticConnections.jira)
        return JiraStarredFilterRepository(connections, transport, meta)
    }

    private suspend fun rows(transport: CapturingTransport): JiraFilterRowsRepository {
        connections.saveJira(SyntheticConnections.jira)
        return JiraFilterRowsRepository(connections, transport, meta)
    }

    private fun ExternalResult<Refreshed<List<JiraFilter>>>.filters(): List<JiraFilter> =
        assertIs<ExternalResult.Ok<Refreshed<List<JiraFilter>>>>(this).value.saved.value

    private fun ExternalResult<Refreshed<JiraIssueList>>.list(): JiraIssueList =
        assertIs<ExternalResult.Ok<Refreshed<JiraIssueList>>>(this).value.saved.value

    @Test
    fun starredFiltersKeepJiraOrderAndOnlyIdNameAndQuery() =
        runTest {
            val transport = syntheticTransport()
            val repository = starred(transport)

            val filters = repository.refresh(JiraStarredFilters, NOW).filters()

            assertEquals(listOf("10010", "10011", "10012", "10013"), filters.map { it.id.value })
            assertEquals("SYNTHETIC: My open bugs", filters.first().name)
            assertEquals(SyntheticJiraSearch.FILTER_TEAM_IN_PROGRESS, filters[1].jql, "a query with quotes survives")
            assertEquals("$base/filter/favourite", transport.requests.single().url)
            assertEquals("GET /rest/api/3/filter/favourite", transport.requests.single().endpoint)

            val stored = assertNotNull(meta.get("filters:starred"))
            listOf("owner", "accountId", "viewUrl", "sharePermissions", "self").forEach {
                assertFalse(it in stored, "the saved list keeps no $it")
            }
            assertEquals(filters, repository.readSaved(JiraStarredFilters)?.value)

            repository.forgetSaved(JiraStarredFilters)
            assertNull(repository.readSaved(JiraStarredFilters))
        }

    @Test
    fun filtersWithoutAUsableIdOrNameAreLeftOutAndAnEmptyListIsKept() =
        runTest {
            val transport =
                CapturingTransport {
                    answer(
                        200,
                        """[{"id":"10020","name":"SYNTHETIC kept"},{"id":"x1","name":"SYNTHETIC bad id"},{"id":"10021","name":" "},""" +
                            """{"id":10022,"name":"SYNTHETIC number id","jql":"project = SYN"},{"id":"10020","name":"SYNTHETIC twice"}]""",
                    )
                }
            val filters = starred(transport).refresh(JiraStarredFilters, NOW).filters()
            assertEquals(listOf("10020", "10022"), filters.map { it.id.value })
            assertNull(filters.first().jql)

            transport.respond = { answer(200, "[]") }
            assertEquals(emptyList(), starred(transport).refresh(JiraStarredFilters, NOW).filters())

            transport.respond = { answer(200, """{"filters":[]}""") }
            assertIs<ExternalResult.Problem>(starred(transport).refresh(JiraStarredFilters, NOW), "not an array")
        }

    @Test
    fun aFilterRunsItsOwnQueryFromTheSavedListAndSavesTheRows() =
        runTest {
            val transport = syntheticTransport()
            starred(transport).refresh(JiraStarredFilters, NOW).filters()
            val repository = rows(transport)

            val list = repository.refresh(id("10011"), NOW).list()

            assertEquals(listOf("SYN-101", "SYN-109", "SYN-104", "SYN-106", "SYN-113"), list.issues.map { it.key.value })
            assertTrue(list.complete)
            val search = transport.requests.last()
            assertEquals("GET /rest/api/3/search/jql", search.endpoint)
            assertTrue(
                search.url.startsWith(
                    "$base/search/jql?jql=project%20%3D%20SYN%20AND%20statusCategory%20%3D%20%22In%20Progress%22%20ORDER%20BY%20updated" +
                        "%20DESC&fields=summary%2Cissuetype%2Cstatus%2Cupdated&",
                ),
                "the filter's own query, encoded",
            )
            assertEquals(2, transport.requests.size, "the query came from the saved list, not from another request")

            val saved = assertNotNull(repository.readSaved(id("10011")))
            assertEquals(list, saved.value)
            assertNotNull(meta.get("filter:10011"))
            assertNull(repository.readSaved(id("10010")), "each filter is saved apart")

            repository.forgetSaved(id("10011"))
            assertNull(repository.readSaved(id("10011")))
        }

    @Test
    fun withoutASavedListTheQueryIsAskedOfJiraFirst() =
        runTest {
            val transport = syntheticTransport()

            val list = rows(transport).refresh(id("10010"), NOW).list()

            assertEquals(listOf("SYN-104"), list.issues.map { it.key.value })
            assertEquals(listOf("GET /rest/api/3/filter/{id}", "GET /rest/api/3/search/jql"), transport.requests.map { it.endpoint })
            assertEquals("$base/filter/10010", transport.requests.first().url)
        }

    @Test
    fun aFilterJiraCannotRunIsAFailedFourHundred() =
        runTest {
            val transport = syntheticTransport()
            starred(transport).refresh(JiraStarredFilters, NOW)
            val repository = rows(transport)

            assertEquals(ExternalResult.Problem(ExternalProblem.Failed("http-400")), repository.refresh(id("10013"), NOW))
            assertNull(meta.get("filter:10013"))
            assertEquals(
                ExternalResult.Problem(ExternalProblem.NotFound),
                repository.refresh(id("99999"), NOW),
                "a filter that is gone or not shared",
            )

            transport.respond = { answer(200, """{"id":"10030","name":"SYNTHETIC no query"}""") }
            assertIs<ExternalResult.Problem>(rows(transport).refresh(id("10030"), NOW), "a filter without a query")
        }

    @Test
    fun aFilterReadsAtMostTwoPagesAndSaysWhenItIsIncomplete() =
        runTest {
            val row = { n: Int ->
                """{"key":"SYN-$n","fields":{"summary":"SYNTHETIC $n","issuetype":{"name":"Task"},"status":{"name":"To Do","statusCategory":{"key":"new"}}}}"""
            }
            val transport =
                CapturingTransport { request ->
                    if ("/filter/" in request.url) {
                        answer(200, """{"id":"10040","name":"SYNTHETIC big","jql":"project = SYN"}""")
                    } else {
                        val page = if ("nextPageToken=" in request.url) request.url.substringAfter("nextPageToken=").toInt() else 0
                        answer(200, """{"issues":[${row(page + 1)}],"nextPageToken":"${page + 1}","isLast":false}""")
                    }
                }

            val list = rows(transport).refresh(id("10040"), NOW).list()

            assertEquals(listOf("SYN-1", "SYN-2"), list.issues.map { it.key.value })
            assertFalse(list.complete)
            assertEquals(1 + JiraFilterRowsRepository.FILTER_PAGES, transport.requests.size)
        }

    @Test
    fun problemsAndAnotherSiteAreReportedLikeTheOtherLists() =
        runTest {
            assertEquals(
                ExternalResult.Problem(ExternalProblem.Unauthorized),
                starred(syntheticTransport(SyntheticScenario.Unauthorized)).refresh(JiraStarredFilters, NOW),
            )
            val limited = rows(syntheticTransport(SyntheticScenario.RateLimited)).refresh(id("10010"), NOW)
            assertIs<ExternalProblem.RateLimited>(assertIs<ExternalResult.Problem>(limited).problem)

            starred(syntheticTransport()).refresh(JiraStarredFilters, NOW)
            rows(syntheticTransport()).refresh(id("10010"), NOW)
            connections.saveJira(SyntheticConnections.jira.copy(site = assertNotNull(JiraSite.parse("other-site.atlassian.net"))))
            assertNull(JiraStarredFilterRepository(connections, syntheticTransport(), meta).readSaved(JiraStarredFilters))
            assertNull(JiraFilterRowsRepository(connections, syntheticTransport(), meta).readSaved(id("10010")))

            connections.clearJira()
            val noConnection = JiraFilterRowsRepository(connections, syntheticTransport(), meta)
            assertEquals(ExternalResult.Problem(ExternalProblem.NotConnected), noConnection.refresh(id("10010"), NOW))
            assertFalse(noConnection.isConnected())
        }

    @Test
    fun filterIdsAreDigitsOnly() {
        assertEquals("10010", JiraFilterId.parse(" 10010 ")?.value)
        listOf("", "0", "01", "-1", "10 010", "abc", "1".repeat(20)).forEach { assertNull(JiraFilterId.parse(it), it) }
    }
}
