package thread.domain.note

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** All content below is synthetic test data. */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteSyncerTest {
    private val noteId = NoteId("synthetic-note-1")
    private val thisDevice = DeviceId("synthetic-device-this")
    private val otherDevice = DeviceId("synthetic-device-other")

    private fun doc(text: String) = RichTextDocument(listOf(RichTextBlock.Paragraph(listOf(RichTextInline.Text(text)))))

    private fun draft(
        text: String,
        base: String? = null,
    ) = LocalDraft(noteId, TITLE, doc(text), base?.let(::RevisionId))

    private fun revision(
        id: String,
        parent: String?,
        text: String,
        device: DeviceId = otherDevice,
        at: Long = 0,
        acknowledged: Boolean = true,
        discarded: Boolean = false,
    ) = NoteRevision(RevisionId(id), parent?.let(::RevisionId), TITLE, doc(text), device, at, acknowledged, discarded)

    private inner class Harness(
        scope: TestScope,
        initial: LocalDraft,
        hasLocalWork: Boolean = true,
    ) {
        val remote = FakeRemote()
        val drafts = DraftAutosaver(InstantStore(), scope.backgroundScope, initial, initialIsStored = true)
        val replaced = mutableListOf<LocalDraft>()
        private var nextId = 0
        val syncer =
            NoteSyncer(
                drafts = drafts,
                remote = remote,
                deviceId = thisDevice,
                scope = scope.backgroundScope,
                hasLocalWork = hasLocalWork,
                newRevisionId = { RevisionId("rev-${++nextId}") },
                onContentReplaced = { replaced += it },
                pushDebounce = 2.seconds,
                pushMaxWait = 10.seconds,
            )

        val state: NoteSyncState get() = syncer.state.value

        fun edit(text: String) {
            drafts.edit(drafts.current.copy(body = doc(text)))
            syncer.localEdited()
        }
    }

    /** A harness whose local content "a" is synced as server revision r1. */
    private fun TestScope.synced(): Harness {
        val h = Harness(this, draft("a", base = "r1"))
        h.remote.add(revision("r1", null, "a"))
        h.syncer.start()
        runCurrent()
        assertTrue(h.state.synced)
        return h
    }

    @Test
    fun firstSyncWritesTheNoteAsItsFirstVersion() =
        runTest {
            val h = Harness(this, draft("a"))
            h.remote.publish()

            h.syncer.start()
            runCurrent()

            assertEquals(listOf(NewRevision(RevisionId("rev-1"), null, TITLE, doc("a"), thisDevice)), h.remote.pushed)
            assertTrue(h.state.pushing)
            assertFalse(h.state.synced, "Not synced before the server acknowledged it")

            h.remote.acknowledgeNext()
            runCurrent()
            assertTrue(h.state.synced)
            assertFalse(h.state.pushing)
            assertEquals(RevisionId("rev-1"), h.drafts.current.baseRevisionId)
        }

    @Test
    fun contentThatIsNotThePersonsWaitsUntilTheServerConfirmsANewNote() =
        runTest {
            val h = Harness(this, draft("synthetic seed"), hasLocalWork = false)
            h.remote.fromServer = false
            h.remote.publish()

            h.syncer.start()
            runCurrent()
            assertTrue(h.remote.pushed.isEmpty(), "The device's local copy alone does not prove the Note is new")
            assertTrue(h.state.loaded)

            h.remote.fromServer = true
            h.remote.publish()
            runCurrent()
            assertEquals(1, h.remote.pushed.size)
        }

    @Test
    fun contentThatIsNotThePersonsIsReplacedByTheServerVersion() =
        runTest {
            val h = Harness(this, draft("synthetic seed"), hasLocalWork = false)
            h.remote.add(revision("r1", null, "server text"))

            h.syncer.start()
            runCurrent()

            assertEquals(draft("server text", base = "r1"), h.drafts.current)
            assertEquals(listOf(draft("server text", base = "r1")), h.replaced)
            assertTrue(h.remote.pushed.isEmpty())
            assertTrue(h.state.synced)
        }

    @Test
    fun editsArePushedAfterAPauseAndSyncedOnlyAfterTheServerAcknowledges() =
        runTest {
            val h = synced()

            h.edit("b")
            assertFalse(h.state.synced)
            advanceTimeBy(1_999)
            assertTrue(h.remote.pushed.isEmpty())
            advanceTimeBy(2)
            runCurrent()

            assertEquals(listOf(NewRevision(RevisionId("rev-1"), RevisionId("r1"), TITLE, doc("b"), thisDevice)), h.remote.pushed)
            assertTrue(h.state.pushing)
            assertFalse(h.state.synced)
            assertEquals(RevisionId("r1"), h.drafts.current.baseRevisionId, "The base moves only after the acknowledgement")

            h.remote.acknowledgeNext()
            runCurrent()
            assertTrue(h.state.synced)
            assertEquals(RevisionId("rev-1"), h.drafts.current.baseRevisionId)
        }

    @Test
    fun continuousEditingStillPushesAfterMaxWait() =
        runTest {
            val h = synced()

            repeat(7) { i ->
                h.edit("t$i")
                advanceTimeBy(1_500)
            }
            runCurrent()

            assertEquals(listOf(doc("t6")), h.remote.pushed.map { it.body })
        }

    @Test
    fun onlyOneWriteWaitsForTheServerAtATime() =
        runTest {
            val h = synced()
            h.edit("b")
            h.syncer.flush()
            runCurrent()

            h.edit("c")
            advanceTimeBy(10_001)
            assertEquals(1, h.remote.pushed.size)

            h.remote.acknowledgeNext()
            runCurrent()
            assertEquals(2, h.remote.pushed.size)
            assertEquals(NewRevision(RevisionId("rev-2"), RevisionId("rev-1"), TITLE, doc("c"), thisDevice), h.remote.pushed[1])
        }

    @Test
    fun newerVersionFromAnotherDeviceReplacesUnchangedContent() =
        runTest {
            val h = synced()

            h.remote.add(revision("r2", "r1", "from the other device", at = 5))
            runCurrent()

            assertEquals(draft("from the other device", base = "r2"), h.drafts.current)
            assertEquals(listOf(draft("from the other device", base = "r2")), h.replaced)
            assertTrue(h.remote.pushed.isEmpty())
            assertNull(h.state.conflict)
            assertTrue(h.state.synced)
        }

    @Test
    fun editsOnTwoDevicesBecomeAConflictInsteadOfAnOverwrite() =
        runTest {
            val h = synced()

            h.edit("this device")
            h.remote.add(revision("r2", "r1", "other device", at = 5))
            runCurrent()

            assertTrue(h.replaced.isEmpty(), "Local edits are never replaced")
            assertEquals(doc("this device"), h.drafts.current.body)
            assertEquals(listOf(NewRevision(RevisionId("rev-1"), RevisionId("r1"), TITLE, doc("this device"), thisDevice)), h.remote.pushed)
            assertEquals(RevisionId("r2"), h.state.conflict?.id)
        }

    @Test
    fun keepThisDeviceDiscardsTheOtherVersionAndCopiesItToTrash() =
        runTest {
            val h = conflicted()

            val copyId = h.syncer.resolveConflict(ConflictChoice.KeepThisDevice)
            runCurrent()

            assertEquals(NoteId("conflict-r2"), copyId)
            val resolution = h.remote.resolutions.single()
            assertEquals(NoteHead(RevisionId("rev-1"), TITLE, doc("this device")), resolution.keep)
            assertEquals(listOf(RevisionId("r2")), resolution.discard)
            val expectedFirst = NewRevision(RevisionId("r2"), null, TITLE, doc("other device"), otherDevice)
            assertEquals(NoteCopy(NoteId("conflict-r2"), expectedFirst, true, RevisionId("r2")), resolution.copy)
            assertEquals(doc("this device"), h.drafts.current.body)
            assertNull(h.state.conflict)

            h.remote.acknowledgeNext()
            runCurrent()
            assertTrue(h.state.synced)
        }

    @Test
    fun keepOtherCopiesThisDevicesContentToTrashAndShowsTheOtherVersion() =
        runTest {
            val h = conflicted()

            val copyId = h.syncer.resolveConflict(ConflictChoice.KeepOther)
            runCurrent()

            assertEquals(NoteId("conflict-rev-2"), copyId)
            val resolution = h.remote.resolutions.single()
            assertEquals(NoteHead(RevisionId("r2"), TITLE, doc("other device")), resolution.keep)
            assertEquals(listOf(RevisionId("rev-1")), resolution.discard)
            val expectedCopy = NoteCopy(NoteId("conflict-rev-2"), newRevision("rev-3", "this device"), true, RevisionId("rev-1"))
            assertEquals(expectedCopy, resolution.copy)
            assertEquals(draft("other device", base = "r2"), h.drafts.current)
            assertNull(h.state.conflict)
            assertTrue(h.state.synced)

            h.remote.acknowledgeNext()
            runCurrent()
            assertEquals(1, h.remote.pushed.size, "The discarded write is not written again")
            assertEquals(draft("other device", base = "r2"), h.drafts.current)
        }

    @Test
    fun keepBothSavesTheOtherVersionAsASecondNote() =
        runTest {
            val h = conflicted()

            val copyId = h.syncer.resolveConflict(ConflictChoice.KeepBoth)
            runCurrent()

            assertEquals(NoteId("conflict-r2"), copyId)
            val copy =
                h.remote.resolutions
                    .single()
                    .copy
            assertEquals("$TITLE (other device copy)", copy.firstRevision.title)
            assertEquals(doc("other device"), copy.firstRevision.body)
            assertFalse(copy.inTrash)
            assertEquals(doc("this device"), h.drafts.current.body)
        }

    @Test
    fun deviceTakesTheOtherVersionWhenAnotherDeviceDiscardedItsOwn() =
        runTest {
            val h = Harness(this, draft("this device", base = "m1"))
            h.remote.add(revision("r0", null, "a"))
            h.remote.add(revision("m1", "r0", "this device", device = thisDevice, at = 4, discarded = true))
            h.remote.add(revision("r2", "r0", "other device", at = 5))

            h.syncer.start()
            runCurrent()

            assertEquals(draft("other device", base = "r2"), h.drafts.current)
            assertTrue(h.remote.pushed.isEmpty())
            assertNull(h.state.conflict)
        }

    @Test
    fun devicesThatDiscardedEachOthersVersionWriteTheirContentAgain() =
        runTest {
            val h = Harness(this, draft("this device", base = "m1"))
            h.remote.add(revision("r0", null, "a"))
            h.remote.add(revision("m1", "r0", "this device", device = thisDevice, at = 4, discarded = true))
            h.remote.add(revision("r2", "r0", "other device", at = 5, discarded = true))

            h.syncer.start()
            runCurrent()

            assertEquals(listOf(NewRevision(RevisionId("rev-1"), RevisionId("m1"), TITLE, doc("this device"), thisDevice)), h.remote.pushed)
            assertFalse(h.state.synced)
        }

    @Test
    fun refusedWriteKeepsTheTextAndWaitsForRetry() =
        runTest {
            val h = synced()
            h.edit("b")
            h.syncer.flush()
            runCurrent()

            h.remote.rejectNext()
            runCurrent()
            assertTrue(h.state.rejected)
            assertFalse(h.state.pushing)
            assertEquals(doc("b"), h.drafts.current.body)

            h.edit("c")
            advanceTimeBy(10_001)
            assertEquals(1, h.remote.pushed.size, "Nothing is written again before retry")

            h.syncer.retry()
            runCurrent()
            assertFalse(h.state.rejected)
            assertEquals(NewRevision(RevisionId("rev-2"), RevisionId("r1"), TITLE, doc("c"), thisDevice), h.remote.pushed[1])
        }

    @Test
    fun writeStillWaitingAfterAReloadIsNotWrittenAgain() =
        runTest {
            val h = Harness(this, draft("b", base = "r1"))
            h.remote.add(revision("r1", null, "a"))
            h.remote.add(revision("rev-old", "r1", "b", device = thisDevice, at = 5, acknowledged = false))

            h.syncer.start()
            runCurrent()
            assertTrue(h.remote.pushed.isEmpty())
            assertTrue(h.state.pushing)

            h.remote.acknowledge(RevisionId("rev-old"))
            runCurrent()
            assertTrue(h.state.synced)
            assertEquals(RevisionId("rev-old"), h.drafts.current.baseRevisionId)
        }

    @Test
    fun unreadableVersionsPauseWriting() =
        runTest {
            val h = synced()
            h.remote.unreadable = 1
            h.remote.publish()
            runCurrent()

            h.edit("b")
            h.syncer.flush()
            runCurrent()

            assertTrue(h.state.unreadable)
            assertTrue(h.remote.pushed.isEmpty())
        }

    @Test
    fun resolveWithoutAConflictDoesNothing() =
        runTest {
            val h = synced()

            assertNull(h.syncer.resolveConflict(ConflictChoice.KeepOther))
            assertTrue(h.remote.resolutions.isEmpty())
        }

    @Test
    fun trashKeepsTheContent() =
        runTest {
            val h = synced()

            h.syncer.setInTrash(true)
            runCurrent()
            assertTrue(h.state.inTrash)
            assertEquals(draft("a", base = "r1"), h.drafts.current)

            h.syncer.setInTrash(false)
            runCurrent()
            assertFalse(h.state.inTrash)
        }

    private fun newRevision(
        id: String,
        text: String,
    ) = NewRevision(RevisionId(id), null, TITLE, doc(text), thisDevice)

    /** A harness in conflict: this device wrote rev-1 ("this device"), the other device wrote r2, both from r1. */
    private fun TestScope.conflicted(): Harness {
        val h = synced()
        h.edit("this device")
        h.remote.add(revision("r2", "r1", "other device", at = 5))
        runCurrent()
        assertEquals(RevisionId("r2"), h.state.conflict?.id)
        return h
    }

    /** Acts like the device's local copy of Firestore: writes show at once, unacknowledged, until the server answers. */
    private inner class FakeRemote : NoteSyncRemote {
        private val revisions = mutableListOf<NoteRevision>()
        private val waiting = mutableListOf<Pair<RevisionId, CompletableDeferred<Unit>>>()
        private val snapshots = MutableStateFlow<RemoteNote?>(null)
        private var inTrash = false
        private var clock = 1_000L

        var fromServer = true
        var unreadable = 0
        val pushed = mutableListOf<NewRevision>()
        val resolutions = mutableListOf<Resolution>()

        fun publish() {
            snapshots.value = RemoteNote(revisions.isNotEmpty(), inTrash, RevisionHistory(revisions.toList()), fromServer, unreadable)
        }

        fun add(revision: NoteRevision) {
            revisions += revision
            publish()
        }

        fun acknowledge(id: RevisionId) {
            update(id) { it.copy(acknowledged = true) }
            publish()
        }

        fun acknowledgeNext() {
            val (id, result) = waiting.removeAt(0)
            acknowledge(id)
            result.complete(Unit)
        }

        fun rejectNext() {
            val (id, result) = waiting.removeAt(0)
            revisions.removeAll { it.id == id }
            publish()
            result.completeExceptionally(NoteSyncRejectedException("synthetic rejection"))
        }

        private fun update(
            id: RevisionId,
            change: (NoteRevision) -> NoteRevision,
        ) {
            val index = revisions.indexOfFirst { it.id == id }
            revisions[index] = change(revisions[index])
        }

        override fun observe(noteId: NoteId): Flow<RemoteNote> = snapshots.filterNotNull()

        override suspend fun push(
            noteId: NoteId,
            revision: NewRevision,
        ) {
            pushed += revision
            revisions +=
                NoteRevision(
                    revision.id,
                    revision.parentId,
                    revision.title,
                    revision.body,
                    revision.deviceId,
                    editedAtMillis = clock++,
                    acknowledged = false,
                    discarded = false,
                )
            val result = CompletableDeferred<Unit>()
            waiting += revision.id to result
            publish()
            result.await()
        }

        override suspend fun resolveConflict(
            noteId: NoteId,
            keep: NoteHead?,
            discard: List<RevisionId>,
            copy: NoteCopy,
        ) {
            resolutions += Resolution(keep, discard, copy)
            discard.forEach { id -> update(id) { it.copy(discarded = true) } }
            publish()
        }

        override suspend fun setInTrash(
            noteId: NoteId,
            inTrash: Boolean,
        ) {
            this.inTrash = inTrash
            publish()
        }

        override suspend fun deleteVersions(noteId: NoteId): Int = 0
    }

    private data class Resolution(
        val keep: NoteHead?,
        val discard: List<RevisionId>,
        val copy: NoteCopy,
    )

    private class InstantStore : LocalDraftStore {
        override suspend fun read(noteId: NoteId): LocalDraft? = null

        override suspend fun write(draft: LocalDraft) = Unit

        override suspend fun delete(noteId: NoteId) = Unit
    }

    private companion object {
        const val TITLE = "Synthetic title"
    }
}
