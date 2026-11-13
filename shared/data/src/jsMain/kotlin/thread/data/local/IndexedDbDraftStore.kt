package thread.data.local

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import thread.domain.note.LocalDraft
import thread.domain.note.LocalDraftStore
import thread.domain.note.LocalDraftStoreException
import thread.domain.note.NoteId
import thread.domain.note.RichTextFormatException
import thread.domain.note.RichTextJson
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [LocalDraftStore] on the browser's IndexedDB (adr/ADR-003-editor-and-richtext.md).
 *
 * Database [databaseName], version 1, object store `noteDrafts` with key path `noteId`. One record per Note:
 * `{noteId, recordVersion: 1, title, body}`, where `body` is the [RichTextJson] string.
 *
 * A write uses durability `"strict"` and returns only on the transaction's `complete` event, so a returned write is
 * on disk as far as the browser can tell. When the database is already open, the write is issued before the first
 * suspension, as [LocalDraftStore] asks.
 *
 * [failWritesForTesting] is a test hook. When it returns true, a write aborts its own transaction after the put, so
 * the real abort path runs and nothing is stored. Production code leaves the default.
 */
class IndexedDbDraftStore(
    private val databaseName: String = DEFAULT_DATABASE_NAME,
    private val failWritesForTesting: () -> Boolean = { false },
) : LocalDraftStore {
    private var opened: IdbDatabase? = null
    private var opening: CompletableDeferred<IdbDatabase>? = null

    override suspend fun read(noteId: NoteId): LocalDraft? {
        val record = readRecord(database(), noteId.value)
        return if (record == null || record == undefined) null else toDraft(noteId, record)
    }

    override suspend fun write(draft: LocalDraft) {
        val record = toRecord(draft)
        writeRecord(database(), record)
    }

    /** The open database. Does not suspend once the database is open. */
    private suspend fun database(): IdbDatabase {
        opened?.let { return it }
        val pending = opening ?: CompletableDeferred<IdbDatabase>().also { startOpening(it) }
        return pending.await()
    }

    private fun startOpening(result: CompletableDeferred<IdbDatabase>) {
        opening = result
        val factory = indexedDbFactory() ?: return failOpening(result, LocalDraftStoreException("IndexedDB is not available"))
        val request =
            try {
                factory.open(databaseName, DATABASE_VERSION)
            } catch (e: Throwable) {
                return failOpening(result, LocalDraftStoreException("Could not open local storage: ${e.message}", e))
            }
        request.onupgradeneeded = {
            val db = request.result.unsafeCast<IdbDatabase>()
            if (!db.objectStoreNames.contains(STORE_DRAFTS)) {
                val options = newJsObject()
                options.keyPath = KEY_NOTE_ID
                db.createObjectStore(STORE_DRAFTS, options)
            }
        }
        request.onsuccess = {
            val db = request.result.unsafeCast<IdbDatabase>()
            // Another tab wants a newer version, or the browser closed the database (storage cleared). The next
            // call opens it again.
            db.onversionchange = {
                db.close()
                forget(db)
            }
            db.onclose = { forget(db) }
            opened = db
            if (opening === result) opening = null
            result.complete(db)
        }
        request.onerror = {
            failOpening(result, LocalDraftStoreException("Could not open local storage: ${request.error?.name ?: "unknown error"}"))
        }
    }

    private fun failOpening(
        result: CompletableDeferred<IdbDatabase>,
        error: LocalDraftStoreException,
    ) {
        if (opening === result) opening = null
        result.completeExceptionally(error)
    }

    private fun forget(db: IdbDatabase) {
        if (opened === db) opened = null
    }

    private suspend fun readRecord(
        db: IdbDatabase,
        key: String,
    ): dynamic =
        suspendCancellableCoroutine<dynamic> { continuation ->
            val transaction =
                try {
                    db.transaction(arrayOf(STORE_DRAFTS), "readonly", undefined)
                } catch (e: Throwable) {
                    continuation.failOnce(LocalDraftStoreException("Could not read local drafts: ${e.message}", e))
                    return@suspendCancellableCoroutine
                }
            var value: dynamic = null
            transaction.oncomplete = { continuation.resumeOnce(value) }
            transaction.onabort = {
                continuation.failOnce(LocalDraftStoreException("Reading local drafts failed: ${transaction.error?.name ?: "AbortError"}"))
            }
            val request = transaction.objectStore(STORE_DRAFTS).get(key)
            request.onsuccess = { value = request.result }
        }

    private suspend fun writeRecord(
        db: IdbDatabase,
        record: dynamic,
    ): Unit =
        suspendCancellableCoroutine { continuation ->
            val transaction =
                try {
                    db.transaction(arrayOf(STORE_DRAFTS), "readwrite", strictDurability())
                } catch (e: Throwable) {
                    continuation.failOnce(LocalDraftStoreException("Could not start saving locally: ${e.message}", e))
                    return@suspendCancellableCoroutine
                }
            transaction.oncomplete = { continuation.resumeOnce(Unit) }
            transaction.onabort = {
                continuation.failOnce(LocalDraftStoreException("Saving locally failed: ${transaction.error?.name ?: "AbortError"}"))
            }
            try {
                transaction.objectStore(STORE_DRAFTS).put(record)
                when {
                    failWritesForTesting() -> transaction.abort()
                    // commit() ends the transaction now instead of after the next task. Older browsers lack it.
                    transaction.asDynamic().commit != undefined -> transaction.commit()
                }
            } catch (e: Throwable) {
                // For example a DataCloneError from put. Abort, so onabort reports the failure.
                try {
                    transaction.abort()
                } catch (alreadyFinished: Throwable) {
                    continuation.failOnce(LocalDraftStoreException("Saving locally failed: ${e.message}", e))
                }
            }
        }

    private fun toRecord(draft: LocalDraft): dynamic {
        val record = newJsObject()
        record[KEY_NOTE_ID] = draft.noteId.value
        record[KEY_RECORD_VERSION] = RECORD_VERSION
        record[KEY_TITLE] = draft.title
        record[KEY_BODY] = RichTextJson.encode(draft.body)
        return record
    }

    private fun toDraft(
        noteId: NoteId,
        record: dynamic,
    ): LocalDraft {
        if (record[KEY_RECORD_VERSION] != RECORD_VERSION) {
            throw LocalDraftStoreException("Unsupported local draft record version")
        }
        val title: dynamic = record[KEY_TITLE]
        val body: dynamic = record[KEY_BODY]
        if (jsTypeOf(title) != "string" || jsTypeOf(body) != "string") {
            throw LocalDraftStoreException("The local draft record is damaged")
        }
        val document =
            try {
                RichTextJson.decode(body.unsafeCast<String>())
            } catch (e: RichTextFormatException) {
                throw LocalDraftStoreException("The local draft cannot be read: ${e.message}", e)
            }
        return LocalDraft(noteId, title.unsafeCast<String>(), document)
    }

    companion object {
        const val DEFAULT_DATABASE_NAME: String = "thread-local"
        internal const val DATABASE_VERSION: Int = 1
        internal const val STORE_DRAFTS: String = "noteDrafts"
        internal const val RECORD_VERSION: Int = 1
        internal const val KEY_NOTE_ID: String = "noteId"
        internal const val KEY_RECORD_VERSION: String = "recordVersion"
        internal const val KEY_TITLE: String = "title"
        internal const val KEY_BODY: String = "body"
    }
}

private fun strictDurability(): dynamic {
    val options = newJsObject()
    options.durability = "strict"
    return options
}

private fun <T> CancellableContinuation<T>.resumeOnce(value: T) {
    if (isActive) resume(value)
}

private fun CancellableContinuation<*>.failOnce(error: Throwable) {
    if (isActive) resumeWithException(error)
}
