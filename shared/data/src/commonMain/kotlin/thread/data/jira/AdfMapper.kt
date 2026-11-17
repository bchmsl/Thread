package thread.data.jira

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import thread.data.external.ExternalTime
import thread.data.external.arr
import thread.data.external.bool
import thread.data.external.int
import thread.data.external.obj
import thread.data.external.objects
import thread.data.external.str
import thread.domain.external.BlockTableCell
import thread.domain.external.BlockTableRow
import thread.domain.external.ExternalBlock
import thread.domain.external.ExternalDocument
import thread.domain.external.ExternalInline
import thread.domain.external.ExternalListItem
import thread.domain.external.PanelTone
import thread.domain.external.StatusColor
import thread.domain.note.LinkHref

/**
 * Maps Atlassian Document Format (Jira descriptions and comments) to [ExternalDocument] (adr/ADR-006, D74).
 *
 * Paragraphs, headings 1 to 6, lists (whose items hold any block), task lists, decisions, quotes, rules, code with its
 * language, panels, expands and tables (cells with blocks, row and column spans, row numbers) keep their shape. Layouts
 * show their content. Bold, italic, strikethrough, inline code and links keep their style; other marks (underline,
 * colors, sub and superscript) keep their text only. Status lozenges keep their color. Images, attachments, app
 * extensions and unknown nodes become [ExternalBlock.Unsupported], so the screen says what it does not show instead of
 * dropping it silently. Links pass [LinkHref.parse] or become plain text. Sizes and nesting are capped. Nothing here
 * produces HTML; the screen renders the result as DOM nodes.
 */
internal object AdfMapper {
    const val MAX_TABLE_ROWS: Int = 100
    const val MAX_TABLE_COLUMNS: Int = 20
    const val REST_OF_TABLE: String = "The rest of this table"
    const val DEEPLY_NESTED: String = "Deeply nested content"

    private const val MAX_DEPTH = 20
    private const val MAX_STORED_DEPTH = 400
    private const val MAX_NAME = 120
    private val SAFE_TYPE = Regex("^[A-Za-z]{1,40}$")
    private val SAFE_LANGUAGE = Regex("^[A-Za-z0-9#+._-]{1,30}$")

    fun document(adf: JsonElement?): ExternalDocument {
        if (adf == null || adf == JsonNull) return ExternalDocument.EMPTY
        val root = adf as? JsonObject
        if (root == null || root.str("type") != "doc") return ExternalDocument(listOf(ExternalBlock.Unsupported(UNKNOWN)))
        return ExternalDocument(blocks(root.arr("content"), depth = 1))
    }

    /**
     * [adf] as this device keeps it (ADR-006: saved copies hold no account ids): a mention keeps its text and loses its
     * account id. Content nested deeper than any document Thread shows is left out.
     */
    fun forStorage(adf: JsonElement?): JsonElement? = adf?.let { clean(it, depth = 0) }

    private fun clean(
        element: JsonElement,
        depth: Int,
    ): JsonElement =
        when (element) {
            is JsonArray -> JsonArray(if (depth >= MAX_STORED_DEPTH) emptyList() else element.map { clean(it, depth + 1) })
            is JsonObject -> {
                val mention = element.str("type") == "mention"
                JsonObject(
                    element.mapValues { (key, value) ->
                        if (mention && key == "attrs" && value is JsonObject) JsonObject(value - "id") else clean(value, depth + 1)
                    },
                )
            }
            else -> element
        }

    private fun blocks(
        content: JsonArray?,
        depth: Int,
    ): List<ExternalBlock> = content.orEmpty().flatMap { node -> (node as? JsonObject)?.let { block(it, depth) }.orEmpty() }

    private fun block(
        node: JsonObject,
        depth: Int,
    ): List<ExternalBlock> {
        if (depth > MAX_DEPTH) return unsupported(DEEPLY_NESTED)
        val content = node.arr("content")
        return when (val type = node.str("type")) {
            "paragraph" -> listOf(ExternalBlock.Paragraph(inlines(content)))
            "heading" -> listOf(ExternalBlock.Heading(headingLevel(node), inlines(content)))
            "bulletList" -> list(null, content, depth)
            "orderedList" -> list(orderStart(node), content, depth)
            "taskList" -> taskList(content, depth)?.let { listOf(it) }.orEmpty()
            "decisionList" -> decisionList(content)
            "blockquote" -> listOf(ExternalBlock.Quote(blocks(content, depth + 1)))
            "panel" -> listOf(ExternalBlock.Panel(panelTone(node), blocks(content, depth + 1)))
            "layoutSection", "layoutColumn" -> blocks(content, depth + 1)
            "expand", "nestedExpand" -> listOf(ExternalBlock.Expand(expandTitle(node), blocks(content, depth + 1)))
            "codeBlock" -> listOf(ExternalBlock.Code(plainText(content), language(node)))
            "rule" -> listOf(ExternalBlock.Rule)
            "table" -> table(node, depth)
            "mediaSingle", "mediaGroup", "media" -> media(node)
            "extension", "bodiedExtension", "multiBodiedExtension" -> unsupported("App content")
            "blockCard", "embedCard" -> card(node)
            else -> unsupported(if (type != null && SAFE_TYPE.matches(type)) "Jira content \"$type\"" else UNKNOWN)
        }
    }

