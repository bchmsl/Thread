package thread.domain.note

import thread.domain.note.LocalSaveStatus.NeedsAttention
import thread.domain.note.LocalSaveStatus.Saved
import thread.domain.note.LocalSaveStatus.Saving
import kotlin.test.Test
import kotlin.test.assertEquals

/** All content below is synthetic test data. */
class NoteSaveStateTest {
    private val other =
        NoteRevision(RevisionId("r2"), null, "Synthetic", RichTextDocument.EMPTY, DeviceId("synthetic-device"), 0, true, false)

    @Test
    fun deviceStorageProblemsComeFirst() {
        val conflicted = NoteSyncState(loaded = true, conflict = other, rejected = true)

        assertEquals(NoteSaveState.LocalFailed, noteSaveState(NeedsAttention, conflicted, online = true))
        assertEquals(NoteSaveState.Saving, noteSaveState(Saving, conflicted, online = true))
    }

    @Test
    fun withoutAnAccountTheNoteIsLocalOnly() {
        assertEquals(NoteSaveState.LocalOnly, noteSaveState(Saved, null, online = true))
    }

    @Test
    fun syncProblemsComeBeforeProgress() {
        assertEquals(NoteSaveState.Conflict, noteSaveState(Saved, NoteSyncState(conflict = other, rejected = true), online = true))
        assertEquals(NoteSaveState.SyncFailed, noteSaveState(Saved, NoteSyncState(rejected = true, synced = true), online = true))
        assertEquals(NoteSaveState.SyncFailed, noteSaveState(Saved, NoteSyncState(unreadable = true), online = true))
    }

    @Test
    fun syncedOnlyWhenTheServerAcknowledgedTheContent() {
        assertEquals(NoteSaveState.Synced, noteSaveState(Saved, NoteSyncState(loaded = true, synced = true), online = false))
        assertEquals(NoteSaveState.Syncing, noteSaveState(Saved, NoteSyncState(loaded = true, pushing = true), online = true))
        assertEquals(NoteSaveState.Offline, noteSaveState(Saved, NoteSyncState(loaded = true, pushing = true), online = false))
        assertEquals(NoteSaveState.Syncing, noteSaveState(Saved, NoteSyncState(), online = true))
    }
}
