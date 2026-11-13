package thread.domain.note

/** Longest preview a Note's listing stores (adr/ADR-011-notes-in-the-workspace.md). */
const val NOTE_PREVIEW_MAX_LENGTH: Int = 280

/**
 * The document as plain text on one line, for previews and filtering. Blocks, list items and hard breaks become
 * single spaces, so "States" and "Active" in two paragraphs do not run together (v3 `T.plain`).
 */
fun RichTextDocument.plainText(): String = buildString { blocks.forEach { appendBlock(it) } }.collapseSpaces()

/** The start of [body]'s text that a Note's listing shows. A cut never splits a character made of two UTF-16 units. */
fun notePreview(body: RichTextDocument): String {
    val cut = body.plainText().take(NOTE_PREVIEW_MAX_LENGTH)
    return (if (cut.lastOrNull()?.isHighSurrogate() == true) cut.dropLast(1) else cut).trimEnd()
}

private fun StringBuilder.appendBlock(block: RichTextBlock) {
    when (block) {
        is RichTextBlock.Paragraph -> appendInline(block.content)
        is RichTextBlock.Heading -> appendInline(block.content)
        is RichTextBlock.BulletList -> block.items.forEach { item -> item.blocks.forEach { appendBlock(it) } }
        is RichTextBlock.OrderedList -> block.items.forEach { item -> item.blocks.forEach { appendBlock(it) } }
        is RichTextBlock.TaskList -> block.items.forEach { item -> item.blocks.forEach { appendBlock(it) } }
    }
    append(' ')
}

private fun StringBuilder.appendInline(content: List<RichTextInline>) {
    content.forEach { inline ->
        when (inline) {
            is RichTextInline.Text -> append(inline.text)
            RichTextInline.HardBreak -> append(' ')
        }
    }
}

private fun String.collapseSpaces(): String = trim().replace(WHITESPACE, " ")

private val WHITESPACE = Regex("\\s+")
