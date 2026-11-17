package thread.data.jira

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import thread.data.CapturingTransport
import thread.data.NOW
import thread.data.answer
import thread.data.external.InMemoryKeyValueStore
import thread.data.external.KeyValueConnectionStore
import thread.data.http.HttpResponse
import thread.data.synthetic.SyntheticConnections
import thread.data.synthetic.SyntheticScenario
import thread.data.syntheticTransport
import thread.domain.external.ExternalBlock
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalResult
import thread.domain.external.JiraAttachment
import thread.domain.external.JiraIssue
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraIssueSummary
import thread.domain.external.JiraRelated
import thread.domain.external.JiraSite
import thread.domain.external.JiraStatus
import thread.domain.external.JiraStatusCategory
import thread.domain.external.Refreshed
import thread.domain.note.LinkHref
import thread.domain.time.LocalDay
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SYNTHETIC connection and answers only. */
class JiraIssueRepositoryTest {
    private val issues = InMemoryKeyValueStore()
    private val meta = InMemoryKeyValueStore()
    private val connections = KeyValueConnectionStore(InMemoryKeyValueStore())
    private val key = requireNotNull(JiraIssueKey.parse("SYN-101"))

    private suspend fun repository(transport: CapturingTransport): JiraIssueRepository {
        connections.saveJira(SyntheticConnections.jira)
        return JiraIssueRepository(connections, transport, issues, meta)
    }

    private fun ExternalResult<Refreshed<JiraIssue>>.issue(): JiraIssue =
        assertIs<ExternalResult.Ok<Refreshed<JiraIssue>>>(this).value.saved.value

    @Test
    fun refreshReadsTheIssueTheSprintAndTheNewestComments() =
        runTest {
            val transport = syntheticTransport()

            val issue = repository(transport).refresh(key, NOW).issue()

            assertEquals("SYN-101", issue.key.value)
            assertEquals("Story", issue.type)
            assertEquals("SYNTHETIC: Show Jira tickets read-only in Thread", issue.title)
            assertEquals(JiraStatus("In Review", JiraStatusCategory.InProgress), issue.status)
            assertEquals("High", issue.priority)
            assertEquals("Synthetic Person A", issue.assignee)
            assertEquals("Synthetic Person B", issue.reporter)
            assertEquals("Synthetic Sprint 41", issue.sprint)
            assertEquals(listOf("synthetic", "m1-probe"), issue.labels)
            // 2026-11-12T16:42:10.123+04:00
            assertEquals(1_794_487_330_123L, issue.updatedAtMillis)
            assertEquals("https://synthetic-site.atlassian.net/browse/SYN-101", issue.webUrl?.value)
            assertEquals(listOf("20001", "20002"), issue.comments.map { it.id })
            assertEquals(2, issue.commentTotal)
            assertTrue(
                issue.comments
                    .first()
                    .body.blocks
                    .any { it is ExternalBlock.BlockTable },
            )
            assertTrue(issue.description.blocks.isNotEmpty())
            assertEquals(
                listOf(
                    JiraRelated(
                        "is blocked by",
                        requireNotNull(JiraIssueKey.parse("SYN-104")),
                        "SYNTHETIC: Rate limit message when Jira slows down",
                        JiraStatus("In Progress", JiraStatusCategory.InProgress),
                        "Bug",
                    ),
                    JiraRelated(
                        "relates to",
                        requireNotNull(JiraIssueKey.parse("SYN-106")),
                        "SYNTHETIC: Search tickets by key, words or label",
                        JiraStatus("In Progress", JiraStatusCategory.InProgress),
                        "Story",
                    ),
                ),
                issue.related,
            )
            // Only the parent's key is kept (D64).
            assertEquals("SYN-110", issue.parentKey?.value)
            assertEquals("SYN-110", issue.summary().parentKey?.value)
        }

    @Test
    fun refreshReadsAttachmentsSubtasksFixVersionsAndTheDueDate() =
        runTest {
            val issue = repository(syntheticTransport()).refresh(key, NOW).issue()

            val site = "https://synthetic-site.atlassian.net/rest/api/3/attachment/content"
            assertEquals(
                listOf(
                    JiraAttachment(
                        "10501",
                        "synthetic-screen.png",
                        245_760,
                        "image/png",
                        1_794_487_200_000L,
                        LinkHref.parse("$site/10501"),
                    ),
                    JiraAttachment(
                        "10502",
                        "synthetic-spec-with-a-long-file-name-for-narrow-screens.pdf",
                        1_572_864,
                        "application/pdf",
                        // 2026-11-10T09:15:00+04:00
                        1_794_287_700_000L,
                        LinkHref.parse("$site/10502"),
                    ),
                    // 2026-11-03T12:00:00+04:00
                    JiraAttachment(
                        "10503",
                        "synthetic-logs.zip",
                        512,
                        "application/zip",
                        1_793_692_800_000L,
                        LinkHref.parse("$site/10503"),
                    ),
                ),
                issue.attachments,
            )
            assertEquals(
                listOf(
                    JiraIssueSummary(
                        requireNotNull(JiraIssueKey.parse("SYN-111")),
                        "Subtask",
                        "SYNTHETIC: Draw the attachment icons",
                        JiraStatus("Done", JiraStatusCategory.Done),
                        null,
                    ),
                    JiraIssueSummary(
                        requireNotNull(JiraIssueKey.parse("SYN-112")),
                        "Subtask",
                        "SYNTHETIC: Check tables on a narrow screen",
                        JiraStatus("To Do", JiraStatusCategory.ToDo),
                        null,
                    ),
                ),
                issue.subtasks,
            )
            assertEquals(listOf("Synthetic 1.4", "Synthetic 1.5"), issue.fixVersions)
            assertEquals(LocalDay(2026, 11, 20), issue.dueDate)
        }

