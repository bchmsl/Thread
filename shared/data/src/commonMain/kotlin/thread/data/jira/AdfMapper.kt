package thread.data.jira

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import thread.data.external.ExternalTime
import thread.data.external.arr
import thread.data.external.int
import thread.data.external.obj
import thread.data.external.objects
import thread.data.external.str
import thread.domain.external.ExternalBlock
import thread.domain.external.ExternalDocument
import thread.domain.note.LinkHref
import thread.domain.note.ListItem
import thread.domain.note.RichTextBlock
import thread.domain.note.RichTextInline
import thread.domain.note.TaskItem

/**
 * Maps Atlassian Document Format (Jira descriptions and comments) to [ExternalDocument] (adr/ADR-006).
 *
 * Text, headings, lists, checklists, links, bold and italic keep their shape. Containers such as panels, quotes,
 * expands and layouts show their content. Code blocks stay code. Tables, media, app extensions and unknown nodes
 * become [ExternalBlock.Unsupported], so the screen shows the v3 placeholder instead of dropping them silently.
 * Other marks (code, strike, colour) keep their text and lose the style. Links pass [LinkHref.parse] or become
 * plain text. Nothing here produces HTML; the screen renders the result as DOM nodes.
 */
internal object AdfMapper {
    private const val MAX_DEPTH = 20
    private val SAFE_TYPE = Regex("^[A-Za-z]{1,40}$")

    fun document(adf: JsonElement?): ExternalDocument {
        if (adf == null || adf == JsonNull) return ExternalDocument.EMPTY
        val root = adf as? JsonObject
        if (root == null || root.str("type") != "doc") return ExternalDocument(listOf(ExternalBlock.Unsupported(UNKNOWN)))
        return ExternalDocument(blocks(root.arr("content"), depth = 1))
    }

    private fun blocks(
        content: JsonArray?,
        depth: Int,
    ): List<ExternalBlock> = content.orEmpty().flatMap { node -> (node as? JsonObject)?.let { block(it, depth) }.orEmpty() }

    private fun block(
        node: JsonObject,
        depth: Int,
    ): List<ExternalBlock> {
        if (depth > MAX_DEPTH) return listOf(ExternalBlock.Unsupported("Deeply nested content"))
        val content = node.arr("content")
        return when (val type = node.str("type")) {
            "paragraph" -> rich(RichTextBlock.Paragraph(inlines(content)))
            "heading" -> rich(RichTextBlock.Heading(headingLevel(node), inlines(content)))
            "bulletList" -> listItems(content, depth)?.let { rich(RichTextBlock.BulletList(it)) }.orEmpty()
            "orderedList" -> listItems(content, depth)?.let { rich(RichTextBlock.OrderedList(orderStart(node), it)) }.orEmpty()
            "taskList" -> taskList(content, depth)?.let { rich(it) }.orEmpty()
            "decisionList" -> decisionList(content)?.let { rich(it) }.orEmpty()
            "blockquote", "panel", "layoutSection", "layoutColumn" -> blocks(content, depth + 1)
            "expand", "nestedExpand" -> expandTitle(node) + blocks(content, depth + 1)
            "codeBlock" -> listOf(ExternalBlock.Code(plainText(content)))
            "rule" -> emptyList()
            "table" -> unsupported("Table")
            "mediaSingle", "mediaGroup", "media" -> unsupported("Image or attachment")
            "extension", "bodiedExtension", "multiBodiedExtension" -> unsupported("App content")
            "blockCard", "embedCard" -> card(node)
            else -> unsupported(if (type != null && SAFE_TYPE.matches(type)) "Jira content \"$type\"" else UNKNOWN)
        }
    }

    private fun rich(block: RichTextBlock): List<ExternalBlock> = listOf(ExternalBlock.Rich(block))

    private fun unsupported(kind: String): List<ExternalBlock> = listOf(ExternalBlock.Unsupported(kind))

    private fun headingLevel(node: JsonObject): Int = (node.obj("attrs")?.int("level") ?: 1).coerceIn(RichTextBlock.Heading.LEVELS)

    private fun orderStart(node: JsonObject): Int = node.obj("attrs")?.int("order")?.takeIf { it >= 0 } ?: 1

    private fun listItems(
        content: JsonArray?,
        depth: Int,
    ): List<ListItem>? =
        content
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .map { item ->
                // A listItem holds blocks. Anything else in a list is shown as its own item.
                val children = if (item.str("type") == "listItem") blocks(item.arr("content"), depth + 1) else block(item, depth + 1)
                ListItem(itemBlocks(children))
            }.takeIf { it.isNotEmpty() }

    /** A checklist. Items hold inline content; a nested checklist belongs to the item before it. */
    private fun taskList(
        content: JsonArray?,
        depth: Int,
    ): RichTextBlock.TaskList? {
        if (depth > MAX_DEPTH) return null
        val items = mutableListOf<TaskItem>()
        for (child in content.orEmpty().mapNotNull { it as? JsonObject }) {
            when (child.str("type")) {
                "taskItem" -> {
                    val checked = child.obj("attrs")?.str("state") == "DONE"
                    items += TaskItem(checked, listOf(RichTextBlock.Paragraph(inlines(child.arr("content")))))
                }
                "taskList" -> {
                    val nested = taskList(child.arr("content"), depth + 1) ?: continue
                    val last = items.removeLastOrNull() ?: TaskItem(false, listOf(RichTextBlock.Paragraph(emptyList())))
                    items += last.copy(blocks = last.blocks + nested)
                }
            }
        }
        return items.takeIf { it.isNotEmpty() }?.let { RichTextBlock.TaskList(it) }
    }

