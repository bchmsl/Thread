package thread.data.external

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

/*
 * Lenient readers for provider JSON. A missing key or a value of the wrong type reads as null, so a provider that
 * adds or drops a field never breaks the screen. Required values are checked by the callers.
 */

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

/** The objects in [key]'s array. Entries that are not objects are skipped. */
internal fun JsonObject.objects(key: String): List<JsonObject> = arr(key).orEmpty().mapNotNull { it as? JsonObject }

/** Copies [key] from [from] when it is there and not null. */
internal fun JsonObjectBuilder.copy(
    from: JsonObject?,
    key: String,
) {
    val value = from?.get(key) ?: return
    if (value != JsonNull) put(key, value)
}

/** A copy of this object with only [keys]. Used to keep no more of a provider answer than Thread shows. */
internal fun JsonObject.pick(vararg keys: String): JsonObject = buildJsonObject { keys.forEach { copy(this@pick, it) } }

/** Puts [value] when it is not null. */
internal fun JsonObjectBuilder.putIfNotNull(
    key: String,
    value: JsonElement?,
) {
    if (value != null) put(key, value)
}

internal object ExternalTime {
    private val COMPACT_OFFSET = Regex("([+-][0-9]{2})([0-9]{2})$")

    /**
     * Epoch milliseconds of an ISO 8601 date-time with an offset or `Z`, or null. Jira writes offsets without a colon
     * (`2026-11-10T09:14:00.000+0400`); GitHub writes `Z`. Both are read.
     */
    fun parseMillis(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = if ('T' in text) text.replace(COMPACT_OFFSET, "$1:$2") else return null
        return try {
            Instant.parse(normalized).toEpochMilliseconds()
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** `yyyy-MM-dd` in UTC, for Jira date nodes. */
    fun utcDate(millis: Long): String = Instant.fromEpochMilliseconds(millis).toString().take(DATE_LENGTH)

    private const val DATE_LENGTH = 10
}