    @Test
    fun attachmentsWithoutASafeIdOrANameAreLeftOut() =
        runTest {
            val synthetic = syntheticTransport()
            val odd =
                Json
                    .parseToJsonElement(
                        """
                        {"attachment":[{"id":"../10501","filename":"a.png"},{"id":"10502","filename":" "},{"id":10503,"filename":"b.png"},
                          {"id":"10504","filename":"ok.txt","size":-1,"mimeType":""}],
                         "duedate":"2026-02-30","fixVersions":[{"id":"1"},{"name":"Synthetic 2.0"}]}
                        """,
                    ).jsonObject
            val transport =
                CapturingTransport { request ->
                    val response = synthetic.respond(request)
                    if (!request.endpoint.endsWith("/issue/{key}")) return@CapturingTransport response
                    val issue = Json.parseToJsonElement(response.body).jsonObject
                    val fields = JsonObject(issue.getValue("fields").jsonObject + odd)
                    HttpResponse(response.status, response.headers, JsonObject(issue + ("fields" to fields)).toString())
                }

            val issue = repository(transport).refresh(key, NOW).issue()

            assertEquals(listOf("10504"), issue.attachments.map { it.id })
            assertEquals(null, issue.attachments.single().sizeBytes)
            assertEquals(null, issue.attachments.single().mimeType)
            assertEquals(listOf("Synthetic 2.0"), issue.fixVersions)
            assertNull(issue.dueDate)
        }

    @Test
    fun requestsUseTheScopedTokenPathWithBasicAuthAndNothingElse() =
        runTest {
            val transport = syntheticTransport()

            repository(transport).refresh(key, NOW)

            val base = "https://api.atlassian.com/ex/jira/00000000-0000-4000-8000-000000000000/rest/api/3"
            val urls = transport.requests.map { it.url }
            assertEquals(
                listOf(
                    "$base/field",
                    "$base/issue/SYN-101?fields=summary%2Cissuetype%2Cstatus%2Cpriority%2Cassignee%2Creporter%2Clabels" +
                        "%2Cupdated%2Cdescription%2Cissuelinks%2Cparent%2Cattachment%2Csubtasks%2CfixVersions%2Cduedate" +
                        "%2Ccustomfield_10020",
                    "$base/issue/SYN-101/comment?orderBy=-created&maxResults=100",
                ),
                urls,
            )
            val expectedAuth = "Basic " + Base64.encode("synthetic.person@example.test:synthetic-token-not-real".encodeToByteArray())
            for (request in transport.requests) {
                assertEquals(mapOf("Accept" to "application/json", "Authorization" to expectedAuth), request.headers)
                assertFalse("synthetic-token-not-real" in request.toString())
            }
            assertEquals(
                listOf("GET /rest/api/3/field", "GET /rest/api/3/issue/{key}", "GET /rest/api/3/issue/{key}/comment"),
                transport.requests.map { it.endpoint },
            )
        }

    @Test
    fun theSprintFieldIsLookedUpAtMostOnceADay() =
        runTest {
            val transport = syntheticTransport()
            val repository = repository(transport)

            repository.refresh(key, NOW)
            repository.refresh(key, NOW + 60_000)
            repository.refresh(key, NOW + 25 * 60 * 60 * 1_000L)

            assertEquals(2, transport.requests.count { it.endpoint == "GET /rest/api/3/field" })
        }

    @Test
    fun theSavedCopyKeepsOnlyWhatThreadShowsAndReadsBackTheSame() =
        runTest {
            val repository = repository(syntheticTransport())

            val fresh = repository.refresh(key, NOW).issue()
            val saved = assertNotNull(repository.readSaved(key))

            assertEquals(fresh, saved.value)
            assertEquals(NOW, saved.savedAtMillis)
            val stored = assertNotNull(issues.get("SYN-101"))
            val dropped =
                listOf(
                    "emailAddress",
                    "accountId",
                    "avatarUrls",
                    "iconUrl",
                    "self",
                    "goal",
                    // Mention account ids in the description and a comment (adr/ADR-006).
                    "synthetic-account",
                    "\"id\":\"x\"",
                    // An attachment's file links (D75).
                    "attachment-content",
                    "attachment-thumbnail",
                    "releaseDate",
                )
            for (text in dropped) {
                assertFalse(text in stored, "The saved copy keeps $text")
            }
            assertTrue("@Synthetic Person B" in stored, "A mention keeps its text")
            // Of an attachment only its name, size, type and date are kept, not who added it (D75).
            val attachments =
                Json
                    .parseToJsonElement(stored)
                    .jsonObject
                    .getValue("data")
                    .jsonObject
                    .getValue("fields")
                    .jsonObject
                    .getValue("attachment")
                    .jsonArray
            assertEquals(3, attachments.size)
            for (attachment in attachments) {
                assertEquals(setOf("id", "filename", "size", "mimeType", "created"), attachment.jsonObject.keys)
            }
            assertEquals(listOf(saved), repository.allSaved())
        }

