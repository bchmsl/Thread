package thread.data.synthetic

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import thread.data.http.HttpNoResponseException
import thread.data.http.HttpRequest
import thread.data.http.HttpResponse
import thread.data.http.HttpTransport
import thread.domain.external.ConnectionStore
import thread.domain.external.GitHubConnection
import thread.domain.external.JiraCloudId
import thread.domain.external.JiraConnection
import thread.domain.external.JiraSite
import thread.domain.external.SecretText

/*
 * SYNTHETIC Jira and GitHub answers for tests and for the fixture mode of the M1 spike page. Every name, key, text
 * and id here is made up. Results that come from these fixtures are never live validation (adr/M1-RESULTS.md).
 */

/** What the synthetic providers answer. */
enum class SyntheticScenario(
    val label: String,
) {
    Normal("Normal answers"),
    Unauthorized("401: token expired"),
    Forbidden("403: no permission"),
    NotFound("404: not found"),
    RateLimited("Rate limited"),
    ServerError("500: server error"),
    NoAnswer("No answer (blocked or network)"),
}

/** SYNTHETIC connections. The token values are not tokens. */
object SyntheticConnections {
    val jira: JiraConnection =
        JiraConnection(
            site = requireNotNull(JiraSite.parse("synthetic-site.atlassian.net")),
            cloudId = requireNotNull(JiraCloudId.parse(SyntheticFixtures.CLOUD_ID)),
            email = "synthetic.person@example.test",
            token = SecretText("synthetic-token-not-real"),
        )

    val gitHub: GitHubConnection = GitHubConnection(SecretText("synthetic-token-not-real"))
}

/** Connections for the fixture mode: in memory only, starting with the SYNTHETIC connections. Never stored. */
class SyntheticConnectionStore : ConnectionStore {
    private var jira: JiraConnection? = SyntheticConnections.jira
    private var gitHub: GitHubConnection? = SyntheticConnections.gitHub

    override suspend fun jira(): JiraConnection? = jira

    override suspend fun saveJira(connection: JiraConnection) {
        jira = connection
    }

    override suspend fun clearJira() {
        jira = null
    }

    override suspend fun gitHub(): GitHubConnection? = gitHub

    private var gitHubViewer: String? = null

    override suspend fun saveGitHub(connection: GitHubConnection) {
        if (connection.token.reveal() != gitHub?.token?.reveal()) gitHubViewer = null
        gitHub = connection
    }

    override suspend fun clearGitHub() {
        gitHub = null
        gitHubViewer = null
    }

    override suspend fun gitHubViewer(): String? = gitHubViewer

    override suspend fun saveGitHubViewer(login: String) {
        gitHubViewer = login
    }
}

/**
 * A transport that answers from [SyntheticFixtures] like Jira and GitHub would. [scenario] turns every answer into
 * one failure kind. When [isOnline] is false it gives no answer, like a browser offline.
 */
