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

/** The site's own field ids Thread needs; each is null when the site has no such field. */
internal data class JiraSiteFields(
    val sprint: String?,
    val storyPoints: String?,
)

/**
 * The ids of the site's Sprint field (Jira Software) and story points field, found with `GET /field` at most once a
 * day and kept in [meta] on this device. Tickets read the sprint through it; the "Current sprint only" list needs it
 * to exist (D50). The sprint board shows story points through the second when it does not know the board's own
 * estimate field (D103 B).
 */
internal class JiraSprintField(
    private val client: JiraClient,
    private val meta: KeyValueStore,
) {
    /** The Sprint field id, or null when the site has none. See [ids]. */
    suspend fun id(
        connection: JiraConnection,
        nowMillis: Long,
    ): ExternalResult<String?> =
        when (val result = ids(connection, nowMillis)) {
            is ExternalResult.Ok -> ExternalResult.Ok(result.value.sprint)
            is ExternalResult.Problem -> result
        }

    /**
     * Both ids. Saved for a day. A failed `/field` request counts as no such fields, except when it shows that no
     * request can work now (no answer, expired token, rate limit).
     */
    suspend fun ids(
        connection: JiraConnection,
        nowMillis: Long,
    ): ExternalResult<JiraSiteFields> {
        val cached = cached(connection)
        val cachedAt = cached?.long("savedAtMillis")
        // A record saved before the story points field was kept has no "points" entry: ask again.
        if (cached != null && cachedAt != null && "points" in cached && nowMillis - cachedAt in 0 until CACHE_MILLIS) {
            return ExternalResult.Ok(JiraSiteFields(cached.str("id"), cached.str("points")))
        }
        return when (val result = client.fields(connection, nowMillis)) {
            is ExternalResult.Ok -> {
                val fields = JiraSiteFields(JiraJson.sprintFieldId(result.value), JiraJson.storyPointsFieldId(result.value))
                val record =
                    buildJsonObject {
                        put("id", fields.sprint)
                        put("points", fields.storyPoints)
                        put("savedAtMillis", nowMillis)
                    }
                succeeded { meta.put(metaKey(connection), record.toString()) }
                ExternalResult.Ok(fields)
            }
            is ExternalResult.Problem ->
                when (result.problem) {
                    ExternalProblem.Unreachable, ExternalProblem.Unauthorized, is ExternalProblem.RateLimited -> result
                    else -> ExternalResult.Ok(JiraSiteFields(null, null))
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
