package thread.data.jira

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import thread.data.external.KeyValueStore
import thread.data.external.SavedRecord
import thread.data.external.succeeded
import thread.data.http.BAD_RESPONSE
import thread.data.http.HttpTransport
import thread.domain.external.ConnectionStore
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalRecordSource
import thread.domain.external.ExternalResult
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraIssueList
import thread.domain.external.JiraList
import thread.domain.external.JiraSearch
import thread.domain.external.JiraSprints
import thread.domain.external.Refreshed
import thread.domain.external.Saved

/**
 * The Jira page lists (adr/ADR-013-jira-in-the-workspace.md): "Assigned to me", saved on this device, and Search,
 * which is never saved. Both use the enhanced JQL search. A saved list holds only the row fields (key, type, title,
 * status, updated) and lives in [meta] next to the Sprint field id. Nothing here is written to Firestore.
 *
 * The assigned list has every issue type: the JQL names none, and no row is dropped for its type (D50). With "Current
 * sprint only" it is a second list, saved apart, of the tickets in an active sprint.
 */
class JiraListRepository(
    private val connections: ConnectionStore,
    transport: HttpTransport,
    private val meta: KeyValueStore,
) : ExternalRecordSource<JiraList, JiraIssueList>,
    JiraSearch,
    JiraSprints {
    private val client = JiraClient(transport)
    private val sprintField = JiraSprintField(client, meta)

    override suspend fun isConnected(): Boolean = connections.jira() != null

    override suspend fun readSaved(key: JiraList): Saved<JiraIssueList>? {
        val connection = connections.jira() ?: return null
        val raw = meta.get(metaKey(key)) ?: return null
        val (savedAt, data) = SavedRecord.decode(raw, connection.site.host) ?: return null
        return Saved(JiraJson.issueList(data), savedAt)
    }

    /** Up to [LIST_PAGES] pages of [JiraClient.SEARCH_PAGE]; [JiraIssueList.complete] says whether that was all. */
    override suspend fun refresh(
        key: JiraList,
        nowMillis: Long,
    ): ExternalResult<Refreshed<JiraIssueList>> {
        val connection = connections.jira() ?: return ExternalResult.Problem(ExternalProblem.NotConnected)
        val jql =
            when (key) {
                JiraList.Assigned -> JiraQuery.ASSIGNED
                JiraList.AssignedCurrentSprint -> JiraQuery.ASSIGNED_CURRENT_SPRINT
            }
        val issues = mutableListOf<JsonObject>()
        var token: String? = null
        var complete = false
        for (page in 1..LIST_PAGES) {
            val answer =
                when (val result = client.search(connection, jql, JiraJson.SUMMARY_FIELDS, token, nowMillis)) {
                    is ExternalResult.Ok -> JiraJson.searchPage(result.value) ?: return ExternalResult.Problem(BAD_RESPONSE)
                    is ExternalResult.Problem -> return result
                }
            issues += answer.issues
            token = answer.nextPageToken
            complete = answer.isLast || token == null
            if (complete) break
        }
        val data =
            buildJsonObject {
                put("issues", JsonArray(issues))
                put("complete", JsonPrimitive(complete))
            }
        val stored = succeeded { meta.put(metaKey(key), SavedRecord.encode(nowMillis, connection.site.host, data)) }
        return ExternalResult.Ok(Refreshed(Saved(JiraJson.issueList(data), nowMillis), stored))
    }

    override suspend fun forgetSaved(key: JiraList) {
        meta.delete(metaKey(key))
    }

    /** One page of results. A key that does not exist or is not shared answers 400; that is no result. */
    override suspend fun search(
        query: String,
        nowMillis: Long,
    ): ExternalResult<JiraIssueList> {
        val connection = connections.jira() ?: return ExternalResult.Problem(ExternalProblem.NotConnected)
        val jql = JiraQuery.search(query) ?: return ExternalResult.Ok(JiraIssueList(emptyList(), complete = true))
        return when (val result = client.search(connection, jql.text, JiraJson.SUMMARY_FIELDS, null, nowMillis)) {
            is ExternalResult.Problem ->
                if (jql.keyOnly && result.problem == ExternalProblem.Failed("http-400")) {
                    ExternalResult.Ok(JiraIssueList(emptyList(), complete = true))
                } else {
                    result
                }
            is ExternalResult.Ok -> {
                val page = JiraJson.searchPage(result.value) ?: return ExternalResult.Problem(BAD_RESPONSE)
                ExternalResult.Ok(JiraIssueList(page.issues.mapNotNull(JiraJson::summary), complete = page.isLast))
            }
        }
    }

    override suspend fun hasSprints(
        nowMillis: Long,
        mayAsk: Boolean,
    ): Boolean? {
        val connection = connections.jira() ?: return null
        return sprintField.exists(connection, nowMillis, mayAsk)
    }

    private fun metaKey(key: JiraList): String = "list:${key.name.lowercase()}"

    internal companion object {
        /** At most 500 rows (10 pages of 50). Most people have far fewer, so one request is usual. */
        const val LIST_PAGES = 10
    }
}

/** The JQL Thread sends. Typed text never reaches JQL unescaped. */
internal object JiraQuery {
    /** v3 "Assigned to me": open tickets of every type, newest change first. */
    const val ASSIGNED: String = "assignee = currentUser() AND statusCategory != Done ORDER BY updated DESC"

    /** "Current sprint only" (D50): `openSprints()` is the sprints that started and are not completed yet. */
    const val ASSIGNED_CURRENT_SPRINT: String =
        "assignee = currentUser() AND statusCategory != Done AND sprint in openSprints() ORDER BY updated DESC"

    private val SPECIAL = Regex("[+\\-&|!(){}\\[\\]^~*?:\\\\/\"']")
    private val SPACES = Regex("\\s+")
    private val LABEL = Regex("^[A-Za-z0-9_.\\-]{1,255}$")

    class Query(
        val text: String,
        val keyOnly: Boolean,
    )

    /**
     * "Key, words or label" (v3): a key looks up that ticket; otherwise the words go to a text search, and a single
     * word is also tried as a label. Lucene special characters and quotes are dropped. Null when nothing is left.
     */
    fun search(raw: String): Query? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        JiraIssueKey.parse(text)?.let { return Query("key = \"${it.value}\"", keyOnly = true) }
        val words = text.replace(SPECIAL, " ").split(SPACES).filter { it.isNotBlank() }
        val clauses = mutableListOf<String>()
        if (words.isNotEmpty()) clauses += "text ~ \"${words.joinToString(" ")}\""
        if (LABEL.matches(text)) clauses += "labels = \"$text\""
        if (clauses.isEmpty()) return null
        return Query("(${clauses.joinToString(" OR ")}) ORDER BY updated DESC", keyOnly = false)
    }
}
