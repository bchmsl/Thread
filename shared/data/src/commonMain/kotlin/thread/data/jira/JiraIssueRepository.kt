package thread.data.jira

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import thread.data.external.KeyValueStore
import thread.data.external.SavedRecord
import thread.data.external.asObject
import thread.data.external.long
import thread.data.external.str
import thread.data.external.succeeded
import thread.data.http.BAD_RESPONSE
import thread.data.http.HttpTransport
import thread.domain.external.ConnectionStore
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalResult
import thread.domain.external.JiraCloudId
import thread.domain.external.JiraConnection
import thread.domain.external.JiraIssue
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraIssueSource
import thread.domain.external.JiraSite
import thread.domain.external.Refreshed
import thread.domain.external.Saved

/**
 * Jira issues from the REST API, with a saved copy of each opened issue on this device (adr/ADR-006).
 *
 * One refresh sends up to three GET requests: `/field` (at most once a day, to find the Sprint field), the issue,
 * and its newest comments. Copies live in [issues], keyed by issue key and marked with the site; [meta] keeps the
 * Sprint field id. Nothing here is written to Firestore.
 */
class JiraIssueRepository(
    private val connections: ConnectionStore,
    transport: HttpTransport,
    private val issues: KeyValueStore,
    private val meta: KeyValueStore,
) : JiraIssueSource {
    private val client = JiraClient(transport)

    override suspend fun isConnected(): Boolean = connections.jira() != null

    override suspend fun readSaved(key: JiraIssueKey): Saved<JiraIssue>? {
        val connection = connections.jira() ?: return null
        val raw = issues.get(key.value) ?: return null
        return decode(raw, connection.site)
    }

    override suspend fun allSaved(): List<Saved<JiraIssue>> {
        val connection = connections.jira() ?: return emptyList()
        return issues
            .all()
            .mapNotNull { (_, raw) -> decode(raw, connection.site) }
            .sortedByDescending { it.savedAtMillis }
    }

    override suspend fun refresh(
        key: JiraIssueKey,
        nowMillis: Long,
    ): ExternalResult<Refreshed<JiraIssue>> {
        val connection = connections.jira() ?: return ExternalResult.Problem(ExternalProblem.NotConnected)
        val sprintFieldId =
            when (val result = sprintFieldId(connection, nowMillis)) {
                is ExternalResult.Ok -> result.value
                is ExternalResult.Problem -> return result
            }
        val issue =
            when (val result = client.issue(connection, key, sprintFieldId, nowMillis)) {
                is ExternalResult.Ok -> result.value
                is ExternalResult.Problem -> return result
            }
        val comments =
            when (val result = client.comments(connection, key, nowMillis)) {
                is ExternalResult.Ok -> result.value
                is ExternalResult.Problem -> return result
            }
        val bundle = JiraJson.bundle(issue, comments, sprintFieldId) ?: return ExternalResult.Problem(BAD_RESPONSE)
        val parsed = JiraJson.issue(bundle, connection.site) ?: return ExternalResult.Problem(BAD_RESPONSE)
        val stored = succeeded { issues.put(key.value, SavedRecord.encode(nowMillis, connection.site.host, bundle)) }
        return ExternalResult.Ok(Refreshed(Saved(parsed, nowMillis), stored))
    }

    override suspend fun forgetSaved(key: JiraIssueKey) {
        issues.delete(key.value)
    }

    /** Deletes every saved Jira copy on this device. */
    suspend fun forgetAllSaved() {
        issues.clear()
        meta.clear()
    }

    /** Asks the site for its cloud id. Browsers may block this (CORS); the person can then type the id. */
    suspend fun lookUpCloudId(
        site: JiraSite,
        nowMillis: Long,
    ): ExternalResult<JiraCloudId> = client.cloudId(site, nowMillis)

    private fun decode(
        raw: String,
        site: JiraSite,
    ): Saved<JiraIssue>? {
        val (savedAt, bundle) = SavedRecord.decode(raw, site.host) ?: return null
        return JiraJson.issue(bundle, site)?.let { Saved(it, savedAt) }
    }

    /**
     * The Sprint field id, from [meta] when it is less than a day old. A failed `/field` request only hides the
     * sprint, except when it shows that no request can work now (no answer, expired token, rate limit).
     */
    private suspend fun sprintFieldId(
        connection: JiraConnection,
        nowMillis: Long,
    ): ExternalResult<String?> {
        val metaKey = "sprintField:${connection.cloudId.value}"
        val cached =
            try {
                meta.get(metaKey)?.let { Json.parseToJsonElement(it).asObject() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        val cachedAt = cached?.long("savedAtMillis")
        if (cached != null && cachedAt != null && nowMillis - cachedAt in 0 until FIELD_CACHE_MILLIS) {
            return ExternalResult.Ok(cached.str("id"))
        }
        return when (val result = client.fields(connection, nowMillis)) {
            is ExternalResult.Ok -> {
                val id = JiraJson.sprintFieldId(result.value)
                val record =
                    buildJsonObject {
                        put("id", id)
                        put("savedAtMillis", nowMillis)
                    }
                succeeded { meta.put(metaKey, record.toString()) }
                ExternalResult.Ok(id)
            }
            is ExternalResult.Problem ->
                when (result.problem) {
                    ExternalProblem.Unreachable, ExternalProblem.Unauthorized, is ExternalProblem.RateLimited -> result
                    else -> ExternalResult.Ok(null)
                }
        }
    }

    private companion object {
        const val FIELD_CACHE_MILLIS = 24 * 60 * 60 * 1_000L
    }
}