    /** Decisions show as a bullet list. */
    private fun decisionList(content: JsonArray?): RichTextBlock.BulletList? =
        content
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.str("type") == "decisionItem" }
            .map { ListItem(listOf(RichTextBlock.Paragraph(inlines(it.arr("content"))))) }
            .takeIf { it.isNotEmpty() }
            ?.let { RichTextBlock.BulletList(it) }

    private fun expandTitle(node: JsonObject): List<ExternalBlock> {
        val title = node.obj("attrs")?.str("title")?.takeIf { it.isNotBlank() } ?: return emptyList()
        return rich(RichTextBlock.Paragraph(listOf(RichTextInline.Text(title, bold = true))))
    }

    private fun card(node: JsonObject): List<ExternalBlock> {
        val url = node.obj("attrs")?.str("url") ?: return unsupported("Link card")
        val href = LinkHref.parse(url) ?: return unsupported("Link card")
        return rich(RichTextBlock.Paragraph(listOf(RichTextInline.Text(href.value, link = href))))
    }

    /** List items hold Notes blocks only, so code becomes lines of text and a placeholder becomes an italic line. */
    private fun itemBlocks(children: List<ExternalBlock>): List<RichTextBlock> {
        val blocks =
            children.map { child ->
                when (child) {
                    is ExternalBlock.Rich -> child.block
                    is ExternalBlock.Code -> RichTextBlock.Paragraph(lines(child.text))
                    is ExternalBlock.Unsupported -> notShown(child.kind)
                    // The Markdown kinds come from GitHub only; ADF never produces them.
                    is ExternalBlock.Paragraph, is ExternalBlock.Heading, is ExternalBlock.MarkdownList, is ExternalBlock.Quote,
                    ExternalBlock.Rule, is ExternalBlock.Table,
                    -> notShown(UNKNOWN)
                }
            }
        return if (blocks.firstOrNull() is RichTextBlock.Paragraph) blocks else listOf(RichTextBlock.Paragraph(emptyList())) + blocks
    }

    private fun notShown(kind: String): RichTextBlock =
        RichTextBlock.Paragraph(listOf(RichTextInline.Text("$kind is not shown here.", italic = true)))

    private fun lines(text: String): List<RichTextInline> =
        text.split('\n').flatMapIndexed { index, line ->
            val parts = if (line.isEmpty()) emptyList() else listOf(RichTextInline.Text(line))
            if (index == 0) parts else listOf(RichTextInline.HardBreak) + parts
        }

    private fun inlines(content: JsonArray?): List<RichTextInline> =
        content.orEmpty().mapNotNull { it as? JsonObject }.flatMap { node ->
            // Some editors put a paragraph where inline content belongs (for example in checklist items).
            if (node.str("type") == "paragraph") node.objects("content").flatMap(::inline) else inline(node)
        }

    private fun inline(node: JsonObject): List<RichTextInline> {
        val attrs = node.obj("attrs")
        return when (node.str("type")) {
            "text" -> text(node)
            "hardBreak" -> listOf(RichTextInline.HardBreak)
            "mention" -> plain(attrs?.str("text")?.takeIf { it.isNotBlank() } ?: "@someone")
            "emoji" -> plain(attrs?.str("text") ?: attrs?.str("shortName"))
            "date" -> plain(attrs?.str("timestamp")?.toLongOrNull()?.let(ExternalTime::utcDate))
            "status" -> plain(attrs?.str("text"))
            "inlineCard" -> link(attrs?.str("url"))
            "placeholder" -> emptyList()
            "mediaInline" -> listOf(RichTextInline.Text("[attachment]", italic = true))
            else -> listOf(RichTextInline.Text("[not shown]", italic = true))
        }
    }

    private fun text(node: JsonObject): List<RichTextInline> {
        val text = node.str("text")?.takeIf { it.isNotEmpty() } ?: return emptyList()
        var bold = false
        var italic = false
        var link: LinkHref? = null
        for (mark in node.objects("marks")) {
            when (mark.str("type")) {
                "strong" -> bold = true
                "em" -> italic = true
                "link" -> link = mark.obj("attrs")?.str("href")?.let(LinkHref::parse)
            }
        }
        return listOf(RichTextInline.Text(text, bold = bold, italic = italic, link = link))
    }

    private fun plain(text: String?): List<RichTextInline> =
        text?.takeIf { it.isNotEmpty() }?.let { listOf(RichTextInline.Text(it)) }.orEmpty()

    private fun link(url: String?): List<RichTextInline> {
        val text = url?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return listOf(RichTextInline.Text(text, link = LinkHref.parse(text)))
    }

    private fun plainText(content: JsonArray?): String =
        content.orEmpty().mapNotNull { it as? JsonObject }.joinToString("") { node ->
            when (node.str("type")) {
                "text" -> node.str("text").orEmpty()
                "hardBreak" -> "\n"
                else -> ""
            }
        }

    private const val UNKNOWN = "This content"
}
