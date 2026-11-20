package thread.data.jira

import kotlinx.coroutines.test.runTest
import thread.data.CapturingTransport
import thread.data.NOW
import thread.data.answer
import thread.data.external.InMemoryKeyValueStore
import thread.data.external.KeyValueConnectionStore
import thread.data.http.HttpNoResponseException
import thread.data.http.HttpRequest
import thread.data.http.HttpResponse
import thread.data.synthetic.SyntheticConnections
import thread.data.synthetic.SyntheticHttpTransport
import thread.data.synthetic.SyntheticScenario
import thread.data.syntheticTransport
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalResult
import thread.domain.external.JiraBoardId
import thread.domain.external.JiraColumnLimits
import thread.domain.external.JiraColumnsNote
import thread.domain.external.JiraMySprints
import thread.domain.external.JiraSite
import thread.domain.external.JiraSprintBoard
import thread.domain.external.JiraSprintId
import thread.domain.external.JiraSprintList
import thread.domain.external.JiraStatusCategory
import thread.domain.external.Refreshed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SYNTHETIC connection and answers only (D103 B). */
class JiraSprintBoardRepositoryTest {
    private val meta = InMemoryKeyValueStore()
    private val connections = KeyValueConnectionStore(InMemoryKeyValueStore())
    private val cloud = "https://api.atlassian.com/ex/jira/00000000-0000-4000-8000-000000000000"
    private val day = 24 * 60 * 60 * 1_000L

    private val sprint7 = assertNotNull(JiraSprintId.parse("37"))
    private val mobile3 = assertNotNull(JiraSprintId.parse("41"))

    private suspend fun sprints(transport: CapturingTransport): JiraMySprintRepository {
        connections.saveJira(SyntheticConnections.jira)
        return JiraMySprintRepository(connections, transport, meta)
    }

    private suspend fun boards(transport: CapturingTransport): JiraSprintBoardRepository {
        connections.saveJira(SyntheticConnections.jira)
        return JiraSprintBoardRepository(connections, transport, meta)
    }

    /** The SYNTHETIC answers, except the board settings, which [settings] answers when it returns non-null. */
    private fun synthetic(settings: (HttpRequest) -> HttpResponse? = { null }): CapturingTransport {
        val synthetic = SyntheticHttpTransport(scenario = { SyntheticScenario.Normal }, isOnline = { true }, nowMillis = { NOW })
        return CapturingTransport { request ->
            (if ("/rest/agile/" in request.url) settings(request) else null) ?: synthetic.get(request)
        }
    }

    private fun ExternalResult<Refreshed<JiraSprintList>>.list(): JiraSprintList =
        assertIs<ExternalResult.Ok<Refreshed<JiraSprintList>>>(this).value.saved.value

    private fun ExternalResult<Refreshed<JiraSprintBoard>>.board(): JiraSprintBoard =
        assertIs<ExternalResult.Ok<Refreshed<JiraSprintBoard>>>(this).value.saved.value

    private fun jql(request: HttpRequest): String = percentDecodeQuery(request.url.substringAfter("jql=").substringBefore('&'))

    @Test
    fun mySprintsAreTheActiveOnesOfMyTicketsWithMyKeys() =
        runTest {
            val transport = synthetic()
            val repository = sprints(transport)

            val list = repository.refresh(JiraMySprints, NOW).list()

            assertTrue(list.siteHasSprints)
            assertEquals(
                listOf("SYN Sprint 7", "SYN Mobile Sprint 3"),
                list.sprints.map { it.name },
                "most of my tickets first; closed left out",
            )
            val seven = list.sprints.first()
            assertEquals("37", seven.id.value)
            assertEquals(JiraBoardId.parse("7"), seven.boardId)
            assertEquals(setOf("SYN-101", "SYN-104", "SYN-109", "SYN-111"), seven.myKeys.map { it.value }.toSet())
            assertTrue(seven.goal.orEmpty().startsWith("SYNTHETIC goal: Ship"))
            assertEquals(1_794_819_600_000L, seven.startMillis, "2026-11-16T09:00Z")
            assertEquals(1_796_058_000_000L, seven.endMillis, "2026-11-30T17:00Z")
            val mobile = list.sprints[1]
            assertEquals(setOf("SYN-107"), mobile.myKeys.map { it.value }.toSet(), "a done ticket of mine counts too")
            assertNull(mobile.goal, "an empty goal is no goal")

            val search = transport.requests.last()
            assertEquals("GET /rest/api/3/search/jql", search.endpoint)
            assertEquals(JiraQuery.MY_OPEN_SPRINTS, jql(search))
            assertTrue("&fields=customfield_10020&" in search.url, "only the Sprint field is asked")

            val stored = assertNotNull(meta.get("sprints:mine"))
            listOf("accountId", "summary", "state", "SYN Sprint 6").forEach { assertFalse(it in stored, "the saved list keeps no $it") }
            assertEquals(list, repository.readSaved(JiraMySprints)?.value)
            repository.forgetSaved(JiraMySprints)
            assertNull(repository.readSaved(JiraMySprints))
        }