class SyntheticHttpTransport(
    private val scenario: () -> SyntheticScenario,
    private val isOnline: () -> Boolean,
    private val nowMillis: () -> Long,
    private val latencyMillis: Long = 0,
) : HttpTransport {
    override suspend fun get(request: HttpRequest): HttpResponse {
        if (latencyMillis > 0) delay(latencyMillis)
        if (!isOnline()) throw HttpNoResponseException("Offline (synthetic)")
        val gitHub = request.url.startsWith("https://api.github.com/")
        return when (scenario()) {
            SyntheticScenario.Normal -> answer(request.url, gitHub)
            SyntheticScenario.Unauthorized -> error(401, gitHub)
            SyntheticScenario.Forbidden -> error(403, gitHub)
            SyntheticScenario.NotFound -> error(404, gitHub)
            SyntheticScenario.RateLimited -> rateLimited(gitHub)
            SyntheticScenario.ServerError -> error(500, gitHub)
            SyntheticScenario.NoAnswer -> throw HttpNoResponseException("No answer (synthetic)")
        }
    }

    private fun answer(
        url: String,
        gitHub: Boolean,
    ): HttpResponse {
        val path = url.substringBefore('?')
        val query = url.substringAfter('?', "")
        if (!gitHub && path.endsWith("/rest/api/3/search/jql")) {
            val (status, body) = SyntheticJiraSearch.answer(query)
            return HttpResponse(status, emptyMap(), body)
        }
        val body = if (gitHub) SyntheticFixtures.gitHub(path, query) else SyntheticFixtures.jira(path)
        return if (body != null) ok(body, gitHub) else error(404, gitHub)
    }

    private fun ok(
        body: String,
        gitHub: Boolean,
    ): HttpResponse = HttpResponse(200, if (gitHub) gitHubRateHeaders(remaining = 4_990) else emptyMap(), body)

    private fun error(
        status: Int,
        gitHub: Boolean,
    ): HttpResponse {
        val body =
            if (gitHub) {
                """{"message":"Synthetic error $status","documentation_url":"https://docs.github.com/rest","status":"$status"}"""
            } else {
                """{"errorMessages":["Synthetic error $status"],"errors":{}}"""
            }
        return HttpResponse(status, if (gitHub) gitHubRateHeaders(remaining = 4_990) else emptyMap(), body)
    }

    private fun rateLimited(gitHub: Boolean): HttpResponse =
        if (gitHub) {
            HttpResponse(403, gitHubRateHeaders(remaining = 0), """{"message":"API rate limit exceeded (synthetic)"}""")
        } else {
            HttpResponse(429, mapOf("retry-after" to "120"), """{"errorMessages":["Rate limit exceeded (synthetic)"]}""")
        }

    private fun gitHubRateHeaders(remaining: Int): Map<String, String> =
        mapOf(
            "x-ratelimit-limit" to "5000",
            "x-ratelimit-remaining" to remaining.toString(),
            "x-ratelimit-reset" to (nowMillis() / 1_000 + 120).toString(),
            "x-ratelimit-used" to (5_000 - remaining).toString(),
            "x-ratelimit-resource" to "core",
        )
}

/** SYNTHETIC answer bodies, shaped like the Jira REST v3 and GitHub REST answers Thread reads. */
object SyntheticFixtures {
    const val CLOUD_ID: String = "00000000-0000-4000-8000-000000000000"
    const val SPRINT_FIELD: String = "customfield_10020"

    private val JIRA_ISSUE = Regex("^.*/rest/api/3/issue/([A-Z0-9_]+-[0-9]+)$")
    private val JIRA_COMMENTS = Regex("^.*/rest/api/3/issue/([A-Z0-9_]+-[0-9]+)/comment$")
    private val GITHUB_PULL = Regex("^https://api\\.github\\.com/repos/([^/]+)/([^/]+)/pulls/([0-9]+)(/reviews)?$")
    private val GITHUB_COMMIT = Regex("^https://api\\.github\\.com/repos/[^/]+/[^/]+/commits/([0-9a-f]{40})/(status|check-runs)$")

    fun jira(path: String): String? {
        if (path.endsWith("/_edge/tenant_info")) return """{"cloudId":"$CLOUD_ID"}"""
        if (path.endsWith("/rest/api/3/field")) return FIELDS
        JIRA_COMMENTS.matchEntire(path)?.let { match -> COMMENTS[match.groupValues[1]]?.let { return it } }
        JIRA_COMMENTS.matchEntire(path)?.let { match ->
            return SyntheticJiraSearch.issue(match.groupValues[1])?.let { """{"startAt":0,"maxResults":100,"total":0,"comments":[]}""" }
        }
        JIRA_ISSUE.matchEntire(path)?.let { return ISSUES[it.groupValues[1]] ?: SyntheticJiraSearch.issue(it.groupValues[1]) }
        return null
    }

    fun gitHub(
        path: String,
        query: String,
    ): String? {
        if (path == "https://api.github.com/user") return VIEWER
        if (path == "https://api.github.com/search/issues") {
            return when {
                "author%3A%40me" in query -> SEARCH_MINE
                "review-requested%3A%40me" in query -> SEARCH_REVIEW
                "repo%3A" in query -> mentions(query)
                else -> null
            }
        }
        GITHUB_PULL.matchEntire(path)?.let { match ->
            val pull = PULLS["${match.groupValues[1]}/${match.groupValues[2]}#${match.groupValues[3]}"] ?: return null
            return if (match.groupValues[4].isEmpty()) pull.json else pull.reviews
        }
        GITHUB_COMMIT.matchEntire(path)?.let { match ->
            val pull = PULLS.values.firstOrNull { it.sha == match.groupValues[1] } ?: return null
            return if (match.groupValues[2] == "status") pull.statuses else pull.checkRuns
        }
        return null
    }

    private val MENTION_REPO = Regex("repo:([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)")
    private val MENTION_KEY = Regex("\"([A-Z][A-Z0-9_]*-[0-9]+)\"")

