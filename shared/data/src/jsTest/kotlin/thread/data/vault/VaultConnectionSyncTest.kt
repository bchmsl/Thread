package thread.data.vault

import kotlinx.coroutines.test.runTest
import thread.data.synthetic.SyntheticConnections
import thread.domain.account.AccountId
import thread.domain.external.ConnectionSyncException
import thread.domain.external.ConnectionSyncProblem
import thread.domain.external.ConnectionSyncState
import thread.domain.external.SecretText
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [VaultConnectionSync] with the browser's real WebCrypto and the server record in memory. Two [TestDevice]s share one
 * record. SYNTHETIC connections and passphrases only.
 */
class VaultConnectionSyncTest {
    private val remote = MemoryVaultRemote()
    private val first = TestDevice(remote)
    private val second = TestDevice(remote)

    private suspend fun connectFirst() {
        first.connections.saveJira(SyntheticConnections.jira)
        first.connections.saveGitHub(SyntheticConnections.gitHub)
    }

    private suspend fun problemOf(block: suspend () -> Unit): ConnectionSyncProblem {
        try {
            block()
        } catch (e: ConnectionSyncException) {
            assertFalse(PASSPHRASE.reveal() in e.toString())
            return e.problem
        }
        fail("Expected a ConnectionSyncException")
    }

    @Test
    fun theRecordHoldsNoConnectionInPlainText() =
        runTest {
            connectFirst()
            first.sync.turnOn(PASSPHRASE)

            val record = assertNotNull(remote.record)
            val plain = SyntheticConnections.jira
            val fields = listOf(record.kdf, record.salt, record.iv, record.ciphertext).joinToString("|")
            val opened = Base64.decode(record.ciphertext).decodeToString()
            for (secret in listOf(plain.token.reveal(), plain.email, plain.site.host, plain.cloudId.value, PASSPHRASE.reveal())) {
                assertFalse(secret in fields)
                assertFalse(secret in opened)
            }
            assertEquals(VaultConnectionSync.KDF, record.kdf)
            assertEquals(16, Base64.decode(record.salt).size)
            assertEquals(12, Base64.decode(record.iv).size)
        }

    @Test
    fun aSecondDeviceUnlocksWithThePassphraseOnly() =
        runTest {
            connectFirst()
            first.sync.turnOn(PASSPHRASE)
            assertEquals(ConnectionSyncState.On(waiting = false), first.sync.check().state)

            assertEquals(ConnectionSyncState.Locked(passphraseChanged = false), second.sync.check().state)
            assertEquals(ConnectionSyncProblem.WrongPassphrase, problemOf { second.sync.unlock(OTHER_PASSPHRASE) })
            assertNull(second.connections.jira())
            assertNull(second.device.vault)

            second.sync.unlock(PASSPHRASE)

            assertEquals(SyntheticConnections.jira, second.connections.jira())
            assertEquals(SyntheticConnections.gitHub, second.connections.gitHub())
            assertEquals(ConnectionSyncState.On(waiting = false), second.sync.check().state)
        }

    @Test
    fun aShortPassphraseIsRefusedAndNothingIsWritten() =
        runTest {
            connectFirst()

            assertEquals(ConnectionSyncProblem.TooShort, problemOf { first.sync.turnOn(SecretText("short")) })
            assertNull(remote.record)
            assertNull(first.device.vault)
        }

    @Test
    fun aChangeOnOneDeviceReachesTheOtherAndTheLastWriteWins() =
        runTest {
            connectFirst()
            first.sync.turnOn(PASSPHRASE)
            second.sync.unlock(PASSPHRASE)

            first.connections.clearJira()
            assertEquals(ConnectionSyncState.On(waiting = false), first.sync.connectionsChanged())

            val check = second.sync.check()
            assertTrue(check.replacedHere)
            assertNull(second.connections.jira())
            assertEquals(SyntheticConnections.gitHub, second.connections.gitHub())
            assertFalse(second.sync.check().replacedHere)

            second.connections.saveJira(SyntheticConnections.jira)
            second.sync.connectionsChanged()
            assertTrue(first.sync.check().replacedHere)
            assertEquals(SyntheticConnections.jira, first.connections.jira())
        }

    @Test
    fun aChangeMadeOfflineWaitsAndIsSavedByTheNextCheck() =
        runTest {
            connectFirst()
            first.sync.turnOn(PASSPHRASE)
            val before = remote.record?.iv

            first.online = false
            first.connections.clearGitHub()
            assertEquals(ConnectionSyncState.On(waiting = true), first.sync.connectionsChanged())
            assertEquals(before, remote.record?.iv)
            assertEquals(ConnectionSyncProblem.Offline, problemOf { first.sync.turnOff() })

            first.online = true
            assertEquals(ConnectionSyncState.On(waiting = false), first.sync.check().state)
            assertNotEquals(before, remote.record?.iv)
            second.sync.unlock(PASSPHRASE)
            assertNull(second.connections.gitHub())
        }

