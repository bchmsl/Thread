package thread.data.local

/*
 * The small part of the IndexedDB API that Thread uses. The Kotlin/JS standard library has no IndexedDB
 * declarations, so these externals follow the web IDL (https://w3c.github.io/IndexedDB/). Only the members used
 * here are declared.
 */

internal external interface IdbFactory {
    fun open(
        name: String,
        version: Int,
    ): IdbOpenDbRequest

    fun deleteDatabase(name: String): IdbOpenDbRequest
}

internal external interface DomError {
    val name: String
    val message: String
}

internal external interface IdbRequest {
    val result: dynamic
    val error: DomError?
    var onsuccess: ((dynamic) -> Unit)?
    var onerror: ((dynamic) -> Unit)?
}

internal external interface IdbOpenDbRequest : IdbRequest {
    var onupgradeneeded: ((dynamic) -> Unit)?
    var onblocked: ((dynamic) -> Unit)?
}

internal external interface DomStringList {
    fun contains(name: String): Boolean
}

internal external interface IdbDatabase {
    val objectStoreNames: DomStringList

    fun createObjectStore(
        name: String,
        options: dynamic,
    ): IdbObjectStore

    fun transaction(
        storeNames: Array<String>,
        mode: String,
        options: dynamic,
    ): IdbTransaction

    fun close()

    var onversionchange: ((dynamic) -> Unit)?
    var onclose: ((dynamic) -> Unit)?
}

internal external interface IdbTransaction {
    val error: DomError?

    fun objectStore(name: String): IdbObjectStore

    fun abort()

    fun commit()

    var oncomplete: ((dynamic) -> Unit)?
    var onabort: ((dynamic) -> Unit)?
}

internal external interface IdbObjectStore {
    fun put(value: dynamic): IdbRequest

    fun get(key: dynamic): IdbRequest
}

/** The browser's IndexedDB factory, or null where IndexedDB does not exist. */
internal fun indexedDbFactory(): IdbFactory? = js("typeof indexedDB !== 'undefined' ? indexedDB : null").unsafeCast<IdbFactory?>()

internal fun newJsObject(): dynamic = js("({})")
