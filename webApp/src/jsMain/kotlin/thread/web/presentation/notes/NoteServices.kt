package thread.web.presentation.notes

import thread.domain.account.AccountId
import thread.domain.note.DeviceId
import thread.domain.note.LocalDraft
import thread.domain.note.LocalDraftStore
import thread.domain.note.NoteSyncRemote
import thread.domain.note.RevisionId

/**
 * What an open Note needs from the data layer, as domain types. Main.kt, the composition root, builds it.
 *
 * - [draftStore] gives the device storage for an account's drafts.
 * - [syncRemote] gives the server side of an account's Notes.
 */
class NoteServices(
    val draftStore: (AccountId?) -> LocalDraftStore,
    val syncRemote: (AccountId) -> NoteSyncRemote,
    val deviceId: DeviceId,
    val newRevisionId: () -> RevisionId,
)

/** Loading one Note's draft from this device. */
internal sealed interface NoteLoad {
    data object Loading : NoteLoad

    data class Ready(
        val draft: LocalDraft,
        val isStored: Boolean,
    ) : NoteLoad

    data class Failed(
        val reason: String,
    ) : NoteLoad
}
