package thread.data.jira

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import thread.data.external.KeyValueStore
import thread.data.external.asObject
import thread.data.external.long
import thread.data.external.str
import thread.data.external.succeeded
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalResult
import thread.domain.external.JiraConnection

/**
 * The id of the site's Sprint field (Jira Software), found with `GET /field` at most once a day and kept in [meta]
 * on this device. Tickets read the sprint through it; the "Current sprint only" list needs it to exist (D50).
 */
internal class JiraSprintField(
    private val client: JiraClient,
    private val meta: KeyValueStore,
) {
    /**
     * The id, or null when the site has no Sprint field. Saved for a day. A failed `/field` request counts as no Sprint
     * field, except when it shows that no request can work now (no answer, expired token, rate limit).
     */
    suspend fun id(
        connection: JiraConnection,
        nowMillis: Long,
    ): ExternalResult<String?> {
        val cached = cached(connection)
        val cachedAt = cached?.long("savedAtMillis")
        if (cached != null && cachedAt != null && nowMillis - cachedAt in 0 until CACHE_MILLIS) {
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
                succeeded { meta.put(metaKey(connection), record.toString()) }
                ExternalResult.Ok(id)
            }
            is ExternalResult.Problem ->
                when (result.problem) {
                    ExternalProblem.Unreachable, ExternalProblem.Unauthorized, is ExternalProblem.RateLimited -> result
                    else -> ExternalResult.Ok(null)
                }
        }
    }

    /** Whether the site has sprints: the saved answer of any age, and Jira's when it is old and [mayAsk]. */
    suspend fun exists(
        connection: JiraConnection,
        nowMillis: Long,
        mayAsk: Boolean,
    ): Boolean? {
        val saved = cached(connection)?.let { it.str("id") != null }
        if (!mayAsk) return saved
        return when (val result = id(connection, nowMillis)) {
            is ExternalResult.Ok -> result.value != null
            is ExternalResult.Problem -> saved
        }
    }

    private suspend fun cached(connection: JiraConnection): JsonObject? =
        try {
            meta.get(metaKey(connection))?.let { Json.parseToJsonElement(it).asObject() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private fun metaKey(connection: JiraConnection): String = "sprintField:${connection.cloudId.value}"

    private companion object {
        const val CACHE_MILLIS = 24 * 60 * 60 * 1_000L
    }
}
