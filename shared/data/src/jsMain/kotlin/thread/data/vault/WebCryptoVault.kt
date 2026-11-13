package thread.data.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import thread.data.local.newJsObject
import thread.domain.external.SecretText
import kotlin.js.Promise

/**
 * [VaultCrypto] on the browser's WebCrypto (https://w3c.github.io/webcrypto/). Kotlin's ByteArray is an Int8Array, which
 * WebCrypto takes as a BufferSource as it is. Keys are made non-extractable, so script on the page can use them but
 * cannot read their bytes. WebCrypto exists only in secure contexts: https pages and 127.0.0.1.
 */
class WebCryptoVault : VaultCrypto {
    override fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        webCrypto().getRandomValues(bytes)
        return bytes
    }

    override suspend fun deriveKey(
        passphrase: SecretText,
        salt: ByteArray,
        iterations: Int,
    ): VaultKey {
        val secret = passphrase.reveal().encodeToByteArray()
        val material =
            try {
                call { subtle().importKey("raw", secret, "PBKDF2", false, arrayOf("deriveKey")) }
            } finally {
                secret.fill(0)
            }
        val params = newJsObject()
        params.name = "PBKDF2"
        params.hash = "SHA-256"
        params.salt = salt
        params.iterations = iterations
        val algorithm = newJsObject()
        algorithm.name = AES_GCM
        algorithm.length = KEY_BITS
        return WebCryptoKey(call { subtle().deriveKey(params, material, algorithm, false, arrayOf("encrypt", "decrypt")) })
    }

    override suspend fun seal(
        key: VaultKey,
        plaintext: ByteArray,
        additionalData: ByteArray,
    ): Sealed {
        val iv = randomBytes(VaultConnectionSync.IV_BYTES)
        val ciphertext = call { subtle().encrypt(gcm(iv, additionalData), cryptoKeyOf(key), plaintext) }
        return Sealed(iv, Int8Array(ciphertext.unsafeCast<ArrayBuffer>()).unsafeCast<ByteArray>())
    }

    override suspend fun open(
        key: VaultKey,
        sealed: Sealed,
        additionalData: ByteArray,
    ): ByteArray {
        val plaintext = call { subtle().decrypt(gcm(sealed.iv, additionalData), cryptoKeyOf(key), sealed.ciphertext) }
        return Int8Array(plaintext.unsafeCast<ArrayBuffer>()).unsafeCast<ByteArray>()
    }

    /**
     * Awaits one WebCrypto promise. Its DOMException is not a Kotlin exception, so it becomes one here: an
     * OperationError, which WebCrypto gives alike for a wrong key and for changed data, is [VaultOpenException].
     */
    private suspend fun call(block: () -> dynamic): dynamic =
        try {
            block().unsafeCast<Promise<dynamic>>().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (errorName(e) == "OperationError") throw VaultOpenException()
            throw IllegalStateException("WebCrypto failed: ${errorName(e) ?: "unknown error"}")
        }

    private fun gcm(
        iv: ByteArray,
        additionalData: ByteArray,
    ): dynamic {
        val params = newJsObject()
        params.name = AES_GCM
        params.iv = iv
        params.additionalData = additionalData
        params.tagLength = TAG_BITS
        return params
    }

    private fun cryptoKeyOf(key: VaultKey): dynamic = (key as WebCryptoKey).cryptoKey

    private companion object {
        const val AES_GCM = "AES-GCM"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
    }
}

/** A non-extractable WebCrypto AES-GCM key. IndexedDB can store it as it is (structured clone). */
class WebCryptoKey internal constructor(
    internal val cryptoKey: dynamic,
) : VaultKey {
    override fun toString(): String = "VaultKey(hidden)"
}

private fun webCrypto(): dynamic = js("globalThis.crypto")

private fun subtle(): dynamic = webCrypto().subtle ?: throw IllegalStateException("WebCrypto needs a secure context")

/** The DOMException name of a rejected WebCrypto promise, such as `OperationError`. */
private fun errorName(error: Throwable): String? {
    val name: dynamic = error.asDynamic().name
    return if (jsTypeOf(name) == "string") name.unsafeCast<String>() else null
}
