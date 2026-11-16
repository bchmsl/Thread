package thread.data.firebase

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import thread.data.synthetic.SyntheticConnections
import thread.data.vault.OTHER_PASSPHRASE
import thread.data.vault.PASSPHRASE
import thread.data.vault.TestDevice
import thread.data.vault.VaultConnectionSync
import thread.data.vault.VaultRecord
import thread.domain.external.ConnectionSyncException
import thread.domain.external.ConnectionSyncProblem
import thread.domain.external.ConnectionSyncState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * [FirestoreVaultRemote] and [VaultConnectionSync] against the Firestore emulator and its rules, with the real WebCrypto
 * and the real iteration count (adr/ADR-016-encrypted-connection-sync.md). Two emulated devices of one synthetic
 * account. SYNTHETIC connections and passphrases only.
 */
class FirestoreVaultEmulatorTest {
    private class Device(
        val emulated: EmulatedDevice,
    ) {
        val remote = FirestoreVaultRemote(emulated.backend, emulated.accountId)
        val test = TestDevice(remote, emulated.accountId, VaultConnectionSync.ITERATIONS)
    }

    /** Signs [subject] in on new emulated devices and closes them after [block], so no stream stays open. */
    private suspend fun withDevices(
        subject: String,
        count: Int,
        block: suspend (List<Device>) -> Unit,
    ) {
        val devices = mutableListOf<Device>()
        try {
            repeat(count) { devices += Device(EmulatedDevice.signIn(subject)) }
            block(devices)
        } finally {
            devices.forEach { it.emulated.backend.close() }
        }
    }

    private suspend fun problemOf(block: suspend () -> Unit): ConnectionSyncProblem {
        try {
            block()
        } catch (e: ConnectionSyncException) {
            return e.problem
        }
        fail("Expected a ConnectionSyncException")
    }

    @Test
    fun theRemoteWritesOnlyWhenTheServerHoldsTheExpectedSalt() =
        emulatorTest {
            withDevices(syntheticSubject(), count = 1) { (device) ->
                val remote = device.remote
                val record =
                    VaultRecord(
                        VaultConnectionSync.KDF,
                        600_000,
                        "c3ludGhldGljLXNhbHQtMQ==",
                        "c3ludGhldGljLWl2",
                        "c3ludGhldGljIGNpcGhlcnRleHQ=",
                    )

                assertNull(remote.read())
                assertTrue(remote.write(record, expectedSalt = null))
                assertFalse(remote.write(record, expectedSalt = null))
                assertFalse(remote.write(record, expectedSalt = "b3RoZXItc3ludGhldGljLTE="))
                assertTrue(remote.write(record, expectedSalt = record.salt))
                assertEquals(record.ciphertext, remote.read()?.ciphertext)

                remote.delete()
                assertNull(remote.read())
            }
        }

    @Test
    fun twoDevicesSyncThroughTheEmulator() =
        emulatorTest {
            withDevices(syntheticSubject(), count = 2) { (firstDevice, secondDevice) ->
                val remote = firstDevice.remote
                val first = firstDevice.test
                val second = secondDevice.test
                first.connections.saveJira(SyntheticConnections.jira)
                first.connections.saveGitHub(SyntheticConnections.gitHub)

                first.sync.turnOn(PASSPHRASE)
                val stored = assertNotNull(remote.read())
                assertEquals(VaultConnectionSync.ITERATIONS, stored.iterations)
                assertFalse(SyntheticConnections.jira.token.reveal() in stored.ciphertext)

                assertEquals(ConnectionSyncState.Locked(passphraseChanged = false), second.sync.check().state)
                assertEquals(ConnectionSyncProblem.WrongPassphrase, problemOf { second.sync.unlock(OTHER_PASSPHRASE) })
                assertNull(second.connections.jira())
                second.sync.unlock(PASSPHRASE)
                assertEquals(SyntheticConnections.jira, second.connections.jira())

                first.connections.clearGitHub()
                assertEquals(ConnectionSyncState.On(waiting = false), first.sync.connectionsChanged())
                assertTrue(second.sync.check().replacedHere)
                assertNull(second.connections.gitHub())

                first.sync.changePassphrase(PASSPHRASE, OTHER_PASSPHRASE)
                assertEquals(ConnectionSyncState.Locked(passphraseChanged = true), second.sync.check().state)
                second.sync.unlock(OTHER_PASSPHRASE)

                second.sync.turnOff()
                assertNull(remote.read())
                assertEquals(ConnectionSyncState.Off, first.sync.check().state)
                assertEquals(SyntheticConnections.jira, first.connections.jira())
            }
        }

    @Test
    fun aListenerOnOneDeviceSeesAnotherDevicesWriteAndItsCheckAppliesIt() =
        emulatorTest {
            withDevices(syntheticSubject(), count = 2) { (firstDevice, secondDevice) ->
                val first = firstDevice.test
                val second = secondDevice.test
                first.connections.saveJira(SyntheticConnections.jira)
                first.connections.saveGitHub(SyntheticConnections.gitHub)
                val changes = Channel<Unit>(Channel.UNLIMITED)
                val listening = launch { second.sync.serverChanges().collect { changes.send(it) } }
                try {
                    withTimeout(LISTEN_TIMEOUT) { changes.receive() }

                    first.sync.turnOn(PASSPHRASE)
                    withTimeout(LISTEN_TIMEOUT) { changes.receive() }
                    assertEquals(ConnectionSyncState.Locked(passphraseChanged = false), second.sync.check().state)
                    second.sync.unlock(PASSPHRASE)

                    first.connections.clearGitHub()
                    first.sync.connectionsChanged()
                    withTimeout(LISTEN_TIMEOUT) { changes.receive() }
                    assertTrue(second.sync.check().replacedHere)
                    assertNull(second.connections.gitHub())
                    assertEquals(SyntheticConnections.jira, second.connections.jira())
                } finally {
                    listening.cancel()
                }
            }
        }

    private companion object {
        val LISTEN_TIMEOUT = 5.seconds
    }
}
