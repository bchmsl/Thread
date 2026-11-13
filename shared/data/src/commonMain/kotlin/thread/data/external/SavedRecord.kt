package thread.data.external

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The stored form of a saved copy: `{recordVersion: 1, savedAtMillis, scope, data}`.
 *
 * [data] is the provider answer cut down to the fields Thread shows, so a better mapper also improves old copies.
 * [scope] is the Jira site or `github`: a copy saved for another Jira site is not shown.
 */
internal object SavedRecord {
    private const val VERSION = 1

    fun encode(
        savedAtMillis: Long,
        scope: String,
        data: JsonObject,
    ): String =
        buildJsonObject {
            put("recordVersion", VERSION)
            put("savedAtMillis", savedAtMillis)
            put("scope", scope)
            put("data", data)
        }.toString()

    /** The save time and data, or null when [raw] is damaged, of another version, or of another [scope]. */
    fun decode(
        raw: String,
        scope: String,
    ): Pair<Long, JsonObject>? {
        val root =
            try {
                Json.parseToJsonElement(raw).asObject()
            } catch (e: IllegalArgumentException) {
                null
            } ?: return null
        if (root.int("recordVersion") != VERSION || root.str("scope") != scope) return null
        val savedAt = root.long("savedAtMillis") ?: return null
        val data = root.obj("data") ?: return null
        return savedAt to data
    }
}

/** Runs [block] and says whether it worked. Device storage errors must not hide a record that just loaded. */
internal suspend fun succeeded(block: suspend () -> Unit): Boolean =
    try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
