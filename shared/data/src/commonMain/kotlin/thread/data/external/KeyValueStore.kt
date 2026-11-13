package thread.data.external

/**
 * A small string store on this device. IndexedDB in the browser; in memory in tests and in the synthetic fixture
 * mode. It keeps connections and saved copies of Jira and GitHub records, which never leave this device (D08, D09).
 */
interface KeyValueStore {
    /** Throws [KeyValueStoreException] when the device storage fails. */
    suspend fun get(key: String): String?

    suspend fun put(
        key: String,
        value: String,
    )

    suspend fun delete(key: String)

    suspend fun all(): List<Pair<String, String>>

    suspend fun clear()
}

class KeyValueStoreException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** [KeyValueStore] in memory, for tests and the synthetic fixture mode. Gone after a reload. */
class InMemoryKeyValueStore : KeyValueStore {
    private val values = LinkedHashMap<String, String>()

    override suspend fun get(key: String): String? = values[key]

    override suspend fun put(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    override suspend fun delete(key: String) {
        values.remove(key)
    }

    override suspend fun all(): List<Pair<String, String>> = values.map { (key, value) -> key to value }

    override suspend fun clear() {
        values.clear()
    }
}
