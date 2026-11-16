package thread.data.vault

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import thread.data.external.asObject
import thread.data.external.int
import thread.data.external.obj
import thread.data.external.str
import thread.domain.account.AccountId
import thread.domain.external.ConnectionStore
import thread.domain.external.ConnectionStoreException
import thread.domain.external.ConnectionSync
import thread.domain.external.ConnectionSyncCheck
import thread.domain.external.ConnectionSyncException
import thread.domain.external.ConnectionSyncProblem
import thread.domain.external.ConnectionSyncState
import thread.domain.external.GitHubConnection
import thread.domain.external.JiraCloudId
import thread.domain.external.JiraConnection
import thread.domain.external.JiraSite
import thread.domain.external.SecretText
import thread.domain.external.SyncPassphrase
import kotlin.io.encoding.Base64

/**
 * [ConnectionSync] with one encrypted record per account (adr/ADR-016-encrypted-connection-sync.md).
 *
 * The record holds this device's Jira and GitHub connections as JSON, sealed with AES-GCM 256. The key comes from the
 * passphrase through PBKDF2-HMAC-SHA-256 with a random 16-byte salt and [iterations]. The additional data binds the
 * record to the account and the payload version, so a record copied to another account does not open.
 *
 * The whole record is written every time and the last write wins. Each write is a transaction that expects the salt
 * this device's key belongs to, so a device with an old key never undoes a new passphrase or brings back a record
 * that was turned off on another device.
 */
