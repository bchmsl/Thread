package thread.data.firebase

import kotlinx.coroutines.await
import kotlinx.coroutines.flow.first
import thread.domain.note.DeviceId
import thread.domain.note.NewRevision
import thread.domain.note.NoteId
import thread.domain.note.NoteSyncRejectedException
import thread.domain.note.RevisionId
import thread.domain.note.newNote
import thread.domain.reminder.ReminderId
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.reminder.newReminder
import thread.domain.task.TaskId
import thread.domain.task.newTask
import thread.domain.time.DEFAULT_WORKSPACE_ZONE
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.TopicId
import thread.domain.topic.newTopic
import thread.domain.trash.TrashEntry
import thread.domain.trash.deleteForeverWrites
import thread.domain.trash.restoreTopic
import thread.domain.trash.trashEntries
import thread.domain.trash.trashTopic
import thread.domain.trash.visibleWorkspace
import thread.domain.workspace.EditResult
import thread.domain.workspace.TrashKind
import thread.domain.workspace.WorkspaceData
import thread.domain.workspace.WorkspaceWrite
import thread.domain.workspace.createNoteWrites
import thread.domain.workspace.createReminderWrite
import thread.domain.workspace.createTaskWrite
import thread.domain.workspace.editPickUp
import thread.domain.workspace.seedWrites
import thread.domain.workspace.toggleTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Trash and Delete forever through [FirestoreWorkspaceStore] and [FirestoreNoteSyncRemote] against the emulators
 * (adr/ADR-012-trash-and-search.md). All accounts and content are synthetic.
 */
class FirestoreTrashEmulatorTest {
    private val topicId = TopicId("t-synthetic-trash")
    private val noteId = NoteId("n-synthetic-trash")
    private val at = WallTime(LocalDay(2026, 11, 14), 9 * 60)

    private fun bundleWrites(): List<WorkspaceWrite> {
        val topic = newTopic(topicId, "Synthetic Topic for Trash", SEED_STATUSES, 0)!!
        val task = newTask(TaskId("k-synthetic-trash"), "Synthetic task in the Topic", topicId, null)!!
        val reminder =
            newReminder(
                ReminderId("r-synthetic-trash"),
                at,
                DEFAULT_WORKSPACE_ZONE,
                "Synthetic reminder",
                ReminderTarget(TargetKind.Topic, topicId.value),
            )!!
        return seedWrites() +
            WorkspaceWrite.PutTopic(topic, isNew = true) +
            createTaskWrite(task) +
            createReminderWrite(reminder) +
            createNoteWrites(newNote(noteId, null).copy(inbox = false), topicId)
    }

    private fun WorkspaceData.ready() = !pendingWrites && topics.isNotEmpty() && tasks.isNotEmpty() && reminders.isNotEmpty()

    @Test
    fun aTopicGoesToTrashAndComesBackWithEverythingAlsoWhatAnotherDeviceAddedMeanwhile() =
        emulatorTest {
            val subject = syntheticSubject()
            val deviceA = EmulatedDevice.signIn(subject)
            val deviceB = EmulatedDevice.signIn(subject)
            val storeA = FirestoreWorkspaceStore(deviceA.backend, deviceA.accountId)
            val storeB = FirestoreWorkspaceStore(deviceB.backend, deviceB.accountId)
            try {
                storeA.write(bundleWrites())
                val before = storeA.observe().first { it.ready() && it.noteLinks.isNotEmpty() }
                storeB.observe().first { it.ready() }

                // B is offline and adds a Task to the Topic while A moves the Topic to Trash.
                deviceB.backend.goOfflineForTesting()
                val late = newTask(TaskId("k-synthetic-late"), "Synthetic task added offline", topicId, null)!!
                storeB.write(listOf(createTaskWrite(late)))
                storeA.write(assertIs<EditResult.Changed>(trashTopic(before.topics.single())).edit.writes)
                val trashed = storeA.observe().first { !it.pendingWrites && it.topics.single().deleted }
                assertNotNull(trashed.topics.single().deletedAtMillis)
                deviceB.backend.goOnlineForTesting()
                storeB.observe().first { !it.pendingWrites && it.tasks.size == 2 }

                val withLate = storeA.observe().first { !it.pendingWrites && it.tasks.size == 2 }
                val visible = visibleWorkspace(withLate)
                assertTrue(visible.topics.isEmpty() && visible.tasks.isEmpty() && visible.reminders.isEmpty())
                assertTrue(visible.noteLinks.isEmpty())
                assertEquals(1, visible.notes.size, "Notes stay in Notes")
                val entry = assertIs<TrashEntry.TopicEntry>(trashEntries(withLate).single())
                assertEquals(setOf("k-synthetic-trash", "k-synthetic-late"), entry.tasks.map { it.id.value }.toSet())
                assertEquals(1, entry.reminders.size)
                assertEquals(listOf(noteId), entry.notes.map { it.id })

                storeA.write(assertIs<EditResult.Changed>(restoreTopic(withLate.topics.single(), withLate.statuses)).edit.writes)
                val restored = storeB.observe().first { !it.pendingWrites && it.topics.singleOrNull()?.deleted == false }
                val shown = visibleWorkspace(restored)
                assertEquals(2, shown.tasks.size, "Both Tasks are back, the one added while it was in Trash too")
                assertEquals(1, shown.reminders.size)
                assertEquals(1, shown.noteLinks.size)
                assertTrue(storeA.refused.value.isEmpty() && storeB.refused.value.isEmpty())
            } finally {
                deviceA.backend.close()
                deviceB.backend.close()
            }
        }