    @Test
    fun aSiteWithoutASprintFieldHasNoSprintsAndIsNotSearched() =
        runTest {
            val transport = CapturingTransport { answer(200, """[{"id":"summary","name":"Summary","schema":{"type":"string"}}]""") }

            val list = sprints(transport).refresh(JiraMySprints, NOW).list()

            assertEquals(JiraSprintList(emptyList(), siteHasSprints = false), list)
            assertEquals(listOf("GET /rest/api/3/field"), transport.requests.map { it.endpoint })
        }

    @Test
    fun theBoardHasItsOwnColumnsAndTheBoardEstimate() =
        runTest {
            val transport = synthetic()
            sprints(transport).refresh(JiraMySprints, NOW)
            val repository = boards(transport)
            transport.requests.clear()

            val board = repository.refresh(sprint7, NOW).board()

            assertEquals(
                listOf(
                    "SYN-104",
                    "SYN-101",
                    "SYN-106",
                    "SYN-109",
                    "SYN-113",
                    "SYN-115",
                    "SYN-112",
                    "SYN-116",
                    "SYN-118",
                    "SYN-114",
                    "SYN-111",
                    "SYN-102",
                    "SYN-117",
                ),
                board.cards.map { it.key.value },
                "Jira's rank order",
            )
            assertTrue(board.complete)
            assertNull(board.columnsNote)
            val config = assertNotNull(board.config)
            assertEquals("SYNTHETIC: SYN board", config.name)
            assertEquals(listOf("To Do", "In Progress", "In Review", "Done"), config.columns.map { it.name })
            assertEquals(3, config.columns[1].max)
            assertEquals(setOf("10002"), config.columns[1].statusIds)
            assertEquals(JiraColumnLimits.IssueCount, config.limits)

            val story = board.cards[1]
            assertEquals("High", story.priority)
            assertEquals("Synthetic Person A", story.assignee)
            assertEquals(5.0, story.storyPoints)
            assertEquals("SYN-110", story.parentKey?.value)
            assertEquals("SYNTHETIC: Workspace polish epic", story.parentTitle)
            assertEquals("10003", story.statusId)
            assertEquals(JiraStatusCategory.InProgress, story.status?.category)
            assertTrue(board.cards[3].isSubtask, "SYN-109 is a sub-task")
            assertNull(board.cards.first { it.key.value == "SYN-115" }.assignee, "unassigned")

            assertEquals(
                listOf("GET /rest/agile/1.0/board/{id}/configuration", "GET /rest/api/3/search/jql"),
                transport.requests.map { it.endpoint },
                "the Sprint and points field ids came from the saved /field answer",
            )
            assertEquals("$cloud/rest/agile/1.0/board/7/configuration", transport.requests.first().url)
            assertEquals("sprint = 37 ORDER BY Rank ASC", jql(transport.requests.last()))
            val fields = "&fields=summary%2Cissuetype%2Cstatus%2Cpriority%2Cassignee%2Cparent%2Cupdated%2Ccustomfield_10016&"
            assertTrue(fields in transport.requests.last().url, "the board's estimate field")

            val stored = assertNotNull(meta.get("sprint:37"))
            listOf("accountId", "iconUrl", "self", "subQuery", "location", "rankCustomFieldId").forEach {
                assertFalse(it in stored, "the saved board keeps no $it")
            }
            assertNotNull(meta.get("boardConfig:7"))
            assertEquals(board, repository.readSaved(sprint7)?.value)
            assertNull(repository.readSaved(mobile3), "each sprint is saved apart")
        }