    /**
     * A mention search (D49 S5): the [MENTION_ITEMS] in the searched repository whose title or body contains a quoted
     * key as text, like a loose search would. Thread checks the rows itself, so the `SYN-1010` row must not be kept.
     */
    private fun mentions(query: String): String {
        val q =
            query
                .split('&')
                .firstOrNull { it.startsWith("q=") }
                ?.let { percentDecode(it.removePrefix("q=")) }
                .orEmpty()
        val repo = MENTION_REPO.find(q)?.groupValues?.get(1)
        val keys = MENTION_KEY.findAll(q).map { it.groupValues[1] }.toList()
        val items =
            MENTION_ITEMS.filter { item ->
                repo != null && item.repo == repo && keys.any { it in item.title || it in item.body.orEmpty() }
            }
        val json =
            items.joinToString(",") { item ->
                val body = item.body?.let { "\"$it\"" } ?: "null"
                """{"number":${item.number},"title":"${item.title}","user":{"login":"synthetic-teammate","id":2},""" +
                    """"draft":false,"state":"${item.state}","updated_at":"${item.updated}",""" +
                    """"repository_url":"https://api.github.com/repos/${item.repo}","body":$body,""" +
                    """"pull_request":{"url":"https://api.github.com/repos/${item.repo}/pulls/${item.number}"}}"""
            }
        return """{"total_count":${items.size},"incomplete_results":false,"items":[$json]}"""
    }

    private class SyntheticMention(
        val repo: String,
        val number: Int,
        val title: String,
        val body: String?,
        val state: String,
        val updated: String,
    )

    /** SYNTHETIC pull requests that name synthetic tickets. #12 and #14 also have a detail below. */
    private val MENTION_ITEMS: List<SyntheticMention> =
        listOf(
            SyntheticMention(
                "synthetic-org/synthetic-app",
                12,
                "SYNTHETIC: Add the offline shell",
                "SYNTHETIC description. Follows SYN-103 and SYN-105.",
                "open",
                "2026-11-12T14:00:00Z",
            ),
            SyntheticMention(
                "synthetic-org/synthetic-app",
                14,
                "SYNTHETIC: Ticket view polish (SYN-101)",
                "Closed synthetic change.",
                "closed",
                "2026-11-10T16:00:00Z",
            ),
            SyntheticMention(
                "synthetic-org/synthetic-app",
                15,
                "SYNTHETIC: Names SYN-1010, not a linked ticket",
                null,
                "open",
                "2026-11-12T15:00:00Z",
            ),
        )

    private val FIELDS =
        """
        [
          {"id":"summary","key":"summary","name":"Summary","custom":false,"schema":{"type":"string","system":"summary"}},
          {"id":"customfield_10011","key":"customfield_10011","name":"Synthetic text field","custom":true,
           "schema":{"type":"string","custom":"com.atlassian.jira.plugin.system.customfieldtypes:textfield","customId":10011}},
          {"id":"$SPRINT_FIELD","key":"$SPRINT_FIELD","name":"Sprint","custom":true,
           "schema":{"type":"array","items":"json","custom":"com.pyxis.greenhopper.jira:gh-sprint","customId":10020}}
        ]
        """.trimIndent()