    @Test
    fun deleteForeverLeavesNoContentAndAStaleOfflineEditCannotBringItBack() =
        emulatorTest {
            val subject = syntheticSubject()
            val deviceA = EmulatedDevice.signIn(subject)
            val deviceB = EmulatedDevice.signIn(subject)
            val storeA = FirestoreWorkspaceStore(deviceA.backend, deviceA.accountId)
            val storeB = FirestoreWorkspaceStore(deviceB.backend, deviceB.accountId)
            try {
                storeA.write(bundleWrites())
                storeA.observe().first { it.ready() }
                val onB = storeB.observe().first { it.ready() }

                // B goes offline and edits the Topic and its Task.
                deviceB.backend.goOfflineForTesting()
                storeB.write(assertIs<EditResult.Changed>(editPickUp(onB.topics.single(), nextStep = "Synthetic stale step")).edit.writes)
                storeB.write(assertIs<EditResult.Changed>(toggleTask(onB.tasks.single(), LocalDay(2026, 11, 13))).edit.writes)

                // A moves the Topic to Trash, then deletes it forever.
                val onA = storeA.observe().first { it.ready() }
                storeA.write(assertIs<EditResult.Changed>(trashTopic(onA.topics.single())).edit.writes)
                val trashed = storeA.observe().first { !it.pendingWrites && it.topics.single().deleted }
                val entry = trashEntries(trashed).single()
                storeA.write(deleteForeverWrites(entry, trashed))
                val gone = storeA.observe().first { !it.pendingWrites && it.topics.isEmpty() }
                assertTrue(gone.tasks.isEmpty() && gone.reminders.isEmpty(), "The Task and Reminder went with it")
                assertEquals(0, gone.unreadable, "Tombstones are not unreadable documents")
                assertEquals(1, gone.notes.size, "The linked Note is not touched")
                val stored = getDoc(doc(deviceA.backend.firestore, "users", deviceA.accountId.value, "topics", topicId.value)).await()
                val keys = js("Object").keys(stored.data(js("({})"))).unsafeCast<Array<String>>().toSet()
                assertEquals(setOf("purgedAt", "schemaVersion"), keys, "No content stays in the tombstone")

                // B comes back online: the server refuses its stale writes and nothing comes back.
                deviceB.backend.goOnlineForTesting()
                val afterB = storeB.observe().first { !it.pendingWrites && it.topics.isEmpty() && it.tasks.isEmpty() }
                assertEquals(0, afterB.unreadable)
                assertTrue(storeB.refused.value.isNotEmpty(), "B's stale edits were refused")
                val stillGone = storeA.observe().first { !it.pendingWrites }
                assertTrue(stillGone.topics.isEmpty() && stillGone.tasks.isEmpty())
                assertTrue(storeA.refused.value.isEmpty())
            } finally {
                deviceA.backend.close()
                deviceB.backend.close()
            }
        }

    @Test
    fun aNoteDeletedForeverLosesItsVersionsAndRefusesAStalePush() =
        emulatorTest {
            val emulated = EmulatedDevice.signIn(syntheticSubject())
            val store = FirestoreWorkspaceStore(emulated.backend, emulated.accountId)
            val remote = FirestoreNoteSyncRemote(emulated.backend, emulated.accountId)
            val device = DeviceId("synthetic-device")
            try {
                store.write(seedWrites() + createNoteWrites(newNote(noteId, null), null))
                remote.push(noteId, NewRevision(RevisionId("rev-1"), null, "Synthetic note", syntheticDoc("one"), device))
                remote.push(noteId, NewRevision(RevisionId("rev-2"), RevisionId("rev-1"), "Synthetic note", syntheticDoc("two"), device))
                remote.setInTrash(noteId, true)
                val trashed = store.observe().first { !it.pendingWrites && it.notes.singleOrNull()?.inTrash == true }
                val entry = assertIs<TrashEntry.NoteEntry>(trashEntries(trashed).single())
                assertTrue(entry.note.deletedAtMillis != null)

                store.write(deleteForeverWrites(entry, trashed))
                val purged = store.observe().first { !it.pendingWrites && it.notes.isEmpty() }
                assertEquals(listOf(noteId), purged.purgedNotes)
                assertEquals(2, remote.deleteVersions(noteId))
                assertEquals(0, remote.deleteVersions(noteId), "Nothing is left to delete")
                val seen = remote.observe(noteId).first { it.fromServer }
                assertFalse(seen.exists)
                assertTrue(seen.history.revisions.isEmpty())

                assertFailsWith<NoteSyncRejectedException> {
                    remote.push(
                        noteId,
                        NewRevision(RevisionId("rev-3"), RevisionId("rev-2"), "Synthetic stale", syntheticDoc("three"), device),
                    )
                }
                assertFailsWith<NoteSyncRejectedException> { remote.setInTrash(noteId, false) }
                assertTrue(store.refused.value.isEmpty())
                assertEquals(TrashKind.Note, (deleteForeverWrites(entry, trashed).first() as WorkspaceWrite.DeleteForever).kind)
            } finally {
                emulated.backend.close()
            }
        }
}
