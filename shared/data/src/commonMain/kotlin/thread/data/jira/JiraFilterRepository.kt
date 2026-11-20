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
import thread.domain.external.JiraConnection
import thread.domain.external.JiraFilter
import thread.domain.external.JiraFilterId
import thread.domain.external.JiraIssueList
import thread.domain.external.JiraStarredFilters
import thread.domain.external.Refreshed
import thread.domain.external.Saved

/**
 * The filters the account starred in Jira (D103), saved on this device in [meta] with their names and queries.
 * Nothing here is written to Firestore.
 */
class JiraStarredFilterRepository(
    private val connections: ConnectionStore,
    transport: HttpTransport,
    private val meta: KeyValueStore,
) : ExternalRecordSource<JiraStarredFilters, List<JiraFilter>> {
    private val client = JiraClient(transport)

    override suspend fun isConnected(): Boolean = connections.jira() != null

    override suspend fun readSaved(key: JiraStarredFilters): Saved<List<JiraFilter>>? {
        val connection = connections.jira() ?: return null
        return savedStarred(meta, connection)
    }

    override suspend fun refresh(
        key: JiraStarredFilters,
        nowMillis: Long,
    ): ExternalResult<Refreshed<List<JiraFilter>>> {
        val connection = connections.jira() ?: return ExternalResult.Problem(ExternalProblem.NotConnected)
        val data =
            when (val result = client.starredFilters(connection, nowMillis)) {
                is ExternalResult.Ok -> JiraJson.starredFilters(result.value) ?: return ExternalResult.Problem(BAD_RESPONSE)
                is ExternalResult.Problem -> return result
            }
        val stored = succeeded { meta.put(STARRED_KEY, SavedRecord.encode(nowMillis, connection.site.host, data)) }
        return ExternalResult.Ok(Refreshed(Saved(JiraJson.filters(data), nowMillis), stored))
    }

    override suspend fun forgetSaved(key: JiraStarredFilters) {
        meta.delete(STARRED_KEY)
    }
}

/**
 * The tickets of one filter (D103), run with the filter's own query through the enhanced JQL search, up to
 * [FILTER_PAGES] pages. The query comes from the saved starred list, else from `GET /filter/{id}`. The rows hold only
 * the row fields and are saved on this device in [meta], one record per filter.
 */
class JiraFilterRowsRepository(
    private val connections: ConnectionStore,
    transport: HttpTransport,
    private val meta: KeyValueStore,
) : ExternalRecordSource<JiraFilterId, JiraIssueList> {
    private val client = JiraClient(transport)

    override suspend fun isConnected(): Boolean = connections.jira() != null

    override suspend fun readSaved(key: JiraFilterId): Saved<JiraIssueList>? {
        val connection = connections.jira() ?: return null
        val raw = meta.get(rowsKey(key)) ?: return null
        val (savedAt, data) = SavedRecord.decode(raw, connection.site.host) ?: return null
        return Saved(JiraJson.issueList(data), savedAt)
    }

    /** A 400 means Jira could not run the filter's query (for example, it names something that is gone). */
    override suspend fun refresh(
        key: JiraFilterId,
        nowMillis: Long,
    ): ExternalResult<Refreshed<JiraIssueList>> {
        val connection = connections.jira() ?: return ExternalResult.Problem(ExternalProblem.NotConnected)
        val jql =
            when (val query = query(connection, key, nowMillis)) {
                is ExternalResult.Ok -> query.value
                is ExternalResult.Problem -> return query
            }
        val issues = mutableListOf<JsonObject>()
        var token: String? = null
        var complete = false
        for (page in 1..FILTER_PAGES) {
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
        val stored = succeeded { meta.put(rowsKey(key), SavedRecord.encode(nowMillis, connection.site.host, data)) }
        return ExternalResult.Ok(Refreshed(Saved(JiraJson.issueList(data), nowMillis), stored))
    }

    override suspend fun forgetSaved(key: JiraFilterId) {
        meta.delete(rowsKey(key))
    }

    /** The filter's query: from the saved starred list, else asked of Jira. */
    private suspend fun query(
        connection: JiraConnection,
        id: JiraFilterId,
        nowMillis: Long,
    ): ExternalResult<String> {
        savedStarred(meta, connection)
            ?.value
            ?.firstOrNull { it.id == id }
            ?.jql
            ?.takeIf { it.isNotBlank() }
            ?.let { return ExternalResult.Ok(it) }
        return when (val result = client.filter(connection, id, nowMillis)) {
            is ExternalResult.Problem -> result
            is ExternalResult.Ok ->
                JiraJson
                    .filter(result.value)
                    ?.takeIf { it.id == id }
                    ?.jql
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ExternalResult.Ok(it) }
                    ?: ExternalResult.Problem(BAD_RESPONSE)
        }
    }

    private fun rowsKey(id: JiraFilterId): String = "filter:${id.value}"

    internal companion object {
        /** At most 100 rows (2 pages of 50), in the filter's own order. */
        const val FILTER_PAGES = 2
    }
}

private const val STARRED_KEY = "filters:starred"

private suspend fun savedStarred(
    meta: KeyValueStore,
    connection: JiraConnection,
): Saved<List<JiraFilter>>? {
    val raw = meta.get(STARRED_KEY) ?: return null
    val (savedAt, data) = SavedRecord.decode(raw, connection.site.host) ?: return null
    return Saved(JiraJson.filters(data), savedAt)
}