    private val SYN_101_DESCRIPTION =
        """
        {"type":"doc","version":1,"content":[
          {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"SYNTHETIC ticket for the M1 probe"}]},
          {"type":"paragraph","content":[
            {"type":"text","text":"Bold","marks":[{"type":"strong"}]},{"type":"text","text":", "},
            {"type":"text","text":"italic","marks":[{"type":"em"}]},{"type":"text","text":", "},
            {"type":"text","text":"a link","marks":[{"type":"link","attrs":{"href":"https://example.test/synthetic"}}]},
            {"type":"text","text":", a refused link","marks":[{"type":"link","attrs":{"href":"javascript:alert(1)"}}]},
            {"type":"text","text":", code","marks":[{"type":"code"}]},{"type":"text","text":" and "},
            {"type":"text","text":"strike","marks":[{"type":"strike"}]},{"type":"text","text":"."},
            {"type":"hardBreak"},
            {"type":"mention","attrs":{"id":"synthetic-account","text":"@Synthetic Person B"}},
            {"type":"text","text":" "},{"type":"emoji","attrs":{"shortName":":rocket:","text":"🚀"}},
            {"type":"text","text":" due "},{"type":"date","attrs":{"timestamp":"1794873600000"}},
            {"type":"text","text":" "},{"type":"status","attrs":{"text":"BLOCKED","color":"red"}},
            {"type":"text","text":" "},{"type":"inlineCard","attrs":{"url":"https://example.test/synthetic-card"}},
            {"type":"text","text":" <b>not html</b>"}
          ]},
          {"type":"heading","attrs":{"level":5},"content":[{"type":"text","text":"A level 5 heading shows as level 3"}]},
          {"type":"bulletList","content":[
            {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"First synthetic point"}]},
              {"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Nested point"}]}]}]}]},
            {"type":"listItem","content":[{"type":"codeBlock","attrs":{"language":"kotlin"},"content":[{"type":"text","text":"val inList = true\nprintln(inList)"}]}]},
            {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Item with a table"}]},{"type":"table","content":[]}]}
          ]},
          {"type":"orderedList","attrs":{"order":3},"content":[
            {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Third step"}]}]},
            {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Fourth step"}]}]}
          ]},
          {"type":"taskList","attrs":{"localId":"synthetic-tasks"},"content":[
            {"type":"taskItem","attrs":{"localId":"t1","state":"DONE"},"content":[{"type":"text","text":"Done synthetic task"}]},
            {"type":"taskItem","attrs":{"localId":"t2","state":"TODO"},"content":[{"type":"text","text":"Open synthetic task"}]},
            {"type":"taskList","attrs":{"localId":"synthetic-nested"},"content":[
              {"type":"taskItem","attrs":{"localId":"t3","state":"TODO"},"content":[{"type":"text","text":"Nested task"}]}
            ]}
          ]},
          {"type":"decisionList","attrs":{"localId":"synthetic-decisions"},"content":[
            {"type":"decisionItem","attrs":{"localId":"d1","state":"DECIDED"},"content":[{"type":"text","text":"Synthetic decision"}]}
          ]},
          {"type":"codeBlock","attrs":{"language":"json"},"content":[{"type":"text","text":"{\n  \"synthetic\": true\n}"}]},
          {"type":"blockquote","content":[{"type":"paragraph","content":[{"type":"text","text":"A quoted synthetic line"}]}]},
          {"type":"panel","attrs":{"panelType":"info"},"content":[{"type":"paragraph","content":[{"type":"text","text":"Panel text"}]}]},
          {"type":"expand","attrs":{"title":"Synthetic expand"},"content":[{"type":"paragraph","content":[{"type":"text","text":"Expanded text"}]}]},
          {"type":"rule"},
          {"type":"table","attrs":{"layout":"default"},"content":[{"type":"tableRow","content":[
            {"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"Cell"}]}]}]}]},
          {"type":"mediaSingle","attrs":{"layout":"center"},"content":[{"type":"media","attrs":{"id":"synthetic-media","type":"file","collection":"synthetic"}}]},
          {"type":"extension","attrs":{"extensionType":"com.example.synthetic","extensionKey":"synthetic-macro"}},
          {"type":"syncBlock","content":[]}
        ]}
        """.trimIndent()

