package thread.data.vault

import thread.domain.external.SecretText

/*
 * The parts the encrypted connection sync is built from (adr/ADR-016-encrypted-connection-sync.md). The web
 * implementations are WebCrypto, IndexedDB and Firestore; nothing here holds a DOM or SDK type.
 */

/** A key made from the passphrase. Opaque: on the web, a non-extractable WebCrypto key. */
interface VaultKey

/** What AES-GCM made: the IV of this one write and the ciphertext with its tag. */
class Sealed(
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

interface VaultCrypto {
    /** Bytes from the platform's secure random source. */
    fun randomBytes(size: Int): ByteArray

    /** PBKDF2-HMAC-SHA-256 from [passphrase] and [salt] to an AES-GCM 256 key that cannot be exported. */
    suspend fun deriveKey(
        passphrase: SecretText,
        salt: ByteArray,
        iterations: Int,
    ): VaultKey

    /** AES-GCM with a fresh random 12-byte IV on every call, so an IV is never reused with a key. */
    suspend fun seal(
        key: VaultKey,
        plaintext: ByteArray,
        additionalData: ByteArray,
    ): Sealed

    /** Throws [VaultOpenException] when the key is not the one that sealed it or the data was changed. */
    suspend fun open(
        key: VaultKey,
        sealed: Sealed,
        additionalData: ByteArray,
    ): ByteArray
}

class VaultOpenException : Exception("The record did not open")

/** users/{uid}/vault/connections: everything but the passphrase needed to open it again. Byte values are base64. */
class VaultRecord(
    val kdf: String,
    val iterations: Int,
    val salt: String,
    val iv: String,
    val ciphertext: String,
)

/** The account's record on the server. Every call needs the server; nothing is read from a cache or queued. */
interface VaultRemote {
    /** The record, or null when there is none. */
    suspend fun read(): VaultRecord?

    /**
     * In one transaction, writes [record] only if the server's record has [expectedSalt], or, with null, if there is
     * no record. Returns false when it did not match and nothing was written.
     */
    suspend fun write(
        record: VaultRecord,
        expectedSalt: String?,
    ): Boolean

    suspend fun delete()
}

class VaultRemoteException(
    val kind: Kind,
) : Exception("Vault server request failed: $kind") {
    enum class Kind {
        /** The server could not be reached. */
        Offline,

        /** The rules or the sign-in refused it. */
        Refused,

        /** The server's record has a shape this version cannot read. */
        Damaged,

        /** Any other failure. Nothing was written. */
        Failed,
    }
}

/** This device's key for one account and the record it belongs to. */
data class DeviceVault(
    val key: VaultKey,
    val kdf: String,
    val iterations: Int,
    val salt: String,
    /** The IV of the last record this device wrote or applied, so a newer one from another device is noticed. */
    val appliedIv: String?,
    /** A change made on this device is not saved to the account yet. */
    val waiting: Boolean,
)

/** Where this device keeps its [DeviceVault]. Never the passphrase. */
interface DeviceVaultStore {
    suspend fun read(): DeviceVault?

    suspend fun write(vault: DeviceVault)

    suspend fun delete()
}
