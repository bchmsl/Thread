package thread.data.jira

import kotlinx.coroutines.test.runTest
import thread.data.CapturingTransport
import thread.data.NOW
import thread.data.answer
import thread.data.external.InMemoryKeyValueStore
import thread.data.external.KeyValueConnectionStore
import thread.data.synthetic.SyntheticConnections
import thread.data.synthetic.SyntheticScenario
import thread.data.syntheticTransport
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalResult
import thread.domain.external.JiraIssueList
import thread.domain.external.JiraList
import thread.domain.external.JiraStatus
import thread.domain.external.JiraStatusCategory
import thread.domain.external.Refreshed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SYNTHETIC connection and answers only. */
class JiraListRepositoryTest {
    private val meta = InMemoryKeyValueStore()
    private val connections = KeyValueConnectionStore(InMemoryKeyValueStore())
    private val base = "https://api.atlassian.com/ex/jira/00000000-0000-4000-8000-000000000000/rest/api/3"

    private suspend fun repository(transport: CapturingTransport): JiraListRepository {
        connections.saveJira(SyntheticConnections.jira)
        return JiraListRepository(connections, transport, meta)
    }

    private fun ExternalResult<Refreshed<JiraIssueList>>.list(): JiraIssueList =
        assertIs<ExternalResult.Ok<Refreshed<JiraIssueList>>>(this).value.saved.value

    private fun ExternalResult<JiraIssueList>.keys(): List<String> =
        assertIs<ExternalResult.Ok<JiraIssueList>>(this).value.issues.map { it.key.value }

    @Test
    fun assignedToMeAsksTheEnhancedSearchForOpenTicketsAndSavesOnlyTheRowFields() =
        runTest {
            val transport = syntheticTransport()
            val repository = repository(transport)

            val list = repository.refresh(JiraList.Assigned, NOW).list()

            assertEquals(
                listOf("SYN-101", "SYN-109", "SYN-103", "SYN-104", "SYN-111", "SYN-105", "SYN-110"),
                list.issues.map { it.key.value },
            )
            assertEquals(
                listOf("Story", "Sub-task", "Task", "Bug", "Synthetic Incident", "Story", "Epic"),
                list.issues.map { it.type },
                "every issue type is listed",
            )
            assertTrue(list.complete)
            val first = list.issues.first()
            assertEquals("Story", first.type)
            assertEquals(JiraStatus("In Review", JiraStatusCategory.InProgress), first.status)
            assertEquals(1_794_487_330_123L, first.updatedAtMillis)
            assertEquals(
                "$base/search/jql?jql=assignee%20%3D%20currentUser%28%29%20AND%20statusCategory%20%21%3D%20Done%20ORDER%20BY" +
                    "%20updated%20DESC&fields=summary%2Cissuetype%2Cstatus%2Cupdated&maxResults=50",
                transport.requests.single().url,
            )
            assertEquals("GET /rest/api/3/search/jql", transport.requests.single().endpoint)

            val saved = assertNotNull(repository.readSaved(JiraList.Assigned))
            assertEquals(list, saved.value)
            assertEquals(NOW, saved.savedAtMillis)
            val stored = assertNotNull(meta.get("list:assigned"))
            assertFalse("iconUrl" in stored || "\"id\"" in stored, "the saved list keeps only the row fields")

            repository.forgetSaved(JiraList.Assigned)
            assertNull(repository.readSaved(JiraList.Assigned))
        }

    @Test
    fun theListFollowsAtMostTenPagesAndSaysWhenItIsIncomplete() =
        runTest {
            val row = { n: Int ->
                """{"key":"SYN-$n","fields":{"summary":"SYNTHETIC $n","issuetype":{"name":"Task"},"status":{"name":"To Do","statusCategory":{"key":"new"}}}}"""
            }
            val transport =
                CapturingTransport { request ->
                    val page = if ("nextPageToken=" in request.url) request.url.substringAfter("nextPageToken=").toInt() else 0
                    answer(200, """{"issues":[${row(page + 1)}],"nextPageToken":"${page + 1}","isLast":false}""")
                }
            val list = repository(transport).refresh(JiraList.Assigned, NOW).list()

            assertEquals((1..10).map { "SYN-$it" }, list.issues.map { it.key.value })
            assertFalse(list.complete)
            assertEquals(JiraListRepository.LIST_PAGES, transport.requests.size)
            assertTrue(transport.requests[1].url.endsWith("&nextPageToken=1"))
        }

    @Test
    fun noRowIsDroppedForItsIssueType() =
        runTest {
            val types = listOf("Bug", "Task", "Story", "Sub-task", "Subtask", "Epic", "SYNTHETIC custom type", "Задача")
            val rows =
                types.mapIndexed { i, type ->
                    """{"key":"SYN-${i + 1}","fields":{"summary":"SYNTHETIC ${i + 1}","issuetype":{"name":"$type","subtask":""" +
                        """${type.startsWith("Sub")}},"status":{"name":"Open","statusCategory":{"key":"new"}}}}"""
                } + """{"key":"SYN-99","fields":{"summary":"SYNTHETIC without a type","status":{"name":"Open"}}}"""
            val transport = CapturingTransport { answer(200, """{"issues":[${rows.joinToString(",")}],"isLast":true}""") }

            val list = repository(transport).refresh(JiraList.Assigned, NOW).list()

            assertEquals(types + listOf(null), list.issues.map { it.type })
            assertEquals(JiraStatus("Open", JiraStatusCategory.Unknown), list.issues.last().status)
            val url = transport.requests.single().url
            val jql = url.substring(url.indexOf("jql="), url.indexOf("&fields"))
            assertFalse("issuetype" in jql, "the JQL names no issue type")
            assertEquals(list, repository(transport).readSaved(JiraList.Assigned)?.value, "the saved copy keeps every type")
        }

