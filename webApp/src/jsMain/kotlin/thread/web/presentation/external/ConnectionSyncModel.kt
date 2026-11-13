package thread.web.presentation.external

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import thread.domain.external.ConnectionSync
import thread.domain.external.ConnectionSyncException
import thread.domain.external.ConnectionSyncProblem
import thread.domain.external.ConnectionSyncState
import thread.domain.external.SecretText

/**
 * Settings state of the encrypted connection sync (adr/ADR-016-encrypted-connection-sync.md). [sync] is null when sync
 * is not available, as with the SYNTHETIC fixtures. [onReplaced] runs after another device's connections replaced this
 * device's, so the Jira and GitHub views load again.
 */
internal class ConnectionSyncModel(
    private val sync: ConnectionSync?,
    private val onReplaced: suspend () -> Unit,
) {
    val available: Boolean get() = sync != null

    /** The last known state, or null before the first answer. */
    var state: ConnectionSyncState? by mutableStateOf(null)
        private set

    /** Why the last check or save did not finish. A good answer clears it. */
    var problem: ConnectionSyncProblem? by mutableStateOf(null)
        private set

    /** True while an action the person started runs. */
    var busy: Boolean by mutableStateOf(false)
        private set

    private var checking = false

    /** Reads the server: saves a change still waiting here, or applies a newer one from another device. */
    suspend fun check() {
        val sync = sync ?: return
        if (checking || busy) return
        checking = true
        try {
            val result = observe { sync.check() } ?: return
            state = result.state
            if (result.replacedHere) onReplaced()
        } finally {
            checking = false
        }
    }

    /**
     * After a connect, disconnect or new token here. Returns the state after saving, or null when sync is not available
     * or could not finish (see [problem]).
     */
    suspend fun connectionsChanged(): ConnectionSyncState? {
        val sync = sync ?: return null
        return observe { sync.connectionsChanged() }?.also { state = it }
    }

    suspend fun turnOn(passphrase: SecretText): ConnectionSyncProblem? =
        act(ConnectionSyncState.On(waiting = false)) { it.turnOn(passphrase) }

    suspend fun unlock(passphrase: SecretText): ConnectionSyncProblem? =
        act(ConnectionSyncState.On(waiting = false), replacesHere = true) { it.unlock(passphrase) }

    suspend fun changePassphrase(
        current: SecretText,
        new: SecretText,
    ): ConnectionSyncProblem? =
        // A newer record from another device may be applied here first, so the views load again.
        act(ConnectionSyncState.On(waiting = false), replacesHere = true) { it.changePassphrase(current, new) }

    suspend fun turnOff(): ConnectionSyncProblem? = act(ConnectionSyncState.Off) { it.turnOff() }

    /** Saves this device's connections over a record that cannot be read. */
    suspend fun replaceSaved(): ConnectionSyncProblem? {
        val sync = sync ?: return ConnectionSyncProblem.Failed
        busy = true
        try {
            val after = observe { sync.connectionsChanged() } ?: return problem
            state = after
            return if (after == ConnectionSyncState.On(waiting = false)) null else ConnectionSyncProblem.Damaged
        } finally {
            busy = false
        }
    }

    /** Runs [action]; null when it worked, else what went wrong. A change made elsewhere meanwhile is read again. */
    private suspend fun act(
        after: ConnectionSyncState,
        replacesHere: Boolean = false,
        action: suspend (ConnectionSync) -> Unit,
    ): ConnectionSyncProblem? {
        val sync = sync ?: return ConnectionSyncProblem.Failed
        busy = true
        val failed =
            try {
                action(sync)
                state = after
                problem = null
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConnectionSyncException) {
                e.problem
            } catch (e: Throwable) {
                ConnectionSyncProblem.Failed
            } finally {
                busy = false
            }
        if (failed == null && replacesHere) onReplaced()
        if (failed == ConnectionSyncProblem.ChangedElsewhere) check()
        return failed
    }

    private suspend fun <T> observe(block: suspend () -> T): T? =
        try {
            block().also { problem = null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConnectionSyncException) {
            problem = e.problem
            null
        } catch (e: Throwable) {
            problem = ConnectionSyncProblem.Failed
            null
        }
}
