package thread.web.presentation.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.dom.Div
import thread.domain.account.AccountId
import thread.domain.note.NoteId
import thread.domain.note.NoteSaveState
import thread.domain.note.NoteSummary
import thread.web.presentation.CardKind
import thread.web.presentation.ItemCard
import thread.web.presentation.LocalIdScope
import thread.web.presentation.Router
import thread.web.presentation.SyncCenter
import thread.web.presentation.WorkspaceModel

/**
 * A Note opened from another screen (`?note=`, D70), as a card on top of it. It runs the same [NoteSession] as the
 * Notes tab. It opens for viewing, except a new Note ([startEditing], or one [opensForWriting]), which opens for
 * writing. "Open full page" goes to the Note in the Notes tab. Compare versions widens the card for its two columns.
 *
 * [onClose] is the card's Close, which the host guards: [onUnsaved] tells it the Note is not saved on this device.
 * [onCloseNow] closes without asking, after Move to Trash.
 */
@Composable
internal fun NoteCard(
    workspace: WorkspaceModel,
    router: Router,
    services: NoteServices,
    accountId: AccountId,
    noteId: NoteId,
    listing: NoteSummary?,
    syncCenter: SyncCenter,
    startEditing: Boolean,
    backTo: String?,
    onBack: () -> Unit,
    onUnsaved: (Boolean) -> Unit,
    onClose: () -> Unit,
    onCloseNow: () -> Unit,
) {
    var editing by remember { mutableStateOf(startEditing || opensForWriting(workspace, noteId, listing)) }
    var canEdit by remember { mutableStateOf(true) }
    var comparing by remember { mutableStateOf(false) }
    val ids = LocalIdScope.current
    ItemCard(
        kind = CardKind.Note,
        label = "Note",
        classes = listOfNotNull("dcard--note", "dcard--wide".takeIf { comparing }),
        editing = editing && canEdit,
        onEditing = if (canEdit) ({ editing = it }) else null,
        backTo = backTo,
        onBack = onBack,
        fullPage = notePlace(noteId),
        onClose = onClose,
    ) {
        Div({
            classes("dcard__body", "dcard__body--page")
            id("${ids}npane")
            tabIndex(-1)
        }) {
            NotePane(
                workspace = workspace,
                router = router,
                services = services,
                accountId = accountId,
                noteId = noteId,
                listing = listing,
                syncCenter = syncCenter,
                onTitle = {},
                onState = { state -> onUnsaved(state == NoteSaveState.LocalFailed) },
                editing = editing && canEdit,
                onEditing = { editing = it },
                onCanEdit = { canEdit = it },
                card = NoteCardMode(onClose = onCloseNow, onComparing = { comparing = it }),
            )
        }
    }
}

/**
 * Whether a Note opens for writing: one made on this device a moment ago that is still empty. Any other Note opens for
 * viewing (D70, owner request #3).
 */
internal fun opensForWriting(
    workspace: WorkspaceModel,
    noteId: NoteId,
    listing: NoteSummary?,
): Boolean = workspace.isMadeHere(noteId) && (listing == null || listing.title.isBlank() && listing.preview.isBlank())
