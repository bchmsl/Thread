package thread.data.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import thread.data.external.KeyValueStore
import thread.data.external.arr
import thread.data.external.int
import thread.data.external.long
import thread.data.external.str
import thread.domain.workspace.ConversionBackup
import thread.domain.workspace.ReminderConversion
import thread.domain.workspace.WorkspaceWrite

/**
 * The local copy of what the Topic reminder conversion changes (D54, adr/ADR-018-topic-info-and-task-reminders.md
 * part B). It lives on this device only, in this account's own storage, and is never synced. [save] stores one record
 * per run before the run writes anything, and fails when the storage fails, so the run then writes nothing.
 *
 * A record is `{recordVersion: 1, kind: "topic-reminders", savedAt, reminders, writes, undo}`: the reminder ids, the
 * writes of the run and the writes that take it back, in the outbox format ([WorkspaceWriteJson]). `undo` holds each
 * reminder exactly as it was stored. Thread never deletes these records.
 */
class KeyValueConversionBackup(
    private val storage: KeyValueStore,
    private val now: () -> Long,
) : ConversionBackup {
    override suspend fun save(conversions: List<ReminderConversion>) {
        if (conversions.isEmpty()) return
        val savedAt = now()
        val record =
            buildJsonObject {
                put("recordVersion", VERSION)
                put("kind", KIND)
                put("savedAt", savedAt)
                put("reminders", buildJsonArray { conversions.forEach { add(it.reminder.id.value) } })
                put("writes", WorkspaceWriteJson.encode(conversions.flatMap { it.writes }))
                put("undo", WorkspaceWriteJson.encode(conversions.flatMap { it.undo }))
            }.toString()
        storage.put("$KIND-$savedAt-${conversions.first().reminder.id.value}", record)
    }

    /** The stored records this version can read, oldest first. For tests and support. */
    internal suspend fun saved(): List<SavedConversion> =
        storage
            .all()
            .mapNotNull { (_, raw) -> decode(raw) }
            .sortedBy { it.savedAt }

    private fun decode(raw: String): SavedConversion? {
        val root =
            try {
                Json.parseToJsonElement(raw) as? JsonObject
            } catch (e: IllegalArgumentException) {
                null
            } ?: return null
        if (root.int("recordVersion") != VERSION || root.str("kind") != KIND) return null
        return SavedConversion(
            savedAt = root.long("savedAt") ?: return null,
            writes = root.arr("writes")?.let(WorkspaceWriteJson::decode) ?: return null,
            undo = root.arr("undo")?.let(WorkspaceWriteJson::decode) ?: return null,
        )
    }

    internal data class SavedConversion(
        val savedAt: Long,
        val writes: List<WorkspaceWrite>,
        val undo: List<WorkspaceWrite>,
    )

    private companion object {
        const val VERSION = 1
        const val KIND = "topic-reminders"
    }
}
