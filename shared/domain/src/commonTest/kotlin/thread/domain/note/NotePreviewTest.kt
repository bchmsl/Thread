package thread.domain.note

import kotlin.test.Test
import kotlin.test.assertEquals

// SYNTHETIC text only.
class NotePreviewTest {
    private fun paragraph(text: String) = RichTextBlock.Paragraph(listOf(RichTextInline.Text(text)))

    @Test
    fun blocksListItemsAndBreaksBecomeSingleSpaces() {
        val doc =
            RichTextDocument(
                listOf(
                    paragraph("States"),
                    RichTextBlock.Heading(2, listOf(RichTextInline.Text("Active"))),
                    RichTextBlock.BulletList(listOf(ListItem(listOf(paragraph("one"))), ListItem(listOf(paragraph("two"))))),
                    RichTextBlock.TaskList(listOf(TaskItem(true, listOf(paragraph("done"))))),
                    RichTextBlock.Paragraph(
                        listOf(RichTextInline.Text("a"), RichTextInline.HardBreak, RichTextInline.Text("b", bold = true)),
                    ),
                ),
            )
        assertEquals("States Active one two done a b", doc.plainText())
    }

    @Test
    fun anEmptyNoteHasAnEmptyPreview() {
        assertEquals("", notePreview(RichTextDocument.EMPTY))
    }

    @Test
    fun thePreviewIsCutToItsLimit() {
        val long = "word ".repeat(100)
        val preview = notePreview(RichTextDocument(listOf(paragraph(long))))
        assertEquals(NOTE_PREVIEW_MAX_LENGTH - 1, preview.length, "The cut ends on a space, which is trimmed")
        assertEquals(long.take(NOTE_PREVIEW_MAX_LENGTH).trimEnd(), preview)
    }

    @Test
    fun theCutNeverSplitsACharacterMadeOfTwoUnits() {
        val text = "a".repeat(NOTE_PREVIEW_MAX_LENGTH - 1) + "\uD83D\uDE00"
        val preview = notePreview(RichTextDocument(listOf(paragraph(text))))
        assertEquals("a".repeat(NOTE_PREVIEW_MAX_LENGTH - 1), preview)
    }
}
