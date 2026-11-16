package thread.domain.focus

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** What a [FocusRunStore.update] stores, and what it hands back to the caller. */
class FocusChange<T>(
    val device: FocusDevice,
    val result: T,
)

/**
 * This device's Focus timer for one account (F2 A): platform code keeps it on the device only, never in the account.
 * Several tabs share it.
 */
interface FocusRunStore {
    /** The stored timer now, and again after every change, from this tab or another one on this device. */
    fun observe(): Flow<FocusDevice>

    /**
     * Reads the stored timer, applies [change] and stores its [FocusChange.device], as one step that no other tab can
     * interleave with. [change] must be quick and must not suspend. Returns [FocusChange.result].
     */
    suspend fun <T> update(change: (FocusDevice) -> FocusChange<T>): T
}

/**
 * The Focus timer's actions. Each one first settles the ends that passed ([settleFocus]), then acts on what the store
 * holds at that moment, not on what a tab showed: an action made from an older view of the run (another tab already
 * paused, stopped or moved it to its break) changes nothing. The sessions that come out are recorded through [record]
 * once the store has the new timer; their ids and values are fixed, so recording one twice stores it once.
 */
class FocusTimer(
    private val store: FocusRunStore,
    private val record: (List<FocusSession>) -> Unit,
    private val nowMillis: () -> Long,
    private val newRunId: () -> String,
) {
    fun observe(): Flow<FocusDevice> = store.observe()

    /** Applies every end that passed. Returns those ends, oldest first, for the screen to show. */
    suspend fun settle(): List<FocusEvent> = act { device, _ -> device to null }

    /** Starts a focus period of [settings] for the picked Task or Topic, unless a run already exists. */
    suspend fun start(settings: FocusSettings): List<FocusEvent> =
        act { device, now ->
            if (device.run != null) device to null else device.copy(run = startFocus(settings, device.lastTarget, now, newRunId())) to null
        }

    /** Pauses [seen] if it is still the running phase the store holds. */
    suspend fun pause(seen: FocusRun): List<FocusEvent> =
        act { device, now ->
            val run = device.run?.takeIf { it.isPhaseOf(seen) && it.running }
            (if (run == null) device else device.copy(run = pauseFocus(run, now))) to null
        }

    /** Resumes [seen] if it is still the paused phase the store holds. */
    suspend fun resume(seen: FocusRun): List<FocusEvent> =
        act { device, now ->
            val run = device.run?.takeIf { it.isPhaseOf(seen) && !it.running }
            (if (run == null) device else device.copy(run = resumeFocus(run, now))) to null
        }

    /** Stops [seen] if it is still the phase the store holds, and records a Stopped session when [stopFocus] gives one. */
    suspend fun stop(seen: FocusRun): List<FocusEvent> =
        act { device, now ->
            val run = device.run?.takeIf { it.isPhaseOf(seen) }
            if (run == null) device to null else device.copy(run = null) to stopFocus(run, now)
        }

    /** Picks the Task or Topic for the next Start (FD4). A run that exists is for it from now on, as in v3. */
    suspend fun choose(target: FocusTarget?): List<FocusEvent> =
        act { device, _ -> device.copy(lastTarget = target, run = device.run?.copy(target = target)) to null }

    /**
     * Not cancellable: once the store may have the new timer, its sessions are recorded too. A cancelled caller could
     * otherwise leave a period that ended in the store with no session for it, and no later settle would record it.
     */
    private suspend fun act(action: (FocusDevice, Long) -> Pair<FocusDevice, FocusSession?>): List<FocusEvent> =
        withContext(NonCancellable) {
            val outcome =
                store.update { stored ->
                    val now = nowMillis()
                    val settled = settleFocus(stored.run, now)
                    val (next, stopped) = action(stored.copy(run = settled.run), now)
                    FocusChange(next, settled.copy(run = next.run, sessions = settled.sessions + listOfNotNull(stopped)))
                }
            if (outcome.sessions.isNotEmpty()) record(outcome.sessions)
            outcome.events
        }

    private fun FocusRun.isPhaseOf(seen: FocusRun): Boolean = runId == seen.runId && phase == seen.phase
}
