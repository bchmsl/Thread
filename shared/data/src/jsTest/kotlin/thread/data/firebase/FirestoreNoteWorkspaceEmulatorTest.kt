package thread.data.firebase

import kotlinx.coroutines.flow.first
import thread.domain.note.DeviceId
import thread.domain.note.NewRevision
import thread.domain.note.NoteCopy
import thread.domain.note.NoteHead
import thread.domain.note.NoteId
import thread.domain.note.RevisionId
import thread.domain.note.linkedTopicIds
import thread.domain.note.newNote
import thread.domain.topic.TopicId
import thread.domain.workspace.EditResult
import thread.domain.workspace.createNoteWrites
import thread.domain.workspace.linkNote
import thread.domain.workspace.seedWrites
import thread.domain.workspace.triageInbox
import thread.domain.workspace.unlinkNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Notes listing and Topic links through [FirestoreWorkspaceStore], next to [FirestoreNoteSyncRemote] on the same
 * documents (adr/ADR-011-notes-in-the-workspace.md). All accounts and content are synthetic.
 */
class FirestoreNoteWorkspaceEmulatorTest {
    private val noteId = NoteId("n-synthetic-1")
    private val topicId = TopicId("t-synthetic-1")
    private val device = DeviceId("synthetic-device")

    private fun revision(
        id: String,
        parent: String?,
        title: String,
        text: String,
    ) = NewRevision(RevisionId(id), parent?.let(::RevisionId), title, syntheticDoc(text), device)

    @Test
    fun theFilingAndTheContentOfANoteMergeIntoOneListing() =
        emulatorTest {
            val emulated = EmulatedDevice.signIn(syntheticSubject())
            val store = FirestoreWorkspaceStore(emulated.backend, emulated.accountId)
            val remote = FirestoreNoteSyncRemote(emulated.backend, emulated.accountId)
            try {
                val made = newNote(noteId, null)
                store.write(seedWrites() + createNoteWrites(made, null))
                remote.push(noteId, revision("rev-1", null, "Synthetic listing", "First synthetic line"))

                val listed =
                    store.observe().first {
                        !it.pendingWrites &&
                            it.notes
                                .singleOrNull()
                                ?.title
                                ?.isNotEmpty() == true
                    }
                val note = listed.notes.single()
                assertEquals("Synthetic listing", note.title)
                assertEquals("First synthetic line", note.preview)
                assertTrue(note.inbox, "D39: made outside a Topic, the Note waits in Inbox")
                assertNotNull(note.createdAtMillis)

                // Filing it writes only the filing fields; the title and preview stay.
                val edit = assertIs<EditResult.Changed>(triageInbox(emptyList(), listed.notes, listed.noteLinks, topicId)).edit
                store.write(edit.writes)
                val filed = store.observe().first { !it.pendingWrites && it.noteLinks.isNotEmpty() && !it.notes.single().inbox }
                assertEquals(listOf(topicId), linkedTopicIds(noteId, filed.noteLinks))
                assertEquals("Synthetic listing", filed.notes.single().title)
                assertEquals(note.createdAtMillis, filed.notes.single().createdAtMillis)

                // A later push keeps the filing.
                remote.push(noteId, revision("rev-2", "rev-1", "Synthetic renamed", "Second synthetic line"))
                val renamed = store.observe().first { !it.pendingWrites && it.notes.single().title == "Synthetic renamed" }
                assertFalse(renamed.notes.single().inbox)
                assertEquals("Second synthetic line", renamed.notes.single().preview)
                assertTrue(store.refused.value.isEmpty())
            } finally {
                emulated.backend.close()
            }
        }

    @Test
    fun twoDevicesLinkingTheSamePairWriteOneDocumentAndUnlinkingKeepsTheNote() =
        emulatorTest {
            val subject = syntheticSubject()
            val deviceA = EmulatedDevice.signIn(subject)
            val deviceB = EmulatedDevice.signIn(subject)
            val storeA = FirestoreWorkspaceStore(deviceA.backend, deviceA.accountId)
            val storeB = FirestoreWorkspaceStore(deviceB.backend, deviceB.accountId)
            try {
                storeA.write(seedWrites() + createNoteWrites(newNote(noteId, null), null))
                storeA.observe().first { it.notes.isNotEmpty() && !it.pendingWrites }
                val onB = storeB.observe().first { it.notes.isNotEmpty() }

                deviceA.backend.goOfflineForTesting()
                val onA = storeA.observe().first { it.notes.isNotEmpty() }
                storeA.write(assertIs<EditResult.Changed>(linkNote(onA.notes.single(), onA.noteLinks, topicId)).edit.writes)
                storeB.write(assertIs<EditResult.Changed>(linkNote(onB.notes.single(), onB.noteLinks, topicId)).edit.writes)
                storeB.observe().first { !it.pendingWrites && it.noteLinks.isNotEmpty() }
                deviceA.backend.goOnlineForTesting()
                val linked = storeA.observe().first { !it.pendingWrites && it.noteLinks.isNotEmpty() }
                assertEquals(1, linked.noteLinks.size, "The link id is made from the pair, so both devices wrote one document")

                storeA.write(assertIs<EditResult.Changed>(unlinkNote(linked.notes.single(), linked.noteLinks, topicId)).edit.writes)
                val unlinked = storeB.observe().first { it.noteLinks.singleOrNull()?.linked == false }
                assertEquals(1, unlinked.notes.size, "Unlinking never deletes the Note")
                assertEquals(emptyList(), linkedTopicIds(noteId, unlinked.noteLinks))
                assertTrue(storeA.refused.value.isEmpty() && storeB.refused.value.isEmpty())
            } finally {
                deviceA.backend.close()
                deviceB.backend.close()
            }
        }

    @Test
    fun resolvingAConflictMovesTheListingToTheKeptVersion() =
        emulatorTest {
            val emulated = EmulatedDevice.signIn(syntheticSubject())
            val store = FirestoreWorkspaceStore(emulated.backend, emulated.accountId)
            val remote = FirestoreNoteSyncRemote(emulated.backend, emulated.accountId)
            try {
                remote.push(noteId, revision("r1", null, "Synthetic start", "start"))
                remote.push(noteId, revision("r2", "r1", "Synthetic kept", "kept text"))
                remote.push(noteId, revision("r3", "r1", "Synthetic other", "other text"))
                store.write(seedWrites())
                assertEquals(
                    "Synthetic other",
                    store
                        .observe()
                        .first { !it.pendingWrites && it.notes.isNotEmpty() }
                        .notes
                        .single()
                        .title,
                )

                val copy = NoteCopy(NoteId("conflict-r3"), revision("r3", null, "Synthetic other", "other text"), true, RevisionId("r3"))
                val kept = NoteHead(RevisionId("r2"), "Synthetic kept", syntheticDoc("kept text"))
                remote.resolveConflict(noteId, keep = kept, discard = listOf(RevisionId("r3")), copy = copy)

                val after = store.observe().first { !it.pendingWrites && it.notes.size == 2 }
                val listing = after.notes.first { it.id == noteId }
                assertEquals("Synthetic kept", listing.title, "The listing follows the kept version")
                assertEquals("kept text", listing.preview)
                val copied = after.notes.first { it.id == copy.id }
                assertEquals("Synthetic other", copied.title)
                assertTrue(copied.inTrash)
            } finally {
                emulated.backend.close()
            }
        }
}