    @Test
    fun theBoardSettingsAreAskedOnceADay() =
        runTest {
            val transport = synthetic()
            sprints(transport).refresh(JiraMySprints, NOW)
            val repository = boards(transport)
            repository.refresh(sprint7, NOW).board()
            transport.requests.clear()

            assertNotNull(repository.refresh(sprint7, NOW + day - 1).board().config)
            assertEquals(listOf("GET /rest/api/3/search/jql"), transport.requests.map { it.endpoint })

            transport.requests.clear()
            assertNotNull(repository.refresh(sprint7, NOW + day).board().config)
            assertEquals(
                listOf("GET /rest/api/3/field", "GET /rest/agile/1.0/board/{id}/configuration", "GET /rest/api/3/search/jql"),
                transport.requests.map { it.endpoint },
                "the field ids and the board settings, each a day old, are asked again",
            )
        }

    @Test
    fun aBoardWithoutTheScopesFollowsStatusAndKeepsTheSiteStoryPoints() =
        runTest {
            val transport = synthetic()
            sprints(transport).refresh(JiraMySprints, NOW)

            val result = boards(transport).refresh(mobile3, NOW)

            val board = result.board()
            assertNull(board.config)
            assertEquals(JiraColumnsNote.NeedsScopes, board.columnsNote, "the 401 of board 8 is about scopes, not expired access")
            assertEquals(listOf("SYN-119", "SYN-108", "SYN-120", "SYN-107"), board.cards.map { it.key.value })
            assertEquals(2.0, board.cards.last().storyPoints, "the site's Story Points field")
            assertTrue("customfield_10016" in transport.requests.last().url)
            assertNull(meta.get("boardConfig:8"), "a refusal is not saved, so a new token works at once")
        }

    @Test
    fun onlyARateLimitStopsTheBoardWhenItsSettingsFail() =
        runTest {
            val cases =
                listOf<Pair<(HttpRequest) -> HttpResponse, JiraColumnsNote>>(
                    { _: HttpRequest -> answer(403) } to JiraColumnsNote.NeedsScopes,
                    { _: HttpRequest -> answer(404) } to JiraColumnsNote.Unavailable,
                    { _: HttpRequest -> answer(500) } to JiraColumnsNote.Unavailable,
                    { _: HttpRequest -> answer(200, """{"name":"SYNTHETIC no columns"}""") } to JiraColumnsNote.Unavailable,
                    { _: HttpRequest -> throw HttpNoResponseException("SYNTHETIC: blocked") } to JiraColumnsNote.Unavailable,
                )
            for ((settings, note) in cases) {
                meta.delete("boardConfig:7")
                val transport = synthetic(settings)
                sprints(transport).refresh(JiraMySprints, NOW)
                val board = boards(transport).refresh(sprint7, NOW).board()
                assertNull(board.config)
                assertEquals(note, board.columnsNote)
                assertEquals(13, board.cards.size, "the tickets still load")
            }

            val limited = synthetic { answer(429, headers = mapOf("retry-after" to "30")) }
            sprints(limited).refresh(JiraMySprints, NOW)
            val problem = assertIs<ExternalResult.Problem>(boards(limited).refresh(sprint7, NOW)).problem
            assertEquals(ExternalProblem.RateLimited(NOW + 30_000), problem)
            assertEquals("GET /rest/agile/1.0/board/{id}/configuration", limited.requests.last().endpoint, "no search after it")
        }

    @Test
    fun oldSettingsAreKeptWhenJiraDoesNotSendNewOnes() =
        runTest {
            val transport = synthetic()
            sprints(transport).refresh(JiraMySprints, NOW)
            boards(transport).refresh(sprint7, NOW).board()

            val refusing = synthetic { answer(401, """{"code":401,"message":"Unauthorized; scope does not match"}""") }
            val board = boards(refusing).refresh(sprint7, NOW + 2 * day).board()

            assertEquals(listOf("To Do", "In Progress", "In Review", "Done"), board.config?.columns?.map { it.name })
            assertNull(board.columnsNote)
        }

