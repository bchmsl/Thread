package thread.web.platform.editor

import thread.domain.note.LinkHref
import thread.domain.note.ListItem
import thread.domain.note.RichTextBlock
import thread.domain.note.RichTextDocument
import thread.domain.note.RichTextInline
import thread.domain.note.TaskItem
import kotlin.math.floor

/**
 * Maps the note editor's Tiptap (ProseMirror) JSON to and from [RichTextDocument] (adr/ADR-003-editor-and-richtext.md).
 *
 * The editor schema that [NoteEditor] configures holds only RichText v1 content, so every editor document maps. A
 * node or mark outside that schema is a programming error and throws [IllegalStateException].
 *
 * Reading from the editor also normalizes: a link whose address Notes do not allow loses the link but keeps its
 * text, an ordered list start that is not a whole number becomes 1, adjacent text runs with the same formatting
 * merge into one, and the editor's trailing empty paragraph is left out ([withoutTrailingParagraph]).
 */
internal object RichTextEditorJson {
    fun toEditor(document: RichTextDocument): dynamic = node("doc", content = document.blocks.map(::blockToEditor))

    fun fromEditor(doc: dynamic): RichTextDocument {
        check(doc.type == "doc") { "Expected an editor document, got \"${doc.type}\"" }
        val blocks = withoutTrailingParagraph(children(doc).map(::blockFromEditor))
        return if (blocks.isEmpty()) RichTextDocument.EMPTY else RichTextDocument(blocks)
    }

    /**
     * Leaves out the empty paragraph that the editor keeps after a last block that is not a paragraph (Tiptap's
     * TrailingNode, so the cursor can always reach the end). The editor adds it again when the note is opened, so the
     * note looks the same and opening it is not an edit. An empty paragraph after a paragraph is the user's and stays.
     */
    private fun withoutTrailingParagraph(blocks: List<RichTextBlock>): List<RichTextBlock> {
        val last = blocks.lastOrNull()
        val beforeLast = blocks.getOrNull(blocks.lastIndex - 1)
        val isTrailingParagraph =
            last is RichTextBlock.Paragraph && last.content.isEmpty() && beforeLast != null && beforeLast !is RichTextBlock.Paragraph
        return if (isTrailingParagraph) blocks.dropLast(1) else blocks
    }

    private fun blockToEditor(block: RichTextBlock): dynamic =
        when (block) {
            is RichTextBlock.Paragraph -> node("paragraph", content = block.content.map(::inlineToEditor))
            is RichTextBlock.Heading ->
                node("heading", attrs = jsObjectOf("level" to block.level), content = block.content.map(::inlineToEditor))
            is RichTextBlock.BulletList -> node("bulletList", content = block.items.map(::listItemToEditor))
            is RichTextBlock.OrderedList ->
                node("orderedList", attrs = jsObjectOf("start" to block.start), content = block.items.map(::listItemToEditor))
            is RichTextBlock.TaskList ->
                node(
                    "taskList",
                    content =
                        block.items.map { item ->
                            node("taskItem", attrs = jsObjectOf("checked" to item.checked), content = item.blocks.map(::blockToEditor))
                        },
                )
        }

    private fun listItemToEditor(item: ListItem): dynamic = node("listItem", content = item.blocks.map(::blockToEditor))

    private fun inlineToEditor(inline: RichTextInline): dynamic =
        when (inline) {
            is RichTextInline.Text -> {
                val text = jsObjectOf("type" to "text", "text" to inline.text)
                val marks =
                    buildList<dynamic> {
                        if (inline.bold) add(jsObjectOf("type" to "bold"))
                        if (inline.italic) add(jsObjectOf("type" to "italic"))
                        inline.link?.let { add(jsObjectOf("type" to "link", "attrs" to jsObjectOf("href" to it.value))) }
                    }
                if (marks.isNotEmpty()) text.marks = marks.toTypedArray()
                text
            }
            RichTextInline.HardBreak -> jsObjectOf("type" to "hardBreak")
        }