    private val ISSUES: Map<String, String> =
        mapOf(
            "SYN-101" to
                """
                {"expand":"renderedFields","id":"10101","self":"https://synthetic.example.test/issue/10101","key":"SYN-101",
                 "fields":{
                   "summary":"SYNTHETIC: Show Jira tickets read-only in Thread",
                   "issuetype":{"id":"10001","name":"Story","subtask":false,"iconUrl":"https://synthetic.example.test/icon"},
                   "status":{"name":"In Review","id":"3","statusCategory":{"id":4,"key":"indeterminate","name":"In Progress"}},
                   "priority":{"name":"High","id":"2"},
                   "assignee":{"accountId":"synthetic-account-a","displayName":"Synthetic Person A","emailAddress":"a@example.test",
                               "avatarUrls":{"48x48":"https://synthetic.example.test/a.png"}},
                   "reporter":{"accountId":"synthetic-account-b","displayName":"Synthetic Person B"},
                   "labels":["synthetic","m1-probe"],
                   "updated":"2026-11-12T16:42:10.123+0400",
                   "description":$SYN_101_DESCRIPTION,
                   "$SPRINT_FIELD":[
                     {"id":40,"name":"Synthetic Sprint 40","state":"closed","boardId":7},
                     {"id":41,"name":"Synthetic Sprint 41","state":"active","boardId":7,"goal":"Synthetic goal"}
                   ],
                   "issuelinks":[
                     {"id":"30001","type":{"id":"1","name":"Blocks","inward":"is blocked by","outward":"blocks"},
                      "inwardIssue":{"id":"10104","key":"SYN-104","fields":{"summary":"SYNTHETIC: Rate limit message when Jira slows down",
                        "status":{"name":"In Progress","statusCategory":{"key":"indeterminate"}}}}},
                     {"id":"30002","type":{"id":"2","name":"Relates","inward":"relates to","outward":"relates to"},
                      "outwardIssue":{"id":"10106","key":"SYN-106","fields":{"summary":"SYNTHETIC: Search tickets by key, words or label",
                        "status":{"name":"In Progress","statusCategory":{"key":"indeterminate"}}}}}
                   ]
                 }}
                """.trimIndent(),
            "SYN-102" to
                """
                {"id":"10102","key":"SYN-102",
                 "fields":{
                   "summary":"SYNTHETIC: A finished ticket without a description",
                   "issuetype":{"name":"Bug"},
                   "status":{"name":"Done","statusCategory":{"key":"done"}},
                   "priority":null,"assignee":null,"reporter":{"displayName":"Synthetic Person A"},
                   "labels":[],"updated":"2026-11-02T09:00:00.000+0000","description":null,"$SPRINT_FIELD":null
                 }}
                """.trimIndent(),
        )

    /** Newest first, as Jira answers with `orderBy=-created`. */
    private val COMMENTS: Map<String, String> =
        mapOf(
            "SYN-101" to
                """
                {"startAt":0,"maxResults":100,"total":2,"comments":[
                  {"id":"20002","author":{"accountId":"synthetic-account-a","displayName":"Synthetic Person A","emailAddress":"a@example.test"},
                   "created":"2026-11-12T15:30:00.000+0400","updated":"2026-11-12T15:30:00.000+0400",
                   "body":{"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"Newest synthetic comment."}]}]}},
                  {"id":"20001","author":{"accountId":"synthetic-account-b","displayName":"Synthetic Person B"},
                   "created":"2026-11-11T10:05:00.000+0400",
                   "body":{"type":"doc","version":1,"content":[{"type":"paragraph","content":[
                     {"type":"text","text":"Older synthetic comment for "},{"type":"mention","attrs":{"id":"x","text":"@Synthetic Person A"}}]},
                     {"type":"table","content":[]}]}}
                ]}
                """.trimIndent(),
            "SYN-102" to """{"startAt":0,"maxResults":100,"total":0,"comments":[]}""",
        )

    /** The token's own SYNTHETIC account (`GET /user`). */
    private const val VIEWER = """{"login":"synthetic-me","id":1,"avatar_url":"https://avatars.githubusercontent.com/u/1?v=4"}"""

    /** SYNTHETIC assignees, as GitHub sends them in a search answer. Id 5 has no avatar image in the screen checks. */
    private fun people(vararg ids: Int): String =
        ids.joinToString(",", "[", "]") { id ->
            val login =
                when (id) {
                    1 -> "synthetic-me"
                    2 -> "synthetic-teammate"
                    3 -> "synthetic-reviewer-a"
                    4 -> "synthetic-reviewer-b"
                    else -> "synthetic-person-with-a-long-login"
                }
            """{"login":"$login","id":$id,"avatar_url":"https://avatars.githubusercontent.com/u/$id?v=4","type":"User"}"""
        }