    @Test
    fun aSprintWithoutAKnownBoardFollowsStatusWithoutAskingTheBoard() =
        runTest {
            val transport = synthetic()

            val board = boards(transport).refresh(sprint7, NOW).board()

            assertEquals(JiraColumnsNote.Unavailable, board.columnsNote)
            assertTrue(transport.requests.none { "/rest/agile/" in it.url })
            assertEquals(5.0, board.cards[1].storyPoints, "the site's Story Points field")
        }

    @Test
    fun theBoardReadsAtMostFourPagesAndSaysWhenItIsIncomplete() =
        runTest {
            val row = { n: Int ->
                """{"key":"SYN-$n","fields":{"summary":"SYNTHETIC $n","issuetype":{"name":"Task","subtask":false},""" +
                    """"status":{"id":"1","name":"To Do","statusCategory":{"key":"new"}}}}"""
            }
            val transport =
                CapturingTransport { request ->
                    when {
                        request.url.endsWith("/field") -> answer(200, "[]")
                        else -> {
                            val page = if ("nextPageToken=" in request.url) request.url.substringAfter("nextPageToken=").toInt() else 0
                            answer(200, """{"issues":[${row(page + 1)}],"nextPageToken":"${page + 1}","isLast":false}""")
                        }
                    }
                }

            val board = boards(transport).refresh(sprint7, NOW).board()

            assertEquals(listOf("SYN-1", "SYN-2", "SYN-3", "SYN-4"), board.cards.map { it.key.value })
            assertFalse(board.complete)
            assertEquals(1 + JiraSprintBoardRepository.SPRINT_PAGES, transport.requests.size)
        }

    @Test
    fun expiredAccessAndOtherProblemsComeFromTheTicketSearch() =
        runTest {
            assertEquals(
                ExternalResult.Problem(ExternalProblem.Unauthorized),
                sprints(syntheticTransport(SyntheticScenario.Unauthorized)).refresh(JiraMySprints, NOW),
            )

            // Saved field ids and sprints, then the token expires: the board settings' 401 is swallowed, the search's is not.
            val transport = synthetic()
            sprints(transport).refresh(JiraMySprints, NOW)
            meta.delete("boardConfig:7")
            transport.respond = { answer(401) }
            assertEquals(ExternalResult.Problem(ExternalProblem.Unauthorized), boards(transport).refresh(sprint7, NOW))
            assertEquals("GET /rest/api/3/search/jql", transport.requests.last().endpoint)

            transport.respond = { throw HttpNoResponseException("SYNTHETIC: offline") }
            assertEquals(ExternalResult.Problem(ExternalProblem.Unreachable), boards(transport).refresh(sprint7, NOW))

            transport.respond = { answer(400, """{"errorMessages":["SYNTHETIC: sprint gone"]}""") }
            assertEquals(ExternalResult.Problem(ExternalProblem.Failed("http-400")), boards(transport).refresh(sprint7, NOW))
            assertNull(meta.get("sprint:37"), "nothing saved")
        }

    @Test
    fun aCopySavedForAnotherSiteIsNotShown() =
        runTest {
            val transport = synthetic()
            sprints(transport).refresh(JiraMySprints, NOW)
            boards(transport).refresh(sprint7, NOW)

            connections.saveJira(SyntheticConnections.jira.copy(site = assertNotNull(JiraSite.parse("other-site.atlassian.net"))))
            assertNull(JiraMySprintRepository(connections, transport, meta).readSaved(JiraMySprints))
            assertNull(JiraSprintBoardRepository(connections, transport, meta).readSaved(sprint7))

            connections.clearJira()
            val noConnection = JiraSprintBoardRepository(connections, transport, meta)
            assertEquals(ExternalResult.Problem(ExternalProblem.NotConnected), noConnection.refresh(sprint7, NOW))
            assertFalse(noConnection.isConnected())
        }

    /** Reads a `%XX`-encoded query value. */
    private fun percentDecodeQuery(value: String): String = thread.data.synthetic.percentDecode(value)
}