    @Test
    fun currentSprintOnlyIsItsOwnListSavedApart() =
        runTest {
            val transport = syntheticTransport()
            val repository = repository(transport)

            val all = repository.refresh(JiraList.Assigned, NOW).list()
            val sprint = repository.refresh(JiraList.AssignedCurrentSprint, NOW + 1).list()

            assertEquals(listOf("SYN-101", "SYN-109", "SYN-104", "SYN-111"), sprint.issues.map { it.key.value })
            assertTrue(
                transport.requests.last().url.contains(
                    "jql=assignee%20%3D%20currentUser%28%29%20AND%20statusCategory%20%21%3D%20Done%20AND%20sprint%20in" +
                        "%20openSprints%28%29%20ORDER%20BY%20updated%20DESC&",
                ),
            )
            assertEquals(all, repository.readSaved(JiraList.Assigned)?.value)
            assertEquals(sprint, repository.readSaved(JiraList.AssignedCurrentSprint)?.value)
            assertNotNull(meta.get("list:assignedcurrentsprint"))

            repository.forgetSaved(JiraList.AssignedCurrentSprint)
            assertNull(repository.readSaved(JiraList.AssignedCurrentSprint))
            assertEquals(all, repository.readSaved(JiraList.Assigned)?.value, "forgetting one list keeps the other")
        }

    @Test
    fun hasSprintsAsksFieldOnceADayAndSendsNothingWhenItMayNotAsk() =
        runTest {
            val transport = syntheticTransport()
            val repository = repository(transport)

            assertNull(repository.hasSprints(NOW, mayAsk = false), "nothing saved and no request allowed")
            assertTrue(transport.requests.isEmpty())

            assertEquals(true, repository.hasSprints(NOW, mayAsk = true))
            assertEquals(listOf("GET /rest/api/3/field"), transport.requests.map { it.endpoint })
            assertEquals(true, repository.hasSprints(NOW + 60_000, mayAsk = true), "saved for a day")
            assertEquals(1, transport.requests.size)

            val dayLater = NOW + 24 * 60 * 60 * 1_000L
            assertEquals(true, repository.hasSprints(dayLater, mayAsk = false), "an older answer while it may not ask")
            assertEquals(1, transport.requests.size)

            transport.respond = { answer(401) }
            assertEquals(true, repository.hasSprints(dayLater, mayAsk = true), "a failed request keeps the older answer")
        }

    @Test
    fun aSiteWithoutTheSprintFieldHasNoSprints() =
        runTest {
            val transport = CapturingTransport { answer(200, """[{"id":"summary","name":"Summary","schema":{"type":"string"}}]""") }
            assertEquals(false, repository(transport).hasSprints(NOW, mayAsk = true))
            connections.clearJira()
            assertNull(JiraListRepository(connections, transport, meta).hasSprints(NOW, mayAsk = true), "not connected")
        }

    @Test
    fun searchFindsByKeyWordsOrLabelAndSavesNothing() =
        runTest {
            val transport = syntheticTransport()
            val repository = repository(transport)

            assertEquals(listOf("SYN-104"), repository.search("syn-104", NOW).keys())
            assertEquals(emptyList(), repository.search("SYN-999", NOW).keys(), "an unknown key answers 400, which is no result")
            assertEquals(listOf("SYN-104"), repository.search("rate limit", NOW).keys())
            assertEquals(listOf("SYN-108"), repository.search("payments", NOW).keys())
            assertEquals(listOf("SYN-101", "SYN-103", "SYN-104", "SYN-106"), repository.search("  jira  ", NOW).keys())
            val before = transport.requests.size
            assertEquals(emptyList(), repository.search("   ", NOW).keys())
            assertEquals(before, transport.requests.size, "a blank query is not sent")
            assertNull(meta.get("list:assigned"))
        }

    @Test
    fun searchReportsProblemsLikeTheOtherRequests() =
        runTest {
            assertEquals(
                ExternalResult.Problem(ExternalProblem.Unauthorized),
                repository(syntheticTransport(SyntheticScenario.Unauthorized)).search("jira", NOW),
            )
            val limited = repository(syntheticTransport(SyntheticScenario.RateLimited)).search("jira", NOW)
            assertIs<ExternalProblem.RateLimited>(assertIs<ExternalResult.Problem>(limited).problem)
            assertEquals(
                ExternalResult.Problem(ExternalProblem.Failed("http-400")),
                repository(CapturingTransport { answer(400) }).search("words", NOW),
                "a 400 on a text search is a real failure",
            )
            connections.clearJira()
            val noConnection = JiraListRepository(connections, syntheticTransport(), meta)
            assertEquals(ExternalResult.Problem(ExternalProblem.NotConnected), noConnection.search("jira", NOW))
            assertFalse(noConnection.isConnected())
        }

    @Test
    fun typedTextNeverReachesJqlUnescaped() {
        assertEquals("key = \"SYN-12\"", JiraQuery.search(" syn-12 ")?.text)
        assertEquals(true, JiraQuery.search("SYN-12")?.keyOnly)
        assertEquals("(text ~ \"say hi there\") ORDER BY updated DESC", JiraQuery.search("say \"hi\" there")?.text)
        assertEquals(
            "(text ~ \"m1 probe\" OR labels = \"m1-probe\") ORDER BY updated DESC",
            JiraQuery.search("m1-probe")?.text,
        )
        // A quote cannot end the JQL string early, so the rest stays words to search for.
        assertEquals(
            "(text ~ \"a OR project = X b\") ORDER BY updated DESC",
            JiraQuery.search("a\" OR project = X b")?.text,
        )
        assertNull(JiraQuery.search("***"))
        assertNull(JiraQuery.search(" "))
    }
}