class VaultConnectionSync(
    private val accountId: AccountId,
    private val connections: ConnectionStore,
    private val crypto: VaultCrypto,
    private val remote: VaultRemote,
    private val device: DeviceVaultStore,
    private val isOnline: () -> Boolean,
    private val iterations: Int = ITERATIONS,
) : ConnectionSync {
    private val mutex = Mutex()

    override suspend fun check(): ConnectionSyncCheck = mutex.withLock { checkLocked(retries = 1) }

    override suspend fun turnOn(passphrase: SecretText) {
        requireLongEnough(passphrase)
        mutex.withLock {
            requireOnline()
            val held = newDeviceVault(passphrase)
            val record = seal(held)
            if (!remoteCall { remote.write(record, expectedSalt = null) }) fail(ConnectionSyncProblem.ChangedElsewhere)
            writeDevice(held.copy(appliedIv = record.iv))
        }
    }

    override suspend fun unlock(passphrase: SecretText) {
        mutex.withLock {
            requireOnline()
            val record = remoteCall { remote.read() } ?: fail(ConnectionSyncProblem.ChangedElsewhere)
            val key = keyFor(passphrase, record)
            val payload = openWith(key, record) ?: fail(ConnectionSyncProblem.WrongPassphrase)
            apply(payload)
            writeDevice(DeviceVault(key, record.kdf, record.iterations, record.salt, appliedIv = record.iv, waiting = false))
        }
    }

    override suspend fun changePassphrase(
        current: SecretText,
        new: SecretText,
    ) {
        requireLongEnough(new)
        mutex.withLock {
            requireOnline()
            val record = remoteCall { remote.read() } ?: fail(ConnectionSyncProblem.ChangedElsewhere)
            val held = readDevice()?.takeIf { it.salt == record.salt } ?: fail(ConnectionSyncProblem.ChangedElsewhere)
            val payload = openWith(keyFor(current, record), record) ?: fail(ConnectionSyncProblem.WrongPassphrase)
            // A newer record from another device that this device has not applied yet is the current state.
            if (!held.waiting && held.appliedIv != record.iv) apply(payload)
            val renewed = newDeviceVault(new)
            val sealed = seal(renewed)
            if (!remoteCall { remote.write(sealed, expectedSalt = record.salt) }) fail(ConnectionSyncProblem.ChangedElsewhere)
            writeDevice(renewed.copy(appliedIv = sealed.iv))
        }
    }

    override suspend fun turnOff() {
        mutex.withLock {
            requireOnline()
            remoteCall { remote.delete() }
            deleteDevice()
        }
    }

    override suspend fun connectionsChanged(): ConnectionSyncState =
        mutex.withLock {
            readDevice()?.let { if (!it.waiting) writeDevice(it.copy(waiting = true)) }
            checkLocked(retries = 1).state
        }

    /**
     * Only the salt and IV the server holds, so a repeat of the same record is not a change. Listening errors end the
     * flow quietly: the checks on sign-in, on a network change and on opening Settings still run.
     */
    override fun serverChanges(): Flow<Unit> =
        remote
            .watch()
            .distinctUntilChanged()
            .map { }
            .catch { }

    private suspend fun checkLocked(retries: Int): ConnectionSyncCheck {
        val held = readDevice()
        val offline = ConnectionSyncCheck(held?.let { ConnectionSyncState.On(it.waiting) } ?: ConnectionSyncState.Unknown, false)
        if (!isOnline()) return offline
        val record =
            try {
                remote.read()
            } catch (e: VaultRemoteException) {
                when (e.kind) {
                    VaultRemoteException.Kind.Offline -> return offline
                    // Online, a failed read is not "offline": it says so, and a change waiting here stays waiting.
                    VaultRemoteException.Kind.Failed -> if (isOnline()) fail(ConnectionSyncProblem.Failed) else return offline
                    VaultRemoteException.Kind.Refused -> fail(ConnectionSyncProblem.Refused)
                    VaultRemoteException.Kind.Damaged -> fail(ConnectionSyncProblem.Damaged)
                }
            }
        return when {
            record == null -> {
                // Turned off on another device. This device's connections stay.
                if (held != null) deleteDevice()
                ConnectionSyncCheck(ConnectionSyncState.Off, false)
            }
            held == null -> ConnectionSyncCheck(ConnectionSyncState.Locked(passphraseChanged = false), false)
            !held.belongsTo(record) -> {
                // A new passphrase on another device. This device asks for it once.
                deleteDevice()
                ConnectionSyncCheck(ConnectionSyncState.Locked(passphraseChanged = true), false)
            }
            held.waiting -> {
                val sealed = seal(held)
                val written =
                    try {
                        remote.write(sealed, expectedSalt = held.salt)
                    } catch (e: VaultRemoteException) {
                        if (e.kind == VaultRemoteException.Kind.Refused) fail(ConnectionSyncProblem.Refused)
                        return offline
                    }
                when {
                    written -> {
                        writeDevice(held.copy(appliedIv = sealed.iv, waiting = false))
                        ConnectionSyncCheck(ConnectionSyncState.On(waiting = false), false)
                    }
                    retries > 0 -> checkLocked(retries - 1)
                    else -> fail(ConnectionSyncProblem.ChangedElsewhere)
                }
            }
            held.appliedIv == record.iv -> ConnectionSyncCheck(ConnectionSyncState.On(waiting = false), false)
            else -> {
                // Another device saved a newer record with the same key.
                val payload = openWith(held.key, record) ?: fail(ConnectionSyncProblem.Damaged)
                apply(payload)
                writeDevice(held.copy(appliedIv = record.iv))
                ConnectionSyncCheck(ConnectionSyncState.On(waiting = false), true)
            }
        }
    }

    private fun DeviceVault.belongsTo(record: VaultRecord): Boolean =
        salt == record.salt && kdf == record.kdf && iterations == record.iterations

    private suspend fun newDeviceVault(passphrase: SecretText): DeviceVault {
        val salt = crypto.randomBytes(SALT_BYTES)
        val key = cryptoCall { crypto.deriveKey(passphrase, salt, iterations) }
        return DeviceVault(key, KDF, iterations, Base64.encode(salt), appliedIv = null, waiting = false)
    }

    /**
     * The key [record] was sealed with, if [passphrase] is right. Fewer iterations than this version uses, or more than
     * [MAX_ITERATIONS], read as damaged.
     */
    private suspend fun keyFor(
        passphrase: SecretText,
        record: VaultRecord,
    ): VaultKey {
        if (record.kdf != KDF || record.iterations !in iterations..MAX_ITERATIONS) fail(ConnectionSyncProblem.Damaged)
        val salt = decode(record.salt)?.takeIf { it.size == SALT_BYTES } ?: fail(ConnectionSyncProblem.Damaged)
        return cryptoCall { crypto.deriveKey(passphrase, salt, record.iterations) }
    }

    /** This device's connections, sealed with [held]'s key into a record for its salt. */
    private suspend fun seal(held: DeviceVault): VaultRecord {
        val plaintext = payload().toString().encodeToByteArray()
        val sealed =
            try {
                cryptoCall { crypto.seal(held.key, plaintext, additionalData()) }
            } finally {
                plaintext.fill(0)
            }
        return VaultRecord(held.kdf, held.iterations, held.salt, Base64.encode(sealed.iv), Base64.encode(sealed.ciphertext))
    }

    /** The payload of [record], or null when [key] does not open it. */
    private suspend fun openWith(
        key: VaultKey,
        record: VaultRecord,
    ): JsonObject? {
        val iv = decode(record.iv)?.takeIf { it.size == IV_BYTES } ?: fail(ConnectionSyncProblem.Damaged)
        val ciphertext = decode(record.ciphertext) ?: fail(ConnectionSyncProblem.Damaged)
        val plaintext =
            try {
                cryptoCall { crypto.open(key, Sealed(iv, ciphertext), additionalData()) }
            } catch (e: VaultOpenException) {
                return null
            }
        return try {
            Json.parseToJsonElement(plaintext.decodeToString()).asObject()?.takeIf { it.int(KEY_VERSION) == PAYLOAD_VERSION }
                ?: fail(ConnectionSyncProblem.Damaged)
        } catch (e: IllegalArgumentException) {
            fail(ConnectionSyncProblem.Damaged)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun additionalData(): ByteArray = "thread/connections/v$PAYLOAD_VERSION/${accountId.value}".encodeToByteArray()

    /** `{payloadVersion: 1, jira?: {site, cloudId, email, token}, github?: {token}}`: the same fields as on the device. */
    private suspend fun payload(): JsonObject {
        val jira = local { connections.jira() }
        val gitHub = local { connections.gitHub() }
        return buildJsonObject {
            put(KEY_VERSION, PAYLOAD_VERSION)
            if (jira != null) {
                put(
                    JIRA,
                    buildJsonObject {
                        put("site", jira.site.host)
                        put("cloudId", jira.cloudId.value)
                        put("email", jira.email)
                        put("token", jira.token.reveal())
                    },
                )
            }
            if (gitHub != null) put(GITHUB, buildJsonObject { put("token", gitHub.token.reveal()) })
        }
    }

    /** Replaces this device's connections with [payload]'s. A provider missing from it is disconnected here. */
    private suspend fun apply(payload: JsonObject) {
        val jira =
            payload.obj(JIRA)?.let { record ->
                val site = record.str("site")?.let(JiraSite::parse)
                val cloudId = record.str("cloudId")?.let(JiraCloudId::parse)
                val email = record.str("email")?.takeIf { '@' in it }
                val token = record.str("token")?.takeIf { it.isNotBlank() }
                if (site == null || cloudId == null || email == null || token == null) fail(ConnectionSyncProblem.Damaged)
                JiraConnection(site, cloudId, email, SecretText(token))
            }
        val gitHub =
            payload.obj(GITHUB)?.let { record ->
                GitHubConnection(SecretText(record.str("token")?.takeIf { it.isNotBlank() } ?: fail(ConnectionSyncProblem.Damaged)))
            }
        local { if (jira != null) connections.saveJira(jira) else connections.clearJira() }
        local { if (gitHub != null) connections.saveGitHub(gitHub) else connections.clearGitHub() }
    }

    private fun requireLongEnough(passphrase: SecretText) {
        if (!SyncPassphrase.isLongEnough(passphrase)) fail(ConnectionSyncProblem.TooShort)
    }

    private fun requireOnline() {
        if (!isOnline()) fail(ConnectionSyncProblem.Offline)
    }

    private suspend fun readDevice(): DeviceVault? = local { device.read() }

    private suspend fun writeDevice(vault: DeviceVault) = local { device.write(vault) }

    private suspend fun deleteDevice() = local { device.delete() }

    /** Device storage errors, [ConnectionStoreException] among them, become [ConnectionSyncProblem.Device] without their message. */
    private suspend fun <T> local(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(ConnectionSyncProblem.Device)
        }

    private suspend fun <T> remoteCall(block: suspend () -> T): T =
        try {
            block()
        } catch (e: VaultRemoteException) {
            fail(
                when (e.kind) {
                    VaultRemoteException.Kind.Offline -> ConnectionSyncProblem.Offline
                    VaultRemoteException.Kind.Refused -> ConnectionSyncProblem.Refused
                    VaultRemoteException.Kind.Damaged -> ConnectionSyncProblem.Damaged
                    VaultRemoteException.Kind.Failed -> ConnectionSyncProblem.Failed
                },
            )
        }

    /** A crypto failure other than a record that does not open is this device's problem. */
    private suspend fun <T> cryptoCall(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultOpenException) {
            throw e
        } catch (e: ConnectionSyncException) {
            throw e
        } catch (e: Throwable) {
            fail(ConnectionSyncProblem.Device)
        }

    private fun decode(value: String): ByteArray? =
        try {
            Base64.decode(value)
        } catch (e: IllegalArgumentException) {
            null
        }

    private fun fail(problem: ConnectionSyncProblem): Nothing = throw ConnectionSyncException(problem)

    companion object {
        const val KDF: String = "PBKDF2-SHA-256"

        /** The OWASP Password Storage Cheat Sheet figure for PBKDF2-HMAC-SHA-256, checked on 2026-11-14. */
        const val ITERATIONS: Int = 600_000

        /** A record asking for more is not derived, so a changed record cannot keep a device busy. */
        const val MAX_ITERATIONS: Int = 10_000_000
        const val SALT_BYTES: Int = 16
        const val IV_BYTES: Int = 12
        private const val PAYLOAD_VERSION = 1
        private const val KEY_VERSION = "payloadVersion"
        private const val JIRA = "jira"
        private const val GITHUB = "github"
    }
}