    /** Mine (owner change #8): three repositories; no, one, two and three assignees; the viewer among them. */
    private val SEARCH_MINE =
        """
        {"total_count":5,"incomplete_results":false,"items":[
          {"number":41,"title":"SYNTHETIC: Paginate the sync endpoint","user":{"login":"synthetic-me","id":1},"draft":false,
           "updated_at":"2026-11-13T10:20:00Z","repository_url":"https://api.github.com/repos/synthetic-org/synthetic-api",
           "assignees":${people(2, 1, 3)}},
          {"number":12,"title":"SYNTHETIC: Add the offline shell","user":{"login":"synthetic-me","id":1},"draft":false,
           "updated_at":"2026-11-12T14:00:00Z","repository_url":"https://api.github.com/repos/synthetic-org/synthetic-app",
           "html_url":"https://github.com/synthetic-org/synthetic-app/pull/12","pull_request":{"url":"x"},"assignees":${people(1)}},
          {"number":13,"title":"SYNTHETIC: Draft change with failing CI","user":{"login":"synthetic-me"},"draft":true,
           "updated_at":"2026-11-11T08:30:00Z","repository_url":"https://api.github.com/repos/synthetic-org/synthetic-app",
           "assignees":[]},
          {"number":38,"title":"SYNTHETIC: Retry on 503 with backoff","user":{"login":"synthetic-me"},"draft":false,
           "updated_at":"2026-11-10T12:00:00Z","repository_url":"https://api.github.com/repos/synthetic-org/synthetic-api",
           "assignees":${people(2, 4)}},
          {"number":5,"title":"SYNTHETIC: Rename the surface tokens","user":{"login":"synthetic-me"},"draft":false,
           "updated_at":"2026-11-09T17:45:00Z","repository_url":"https://api.github.com/repos/synthetic-labs/synthetic-design-tokens",
           "assignees":${people(4)}}
        ]}
        """.trimIndent()

    /** Review requested (owner change #8): the viewer assigned to someone else's pull request, four assignees, a long name. */
    private val SEARCH_REVIEW =
        """
        {"total_count":4,"incomplete_results":false,"items":[
          {"number":44,"title":"SYNTHETIC: Add request ids to the logs","user":{"login":"synthetic-reviewer-a"},"draft":false,
           "updated_at":"2026-11-13T15:05:00Z","repository_url":"https://api.github.com/repos/synthetic-org/synthetic-api",
           "assignees":${people(1)}},
          {"number":7,"title":"SYNTHETIC: Review requested from you","user":{"login":"synthetic-teammate"},"draft":false,
           "updated_at":"2026-11-12T11:15:00Z","repository_url":"https://api.github.com/repos/synthetic-org/synthetic-lib",
           "assignees":${people(2)}},
          {"number":101,"title":"SYNTHETIC: A long title that wraps over two lines on a narrow screen, to check the row",
           "user":{"login":"synthetic-person-with-a-long-login"},"draft":false,"updated_at":"2026-11-11T19:30:00Z",
           "repository_url":"https://api.github.com/repos/synthetic-labs/synthetic-repository-with-a-very-long-name-for-wrapping",
           "assignees":${people(3, 5, 4, 2)}},
          {"number":9,"title":"SYNTHETIC: Bump the date library","user":{"login":"synthetic-teammate"},"draft":true,
           "updated_at":"2026-11-08T09:00:00Z","repository_url":"https://api.github.com/repos/synthetic-org/synthetic-lib",
           "assignees":null}
        ]}
        """.trimIndent()

    /** A plain SYNTHETIC pull request for the rows above that have no detail of their own: no description, CI passing. */
    private fun plainPull(
        id: String,
        title: String,
        author: String,
        updated: String,
        shaDigit: Char,
    ): Pair<String, SyntheticPull> {
        val sha = shaDigit.toString().repeat(40)
        val number = id.substringAfter('#')
        return id to
            SyntheticPull(
                sha = sha,
                json =
                    """{"number":$number,"title":"$title","user":{"login":"$author"},"draft":false,""" +
                        """"head":{"ref":"feature/synthetic-$number","sha":"$sha"},"base":{"ref":"main"},"body":null,""" +
                        """"updated_at":"$updated","requested_reviewers":[{"login":"synthetic-me"}],"requested_teams":[]}""",
                reviews = "[]",
                statuses = """{"state":"success","total_count":1,"statuses":[{"context":"synthetic/lint","state":"success"}]}""",
                checkRuns = """{"total_count":1,"check_runs":[{"name":"build","status":"completed","conclusion":"success"}]}""",
            )
    }

    private class SyntheticPull(
        val sha: String,
        val json: String,
        val reviews: String,
        val statuses: String?,
        val checkRuns: String,
    )

