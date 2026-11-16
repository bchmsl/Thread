package thread.data.focus

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import thread.data.local.IndexedDbKeyValueDatabase
import thread.domain.account.AccountId
import thread.domain.focus.FocusChange
import thread.domain.focus.FocusDevice
import thread.domain.focus.FocusRunStore

/**
 * This device's Focus timer for one account (F2 A, adr/ADR-019-focus-timer.md): one record in the IndexedDB database
 * `thread-focus-<uid>`, never in the account. Each [update] is one read-write transaction, so two tabs never interleave.
 * After a change, the other tabs of this browser profile hear of it on a `BroadcastChannel` named per account and read
 * the record again; other observers in this tab hear of it at once.
 */
class IndexedDbFocusRunStore internal constructor(
    private val database: IndexedDbKeyValueDatabase,
    private val channelName: String,
) : FocusRunStore {
    constructor(accountId: AccountId) : this(
        IndexedDbKeyValueDatabase(IndexedDbKeyValueDatabase.focusFor(accountId), listOf(IndexedDbKeyValueDatabase.FOCUS_STORE)),
        "thread-focus-${accountId.value}",
    )

    private val records = database.store(IndexedDbKeyValueDatabase.FOCUS_STORE)
    private val changedHere = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val sender: BroadcastChannel? = openChannel(channelName)

    override fun observe(): Flow<FocusDevice> =
        channelFlow {
            val changed = Channel<Unit>(Channel.CONFLATED)
            changed.trySend(Unit)
            launch { changedHere.collect { changed.trySend(Unit) } }
            val listener = openChannel(channelName)
            listener?.onmessage = { changed.trySend(Unit) }
            launch { for (signal in changed) send(read()) }
            awaitClose { listener?.close() }
        }.distinctUntilChanged()

    override suspend fun <T> update(change: (FocusDevice) -> FocusChange<T>): T {
        var outcome: FocusChange<T>? = null
        var stored = false
        database.update(IndexedDbKeyValueDatabase.FOCUS_STORE, KEY) { text ->
            val device = text?.let(FocusDeviceJson::decode) ?: FocusDevice()
            val next = change(device)
            outcome = next
            stored = next.device != device
            if (stored) FocusDeviceJson.encode(next.device) else null
        }
        if (stored) {
            changedHere.tryEmit(Unit)
            try {
                sender?.postMessage(CHANGED)
            } catch (e: Throwable) {
                // The other tabs read the record again when they become visible.
            }
        }
        return outcome!!.result
    }

    /** The stored record. One this build cannot read counts as no timer; the next change replaces it. */
    private suspend fun read(): FocusDevice = records.get(KEY)?.let(FocusDeviceJson::decode) ?: FocusDevice()

    private companion object {
        const val KEY = "device"
        const val CHANGED = "changed"
    }
}

/** The web `BroadcastChannel`: messages between pages of one origin. Not in the Kotlin DOM declarations. */
internal external class BroadcastChannel(
    name: String,
) {
    var onmessage: ((dynamic) -> Unit)?

    fun postMessage(message: Any?)

    fun close()
}

/** A channel, or null where the browser has none: the tabs then catch up when they become visible. */
private fun openChannel(name: String): BroadcastChannel? =
    if (jsTypeOf(js("globalThis").BroadcastChannel) == "function") BroadcastChannel(name) else null
