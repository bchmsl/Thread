package thread.domain.note

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import thread.domain.note.LocalSaveStatus.NeedsAttention
import thread.domain.note.LocalSaveStatus.Saved
import thread.domain.note.LocalSaveStatus.Saving
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class DraftAutosaverTest {
    private val noteId = NoteId("synthetic-note-1")

    private fun draft(
        text: String,
        title: String = "Synthetic title",
    ) = LocalDraft(noteId, title, RichTextDocument(listOf(RichTextBlock.Paragraph(listOf(RichTextInline.Text(text))))))

    private fun TestScope.autosaver(
        store: FakeStore,
        initial: LocalDraft = draft("a"),
        initialIsStored: Boolean = true,
    ) = DraftAutosaver(store, backgroundScope, initial, initialIsStored, debounce = 300.milliseconds, maxWait = 1000.milliseconds)

    @Test
    fun storedInitialDraftIsSavedAndNotWrittenAgain() =
        runTest {
            val store = FakeStore()
            val saver = autosaver(store)

            advanceUntilIdle()

            assertEquals(Saved, saver.status.value)
            assertTrue(store.writes.isEmpty())
        }

    @Test
    fun newInitialDraftIsWrittenAfterTheDebounce() =
        runTest {
            val store = FakeStore()
            val saver = autosaver(store, initial = draft("seed"), initialIsStored = false)
            assertEquals(Saving, saver.status.value)

            advanceTimeBy(299)
            assertTrue(store.writes.isEmpty())
            advanceTimeBy(2)
            store.completeNext()
            runCurrent()

            assertEquals(listOf(draft("seed")), store.writtenDrafts)
            assertEquals(Saved, saver.status.value)
        }

    @Test
    fun editsWaitForAPauseThenWriteOnlyTheNewestContent() =
        runTest {
            val store = FakeStore()
            val saver = autosaver(store)

            saver.edit(draft("b"))
            advanceTimeBy(200)
            saver.edit(draft("c"))
            advanceTimeBy(200)
            assertTrue(store.writes.isEmpty(), "The second edit restarts the pause")
            advanceTimeBy(101)

            assertEquals(listOf(draft("c")), store.writtenDrafts)
        }

    @Test
    fun continuousEditingStillWritesAfterMaxWait() =
        runTest {
            val store = FakeStore()
            val saver = autosaver(store)

            listOf("t0", "t250", "t500", "t750").forEach { text ->
                saver.edit(draft(text))
                advanceTimeBy(250)
            }
            // Now at 1000 ms, still editing. The max wait from the first edit fires at 1000 ms.
            runCurrent()

            assertEquals(listOf(draft("t750")), store.writtenDrafts)
        }

    @Test
    fun flushStartsTheWriteBeforeReturning() =
        runTest {
            val store = FakeStore()
            val saver = autosaver(store)

            saver.edit(draft("typed just now"))
            saver.flush()

            // No virtual time passed and no task ran: a pagehide handler gets the same behavior.
            assertEquals(listOf(draft("typed just now")), store.writtenDrafts)
            advanceTimeBy(5_000)
            assertEquals(1, store.writes.size, "The flush cancels the pending timers")
        }

    @Test
    fun flushWithoutNewEditsWritesNothing() =
        runTest {
            val store = FakeStore()
            val saver = autosaver(store)

            saver.flush()
            saver.edit(draft("b"))
            saver.flush()
            saver.flush()

            assertEquals(1, store.writes.size)
        }

    @Test
    fun savedOnlyAfterTheStoreConfirms() =
        runTest {
            val store = FakeStore()
            val saver = autosaver(store)

            saver.edit(draft("b"))
            assertEquals(Saving, saver.status.value)
            advanceTimeBy(301)
            assertEquals(1, store.writes.size)
            assertEquals(Saving, saver.status.value, "Written but not confirmed")

            store.completeNext()
            runCurrent()
            assertEquals(Saved, saver.status.value)
        }

    @Test
    fun editDuringAWriteStaysSavingUntilTheNewestIsStored() =
        runTest {
            val store = FakeStore()
            val saver = autosaver(store)

            saver.edit(draft("b"))
            advanceTimeBy(301)
            saver.edit(draft("c"))
            store.completeNext()
            runCurrent()
            assertEquals(Saving, saver.status.value, "The stored version is older than the screen")

            advanceTimeBy(301)
            store.completeNext()
            runCurrent()
            assertEquals(listOf(draft("b"), draft("c")), store.writtenDrafts)
            assertEquals(Saved, saver.status.value)
        }

    @Test
    fun failedWriteNeedsAttentionAndNeverShowsSavedWhileStorageFails() =
        runTest {
            val store = FakeStore()
            val saver = autosaver(store)
            val seen = mutableListOf<LocalSaveStatus>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { saver.status.toList(seen) }

            saver.edit(draft("b"))
            advanceTimeBy(301)
            store.failNext()
            runCurrent()
            assertEquals(NeedsAttention, saver.status.value)

            // Later edits keep trying, but the status stays NeedsAttention while every write fails.
            saver.edit(draft("c"))
            assertEquals(NeedsAttention, saver.status.value)
            advanceTimeBy(301)
            assertEquals(NeedsAttention, saver.status.value)
            store.failNext()
            runCurrent()

            assertEquals(2, store.writes.size)
            assertEquals(listOf(Saved, Saving, NeedsAttention), seen)
        }

    @Test
    fun newerSuccessfulWriteClearsNeedsAttention() =
        runTest {
            val store = FakeStore()
            val saver = autosaver(store)

            saver.edit(draft("b"))
            advanceTimeBy(301)
            store.failNext()
            runCurrent()
            saver.edit(draft("c"))
            advanceTimeBy(301)
            store.completeNext()
            runCurrent()

            assertEquals(Saved, saver.status.value)
        }

    @Test
    fun retryWritesTheNewestContentAgain() =
        runTest {
            val store = FakeStore()
            val saver = autosaver(store)

            saver.edit(draft("b"))
            saver.flush()
            store.failNext()
            runCurrent()
            saver.retry()

            assertEquals(listOf(draft("b"), draft("b")), store.writtenDrafts)
            store.completeNext()
            runCurrent()
            assertEquals(Saved, saver.status.value)

            saver.retry()
            assertEquals(2, store.writes.size, "Nothing to retry once the newest content is stored")
        }

    @Test
    fun unexpectedErrorCountsAsAFailure() =
        runTest {
            val store = FakeStore()
            val saver = autosaver(store)

            saver.edit(draft("b"))
            saver.flush()
            store.writes
                .single()
                .result
                .completeExceptionally(IllegalStateException("synthetic platform error"))
            runCurrent()

            assertEquals(NeedsAttention, saver.status.value)
        }

    @Test
    fun contentEqualToTheNewestVersionIsNotAnEdit() =
        runTest {
            val store = FakeStore()
            val saver = autosaver(store, initial = draft("a"))

            saver.edit(draft("a"))
            advanceUntilIdle()

            assertEquals(Saved, saver.status.value)
            assertTrue(store.writes.isEmpty())
        }

    @Test
    fun titleChangeIsAnEdit() =
        runTest {
            val store = FakeStore()
            val saver = autosaver(store)

            saver.edit(draft("a", title = "New title"))
            saver.flush()

            assertEquals(listOf(draft("a", title = "New title")), store.writtenDrafts)
            assertEquals(draft("a", title = "New title"), saver.current)
        }

    @Test
    fun refusesADraftOfAnotherNote() =
        runTest {
            val saver = autosaver(FakeStore())

            assertFailsWith<IllegalArgumentException> {
                saver.edit(LocalDraft(NoteId("other-note"), "", RichTextDocument.EMPTY))
            }
            assertFalse(saver.status.value == Saving)
        }

    @Test
    fun rebaseWhenNothingWaitsWritesTheBaseWithoutAnEdit() =
        runTest {
            val store = FakeStore()
            val saver = autosaver(store)

            saver.rebase(RevisionId("synthetic-revision"))

            assertEquals(listOf(draft("a").copy(baseRevisionId = RevisionId("synthetic-revision"))), store.writtenDrafts)
            assertEquals(Saved, saver.status.value, "The content did not change")
            store.failNext()
            runCurrent()
            assertEquals(Saved, saver.status.value, "The content was stored before")
        }

    @Test
    fun rebaseWhileAWriteWaitsGoesWithThatWrite() =
        runTest {
            val store = FakeStore()
            val saver = autosaver(store)

            saver.edit(draft("b"))
            saver.rebase(RevisionId("synthetic-revision"))
            assertTrue(store.writes.isEmpty())
            advanceTimeBy(301)

            assertEquals(listOf(draft("b").copy(baseRevisionId = RevisionId("synthetic-revision"))), store.writtenDrafts)
        }

    @Test
    fun successfulRebaseWriteAfterAFailureStoresTheContent() =
        runTest {
            val store = FakeStore()
            val saver = autosaver(store)
            saver.edit(draft("b"))
            saver.flush()
            store.failNext()
            runCurrent()
            assertEquals(NeedsAttention, saver.status.value)

            saver.rebase(RevisionId("synthetic-revision"))
            store.completeNext()
            runCurrent()

            assertEquals(Saved, saver.status.value)
        }

    /** Writes wait until the test completes or fails them, like a real asynchronous store. */
    private class FakeStore : LocalDraftStore {
        class PendingWrite(
            val draft: LocalDraft,
        ) {
            val result = CompletableDeferred<Unit>()
        }

        val writes = mutableListOf<PendingWrite>()
        val writtenDrafts: List<LocalDraft> get() = writes.map { it.draft }

        fun completeNext() {
            writes.first { it.result.isActive }.result.complete(Unit)
        }

        fun failNext() {
            writes.first { it.result.isActive }.result.completeExceptionally(LocalDraftStoreException("synthetic failure"))
        }

        override suspend fun read(noteId: NoteId): LocalDraft? = null

        override suspend fun write(draft: LocalDraft) {
            val pending = PendingWrite(draft)
            writes += pending
            pending.result.await()
        }
    }
}
