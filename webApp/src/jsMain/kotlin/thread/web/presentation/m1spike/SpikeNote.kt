package thread.web.presentation.m1spike

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import thread.domain.account.AccountId
import thread.domain.account.AccountSession
import thread.domain.note.DeviceId
import thread.domain.note.LinkHref
import thread.domain.note.LocalDraft
import thread.domain.note.LocalDraftStore
import thread.domain.note.NoteId
import thread.domain.note.NoteSyncRemote
import thread.domain.note.RevisionId
import thread.domain.note.RichTextBlock
import thread.domain.note.RichTextDocument
import thread.domain.note.RichTextInline
import thread.domain.note.TaskItem

/**
 * What the M1 spike needs from the data layer, as domain types. Main.kt, the composition root, builds it.
 *
 * - [draftStore] gives the device storage for an account's drafts, or for drafts made while signed out (null).
 * - [syncRemote] gives the server side of an account's Notes.
 */
class SpikeServices(
    val account: AccountSession,
    val draftStore: (AccountId?) -> LocalDraftStore,
    val syncRemote: (AccountId) -> NoteSyncRemote,
    val deviceId: DeviceId,
    val newRevisionId: () -> RevisionId,
)

/**
 * Test hooks of the M1 spike page. Only the spike's dashed test hook bar changes them.
 *
 * [signInAsSyntheticUser] signs in to the local Auth emulator as a synthetic account, without the Google popup.
 */
class SpikeTestHooks(
    val signInAsSyntheticUser: suspend (subject: String) -> Unit,
) {
    /** When true, every local save aborts its IndexedDB transaction (check E4). */
    var failLocalSaves by mutableStateOf(false)

    /** When true, every write to the server carries a field the security rules refuse (check E3). */
    var failServerWrites by mutableStateOf(false)
}

/** The one Note the M1 spike edits. */
internal val SPIKE_NOTE_ID = NoteId("m1-spike-note")

/** SYNTHETIC sample content, shown until the spike note is first saved. Not user data. */
internal fun syntheticSpikeNote(): LocalDraft =
    LocalDraft(
        noteId = SPIKE_NOTE_ID,
        title = "Synthetic note for the editor spike",
        body =
            RichTextDocument(
                listOf(
                    paragraph(
                        RichTextInline.Text(
                            "Synthetic sample text. Edit it freely. Signed out, it stays in this browser. Signed in, it syncs only " +
                                "to the local Firebase emulators.",
                        ),
                    ),
                    RichTextBlock.Heading(2, listOf(RichTextInline.Text("Formatting"))),
                    paragraph(
                        RichTextInline.Text("Text can be "),
                        RichTextInline.Text("bold", bold = true),
                        RichTextInline.Text(", "),
                        RichTextInline.Text("italic", italic = true),
                        RichTextInline.Text(" or a "),
                        RichTextInline.Text("link", link = requireNotNull(LinkHref.parse("https://example.com"))),
                        RichTextInline.Text("."),
                    ),
                    RichTextBlock.TaskList(
                        listOf(
                            TaskItem(checked = true, blocks = listOf(paragraph(RichTextInline.Text("A ticked checklist item")))),
                            TaskItem(checked = false, blocks = listOf(paragraph(RichTextInline.Text("Press ⌘Enter to tick this one")))),
                        ),
                    ),
                    paragraph(RichTextInline.Text("ქართული ტექსტი: სინთეზური ნიმუში.")),
                ),
            ),
    )

private fun paragraph(vararg content: RichTextInline) = RichTextBlock.Paragraph(content.toList())