    private fun blockFromEditor(node: dynamic): RichTextBlock =
        when (val type = node.type) {
            "paragraph" -> RichTextBlock.Paragraph(inlinesFromEditor(node))
            "heading" -> {
                val level = wholeNumberOrNull(node.attrs?.level)
                check(level != null && level in RichTextBlock.Heading.LEVELS) { "Heading level ${node.attrs?.level} is not in RichText v1" }
                RichTextBlock.Heading(level, inlinesFromEditor(node))
            }
            "bulletList" -> RichTextBlock.BulletList(children(node).map { ListItem(itemBlocksFromEditor(it, "listItem")) })
            "orderedList" ->
                RichTextBlock.OrderedList(
                    start = wholeNumberOrNull(node.attrs?.start) ?: 1,
                    items = children(node).map { ListItem(itemBlocksFromEditor(it, "listItem")) },
                )
            "taskList" ->
                RichTextBlock.TaskList(
                    children(node).map { TaskItem(checked = it.attrs?.checked == true, blocks = itemBlocksFromEditor(it, "taskItem")) },
                )
            else -> error("Editor node \"$type\" is not in RichText v1")
        }

    private fun itemBlocksFromEditor(
        item: dynamic,
        expectedType: String,
    ): List<RichTextBlock> {
        check(item.type == expectedType) { "Expected \"$expectedType\", got \"${item.type}\"" }
        return children(item).map(::blockFromEditor)
    }

    private fun inlinesFromEditor(node: dynamic): List<RichTextInline> {
        val inlines = mutableListOf<RichTextInline>()
        for (child in children(node)) {
            when (val type = child.type) {
                "text" -> {
                    val text = child.text
                    if (jsTypeOf(text) != "string" || text == "") continue
                    appendText(inlines, textFromEditor(text.unsafeCast<String>(), child.marks))
                }
                "hardBreak" -> inlines += RichTextInline.HardBreak
                else -> error("Editor inline \"$type\" is not in RichText v1")
            }
        }
        return inlines
    }

    private fun textFromEditor(
        text: String,
        marks: dynamic,
    ): RichTextInline.Text {
        var bold = false
        var italic = false
        var link: LinkHref? = null
        if (marks != null) {
            for (mark in marks.unsafeCast<Array<dynamic>>()) {
                when (val type = mark.type) {
                    "bold" -> bold = true
                    "italic" -> italic = true
                    "link" -> link = (mark.attrs?.href as? String)?.let(LinkHref::parse)
                    else -> error("Editor mark \"$type\" is not in RichText v1")
                }
            }
        }
        return RichTextInline.Text(text, bold = bold, italic = italic, link = link)
    }

    private fun appendText(
        inlines: MutableList<RichTextInline>,
        text: RichTextInline.Text,
    ) {
        val last = inlines.lastOrNull()
        if (last is RichTextInline.Text && last.bold == text.bold && last.italic == text.italic && last.link == text.link) {
            inlines[inlines.lastIndex] = last.copy(text = last.text + text.text)
        } else {
            inlines += text
        }
    }

    private fun children(node: dynamic): List<dynamic> {
        val content = node.content
        return if (content == null) emptyList() else content.unsafeCast<Array<dynamic>>().toList()
    }

    private fun wholeNumberOrNull(value: dynamic): Int? {
        if (jsTypeOf(value) != "number") return null
        val number = value.unsafeCast<Double>()
        val isWhole = !number.isNaN() && !number.isInfinite() && number == floor(number)
        return if (isWhole && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE) number.toInt() else null
    }

    private fun node(
        type: String,
        attrs: dynamic = null,
        content: List<dynamic> = emptyList(),
    ): dynamic {
        val node = jsObjectOf("type" to type)
        if (attrs != null) node.attrs = attrs
        if (content.isNotEmpty()) node.content = content.toTypedArray()
        return node
    }
}

/** A new plain JS object with [entries]. */
internal fun jsObjectOf(vararg entries: Pair<String, Any?>): dynamic {
    val result: dynamic = js("({})")
    for ((key, value) in entries) result[key] = value
    return result
}
