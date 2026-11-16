package thread.web.presentation.external

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import thread.domain.external.ConnectionSync
import thread.domain.external.ConnectionSyncCheck
import thread.domain.external.ConnectionSyncState
import thread.domain.external.SecretText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [ConnectionSyncModel] with a fake [ConnectionSync] whose calls wait on gates. The test's scope stands for the workspace's. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSyncModelTest {
    private val sync = GatedSync()

    @Test
    fun aSaveStartedByAFormFinishesAfterTheFormLeaves() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            sync.onConnectionsChanged = {
                gate.await()
                ConnectionSyncState.On(waiting = false)
            }
            val model = ConnectionSyncModel(sync, this) {}

            val form = launch { model.connectionsChanged().await() }
            runCurrent()
            form.cancel()
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, sync.savesFinished)
            assertEquals(ConnectionSyncState.On(waiting = false), model.state)
        }

    @Test
    fun aCheckAskedForDuringACheckRunsOnceAfterIt() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            sync.onCheck = { if (sync.checks == 1) gate.await() }
            val model = ConnectionSyncModel(sync, this) {}

            launch { model.check() }
            runCurrent()
            model.check()
            model.check()
            assertEquals(1, sync.checks)

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(2, sync.checks)
        }

    @Test
    fun aCheckAskedForDuringAnActionRunsWhenItEnds() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            sync.onUnlock = { gate.await() }
            val model = ConnectionSyncModel(sync, this) {}

            val unlock = async { model.unlock(SecretText("synthetic passphrase one")) }
            runCurrent()
            assertTrue(model.busy)
            model.check()
            assertEquals(0, sync.checks)

            gate.complete(Unit)
            advanceUntilIdle()
            assertNull(unlock.await())
            assertEquals(1, sync.checks)
        }

    @Test
    fun aCheckAskedForWhileTheRunningOnesCallerLeavesStillRuns() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            sync.onCheck = { if (sync.checks == 1) gate.await() }
            val model = ConnectionSyncModel(sync, this) {}

            val settings = launch { model.check() }
            runCurrent()
            model.check()
            settings.cancel()
            advanceUntilIdle()

            assertEquals(2, sync.checks)
        }

    @Test
    fun eachServerChangeRunsACheckAndAReplacementReloadsTheViews() =
        runTest {
            val changes = MutableSharedFlow<Unit>()
            sync.changes = changes
            sync.replacedHere = true
            var reloaded = 0
            val model = ConnectionSyncModel(sync, this) { reloaded++ }

            val following = launch { model.followServer() }
            runCurrent()
            changes.emit(Unit)
            advanceUntilIdle()
            assertEquals(1, sync.checks)
            changes.emit(Unit)
            advanceUntilIdle()
            assertEquals(2, sync.checks)
            assertEquals(2, reloaded)
            following.cancel()
        }

    @Test
    fun theViewsLoadAgainAfterAnUnlockWhoseFormLeaves() =
        runTest {
            val loading = CompletableDeferred<Unit>()
            var reloaded = 0
            val model =
                ConnectionSyncModel(sync, this) {
                    loading.await()
                    reloaded++
                }

            // The Unlock form leaves as soon as the state is On, while the views still load.
            val form = launch { model.unlock(SecretText("synthetic passphrase one")) }
            runCurrent()
            assertEquals(ConnectionSyncState.On(waiting = false), model.state)
            form.cancel()
            loading.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, reloaded)
        }

    @Test
    fun withoutSyncNothingRuns() =
        runTest {
            val model = ConnectionSyncModel(null, this) {}

            assertNull(model.connectionsChanged().await())
            model.check()
            model.followServer()
            assertNull(model.state)
        }
}

/** Counts calls; each waits on the gate a test sets. SYNTHETIC values only. */
private class GatedSync : ConnectionSync {
    var checks = 0
    var savesFinished = 0
    var replacedHere = false
    var onCheck: suspend () -> Unit = {}
    var onUnlock: suspend () -> Unit = {}
    var onConnectionsChanged: suspend () -> ConnectionSyncState = { ConnectionSyncState.On(waiting = false) }
    var changes: Flow<Unit> = emptyFlow()

    override suspend fun check(): ConnectionSyncCheck {
        checks++
        onCheck()
        return ConnectionSyncCheck(ConnectionSyncState.On(waiting = false), replacedHere)
    }

    override suspend fun turnOn(passphrase: SecretText) = Unit

    override suspend fun unlock(passphrase: SecretText) = onUnlock()

    override suspend fun changePassphrase(
        current: SecretText,
        new: SecretText,
    ) = Unit

    override suspend fun turnOff() = Unit

    override suspend fun connectionsChanged(): ConnectionSyncState = onConnectionsChanged().also { savesFinished++ }

    override fun serverChanges(): Flow<Unit> = changes
}
