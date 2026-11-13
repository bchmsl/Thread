package thread.domain.note

import kotlin.test.Test
import kotlin.test.assertEquals

class OverallSaveStateTest {
    @Test
    fun nothingPendingIsSyncedOnlineAndOfflineOtherwise() {
        assertEquals(NoteSaveState.Synced, overallSaveState(emptyList(), online = true))
        assertEquals(NoteSaveState.Offline, overallSaveState(listOf(NoteSaveState.Synced), online = false))
    }

    @Test
    fun problemsComeBeforeWorkUnderWay() {
        val all = NoteSaveState.entries

        assertEquals(NoteSaveState.Conflict, overallSaveState(all, online = true))
        assertEquals(NoteSaveState.LocalFailed, overallSaveState(all - NoteSaveState.Conflict, online = true))
        assertEquals(
            NoteSaveState.SyncFailed,
            overallSaveState(listOf(NoteSaveState.Syncing, NoteSaveState.Saving, NoteSaveState.SyncFailed), online = true),
        )
    }

    @Test
    fun savingComesBeforeOfflineAndOfflineBeforeSyncing() {
        assertEquals(NoteSaveState.Saving, overallSaveState(listOf(NoteSaveState.Offline, NoteSaveState.Saving), online = false))
        assertEquals(NoteSaveState.Offline, overallSaveState(listOf(NoteSaveState.Syncing, NoteSaveState.Offline), online = true))
        assertEquals(NoteSaveState.Syncing, overallSaveState(listOf(NoteSaveState.Synced, NoteSaveState.Syncing), online = true))
    }
}
