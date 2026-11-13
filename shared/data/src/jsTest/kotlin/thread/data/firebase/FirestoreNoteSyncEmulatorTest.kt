package thread.data.firebase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import thread.data.local.randomRevisionId
import thread.domain.note.ConflictChoice
import thread.domain.note.DeviceId
import thread.domain.note.DraftAutosaver
import thread.domain.note.LocalDraft
import thread.domain.note.NewRevision
import thread.domain.note.NoteCopy
import thread.domain.note.NoteHead
import thread.domain.note.NoteId
import thread.domain.note.NoteSyncRejectedException
import thread.domain.note.NoteSyncer
import thread.domain.note.RevisionId
import thread.domain.note.RichTextDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** [FirestoreNoteSyncRemote] and [NoteSyncer] against the emulators. All accounts and content are synthetic. */
class FirestoreNoteSyncEmulatorTest {
    private val noteId = NoteId("synthetic-note-1")
    private val deviceX = DeviceId("synthetic-device-x")
    private val deviceY = DeviceId("synthetic-device-y")

    private fun newRevision(
        id: String,
        parent: String?,
        text: String,
        device: DeviceId = deviceX,
    ) = NewRevision(RevisionId(id), parent?.let(::RevisionId), "Synthetic title", syntheticDoc(text), device)

