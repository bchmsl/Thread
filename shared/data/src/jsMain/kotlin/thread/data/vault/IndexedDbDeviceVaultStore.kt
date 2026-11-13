package thread.data.vault

import thread.data.local.IndexedDbKeyValueDatabase
import thread.data.local.newJsObject
import thread.domain.account.AccountId

/**
 * One account's [DeviceVault] in its own IndexedDB database on this browser profile, thread-vault-{uid}
 * (adr/ADR-016-encrypted-connection-sync.md). The key is stored as the non-extractable CryptoKey itself, so its bytes
 * never reach script, and the passphrase is never stored. A record that is not a valid one reads as no key, so the
 * device asks for the passphrase again.
 */
class IndexedDbDeviceVaultStore(
    databaseName: String,
) : DeviceVaultStore {
    constructor(accountId: AccountId) : this(IndexedDbKeyValueDatabase.vaultFor(accountId))

    private val database = IndexedDbKeyValueDatabase(databaseName, listOf(STORE), strictDurability = true)

    override suspend fun read(): DeviceVault? {
        val value: dynamic = database.getObject(STORE, KEY) ?: return null
        val cryptoKey: dynamic = value.cryptoKey
        val kdf: dynamic = value.kdf
        val iterations: dynamic = value.iterations
        val salt: dynamic = value.salt
        val appliedIv: dynamic = value.appliedIv
        val waiting: dynamic = value.waiting
        val valid =
            value.recordVersion == VERSION &&
                isCryptoKey(cryptoKey) &&
                jsTypeOf(kdf) == "string" &&
                jsTypeOf(iterations) == "number" &&
                jsTypeOf(salt) == "string" &&
                (appliedIv == null || jsTypeOf(appliedIv) == "string") &&
                jsTypeOf(waiting) == "boolean"
        if (!valid) return null
        return DeviceVault(
            key = WebCryptoKey(cryptoKey),
            kdf = kdf.unsafeCast<String>(),
            iterations = iterations.unsafeCast<Int>(),
            salt = salt.unsafeCast<String>(),
            appliedIv = appliedIv?.unsafeCast<String>(),
            waiting = waiting.unsafeCast<Boolean>(),
        )
    }

    override suspend fun write(vault: DeviceVault) {
        val value = newJsObject()
        value.recordVersion = VERSION
        value.cryptoKey = (vault.key as WebCryptoKey).cryptoKey
        value.kdf = vault.kdf
        value.iterations = vault.iterations
        value.salt = vault.salt
        value.appliedIv = vault.appliedIv
        value.waiting = vault.waiting
        database.putObject(STORE, KEY, value)
    }

    override suspend fun delete() {
        database.store(STORE).delete(KEY)
    }

    private companion object {
        const val STORE = IndexedDbKeyValueDatabase.VAULT_STORE
        const val KEY = "device"
        const val VERSION = 1
    }
}

private fun isCryptoKey(value: dynamic): Boolean = js("typeof CryptoKey !== 'undefined' && value instanceof CryptoKey") as Boolean
