package thread.web.presentation.external

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import thread.domain.external.ConnectionSync
import thread.domain.external.ConnectionSyncException
import thread.domain.external.ConnectionSyncProblem
import thread.domain.external.ConnectionSyncState
import thread.domain.external.SecretText

/**
 * Settings state of the encrypted connection sync (adr/ADR-016-encrypted-connection-sync.md). [sync] is null when sync
 * is not available, as with the SYNTHETIC fixtures. [onReplaced] runs after another device's connections replaced this
 * device's, so the Jira and GitHub views load again. [scope] is the workspace's, so saves and checks this model starts
 * itself outlive the page that asked for them.
 */
internal class ConnectionSyncModel(
    private val sync: ConnectionSync?,
    private val scope: CoroutineScope,
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

    /** A check was asked for while another check or an action ran. It runs once that ends, so none is lost. */
    private var checkAgain = false

    /** Reads the server: saves a change still waiting here, or applies a newer one from another device. */
    suspend fun check() {
        val sync = sync ?: return
        if (checking || busy) {
            checkAgain = true
            return
        }
        checking = true
        try {
            do {
                checkAgain = false
                observe { sync.check() }?.let { result ->
                    state = result.state
                    if (result.replacedHere) replaced()
                }
            } while (checkAgain && !busy)
        } finally {
            checking = false
            // The caller left mid-check (Settings closed, the network changed): the workspace runs the one still asked for.
            if (checkAgain && !busy) scope.launch { check() }
        }
    }

    /**
     * While collected, checks each time the saved record changes on the server, so another device's change shows here
     * without a reload. Returns when the server stops answering; the checks on sign-in and on a network change remain.
     */
    suspend fun followServer() {
        sync?.serverChanges()?.collect { check() }
    }

    /**
     * After a connect, disconnect or new token here. The save runs in the workspace scope, so leaving the form or
     * Settings does not stop it. Gives the state after saving, or null when sync is not available or could not finish
     * (see [problem]).
     */
    fun connectionsChanged(): Deferred<ConnectionSyncState?> {
        // Not CompletableDeferred(null): that is the overload taking a parent job, and it never completes.
        val sync = sync ?: return CompletableDeferred<ConnectionSyncState?>().apply { complete(null) }
        return scope.async { observe { sync.connectionsChanged() }?.also { state = it } }
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
            checkIfAsked()
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
        if (failed == null && replacesHere) replaced()
        if (failed == ConnectionSyncProblem.ChangedElsewhere) check() else checkIfAsked()
        return failed
    }

    /**
     * The Jira and GitHub views load again in the workspace scope. The caller may leave meanwhile: the Unlock form
     * leaves as soon as the state is On, and its toast follows without a pause.
     */
    private fun replaced() {
        scope.launch { onReplaced() }
    }

    /** A check asked for during an action runs now, in the workspace scope. */
    private fun checkIfAsked() {
        if (checkAgain && !checking) scope.launch { check() }
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