    @Test
    fun pushIsAcknowledgedOnlyByTheServer() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            val remote = FirestoreNoteSyncRemote(device.backend, device.accountId)
            try {
                device.backend.goOfflineForTesting()
                val push = launch { remote.push(noteId, newRevision("rev-1", null, "a")) }

                val pending = remote.observe(noteId).first { it.history[RevisionId("rev-1")] != null }
                assertFalse(pending.history[RevisionId("rev-1")]!!.acknowledged, "Offline, the write is only local")
                assertFalse(pending.fromServer)
                assertTrue(push.isActive)

                device.backend.goOnlineForTesting()
                push.join()
                val acknowledged = remote.observe(noteId).first { it.fromServer && it.history[RevisionId("rev-1")]?.acknowledged == true }
                assertEquals(listOf(RevisionId("rev-1")), acknowledged.history.leaves.map { it.id })
                assertEquals(
                    syntheticDoc("a"),
                    acknowledged.history.leaves
                        .single()
                        .body,
                )
                assertNotNull(
                    acknowledged.history.leaves
                        .single()
                        .editedAtMillis,
                )
            } finally {
                device.backend.close()
            }
        }

    @Test
    fun refusedWriteFailsAndDisappearsFromTheLocalCopy() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            val remote = FirestoreNoteSyncRemote(device.backend, device.accountId, rejectWritesForTesting = { true })
            try {
                val error =
                    try {
                        remote.push(noteId, newRevision("rev-1", null, "refused"))
                        null
                    } catch (e: NoteSyncRejectedException) {
                        e
                    }
                assertNotNull(error, "The rules refuse the write")
                assertTrue("permission-denied" in error.message.orEmpty(), error.message)

                val note = remote.observe(noteId).first { it.fromServer }
                assertTrue(note.history.revisions.isEmpty())
            } finally {
                device.backend.close()
            }
        }

    @Test
    fun anotherAccountCannotListenOrWrite() =
        emulatorTest {
            val owner = EmulatedDevice.signIn(syntheticSubject())
            val stranger = EmulatedDevice.signIn(syntheticSubject())
            try {
                FirestoreNoteSyncRemote(owner.backend, owner.accountId).push(noteId, newRevision("rev-1", null, "private"))
                val intruder = FirestoreNoteSyncRemote(stranger.backend, owner.accountId)

                val listenError =
                    try {
                        intruder.observe(noteId).collect { }
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        e
                    }
                assertIs<NoteSyncRejectedException>(listenError)
                val writeError =
                    try {
                        intruder.push(noteId, newRevision("rev-2", "rev-1", "intruder"))
                        null
                    } catch (e: NoteSyncRejectedException) {
                        e
                    }
                assertNotNull(writeError)
            } finally {
                owner.backend.close()
                stranger.backend.close()
            }
        }

    @Test
    fun resolvingAConflictDiscardsAndCopiesInOneWrite() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            val remote = FirestoreNoteSyncRemote(device.backend, device.accountId)
            try {
                remote.push(noteId, newRevision("r1", null, "start"))
                remote.push(noteId, newRevision("r2", "r1", "x"))
                remote.push(noteId, newRevision("r3", "r1", "y", device = deviceY))
                assertEquals(
                    2,
                    remote
                        .observe(noteId)
                        .first { it.fromServer }
                        .history.leaves.size,
                )

                val copy = NoteCopy(NoteId("conflict-r3"), newRevision("r3", null, "y", device = deviceY), true, RevisionId("r3"))
                remote.resolveConflict(
                    noteId,
                    keep = NoteHead(RevisionId("r2"), "Synthetic title", syntheticDoc("x")),
                    discard = listOf(RevisionId("r3")),
                    copy = copy,
                )

                val after = remote.observe(noteId).first { it.fromServer && it.history[RevisionId("r3")]?.discarded == true }
                assertEquals(listOf(RevisionId("r2")), after.history.leaves.map { it.id })
                val copied = remote.observe(copy.id).first { it.fromServer && it.exists }
                assertTrue(copied.inTrash)
                assertEquals(
                    syntheticDoc("y"),
                    copied.history.leaves
                        .single()
                        .body,
                )

                // A second device that resolves the same way writes the same copy again.
                remote.resolveConflict(
                    noteId,
                    keep = NoteHead(RevisionId("r2"), "Synthetic title", syntheticDoc("x")),
                    discard = listOf(RevisionId("r3")),
                    copy = copy,
                )
            } finally {
                device.backend.close()
            }
        }

    @Test
    fun aLaterEditDoesNotTakeANoteOutOfTrash() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            val remote = FirestoreNoteSyncRemote(device.backend, device.accountId)
            try {
                remote.push(noteId, newRevision("r1", null, "start"))
                remote.setInTrash(noteId, true)
                remote.push(noteId, newRevision("r2", "r1", "edited elsewhere"))

                val note = remote.observe(noteId).first { it.fromServer && it.history[RevisionId("r2")]?.acknowledged == true }
                assertTrue(note.inTrash)

                remote.setInTrash(noteId, false)
                assertFalse(remote.observe(noteId).first { it.fromServer && !it.inTrash }.inTrash)
            } finally {
                device.backend.close()
            }
        }

    @Test
    fun twoDevicesEditingOfflineGetAConflictAndResolvingItLosesNoText() =
        emulatorTest {
            val subject = syntheticSubject()
            val a = EmulatedDevice.signIn(subject)
            val b = EmulatedDevice.signIn(subject)
            assertEquals(a.accountId, b.accountId)
            val scopeA = CoroutineScope(Dispatchers.Default + Job())
            val scopeB = CoroutineScope(Dispatchers.Default + Job())
            try {
                val draftsA = DraftAutosaver(MemoryDraftStore(), scopeA, LocalDraft(noteId, "Synthetic title", syntheticDoc("start")), true)
                val syncA = syncer(draftsA, a, DeviceId("synthetic-device-a"), scopeA, hasLocalWork = true)
                syncA.start()
                syncA.state.first { it.synced }

                val draftsB = DraftAutosaver(MemoryDraftStore(), scopeB, LocalDraft(noteId, "", RichTextDocument.EMPTY), true)
                val syncB = syncer(draftsB, b, DeviceId("synthetic-device-b"), scopeB, hasLocalWork = false)
                syncB.start()
                syncB.state.first { it.synced }
                assertEquals(syntheticDoc("start"), draftsB.current.body, "B shows the server version")

                a.backend.goOfflineForTesting()
                b.backend.goOfflineForTesting()
                draftsA.edit(draftsA.current.copy(body = syntheticDoc("edited on A")))
                syncA.localEdited()
                syncA.flush()
                draftsB.edit(draftsB.current.copy(body = syntheticDoc("edited on B")))
                syncB.localEdited()
                syncB.flush()
                syncA.state.first { it.pushing }
                syncB.state.first { it.pushing }
                a.backend.goOnlineForTesting()
                b.backend.goOnlineForTesting()

                assertEquals(
                    syntheticDoc("edited on B"),
                    syncA.state
                        .first { it.conflict != null }
                        .conflict
                        ?.body,
                )
                assertEquals(
                    syntheticDoc("edited on A"),
                    syncB.state
                        .first { it.conflict != null }
                        .conflict
                        ?.body,
                )
                assertEquals(syntheticDoc("edited on A"), draftsA.current.body, "A keeps its text while in conflict")
                assertEquals(syntheticDoc("edited on B"), draftsB.current.body, "B keeps its text while in conflict")

                syncA.state.first { it.conflict != null && !it.pushing }
                val copyId = assertNotNull(syncA.resolveConflict(ConflictChoice.KeepThisDevice))

                syncB.state.first { it.conflict == null && it.synced && draftsB.current.body == syntheticDoc("edited on A") }
                val copy = FirestoreNoteSyncRemote(a.backend, a.accountId).observe(copyId).first { it.fromServer && it.exists }
                assertTrue(copy.inTrash, "The version not kept is in Trash")
                assertEquals(
                    syntheticDoc("edited on B"),
                    copy.history.leaves
                        .single()
                        .body,
                )
            } finally {
                scopeA.cancel()
                scopeB.cancel()
                a.backend.close()
                b.backend.close()
            }
        }

    private fun syncer(
        drafts: DraftAutosaver,
        device: EmulatedDevice,
        deviceId: DeviceId,
        scope: CoroutineScope,
        hasLocalWork: Boolean,
    ) = NoteSyncer(
        drafts = drafts,
        remote = FirestoreNoteSyncRemote(device.backend, device.accountId),
        deviceId = deviceId,
        scope = scope,
        hasLocalWork = hasLocalWork,
        newRevisionId = ::randomRevisionId,
        onContentReplaced = {},
        pushDebounce = 100.milliseconds,
        pushMaxWait = 500.milliseconds,
    )
}