    /** A SYNTHETIC description that uses every Markdown element Thread shows (D57). It names SYN-103 and SYN-105 (D49). */
    private val MARKDOWN_SHOWCASE: String =
        """
        <!-- SYNTHETIC: the template's hint. GitHub hides it. -->
        ## Summary

        SYNTHETIC description. Adds the **offline shell**, so the app opens _without a network_. ~~Ship it later~~ It ships now,
        after #13. Thanks @synthetic-reviewer-a!

        Follows SYN-103 and SYN-105.

        ### Changes

        - Cache the app shell with a `service worker`
        - Keep the sync status truthful
          - pending edits stay on the device
          - see the [synthetic notes](https://example.com/synthetic-notes)
        - First line of an item<br>second line of the same item

        ### Checklist

        - [x] Unit tests
        - [x] Offline reload checked
        - [ ] Safari check

        ### Steps to test

        1. Open the app and go offline.
        2. Reload the page.
        3. Edit a Note, then go online again.

        > **Note**
        > The shell is versioned by its build id, so an old shell never serves new code.

        ```kotlin
        val shell = OfflineShell(buildId = "synthetic")
        shell.install()
        ```

        | Screen | Before | After |
        |:-------|:------:|------:|
        | Today | 1.2 s | 0.3 s |
        | Notes | 0.9 s | 0.2 s |

        ---

        ![Synthetic screenshot](https://example.com/synthetic-screenshot.png)

        #### Follow-ups
        Tracked in https://example.com/synthetic-board and on www.example.com/synthetic.
        """.trimIndent()

    /** A SYNTHETIC hostile description (D57). All of it must show as harmless text, and no link may run script. */
    private val HOSTILE_BODY: String =
        """
        SYNTHETIC hostile description. Everything below must show as plain text.

        <script>alert('synthetic')</script>
        [javascript link](javascript:alert(1)) [data link](data:text/html;base64,PHNjcmlwdD4=) <javascript:alert(1)>
        <a href="javascript:alert(1)">raw anchor</a> <img src="javascript:alert(1)" onerror="alert(1)">
        <iframe src="https://evil.example"></iframe> <style>body{display:none}</style> <svg onload=alert(1)>

        > > > > > > > > > > > > > > > > > > > > Deeply quoted text

        A table of 30 columns and 150 rows follows. Thread shows 20 columns and 100 rows.

        """.trimIndent() + "\n\n" + hostileTable()

    private fun hostileTable(): String {
        val header = (1..30).joinToString("|", "|", "|") { "Column $it" }
        val delimiter = "|---".repeat(30) + "|"
        val rows = (1..150).joinToString("\n") { row -> "|<script>row $row</script>" + "|cell".repeat(29) + "|" }
        return "$header\n$delimiter\n$rows"
    }

