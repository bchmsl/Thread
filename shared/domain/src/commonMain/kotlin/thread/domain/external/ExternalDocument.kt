package thread.domain.external

import thread.domain.note.LinkHref
import thread.domain.note.RichTextBlock

/**
 * Rich text from Jira or GitHub, shown read-only (adr/ADR-006-jira-access-path.md, adr/ADR-007-github-access-and-cache.md).
 *
 * Jira text uses [ExternalBlock.Rich], which reuses the RichText v1 blocks of Notes, plus [ExternalBlock.Code] and
 * [ExternalBlock.Unsupported]. GitHub Markdown needs styles that Notes do not have (strikethrough, inline code, quotes,
 * rules, tables, heading levels 4 to 6, code inside a list item), so it uses the Markdown kinds below instead. They
 * live only here: the Notes schema does not change. It is never stored as a Note and never written to the server. An
 * empty list means the record has no text.
 */
data class ExternalDocument(
    val blocks: List<ExternalBlock>,
) {
    companion object {
        val EMPTY: ExternalDocument = ExternalDocument(emptyList())
    }
}

sealed interface ExternalBlock {
    data class Rich(
        val block: RichTextBlock,
    ) : ExternalBlock

    /** A code block. [text] keeps its line breaks and spaces. */
    data class Code(
        val text: String,
    ) : ExternalBlock

    /** Content Thread does not show, such as a table or an image. [kind] names it for the person, for example "Table". */
    data class Unsupported(
        val kind: String,
    ) : ExternalBlock

    /** A Markdown paragraph. */
    data class Paragraph(
        val content: List<ExternalInline>,
    ) : ExternalBlock

    /** A Markdown heading, [level] 1 to 6. */
    data class Heading(
        val level: Int,
        val content: List<ExternalInline>,
    ) : ExternalBlock {
        init {
            require(level in LEVELS) { "Heading level must be in $LEVELS, was $level" }
        }

        companion object {
            val LEVELS: IntRange = 1..6
        }
    }

    /** A Markdown list. [start] is the first number of an ordered list and null for a bullet list. */
    data class MarkdownList(
        val start: Int?,
        val items: List<ExternalListItem>,
    ) : ExternalBlock {
        init {
            require(items.isNotEmpty()) { "A list has at least one item" }
        }
    }

    data class Quote(
        val blocks: List<ExternalBlock>,
    ) : ExternalBlock

    /** A horizontal rule. */
    data object Rule : ExternalBlock

    /** A table. Every row has one cell for each entry of [align]. */
    data class Table(
        val align: List<CellAlign>,
        val header: List<TableCell>,
        val rows: List<List<TableCell>>,
    ) : ExternalBlock {
        init {
            require(align.isNotEmpty()) { "A table has at least one column" }
            require(header.size == align.size && rows.all { it.size == align.size }) { "Every row has one cell per column" }
        }
    }
}

/** A Markdown list item. [checked] is null for a plain item and true or false for a task list item, which is read-only. */
data class ExternalListItem(
    val checked: Boolean?,
    val blocks: List<ExternalBlock>,
)

enum class CellAlign { None, Left, Center, Right }

data class TableCell(
    val content: List<ExternalInline>,
)

sealed interface ExternalInline {
    /** A run of text with the same styles. [text] is never empty. [code] is inline code. */
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
        val link: LinkHref? = null,
    ) : ExternalInline {
        init {
            require(text.isNotEmpty()) { "Text is never empty" }
        }
    }

    data object HardBreak : ExternalInline
}