    @Test
    fun aCopyFromAnotherSiteIsNotShown() =
        runTest {
            val repository = repository(syntheticTransport())
            repository.refresh(key, NOW)

            connections.saveJira(SyntheticConnections.jira.copy(site = requireNotNull(JiraSite.parse("other-synthetic.atlassian.net"))))

            assertNull(repository.readSaved(key))
            assertEquals(emptyList(), repository.allSaved())
        }

    @Test
    fun anIssueWithoutDescriptionCommentsOrSprintLoads() =
        runTest {
            val issue = repository(syntheticTransport()).refresh(requireNotNull(JiraIssueKey.parse("SYN-102")), NOW).issue()

            assertEquals(JiraStatus("Done", JiraStatusCategory.Done), issue.status)
            assertEquals(null, issue.priority)
            assertEquals(null, issue.assignee)
            assertEquals(null, issue.sprint)
            assertTrue(issue.description.blocks.isEmpty())
            assertEquals(0, issue.commentTotal)
        }

    @Test
    fun failedAnswersMapToProblems() =
        runTest {
            val cases =
                listOf(
                    answer(401) to ExternalProblem.Unauthorized,
                    answer(403) to ExternalProblem.Forbidden,
                    answer(404) to ExternalProblem.NotFound,
                    answer(429, headers = mapOf("retry-after" to "120")) to ExternalProblem.RateLimited(NOW + 120_000),
                    answer(429, headers = mapOf("x-ratelimit-reset" to "2027-01-15T08:00:30.000Z")) to
                        ExternalProblem.RateLimited(1_800_000_030_000L),
                    answer(429) to ExternalProblem.RateLimited(NOW + 60_000),
                    answer(500) to ExternalProblem.Failed("http-500"),
                    answer(200, body = "<html>not json</html>") to ExternalProblem.Failed("bad-response"),
                )
            for ((response, problem) in cases) {
                val transport = CapturingTransport { if (it.endpoint.endsWith("/field")) answer(200, "[]") else response }
                meta.clear()

                val result = repository(transport).refresh(key, NOW)

                assertEquals(ExternalResult.Problem(problem), result, "For HTTP ${response.status}")
            }
        }

    @Test
    fun noAnswerIsUnreachable() =
        runTest {
            val result = repository(syntheticTransport(SyntheticScenario.NoAnswer)).refresh(key, NOW)

            assertEquals(ExternalResult.Problem(ExternalProblem.Unreachable), result)
        }

    @Test
    fun aRefusedFieldListOnlyHidesTheSprint() =
        runTest {
            val synthetic = syntheticTransport()
            val transport = CapturingTransport { if (it.endpoint.endsWith("/field")) answer(403) else synthetic.respond(it) }

            val issue = repository(transport).refresh(key, NOW).issue()

            assertEquals(null, issue.sprint)
            assertEquals("SYN-101", issue.key.value)
        }

    @Test
    fun anExpiredTokenStopsAtTheFirstRequest() =
        runTest {
            val transport = syntheticTransport(SyntheticScenario.Unauthorized)

            val result = repository(transport).refresh(key, NOW)

            assertEquals(ExternalResult.Problem(ExternalProblem.Unauthorized), result)
            assertEquals(1, transport.requests.size)
        }

    @Test
    fun withoutAConnectionNothingIsSent() =
        runTest {
            val transport = syntheticTransport()
            val repository = JiraIssueRepository(connections, transport, issues, meta)

            assertFalse(repository.isConnected())
            assertEquals(ExternalResult.Problem(ExternalProblem.NotConnected), repository.refresh(key, NOW))
            assertEquals(emptyList(), transport.requests)
        }

    @Test
    fun theCloudIdComesFromTheSitesTenantInfoWithoutAToken() =
        runTest {
            val transport = syntheticTransport()

            val result = repository(transport).lookUpCloudId(SyntheticConnections.jira.site, NOW)

            assertEquals(ExternalResult.Ok(SyntheticConnections.jira.cloudId), result)
            assertEquals("https://synthetic-site.atlassian.net/_edge/tenant_info", transport.requests.single().url)
            assertEquals(mapOf("Accept" to "application/json"), transport.requests.single().headers)
        }

    @Test
    fun forgettingRemovesTheCopy() =
        runTest {
            val repository = repository(syntheticTransport())
            repository.refresh(key, NOW)

            repository.forgetSaved(key)

            assertNull(repository.readSaved(key))
        }
}
