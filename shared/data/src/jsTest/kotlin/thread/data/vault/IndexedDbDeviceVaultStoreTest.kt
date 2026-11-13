package thread.data.vault

import kotlinx.coroutines.test.runTest
import thread.data.local.IndexedDbKeyValueDatabase
import thread.data.local.newJsObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Runs against the real IndexedDB and WebCrypto of ChromeHeadless, in a database of its own. SYNTHETIC values only. */
class IndexedDbDeviceVaultStoreTest {
    private val name = "thread-test-vault-${Random.nextLong().toString(16)}"
    private val crypto = WebCryptoVault()
    private val additionalData = "synthetic".encodeToByteArray()

    @Test
    fun theKeyItselfIsStoredAndStillWorksAfterReadingItBack() =
        runTest {
            val key = crypto.deriveKey(PASSPHRASE, crypto.randomBytes(16), TestDevice.TEST_ITERATIONS)
            val sealed = crypto.seal(key, "synthetic payload".encodeToByteArray(), additionalData)
            val vault = DeviceVault(key, VaultConnectionSync.KDF, TestDevice.TEST_ITERATIONS, "c2FsdA==", appliedIv = null, waiting = true)

            IndexedDbDeviceVaultStore(name).write(vault)
            val read = assertNotNull(IndexedDbDeviceVaultStore(name).read())

            assertEquals(vault.copy(key = read.key), read)
            assertEquals(false, (read.key as WebCryptoKey).cryptoKey.extractable)
            assertContentEquals("synthetic payload".encodeToByteArray(), crypto.open(read.key, sealed, additionalData))

            IndexedDbDeviceVaultStore(name).write(read.copy(appliedIv = "aXY=", waiting = false))
            assertEquals("aXY=", IndexedDbDeviceVaultStore(name).read()?.appliedIv)

            IndexedDbDeviceVaultStore(name).delete()
            assertNull(IndexedDbDeviceVaultStore(name).read())
        }

    @Test
    fun aRecordOfAnotherShapeReadsAsNoKey() =
        runTest {
            val database = IndexedDbKeyValueDatabase(name, listOf(IndexedDbKeyValueDatabase.VAULT_STORE))
            val notAKey = newJsObject()
            notAKey.recordVersion = 1
            notAKey.cryptoKey = "not a key"
            notAKey.kdf = VaultConnectionSync.KDF
            notAKey.iterations = 1
            notAKey.salt = "c2FsdA=="
            notAKey.appliedIv = null
            notAKey.waiting = false
            database.putObject(IndexedDbKeyValueDatabase.VAULT_STORE, "device", notAKey)

            assertNull(IndexedDbDeviceVaultStore(name).read())
        }
}
