package thread.web.presentation.m1spike

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import thread.domain.note.LinkHref
import thread.domain.note.LocalDraft
import thread.domain.note.NoteId
import thread.domain.note.RichTextBlock
import thread.domain.note.RichTextDocument
import thread.domain.note.RichTextInline
import thread.domain.note.TaskItem

/** Test hooks of the M1 spike page. Only the spike's dashed test hook bar changes them. */
class SpikeTestHooks {
    /** When true, every local save aborts its IndexedDB transaction (check E4). */
    var failLocalSaves by mutableStateOf(false)
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
                        RichTextInline.Text("Synthetic sample text. Edit it freely. It stays in this browser and is never uploaded."),
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
