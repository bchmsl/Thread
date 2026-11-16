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
import thread.domain.external.ExternalBlock
import thread.domain.external.ExternalDocument
import thread.domain.external.ExternalInline
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalResult
import thread.domain.external.GitHubConnection
import thread.domain.external.GitHubPerson
import thread.domain.external.PullRequestChanges
import thread.domain.external.PullRequestDetail
import thread.domain.external.PullRequestList
import thread.domain.external.PullRequestListPage
import thread.domain.external.PullRequestRef
import thread.domain.external.Refreshed
import thread.domain.external.ReviewState
import thread.domain.external.Reviewer
import thread.domain.external.ReviewerState
import thread.domain.external.SecretText
import thread.domain.note.LinkHref
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private companion object {
        const val USER = "https://api.github.com/user"

        /** The address Thread builds from a SYNTHETIC account id. */
        fun avatar(id: Int): LinkHref? = LinkHref.parse("https://avatars.githubusercontent.com/u/$id?s=40&v=4")
    }

    @Test
    fun theTwoListsComeFromSearch() =
        runTest {
            val transport = syntheticTransport()
            val lists = lists(transport)

            val mine = lists.refresh(PullRequestList.Mine, NOW).value()
            val review = lists.refresh(PullRequestList.ReviewRequested, NOW).value()

            assertEquals(
                listOf(
                    "synthetic-org/synthetic-api#41",
                    "synthetic-org/synthetic-app#12",
                    "synthetic-org/synthetic-app#13",
                    "synthetic-org/synthetic-api#38",
                    "synthetic-labs/synthetic-design-tokens#5",
                ),
                mine.items.map { it.ref.id },
            )
            assertEquals(listOf(false, false, true, false, false), mine.items.map { it.isDraft })
            assertEquals("https://github.com/synthetic-org/synthetic-app/pull/12", mine.items[1].webUrl?.value)
            assertEquals(
                listOf(
                    "synthetic-org/synthetic-api#44",
                    "synthetic-org/synthetic-lib#7",
                    "synthetic-labs/synthetic-repository-with-a-very-long-name-for-wrapping#101",
                    "synthetic-org/synthetic-lib#9",
                ),
                review.items.map { it.ref.id },
            )
            // Owner change #8: the assignees in GitHub's order, and the token's own login from one GET /user.
            assertEquals(
                listOf("synthetic-teammate", "synthetic-me", "synthetic-reviewer-a"),
                mine.items[0].assignees?.map { it.login },
            )
            assertEquals(
                listOf(listOf("synthetic-me"), emptyList()),
                mine.items.subList(1, 3).map { pull ->
                    pull.assignees?.map { it.login }
                },
            )
            assertEquals(emptyList(), review.items[3].assignees, "GitHub's \"assignees\": null is nobody")
            // Owner change #13 (D69): a row shows the author, so the author keeps an avatar; no id, no avatar.
            assertEquals(GitHubPerson("synthetic-me", avatar(1)), mine.items[1].author)
            assertEquals(GitHubPerson("synthetic-reviewer-a", avatar(3)), review.items[0].author)
            assertEquals(GitHubPerson("synthetic-lead", null), review.items[3].author)
            assertEquals("synthetic-me", mine.viewer)
            assertEquals("synthetic-me", review.viewer)
            assertEquals(
                listOf(
                    "https://api.github.com/search/issues?q=is%3Apr%20is%3Aopen%20author%3A%40me%20archived%3Afalse&sort=updated&order=desc&per_page=50",
                    "https://api.github.com/user",
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
            // The author's own comment does not count; a re-requested reviewer shows as requested. A person's avatar comes
            // from the id (D69); a team has none.
            assertEquals(
                listOf(
                    Reviewer("synthetic-reviewer-a", ReviewerState.Approved, avatar(3)),
                    Reviewer("synthetic-reviewer-b", ReviewerState.Requested, avatar(4)),
                    Reviewer("synthetic-team", ReviewerState.Requested, isTeam = true),
                ),
                detail.reviewers,
            )
            // Owner change #13 (D69): the author with an avatar, the assignees and the size of the change.
            assertEquals(GitHubPerson("synthetic-me", avatar(1)), detail.author)
            assertEquals(
                listOf(GitHubPerson("synthetic-me", avatar(1)), GitHubPerson("synthetic-teammate", avatar(2))),
                detail.assignees,
            )
            assertEquals(PullRequestChanges(commits = 4, changedFiles = 6, additions = 128, deletions = 14), detail.changes)
            assertEquals(ReviewState.Approved, detail.review)
            assertEquals(CiSummary(CiState.Running, statusesReadable = true, checksReadable = true), detail.ci)
            // The screen shows the parsed description (D57); the Markdown source stays for the Jira key search (D49).
            assertTrue("Follows SYN-103 and SYN-105." in detail.body)
            assertEquals(ExternalBlock.Heading(2, listOf(ExternalInline.Text("Summary"))), detail.description.blocks.first())
            assertTrue(detail.description.blocks.any { it is ExternalBlock.Table })
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
            assertEquals(ExternalDocument.EMPTY, detail.description)
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
    fun aDetailSavedBeforeOwnerChange13ReadsWithoutAssigneesOrCounts() =
        runTest {
            val details = details(syntheticTransport())
            val key = ref("synthetic-org/synthetic-app#12")
            store.put(
                key.id,
                """
                {"recordVersion":1,"savedAtMillis":$NOW,"scope":"github","data":{"pull":{"title":"SYNTHETIC: Old copy",
                  "user":{"login":"synthetic-me"},"draft":false,"head":{"ref":"feature/a"},"base":{"ref":"main"},
                  "requested_reviewers":[{"login":"synthetic-reviewer-a"}],"requested_teams":[{"name":"synthetic-team"}]},
                 "reviews":[]}}
                """.trimIndent(),
            )

            val saved = assertNotNull(details.readSaved(key)).value

            assertNull(saved.assignees, "not known, so the Assignees card asks for a refresh")
            assertNull(saved.changes, "not known, so the counts stay hidden")
            assertEquals(GitHubPerson("synthetic-me", null), saved.author)
            assertEquals(
                listOf(
                    Reviewer("synthetic-reviewer-a", ReviewerState.Requested),
                    Reviewer("synthetic-team", ReviewerState.Requested, isTeam = true),
                ),
                saved.reviewers,
            )
        }

    @Test
    fun countsShowOnlyWhenAllFourAreReadable() =
        runTest {
            val details = details(syntheticTransport())
            val key = ref("synthetic-org/synthetic-app#12")
            val cases =
                listOf(
                    """"commits":2,"changed_files":3,"additions":0,"deletions":0""" to PullRequestChanges(2, 3, 0, 0),
                    """"commits":2,"changed_files":3,"additions":5""" to null,
                    """"commits":2,"changed_files":3,"additions":5,"deletions":-1""" to null,
                    """"commits":"2","changed_files":3,"additions":5,"deletions":1""" to null,
                    """"commits":2.5,"changed_files":3,"additions":5,"deletions":1""" to null,
                )
            for ((counts, expected) in cases) {
                store.put(
                    key.id,
                    """
                    {"recordVersion":1,"savedAtMillis":$NOW,"scope":"github","data":{"pull":{"title":"SYNTHETIC: Counts",
                      "draft":false,"head":{"ref":"feature/a"},"base":{"ref":"main"},"assignees":[],$counts},"reviews":[]}}
                    """.trimIndent(),
                )

                val saved = assertNotNull(details.readSaved(key)).value

                assertEquals(expected, saved.changes, counts)
                assertEquals(emptyList(), saved.assignees, counts)
            }
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

    @Test
    fun searchStaysInsideThreadsShareOfTheSearchLimit() =
        runTest {
            val transport = syntheticTransport()
            val lists = lists(transport)
            val budget = GitHubPullRequestLists.SEARCH_BUDGET_PER_MINUTE

            repeat(budget) { lists.refresh(PullRequestList.Mine, NOW + it).value() }
            val refused = lists.refresh(PullRequestList.ReviewRequested, NOW + 30_000)

            assertEquals(ExternalResult.Problem(ExternalProblem.RateLimited(NOW + 60_000)), refused)
            assertEquals(budget, transport.searches(), "no search goes out once the minute's budget is spent")
            lists.refresh(PullRequestList.ReviewRequested, NOW + 60_000).value()
            assertEquals(budget + 1, transport.searches())
            assertEquals(budget + 2, transport.requests.size, "plus the one GET /user")
        }

    @Test
    fun theViewerIsAskedOnceAndKeptWithTheConnection() =
        runTest {
            val transport = syntheticTransport()
            val lists = lists(transport)

            lists.refresh(PullRequestList.Mine, NOW).value()
            lists.refresh(PullRequestList.ReviewRequested, NOW + 1).value()
            val again = lists.refresh(PullRequestList.Mine, NOW + 2).value()

            assertEquals(1, transport.requests.count { it.url == USER })
            assertEquals("synthetic-me", again.viewer)
            assertEquals("synthetic-me", connections.gitHubViewer())
            assertEquals("synthetic-me", assertNotNull(lists.readSaved(PullRequestList.Mine)).value.viewer)
        }

    @Test
    fun aViewerGitHubCannotGiveLeavesItUnknownAndTheListStillLoads() =
        runTest {
            val synthetic = syntheticTransport()
            val failures =
                listOf(
                    answer(403, """{"message":"Resource not accessible by personal access token"}"""),
                    answer(200, """{"login":"../not-a-login","id":1}"""),
                    answer(200, "not json"),
                )
            for (failure in failures) {
                connections.clearGitHub()
                val transport = CapturingTransport { if (it.url == USER) failure else synthetic.respond(it) }
                val lists = lists(transport)

                val page = lists.refresh(PullRequestList.Mine, NOW).value()

                assertEquals(5, page.items.size, failure.body)
                assertNull(page.viewer, failure.body)
                assertNull(connections.gitHubViewer(), failure.body)
                lists.refresh(PullRequestList.Mine, NOW + 1).value()
                assertEquals(2, transport.requests.count { it.url == USER }, "the next refresh asks again")
            }
        }

    @Test
    fun aLoginForATokenReplacedMeanwhileIsNotKept() =
        runTest {
            val synthetic = syntheticTransport()
            val other = GitHubConnection(SecretText("synthetic-other-token-not-real"))
            val transport =
                CapturingTransport {
                    if (it.url == USER) connections.saveGitHub(other)
                    synthetic.respond(it)
                }

            val page = lists(transport).refresh(PullRequestList.Mine, NOW).value()

            assertEquals("synthetic-me", page.viewer, "the list it answers was searched with the old token")
            assertNull(connections.gitHubViewer())
            assertEquals(other, connections.gitHub())
        }

    @Test
    fun aListSavedBeforeAssigneesWereKeptReadsAsNotKnown() =
        runTest {
            val lists = lists(syntheticTransport())
            store.put(
                "mine",
                """
                {"recordVersion":1,"savedAtMillis":$NOW,"scope":"github","data":{"total_count":1,"incomplete_results":false,"items":[
                  {"number":12,"title":"SYNTHETIC: Add the offline shell","user":{"login":"synthetic-me"},"draft":false,
                   "updated_at":"2026-11-12T14:00:00Z","repository_url":"https://api.github.com/repos/synthetic-org/synthetic-app"}]}}
                """.trimIndent(),
            )

            val saved = assertNotNull(lists.readSaved(PullRequestList.Mine)).value

            assertEquals(listOf("synthetic-org/synthetic-app#12"), saved.items.map { it.ref.id })
            assertNull(saved.items.single().assignees)
        }

    @Test
    fun assigneesKeepTheLoginAndIdOnlyAndTheAvatarComesFromTheId() =
        runTest {
            val search =
                """
                {"total_count":1,"incomplete_results":false,"items":[{"number":3,"title":"SYNTHETIC: Assignees",
                  "user":{"login":"synthetic-me"},"draft":false,"updated_at":"2026-11-12T14:00:00Z",
                  "repository_url":"https://api.github.com/repos/synthetic-org/synthetic-app","assignees":[
                    {"login":"synthetic-a","id":7,"avatar_url":"https://synthetic-elsewhere.example/a.png","email":"synthetic@example.test"},
                    {"login":"<img src=x>","id":8},
                    {"login":"synthetic-b"},
                    {"login":"synthetic-c","id":0},
                    {"login":"synthetic-bot[bot]","id":9},
                    "not an object"]}]}
                """.trimIndent()
            val transport =
                CapturingTransport {
                    if (it.url ==
                        USER
                    ) {
                        answer(200, """{"login":"synthetic-me","id":1}""")
                    } else {
                        answer(200, search)
                    }
                }

            val page = lists(transport).refresh(PullRequestList.Mine, NOW).value()

            assertEquals(
                listOf(
                    GitHubPerson("synthetic-a", LinkHref.parse("https://avatars.githubusercontent.com/u/7?s=40&v=4")),
                    GitHubPerson("synthetic-b", null),
                    GitHubPerson("synthetic-c", null),
                    GitHubPerson("synthetic-bot[bot]", LinkHref.parse("https://avatars.githubusercontent.com/u/9?s=40&v=4")),
                ),
                page.items.single().assignees,
            )
            val stored = assertNotNull(store.get("mine"))
            assertFalse("avatar_url" in stored, "Thread builds the avatar address itself")
            assertFalse("synthetic@example.test" in stored, "the saved copy keeps the login and id only")
        }

    private fun CapturingTransport.searches(): Int = requests.count { "/search/" in it.url }

    @Test
    fun allSavedListsTheSavedDetailsNewestFirstAndNothingWhenDisconnected() =
        runTest {
            val details = details(syntheticTransport())
            details.refresh(ref("synthetic-org/synthetic-app#12"), NOW).value()
            details.refresh(ref("synthetic-org/synthetic-lib#7"), NOW + 1_000).value()
            store.put("not a pull request id", "x")

            assertEquals(
                listOf("synthetic-org/synthetic-lib#7", "synthetic-org/synthetic-app#12"),
                details.allSaved().map { it.value.ref.id },
            )
            connections.clearGitHub()
            assertEquals(emptyList(), details.allSaved())
        }
}
