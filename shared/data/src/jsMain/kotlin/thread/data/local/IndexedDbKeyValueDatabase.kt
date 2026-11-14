package thread.data.local

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import thread.data.external.KeyValueStore
import thread.data.external.KeyValueStoreException
import thread.domain.account.AccountId
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * An IndexedDB database of simple string stores, for connections and saved copies of Jira and GitHub records
 * (adr/ADR-006, ADR-007, ADR-008), each account's record of workspace changes on their way to the server
 * (adr/ADR-009) and each account's local copy of automatic changes (D54). Each store holds records `{key, value}` with key path `key`. The connection sync key is the one
 * value that is not a string ([getObject], adr/ADR-016).
 *
 * It is separate from the Note drafts database (thread-local and thread-drafts-*), so clearing saved copies can
 * never touch a Note. [strictDurability] makes writes wait until the browser reports them on disk; connections use
 * it, saved copies do not need it.
 */
class IndexedDbKeyValueDatabase(
    private val name: String,
    private val storeNames: List<String>,
    private val strictDurability: Boolean = false,
) {
    private var opened: IdbDatabase? = null
    private var opening: CompletableDeferred<IdbDatabase>? = null

    fun store(storeName: String): KeyValueStore {
        require(storeName in storeNames) { "Unknown store" }
        return Store(storeName)
    }

    /**
     * The value of [key] in [storeName] as IndexedDB stored it, or null. For values a string cannot hold, such as a
     * non-extractable WebCrypto key, which IndexedDB keeps as it is (structured clone, adr/ADR-016).
     */
    internal suspend fun getObject(
        storeName: String,
        key: String,
    ): dynamic {
        require(storeName in storeNames) { "Unknown store" }
        val record: dynamic = request(storeName, READONLY) { it.get(key) }
        return if (record == null || record == undefined) null else record[VALUE]
    }

    internal suspend fun putObject(
        storeName: String,
        key: String,
        value: dynamic,
    ) {
        require(storeName in storeNames) { "Unknown store" }
        val record = newJsObject()
        record[KEY] = key
        record[VALUE] = value
        request(storeName, READWRITE) { it.put(record) }
    }

    private inner class Store(
        private val storeName: String,
    ) : KeyValueStore {
        override suspend fun get(key: String): String? {
            val record: dynamic = request(storeName, READONLY) { it.get(key) }
            if (record == null || record == undefined) return null
            val value: dynamic = record[VALUE]
            return if (jsTypeOf(value) == "string") value.unsafeCast<String>() else null
        }

        override suspend fun put(
            key: String,
            value: String,
        ) {
            val record = newJsObject()
            record[KEY] = key
            record[VALUE] = value
            request(storeName, READWRITE) { it.put(record) }
        }

        override suspend fun delete(key: String) {
            request(storeName, READWRITE) { it.delete(key) }
        }

        override suspend fun all(): List<Pair<String, String>> {
            val records = request(storeName, READONLY) { it.getAll() }.unsafeCast<Array<dynamic>>()
            return records.mapNotNull { record ->
                val key: dynamic = record[KEY]
                val value: dynamic = record[VALUE]
                if (jsTypeOf(key) == "string" &&
                    jsTypeOf(value) == "string"
                ) {
                    key.unsafeCast<String>() to value.unsafeCast<String>()
                } else {
                    null
                }
            }
        }

        override suspend fun clear() {
            request(storeName, READWRITE) { it.clear() }
        }
    }

    /** Runs one request in its own transaction and returns its result once the transaction completes. */
    private suspend fun request(
        storeName: String,
        mode: String,
        block: (IdbObjectStore) -> IdbRequest,
    ): dynamic {
        val db = database()
        return suspendCancellableCoroutine<dynamic> { continuation ->
            val options = newJsObject()
            if (mode == READWRITE && strictDurability) options.durability = "strict"
            val transaction =
                try {
                    db.transaction(arrayOf(storeName), mode, options)
                } catch (e: Throwable) {
                    continuation.failOnce(KeyValueStoreException("Could not use device storage: ${e.message}", e))
                    return@suspendCancellableCoroutine
                }
            var result: dynamic = null
            transaction.oncomplete = { continuation.resumeOnce(result) }
            transaction.onabort = {
                continuation.failOnce(KeyValueStoreException("Device storage failed: ${transaction.error?.name ?: "AbortError"}"))
            }
            try {
                val request = block(transaction.objectStore(storeName))
                request.onsuccess = { result = request.result }
            } catch (e: Throwable) {
                try {
                    transaction.abort()
                } catch (alreadyFinished: Throwable) {
                    continuation.failOnce(KeyValueStoreException("Device storage failed: ${e.message}", e))
                }
            }
        }
    }

    private suspend fun database(): IdbDatabase {
        opened?.let { return it }
        val pending = opening ?: CompletableDeferred<IdbDatabase>().also { startOpening(it) }
        return pending.await()
    }

    private fun startOpening(result: CompletableDeferred<IdbDatabase>) {
        opening = result
        val factory = indexedDbFactory() ?: return failOpening(result, KeyValueStoreException("IndexedDB is not available"))
        val request =
            try {
                factory.open(name, VERSION)
            } catch (e: Throwable) {
                return failOpening(result, KeyValueStoreException("Could not open device storage: ${e.message}", e))
            }
        request.onupgradeneeded = {
            val db = request.result.unsafeCast<IdbDatabase>()
            for (storeName in storeNames) {
                if (!db.objectStoreNames.contains(storeName)) {
                    val options = newJsObject()
                    options.keyPath = KEY
                    db.createObjectStore(storeName, options)
                }
            }
        }
        request.onsuccess = {
            val db = request.result.unsafeCast<IdbDatabase>()
            db.onversionchange = {
                db.close()
                if (opened === db) opened = null
            }
            db.onclose = { if (opened === db) opened = null }
            opened = db
            if (opening === result) opening = null
            result.complete(db)
        }
        request.onerror = {
            failOpening(result, KeyValueStoreException("Could not open device storage: ${request.error?.name ?: "unknown error"}"))
        }
    }

    private fun failOpening(
        result: CompletableDeferred<IdbDatabase>,
        error: KeyValueStoreException,
    ) {
        if (opening === result) opening = null
        result.completeExceptionally(error)
    }

    companion object {
        private const val VERSION = 1
        private const val KEY = "key"
        private const val VALUE = "value"
        private const val READONLY = "readonly"
        private const val READWRITE = "readwrite"

        /** Connections (tokens) of this browser profile. */
        const val CONNECTIONS: String = "thread-connections"
        const val CONNECTIONS_STORE: String = "connections"

        /** Saved copies of Jira and GitHub records. The synthetic fixture mode uses its own database. */
        const val EXTERNAL_CACHE: String = "thread-external-cache"
        const val EXTERNAL_CACHE_SYNTHETIC: String = "thread-external-cache-synthetic"
        const val JIRA_ISSUES: String = "jiraIssues"
        const val JIRA_META: String = "jiraMeta"
        const val PR_LISTS: String = "prLists"
        const val PULL_REQUESTS: String = "pullRequests"
        val EXTERNAL_CACHE_STORES: List<String> = listOf(JIRA_ISSUES, JIRA_META, PR_LISTS, PULL_REQUESTS)

        /** One account's record of workspace changes on their way to the server (owner-review-queue Q14). */
        fun workspaceOutboxFor(accountId: AccountId): String = "thread-outbox-${accountId.value}"

        const val WORKSPACE_OUTBOX_STORE: String = "changes"

        /** One account's connection sync key on this device (adr/ADR-016-encrypted-connection-sync.md). */
        fun vaultFor(accountId: AccountId): String = "thread-vault-${accountId.value}"

        const val VAULT_STORE: String = "keys"

        /** One account's local copies of automatic changes, such as the Topic reminder conversion (D54). */
        fun workspaceBackupFor(accountId: AccountId): String = "thread-backup-${accountId.value}"

        const val WORKSPACE_BACKUP_STORE: String = "conversions"
    }
}

private fun <T> CancellableContinuation<T>.resumeOnce(value: T) {
    if (isActive) resume(value)
}

private fun CancellableContinuation<*>.failOnce(error: Throwable) {
    if (isActive) resumeWithException(error)
}
