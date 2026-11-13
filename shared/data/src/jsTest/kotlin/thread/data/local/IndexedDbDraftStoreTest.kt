package thread.data.local

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import thread.domain.note.LinkHref
import thread.domain.note.ListItem
import thread.domain.note.LocalDraft
import thread.domain.note.LocalDraftStoreException
import thread.domain.note.NoteId
import thread.domain.note.RevisionId
import thread.domain.note.RichTextBlock
import thread.domain.note.RichTextDocument
import thread.domain.note.RichTextInline
import thread.domain.note.RichTextJson
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs against the real IndexedDB of headless Chrome. Every test uses its own database and deletes it. */
class IndexedDbDraftStoreTest {
    private val noteId = NoteId("synthetic-note-1")

    private fun draft(text: String) =
        LocalDraft(
            noteId = noteId,
            title = "Synthetic title",
            body =
                RichTextDocument(
                    listOf(
                        RichTextBlock.Heading(2, listOf(RichTextInline.Text("Heading"))),
                        RichTextBlock.Paragraph(
                            listOf(
                                RichTextInline.Text(text, bold = true),
                                RichTextInline.HardBreak,
                                RichTextInline.Text("link", link = LinkHref.parse("https://example.com")),
                            ),
                        ),
                        RichTextBlock.BulletList(listOf(ListItem(listOf(RichTextBlock.Paragraph(listOf(RichTextInline.Text("ქართული"))))))),
                    ),
                ),
        )

    private fun withDatabase(test: suspend (name: String) -> Unit) =
        runTest {
            val name = "thread-test-" + Random.nextLong().toString(16)
            try {
                test(name)
            } finally {
                deleteDatabase(name)
            }
        }

    @Test
    fun readReturnsNullWhenNothingIsStored() =
        withDatabase { name ->
            assertNull(IndexedDbDraftStore(name).read(noteId))
        }

    @Test
    fun writtenDraftIsReadBackAlsoByANewConnection() =
        withDatabase { name ->
            IndexedDbDraftStore(name).write(draft("first"))

            assertEquals(draft("first"), IndexedDbDraftStore(name).read(noteId))
        }

    @Test
    fun writeReplacesThePreviousDraft() =
        withDatabase { name ->
            val store = IndexedDbDraftStore(name)
            store.write(draft("first"))
            store.write(draft("second"))

            assertEquals(draft("second"), store.read(noteId))
        }

    @Test
    fun recordHasTheDocumentedShape() =
        withDatabase { name ->
            IndexedDbDraftStore(name).write(draft("shape"))

            val record = rawGet(name, noteId.value)
            assertEquals(noteId.value, record.noteId)
            assertEquals(2, record.recordVersion)
            assertEquals("Synthetic title", record.title)
            assertEquals(RichTextJson.encode(draft("shape").body), record.body)
            assertEquals(null, record.baseRevisionId)
        }

    @Test
    fun baseRevisionIsStoredWithTheDraft() =
        withDatabase { name ->
            val based = draft("based").copy(baseRevisionId = RevisionId("synthetic-revision"))
            IndexedDbDraftStore(name).write(based)

            assertEquals(based, IndexedDbDraftStore(name).read(noteId))
        }

    @Test
    fun phaseOneRecordIsReadWithoutABase() =
        withDatabase { name ->
            IndexedDbDraftStore(name).write(draft("phase one"))
            val record = rawGet(name, noteId.value)
            record.recordVersion = 1
            js("(function (o) { delete o.baseRevisionId; })")(record)
            rawPut(name, record)

            assertEquals(draft("phase one"), IndexedDbDraftStore(name).read(noteId))
        }

    @Test
    fun failedWriteThrowsAndKeepsThePreviousDraft() =
        withDatabase { name ->
            var fail = false
            val store = IndexedDbDraftStore(name, failWritesForTesting = { fail })
            store.write(draft("kept"))

            fail = true
            val error = assertFailsWith<LocalDraftStoreException> { store.write(draft("lost")) }
            assertTrue("AbortError" in error.message.orEmpty(), error.message)

            fail = false
            assertEquals(draft("kept"), store.read(noteId))
        }

    @Test
    fun damagedRecordIsReportedNotDropped() =
        withDatabase { name ->
            val store = IndexedDbDraftStore(name)
            store.write(draft("valid"))
            val record = rawGet(name, noteId.value)
            record.body = """{"schemaVersion":1,"content":[{"type":"blockquote","content":[]}]}"""
            rawPut(name, record)

            val error = assertFailsWith<LocalDraftStoreException> { IndexedDbDraftStore(name).read(noteId) }
            assertTrue("unknown block type" in error.message.orEmpty(), error.message)
        }

    @Test
    fun newerRecordVersionIsRefused() =
        withDatabase { name ->
            IndexedDbDraftStore(name).write(draft("valid"))
            val record = rawGet(name, noteId.value)
            record.recordVersion = 3
            rawPut(name, record)

            assertFailsWith<LocalDraftStoreException> { IndexedDbDraftStore(name).read(noteId) }
        }

    private suspend fun openRaw(name: String): IdbDatabase =
        suspendCancellableCoroutine { continuation ->
            val request = requireNotNull(indexedDbFactory()).open(name, IndexedDbDraftStore.DATABASE_VERSION)
            request.onsuccess = { continuation.resume(request.result.unsafeCast<IdbDatabase>()) }
        }

    private suspend fun rawGet(
        name: String,
        key: String,
    ): dynamic {
        val db = openRaw(name)
        return suspendCancellableCoroutine<dynamic> { continuation ->
            val request =
                db
                    .transaction(
                        arrayOf(IndexedDbDraftStore.STORE_DRAFTS),
                        "readonly",
                        undefined,
                    ).objectStore(IndexedDbDraftStore.STORE_DRAFTS)
                    .get(key)
            request.onsuccess = {
                db.close()
                continuation.resume(request.result)
            }
        }
    }

    private suspend fun rawPut(
        name: String,
        record: dynamic,
    ) {
        val db = openRaw(name)
        suspendCancellableCoroutine { continuation ->
            val transaction = db.transaction(arrayOf(IndexedDbDraftStore.STORE_DRAFTS), "readwrite", undefined)
            transaction.oncomplete = {
                db.close()
                continuation.resume(Unit)
            }
            transaction.objectStore(IndexedDbDraftStore.STORE_DRAFTS).put(record)
        }
    }

    private suspend fun deleteDatabase(name: String) {
        suspendCancellableCoroutine { continuation ->
            val request = requireNotNull(indexedDbFactory()).deleteDatabase(name)
            request.onsuccess = { continuation.resume(Unit) }
            request.onerror = { continuation.resume(Unit) }
        }
    }
}