    @Test
    fun aNewPassphraseLocksOtherDevicesAndAnOldKeyNeverWritesOverIt() =
        runTest {
            connectFirst()
            first.sync.turnOn(PASSPHRASE)
            second.sync.unlock(PASSPHRASE)

            assertEquals(
                ConnectionSyncProblem.WrongPassphrase,
                problemOf { first.sync.changePassphrase(OTHER_PASSPHRASE, OTHER_PASSPHRASE) },
            )
            val unchanged = remote.record?.salt

            // The second device changes a connection while offline, then the first changes the passphrase.
            second.online = false
            second.connections.clearJira()
            second.sync.connectionsChanged()
            first.sync.changePassphrase(PASSPHRASE, OTHER_PASSPHRASE)
            val renewed = remote.record?.salt
            assertNotEquals(unchanged, renewed)

            second.online = true
            assertEquals(ConnectionSyncState.Locked(passphraseChanged = true), second.sync.check().state)
            assertEquals(renewed, remote.record?.salt)
            assertNull(second.device.vault)

            assertEquals(ConnectionSyncProblem.WrongPassphrase, problemOf { second.sync.unlock(PASSPHRASE) })
            second.sync.unlock(OTHER_PASSPHRASE)
            assertEquals(SyntheticConnections.jira, second.connections.jira())
        }

    @Test
    fun turningOffDeletesTheRecordAndKeepsEveryDevicesConnections() =
        runTest {
            connectFirst()
            first.sync.turnOn(PASSPHRASE)
            second.sync.unlock(PASSPHRASE)

            first.sync.turnOff()

            assertNull(remote.record)
            assertNull(first.device.vault)
            assertEquals(SyntheticConnections.jira, first.connections.jira())

            // The other device notices, keeps its connections and never brings the record back.
            second.connections.clearGitHub()
            assertEquals(ConnectionSyncState.Off, second.sync.connectionsChanged())
            assertNull(remote.record)
            assertNull(second.device.vault)
            assertEquals(SyntheticConnections.jira, second.connections.jira())
        }

    @Test
    fun aChangedRecordIsReportedAndChangesNothingHere() =
        runTest {
            connectFirst()
            first.sync.turnOn(PASSPHRASE)
            second.sync.unlock(PASSPHRASE)
            val held = second.device.vault

            val record = assertNotNull(remote.record)
            val bytes = Base64.decode(record.ciphertext)
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
            // A new IV too, so the device sees a record it has not applied yet.
            remote.record = VaultRecord(record.kdf, record.iterations, record.salt, Base64.encode(randomIv()), Base64.encode(bytes))

            assertEquals(ConnectionSyncProblem.Damaged, problemOf { second.sync.check() })
            assertEquals(SyntheticConnections.jira, second.connections.jira())
            assertEquals(held, second.device.vault)

            val third = TestDevice(remote)
            assertEquals(ConnectionSyncProblem.WrongPassphrase, problemOf { third.sync.unlock(PASSPHRASE) })
            assertNull(third.connections.jira())

            // Saving this device's connections again replaces the damaged record.
            assertEquals(ConnectionSyncState.On(waiting = false), second.sync.connectionsChanged())
            third.sync.unlock(PASSPHRASE)
            assertEquals(SyntheticConnections.jira, third.connections.jira())
        }

    @Test
    fun theRecordOpensOnlyForTheAccountItWasMadeFor() =
        runTest {
            connectFirst()
            first.sync.turnOn(PASSPHRASE)

            val elsewhere = MemoryVaultRemote().also { it.record = remote.record }
            val otherAccount = TestDevice(elsewhere, AccountId("synthetic-other-account"))

            assertEquals(ConnectionSyncProblem.WrongPassphrase, problemOf { otherAccount.sync.unlock(PASSPHRASE) })
        }

    @Test
    fun aRecordAskingForTooFewOrTooManyIterationsIsNotDerived() =
        runTest {
            connectFirst()
            first.sync.turnOn(PASSPHRASE)
            val record = assertNotNull(remote.record)

            for (iterations in listOf(TestDevice.TEST_ITERATIONS - 1, VaultConnectionSync.MAX_ITERATIONS + 1)) {
                remote.record = VaultRecord(record.kdf, iterations, record.salt, record.iv, record.ciphertext)
                assertEquals(ConnectionSyncProblem.Damaged, problemOf { second.sync.unlock(PASSPHRASE) })
            }
        }

    @Test
    fun withoutTheServerTheStateComesFromThisDevice() =
        runTest {
            connectFirst()
            first.online = false
            assertEquals(ConnectionSyncState.Unknown, first.sync.check().state)
            assertEquals(ConnectionSyncProblem.Offline, problemOf { first.sync.turnOn(PASSPHRASE) })

            first.online = true
            first.sync.turnOn(PASSPHRASE)
            remote.failing = VaultRemoteException.Kind.Offline
            assertEquals(ConnectionSyncState.On(waiting = false), first.sync.check().state)
            assertEquals(ConnectionSyncProblem.Offline, problemOf { first.sync.changePassphrase(PASSPHRASE, OTHER_PASSPHRASE) })

            remote.failing = VaultRemoteException.Kind.Refused
            assertEquals(ConnectionSyncProblem.Refused, problemOf { first.sync.check() })
            remote.failing = null
        }

    @Test
    fun turningOnTwiceForOneAccountIsRefused() =
        runTest {
            connectFirst()
            first.sync.turnOn(PASSPHRASE)

            assertEquals(ConnectionSyncProblem.ChangedElsewhere, problemOf { second.sync.turnOn(OTHER_PASSPHRASE) })
            assertNull(second.device.vault)
        }
}

private fun randomIv(): ByteArray = WebCryptoVault().randomBytes(VaultConnectionSync.IV_BYTES)
