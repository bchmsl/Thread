package thread.domain.external

import kotlinx.coroutines.flow.Flow

/**
 * Sync of one account's Jira and GitHub connections across devices (adr/ADR-016-encrypted-connection-sync.md).
 *
 * The device encrypts the connections with a key made from a passphrase the person picks, and only the encrypted
 * record goes to the server. The passphrase is never stored: each device asks for it once and keeps the key. There is
 * no recovery. With a forgotten passphrase, the person turns sync off and connects again.
 *
 * Every write needs the server, so it is never queued. A change made on this device while offline is marked and
 * saved by the next [check].
 */
interface ConnectionSync {
    /**
     * Reads the saved record from the server. On a device with the key, it saves this device's changes still waiting,
     * or applies a newer record from another device to this device's connections.
     */
    suspend fun check(): ConnectionSyncCheck

    /** Encrypts this device's connections with a key made from [passphrase] and saves them to the account. */
    suspend fun turnOn(passphrase: SecretText)

    /** Opens the saved record with [passphrase] and replaces this device's connections with it. */
    suspend fun unlock(passphrase: SecretText)

    /** Checks [current], then saves the connections again with a key made from [new]. Other devices ask for it once. */
    suspend fun changePassphrase(
        current: SecretText,
        new: SecretText,
    )

    /** Deletes the saved record from the account and forgets the key here. This device's connections stay. */
    suspend fun turnOff()

    /**
     * After a connect, disconnect or new token on this device: saves the connections to the account when this device
     * has the key. Offline, the change waits for the next [check]. Never throws for a missing network.
     */
    suspend fun connectionsChanged(): ConnectionSyncState

    /**
     * Tells, while collected, that the saved record may have changed on the server: created, deleted, written again
     * or saved with a new passphrase, also by this device. It carries no data; the caller runs [check], which reads
     * and decides. It ends quietly when the server stops answering, so the caller may collect it again later.
     */
    fun serverChanges(): Flow<Unit>
}

sealed interface ConnectionSyncState {
    /** The account has no saved connections. */
    data object Off : ConnectionSyncState

    /** This device has the key. [waiting]: a change made here is not saved to the account yet. */
    data class On(
        val waiting: Boolean,
    ) : ConnectionSyncState

    /**
     * The account has saved connections that this device cannot open yet: a new device, or [passphraseChanged] on
     * another device.
     */
    data class Locked(
        val passphraseChanged: Boolean,
    ) : ConnectionSyncState

    /** The server could not be reached and this device has no key, so whether sync is on is not known. */
    data object Unknown : ConnectionSyncState
}

/** What a [ConnectionSync.check] found. [replacedHere]: this device's connections now hold another device's change. */
class ConnectionSyncCheck(
    val state: ConnectionSyncState,
    val replacedHere: Boolean,
)

enum class ConnectionSyncProblem {
    /** The passphrase is shorter than [SyncPassphrase.MIN_LENGTH]. */
    TooShort,

    /** The passphrase does not open the saved connections. Nothing changed. */
    WrongPassphrase,

    /** The server could not be reached. Nothing changed. */
    Offline,

    /** The saved connections changed on another device meanwhile (turned on, off or a new passphrase). Check again. */
    ChangedElsewhere,

    /** The saved record cannot be read: damaged or changed by someone else. This device's connections did not change. */
    Damaged,

    /** The server refused the request. */
    Refused,

    /** The server did not finish the request. Nothing was written. */
    Failed,

    /** This device's storage failed. */
    Device,
}

/** Carries only the problem, never a cause, a passphrase, a token or a record. */
class ConnectionSyncException(
    val problem: ConnectionSyncProblem,
) : Exception("Connection sync: $problem")

object SyncPassphrase {
    /** A few unrelated words pass easily. The key derivation slows down guessing, but a short passphrase stays weak. */
    const val MIN_LENGTH: Int = 12

    fun isLongEnough(passphrase: SecretText): Boolean = passphrase.reveal().length >= MIN_LENGTH
}