    private fun unsupported(kind: String): List<ExternalBlock> = listOf(ExternalBlock.Unsupported(kind))

    private fun headingLevel(node: JsonObject): Int = (node.obj("attrs")?.int("level") ?: 1).coerceIn(ExternalBlock.Heading.LEVELS)

    private fun orderStart(node: JsonObject): Int = node.obj("attrs")?.int("order")?.takeIf { it >= 0 } ?: 1

    private fun list(
        start: Int?,
        content: JsonArray?,
        depth: Int,
    ): List<ExternalBlock> {
        val items =
            content.orEmpty().mapNotNull { it as? JsonObject }.map { item ->
                // A listItem holds blocks. Anything else in a list is shown as its own item.
                val children = if (item.str("type") == "listItem") blocks(item.arr("content"), depth + 1) else block(item, depth + 1)
                ExternalListItem(null, children)
            }
        return if (items.isEmpty()) emptyList() else listOf(ExternalBlock.MarkdownList(start, items))
    }

    /**
     * A checklist. A task item holds inline content, a block task item holds blocks, and a nested checklist belongs to
     * the item before it.
     */
    private fun taskList(
        content: JsonArray?,
        depth: Int,
    ): ExternalBlock.MarkdownList? {
        if (depth > MAX_DEPTH) return null
        val items = mutableListOf<ExternalListItem>()
        for (child in content.orEmpty().mapNotNull { it as? JsonObject }) {
            val done = child.obj("attrs")?.str("state") == "DONE"
            when (child.str("type")) {
                "taskItem" -> items += ExternalListItem(done, listOf(ExternalBlock.Paragraph(inlines(child.arr("content")))))
                "blockTaskItem" -> items += ExternalListItem(done, blocks(child.arr("content"), depth + 1))
                "taskList" -> {
                    val nested = taskList(child.arr("content"), depth + 1) ?: continue
                    val last = items.removeLastOrNull() ?: ExternalListItem(null, emptyList())
                    items += last.copy(blocks = last.blocks + nested)
                }
            }
        }
        return items.takeIf { it.isNotEmpty() }?.let { ExternalBlock.MarkdownList(null, it) }
    }

    private fun decisionList(content: JsonArray?): List<ExternalBlock> {
        val items =
            content
                .orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { it.str("type") == "decisionItem" }
                .map { ExternalListItem(null, listOf(ExternalBlock.Paragraph(inlines(it.arr("content")))), decision = true) }
        return if (items.isEmpty()) emptyList() else listOf(ExternalBlock.MarkdownList(null, items))
    }

    private fun panelTone(node: JsonObject): PanelTone =
        when (node.obj("attrs")?.str("panelType")) {
            "info" -> PanelTone.Info
            "note" -> PanelTone.Note
            "success", "tip" -> PanelTone.Success
            "warning" -> PanelTone.Warning
            "error" -> PanelTone.Error
            else -> PanelTone.Neutral
        }

