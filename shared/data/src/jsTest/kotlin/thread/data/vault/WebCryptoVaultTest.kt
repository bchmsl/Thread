package thread.data.vault

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import thread.domain.external.SecretText
import kotlin.js.Promise
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The real WebCrypto of ChromeHeadless. SYNTHETIC passphrases and payloads only. */
class WebCryptoVaultTest {
    private val crypto = WebCryptoVault()
    private val salt = crypto.randomBytes(VaultConnectionSync.SALT_BYTES)
    private val additionalData = "thread/connections/v1/synthetic-account".encodeToByteArray()
    private val payload = "synthetic payload ${Random.nextInt()}".encodeToByteArray()

    @Test
    fun theDerivedKeyCannotBeExported() =
        runTest {
            val key = crypto.deriveKey(PASSPHRASE, salt, VaultConnectionSync.ITERATIONS) as WebCryptoKey

            assertEquals(false, key.cryptoKey.extractable)
            assertEquals("AES-GCM", key.cryptoKey.algorithm.name)
            assertEquals(256, key.cryptoKey.algorithm.length)
            val exported = js("crypto.subtle").exportKey("raw", key.cryptoKey).unsafeCast<Promise<dynamic>>()
            val refused = runCatching { exported.await() }
            assertTrue(refused.isFailure)
            assertEquals("VaultKey(hidden)", key.toString())
        }

    @Test
    fun everySealHasAFreshIvAndOpensOnlyWithTheSameKeyAndData() =
        runTest {
            val key = crypto.deriveKey(PASSPHRASE, salt, TestDevice.TEST_ITERATIONS)
            val one = crypto.seal(key, payload, additionalData)
            val two = crypto.seal(key, payload, additionalData)

            assertEquals(12, one.iv.size)
            assertFalse(one.iv.contentEquals(two.iv))
            assertNotEquals(one.ciphertext.toList(), two.ciphertext.toList())
            assertEquals(payload.size + 16, one.ciphertext.size)
            assertContentEquals(payload, crypto.open(key, one, additionalData))
            // The same passphrase and salt make the same key again: that is how another device opens the record.
            assertContentEquals(payload, crypto.open(crypto.deriveKey(PASSPHRASE, salt, TestDevice.TEST_ITERATIONS), one, additionalData))

            val otherKey = crypto.deriveKey(OTHER_PASSPHRASE, salt, TestDevice.TEST_ITERATIONS)
            val otherSalt = crypto.deriveKey(PASSPHRASE, crypto.randomBytes(16), TestDevice.TEST_ITERATIONS)
            val changed = one.ciphertext.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
            assertFailsWithOpen { crypto.open(otherKey, one, additionalData) }
            assertFailsWithOpen { crypto.open(otherSalt, one, additionalData) }
            assertFailsWithOpen { crypto.open(key, one, "thread/connections/v1/someone-else".encodeToByteArray()) }
            assertFailsWithOpen { crypto.open(key, Sealed(one.iv, changed), additionalData) }
            assertFailsWithOpen { crypto.open(key, Sealed(two.iv, one.ciphertext), additionalData) }
        }

    @Test
    fun randomBytesAreNotRepeated() {
        val seen = (1..50).map { crypto.randomBytes(16).toList() }.toSet()

        assertEquals(50, seen.size)
        assertFailsWith<IllegalArgumentException> { SecretText("") }
    }

    private suspend fun assertFailsWithOpen(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue(failure is VaultOpenException, "Expected VaultOpenException, got $failure")
    }
}
