package thread.data.vault

import thread.data.external.InMemoryKeyValueStore
import thread.data.external.KeyValueConnectionStore
import thread.domain.account.AccountId
import thread.domain.external.ConnectionStore
import thread.domain.external.SecretText

/** A server record in memory with the same match-then-write rule as the Firestore transaction. */
internal class MemoryVaultRemote : VaultRemote {
    var record: VaultRecord? = null

    /** When set, every call fails like this. */
    var failing: VaultRemoteException.Kind? = null

    override suspend fun read(): VaultRecord? {
        failing?.let { throw VaultRemoteException(it) }
        return record
    }

    override suspend fun write(
        record: VaultRecord,
        expectedSalt: String?,
    ): Boolean {
        failing?.let { throw VaultRemoteException(it) }
        val matches = if (expectedSalt == null) this.record == null else this.record?.salt == expectedSalt
        if (matches) this.record = record
        return matches
    }

    override suspend fun delete() {
        failing?.let { throw VaultRemoteException(it) }
        record = null
    }
}

internal class MemoryDeviceVaultStore : DeviceVaultStore {
    var vault: DeviceVault? = null

    override suspend fun read(): DeviceVault? = vault

    override suspend fun write(vault: DeviceVault) {
        this.vault = vault
    }

    override suspend fun delete() {
        vault = null
    }
}

/** One device of one account: its own connections and key, the account's shared record. */
internal class TestDevice(
    remote: VaultRemote,
    account: AccountId = AccountId("synthetic-account"),
    iterations: Int = TEST_ITERATIONS,
) {
    var online = true
    val connections: ConnectionStore = KeyValueConnectionStore(InMemoryKeyValueStore())
    val device = MemoryDeviceVaultStore()
    val sync = VaultConnectionSync(account, connections, WebCryptoVault(), remote, device, { online }, iterations)

    companion object {
        /** Low so the tests stay fast. The emulator tests use the real figure, which the rules require. */
        const val TEST_ITERATIONS = 1_000
    }
}

internal val PASSPHRASE = SecretText("synthetic passphrase one")
internal val OTHER_PASSPHRASE = SecretText("synthetic passphrase two")