    private fun expandTitle(node: JsonObject): String? =
        node
            .obj("attrs")
            ?.str("title")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_NAME)

    private fun language(node: JsonObject): String? = node.obj("attrs")?.str("language")?.takeIf { SAFE_LANGUAGE.matches(it) }

    /**
     * A table of at most [MAX_TABLE_ROWS] rows and [MAX_TABLE_COLUMNS] columns; the screen says when there is more.
     * Cell widths and colors are left out (D74).
     */
    private fun table(
        node: JsonObject,
        depth: Int,
    ): List<ExternalBlock> {
        val rows = mutableListOf<BlockTableRow>()
        var cut = false
        for (row in node.objects("content").filter { it.str("type") == "tableRow" }) {
            if (rows.size == MAX_TABLE_ROWS) {
                cut = true
                break
            }
            val cells = mutableListOf<BlockTableCell>()
            var columns = 0
            for (cell in row.objects("content")) {
                val header =
                    when (cell.str("type")) {
                        "tableHeader" -> true
                        "tableCell" -> false
                        else -> continue
                    }
                val room = MAX_TABLE_COLUMNS - columns
                val attrs = cell.obj("attrs")
                val colspan = (attrs?.int("colspan") ?: 1).coerceAtLeast(1)
                if (room <= 0 || colspan > room) cut = true
                if (room <= 0) break
                val rowspan = (attrs?.int("rowspan") ?: 1).coerceIn(1, MAX_TABLE_ROWS)
                cells += BlockTableCell(header, colspan.coerceAtMost(room), rowspan, blocks(cell.arr("content"), depth + 1))
                columns += colspan.coerceAtMost(room)
            }
            if (cells.isNotEmpty()) rows += BlockTableRow(cells)
        }
        if (rows.isEmpty()) return emptyList()
        val table = ExternalBlock.BlockTable(rows, numbered = node.obj("attrs")?.bool("isNumberColumnEnabled") == true)
        return if (cut) listOf(table, ExternalBlock.Unsupported(REST_OF_TABLE)) else listOf(table)
    }

    /** One placeholder per file, named when Jira gives the file name. Thread does not load pictures (D57, D75). */
    private fun media(node: JsonObject): List<ExternalBlock> {
        val kind = if (node.str("type") == "mediaSingle") "Image" else "Attachment"
        val files = if (node.str("type") == "media") listOf(node) else node.objects("content").filter { it.str("type") == "media" }
        if (files.isEmpty()) return unsupported(kind)
        return files.map { file ->
            val name =
                file
                    .obj("attrs")
                    ?.str("alt")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.take(MAX_NAME)
            ExternalBlock.Unsupported(if (name == null) kind else "$kind “$name”")
        }
    }

    private fun card(node: JsonObject): List<ExternalBlock> {
        val url = node.obj("attrs")?.str("url") ?: return unsupported("Link card")
        val href = LinkHref.parse(url) ?: return unsupported("Link card")
        return listOf(ExternalBlock.Paragraph(listOf(ExternalInline.Text(href.value, link = href))))
    }

    private fun inlines(content: JsonArray?): List<ExternalInline> =
        content.orEmpty().mapNotNull { it as? JsonObject }.flatMap { node ->
            // Some editors put a paragraph where inline content belongs (for example in checklist items).
            if (node.str("type") == "paragraph") node.objects("content").flatMap(::inline) else inline(node)
        }

    private fun inline(node: JsonObject): List<ExternalInline> {
        val attrs = node.obj("attrs")
        return when (node.str("type")) {
            "text" -> text(node)
            "hardBreak" -> listOf(ExternalInline.HardBreak)
            "mention" -> plain(attrs?.str("text")?.takeIf { it.isNotBlank() } ?: "@someone")
            "emoji" -> plain(attrs?.str("text") ?: attrs?.str("shortName"))
            "date" -> plain(attrs?.str("timestamp")?.toLongOrNull()?.let(ExternalTime::utcDate))
            "status" -> status(attrs)
            "inlineCard" -> link(attrs?.str("url"))
            "placeholder" -> emptyList()
            "mediaInline" -> listOf(ExternalInline.Text("[attachment]", italic = true))
            else -> listOf(ExternalInline.Text("[not shown]", italic = true))
        }
    }

    private fun text(node: JsonObject): List<ExternalInline> {
        val text = node.str("text")?.takeIf { it.isNotEmpty() } ?: return emptyList()
        var bold = false
        var italic = false
        var strike = false
        var code = false
        var link: LinkHref? = null
        for (mark in node.objects("marks")) {
            when (mark.str("type")) {
                "strong" -> bold = true
                "em" -> italic = true
                "strike" -> strike = true
                "code" -> code = true
                "link" -> link = mark.obj("attrs")?.str("href")?.let(LinkHref::parse)
            }
        }
        return listOf(ExternalInline.Text(text, bold = bold, italic = italic, strike = strike, code = code, link = link))
    }

    private fun status(attrs: JsonObject?): List<ExternalInline> {
        val text = attrs?.str("text")?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val color =
            when (attrs.str("color")) {
                "purple" -> StatusColor.Purple
                "blue" -> StatusColor.Blue
                "red" -> StatusColor.Red
                "yellow" -> StatusColor.Yellow
                "green" -> StatusColor.Green
                else -> StatusColor.Neutral
            }
        return listOf(ExternalInline.Status(text, color))
    }

    private fun plain(text: String?): List<ExternalInline> =
        text?.takeIf { it.isNotEmpty() }?.let { listOf(ExternalInline.Text(it)) }.orEmpty()

    private fun link(url: String?): List<ExternalInline> {
        val text = url?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return listOf(ExternalInline.Text(text, link = LinkHref.parse(text)))
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