    private val PULLS: Map<String, SyntheticPull> =
        mapOf(
            "synthetic-org/synthetic-app#12" to
                SyntheticPull(
                    sha = "1".repeat(40),
                    json =
                        """
                        {"number":12,"title":"SYNTHETIC: Add the offline shell","user":{"login":"synthetic-me"},"draft":false,
                         "head":{"ref":"feature/offline-shell","sha":"${"1".repeat(40)}"},"base":{"ref":"main"},
                         "body":${JsonPrimitive(MARKDOWN_SHOWCASE)},
                         "updated_at":"2026-11-12T14:00:00Z",
                         "requested_reviewers":[{"login":"synthetic-reviewer-b"}],"requested_teams":[{"name":"synthetic-team","slug":"synthetic-team"}]}
                        """.trimIndent(),
                    reviews =
                        """
                        [{"user":{"login":"synthetic-reviewer-a"},"state":"COMMENTED"},
                         {"user":{"login":"synthetic-reviewer-a"},"state":"APPROVED"},
                         {"user":{"login":"synthetic-reviewer-b"},"state":"CHANGES_REQUESTED"},
                         {"user":{"login":"synthetic-me"},"state":"COMMENTED"}]
                        """.trimIndent(),
                    statuses = """{"state":"success","total_count":1,"statuses":[{"context":"synthetic/lint","state":"success"}]}""",
                    checkRuns =
                        """
                        {"total_count":2,"check_runs":[{"name":"build","status":"completed","conclusion":"success"},
                          {"name":"tests","status":"in_progress","conclusion":null}]}
                        """.trimIndent(),
                ),
            "synthetic-org/synthetic-app#13" to
                SyntheticPull(
                    sha = "2".repeat(40),
                    json =
                        """
                        {"number":13,"title":"SYNTHETIC: Draft change with failing CI","user":{"login":"synthetic-me"},"draft":true,
                         "head":{"ref":"feature/draft","sha":"${"2".repeat(40)}"},"base":{"ref":"main"},"body":null,
                         "updated_at":"2026-11-11T08:30:00Z","requested_reviewers":[],"requested_teams":[]}
                        """.trimIndent(),
                    reviews = """[{"user":{"login":"synthetic-reviewer-a"},"state":"CHANGES_REQUESTED"}]""",
                    statuses = """{"state":"pending","total_count":0,"statuses":[]}""",
                    checkRuns = """{"total_count":1,"check_runs":[{"name":"build","status":"completed","conclusion":"failure"}]}""",
                ),
            "synthetic-org/synthetic-app#14" to
                SyntheticPull(
                    sha = "4".repeat(40),
                    json =
                        """
                        {"number":14,"title":"SYNTHETIC: Ticket view polish (SYN-101)","user":{"login":"synthetic-teammate"},"draft":false,
                         "state":"closed","head":{"ref":"feature/ticket-polish","sha":"${"4".repeat(40)}"},"base":{"ref":"main"},
                         "body":"Closed synthetic change.","updated_at":"2026-11-10T16:00:00Z","requested_reviewers":[],"requested_teams":[]}
                        """.trimIndent(),
                    reviews = """[{"user":{"login":"synthetic-me"},"state":"APPROVED"}]""",
                    statuses = """{"state":"success","total_count":1,"statuses":[{"context":"synthetic/lint","state":"success"}]}""",
                    checkRuns = """{"total_count":1,"check_runs":[{"name":"build","status":"completed","conclusion":"success"}]}""",
                ),
            "synthetic-org/synthetic-app#15" to
                SyntheticPull(
                    sha = "5".repeat(40),
                    json =
                        """
                        {"number":15,"title":"SYNTHETIC: Hostile description check","user":{"login":"synthetic-teammate"},"draft":false,
                         "head":{"ref":"test/hostile","sha":"${"5".repeat(
                            40,
                        )}"},"base":{"ref":"main"},"body":${JsonPrimitive(HOSTILE_BODY)},
                         "updated_at":"2026-11-13T09:00:00Z","requested_reviewers":[],"requested_teams":[]}
                        """.trimIndent(),
                    reviews = "[]",
                    statuses = """{"state":"success","total_count":1,"statuses":[{"context":"synthetic/lint","state":"success"}]}""",
                    checkRuns = """{"total_count":1,"check_runs":[{"name":"build","status":"completed","conclusion":"success"}]}""",
                ),
            "synthetic-org/synthetic-lib#7" to
                SyntheticPull(
                    sha = "3".repeat(40),
                    json =
                        """
                        {"number":7,"title":"SYNTHETIC: Review requested from you","user":{"login":"synthetic-teammate"},"draft":false,
                         "head":{"ref":"fix/synthetic","sha":"${"3".repeat(40)}"},"base":{"ref":"develop"},"body":"Short synthetic body.",
                         "updated_at":"2026-11-12T11:15:00Z","requested_reviewers":[{"login":"synthetic-me"}],"requested_teams":[]}
                        """.trimIndent(),
                    reviews = "[]",
                    // This token may not read commit statuses here: the detail shows CI from check runs only.
                    statuses = null,
                    checkRuns = """{"total_count":1,"check_runs":[{"name":"build","status":"completed","conclusion":"success"}]}""",
                ),
            plainPull(
                "synthetic-org/synthetic-api#41",
                "SYNTHETIC: Paginate the sync endpoint",
                "synthetic-me",
                "2026-11-13T10:20:00Z",
                'a',
            ),
            plainPull(
                "synthetic-org/synthetic-api#38",
                "SYNTHETIC: Retry on 503 with backoff",
                "synthetic-me",
                "2026-11-10T12:00:00Z",
                'b',
            ),
            plainPull(
                "synthetic-labs/synthetic-design-tokens#5",
                "SYNTHETIC: Rename the surface tokens",
                "synthetic-me",
                "2026-11-09T17:45:00Z",
                'c',
            ),
            plainPull(
                "synthetic-org/synthetic-api#44",
                "SYNTHETIC: Add request ids to the logs",
                "synthetic-reviewer-a",
                "2026-11-13T15:05:00Z",
                'd',
            ),
            plainPull(
                "synthetic-labs/synthetic-repository-with-a-very-long-name-for-wrapping#101",
                "SYNTHETIC: A long title that wraps over two lines on a narrow screen, to check the row",
                "synthetic-person-with-a-long-login",
                "2026-11-11T19:30:00Z",
                'e',
            ),
            plainPull(
                "synthetic-org/synthetic-lib#9",
                "SYNTHETIC: Bump the date library",
                "synthetic-teammate",
                "2026-11-08T09:00:00Z",
                'f',
            ),
        )
}
