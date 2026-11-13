package thread.data.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import thread.data.external.KeyValueStore
import thread.data.external.arr
import thread.data.external.int
import thread.data.external.long
import thread.data.external.str
import thread.domain.workspace.RefusedChange
import thread.domain.workspace.WorkspaceData
import thread.domain.workspace.WorkspaceWrite
import thread.domain.workspace.unconfirmedChanges
import kotlin.random.Random

/**
 * The device-local record of workspace changes on their way to the server (owner-review-queue Q14,
 * adr/ADR-009-workspace-entities-and-sync.md). The device copy (Firestore's cache) still sends them; this record only
 * remembers each change until the server answers, so a refusal is never lost:
 *
 * - [sent] records a change before it goes out, and [accepted] forgets it.
 * - [refused] keeps it, also after a reload, until [discard].
 * - A change still recorded as sent when a later page starts lost its answer in the reload. [load] returns those
 *   leftovers, and [resolve] keeps the ones the stored data does not show ([unconfirmedChanges]).
 *
 * Storage calls run one at a time, in order, on [scope]. A failing device storage is ignored: this record backs up
 * the sync panel and must never block or fail a write. A refused change then stays listed until the page closes.
 * Records are `{recordVersion: 1, id, session, at, seq, state, writes}`; one this version cannot read stays in
 * storage untouched.
 */
internal class WorkspaceOutbox(
    private val storage: KeyValueStore,
    scope: CoroutineScope,
    private val now: () -> Long,
    private val session: String = Random.nextLong().toULong().toString(RADIX),
) {
    private val jobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val listed = MutableStateFlow<List<RefusedChange>>(emptyList())
    private var seq = 0

    /** The writes this page made, kept only while leftovers from an earlier page wait to be checked. */
    private var madeHere: MutableList<WorkspaceWrite>? = mutableListOf()

    /** Refused and unconfirmed changes, oldest first. */
    val refused: StateFlow<List<RefusedChange>> = listed.asStateFlow()

    init {
        scope.launch {
            for (job in jobs) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Device storage failed. See the class comment.
                }
            }
        }
    }

    /** Records [writes] as sent and returns the change's id. */
    fun sent(writes: List<WorkspaceWrite>): String {
        val id = "$session-${++seq}"
        madeHere?.addAll(writes)
        val record = encode(RefusedChange(id, writes), SENT)
        jobs.trySend { storage.put(id, record) }
        return id
    }

    fun accepted(id: String) {
        jobs.trySend { storage.delete(id) }
    }

    fun refused(
        id: String,
        writes: List<WorkspaceWrite>,
    ) = keep(RefusedChange(id, writes))

    fun discard(change: RefusedChange) {
        listed.update { list -> list.filterNot { it.id == change.id } }
        jobs.trySend { storage.delete(change.id) }
    }

    /** Lists [change] and records it, refused or unconfirmed. */
    fun keep(change: RefusedChange) {
        listed.update { list -> if (list.any { it.id == change.id }) list else list + change }
        val record = encode(change, if (change.unconfirmed) UNCONFIRMED else REFUSED)
        jobs.trySend { storage.put(change.id, record) }
    }

    /**
     * Lists the refused and unconfirmed changes recorded by earlier pages, and returns the changes they sent that got
     * no answer (the leftovers), oldest first.
     */
    suspend fun load(): List<RefusedChange> {
        val result = CompletableDeferred<List<RefusedChange>>()
        jobs.trySend {
            try {
                result.complete(readRecords())
            } finally {
                // The storage failed: no leftovers can be checked, so stop collecting this page's writes.
                if (result.complete(emptyList())) madeHere = null
            }
        }
        return result.await()
    }

    /**
     * Keeps the [leftovers] that [data] does not show, as unconfirmed, and forgets the rest. [data] must be read after
     * the server answered every write from before the reload.
     */
    fun resolve(
        leftovers: List<RefusedChange>,
        data: WorkspaceData,
    ) {
        val later = madeHere.orEmpty().toList()
        madeHere = null
        val unconfirmed = unconfirmedChanges(leftovers, later, data)
        listed.update { list -> unconfirmed + list }
        val kept = unconfirmed.associateBy { it.id }
        jobs.trySend {
            leftovers.forEach { leftover ->
                val change = kept[leftover.id]
                if (change == null) storage.delete(leftover.id) else storage.put(change.id, encode(change, UNCONFIRMED))
            }
        }
    }

    private suspend fun readRecords(): List<RefusedChange> {
        val records = storage.all().mapNotNull { (_, raw) -> decode(raw) }.sortedWith(compareBy({ it.at }, { it.seq }))
        val shown = records.filter { it.state != SENT }.map { it.change }
        listed.update { list -> shown.filter { old -> list.none { it.id == old.id } } + list }
        val leftovers = records.filter { it.state == SENT && it.session != session }.map { it.change }
        if (leftovers.isEmpty()) madeHere = null
        return leftovers
    }

    private class Record(
        val change: RefusedChange,
        val session: String,
        val at: Long,
        val seq: Int,
        val state: String,
    )

    private fun encode(
        change: RefusedChange,
        state: String,
    ): String =
        buildJsonObject {
            put("recordVersion", VERSION)
            put("id", change.id)
            put("session", session)
            put("at", now())
            put("seq", seq)
            put("state", state)
            put("writes", WorkspaceWriteJson.encode(change.writes))
        }.toString()

    private fun decode(raw: String): Record? {
        val root =
            try {
                Json.parseToJsonElement(raw) as? JsonObject
            } catch (e: IllegalArgumentException) {
                null
            } ?: return null
        if (root.int("recordVersion") != VERSION) return null
        val state = root.str("state")?.takeIf { it in STATES } ?: return null
        val writes = root.arr("writes")?.let(WorkspaceWriteJson::decode) ?: return null
        return Record(
            change = RefusedChange(root.str("id") ?: return null, writes, unconfirmed = state == UNCONFIRMED),
            session = root.str("session") ?: return null,
            at = root.long("at") ?: return null,
            seq = root.int("seq") ?: return null,
            state = state,
        )
    }

    private companion object {
        const val VERSION = 1
        const val RADIX = 36
        const val SENT = "sent"
        const val REFUSED = "refused"
        const val UNCONFIRMED = "unconfirmed"
        val STATES = setOf(SENT, REFUSED, UNCONFIRMED)
    }
}
