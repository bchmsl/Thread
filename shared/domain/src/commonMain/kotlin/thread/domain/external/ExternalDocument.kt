package thread.domain.external

import thread.domain.note.LinkHref

/**
 * Rich text from Jira or GitHub, shown read-only (adr/ADR-006-jira-access-path.md, adr/ADR-007-github-access-and-cache.md).
 *
 * Both use the same kinds (D57): paragraphs, headings 1 to 6, lists that hold any block, quotes, rules, code and tables,
 * with bold, italic, strikethrough and inline code. Jira adds panels, expands, status lozenges, decisions and tables
 * whose cells hold blocks and span rows and columns (D74). The kinds live only here: the Notes schema does not change.
 * A document is never stored as a Note and never written to the server. An empty list means the record has no text.
 */
data class ExternalDocument(
    val blocks: List<ExternalBlock>,
) {
    companion object {
        val EMPTY: ExternalDocument = ExternalDocument(emptyList())
    }
}

sealed interface ExternalBlock {
    /** A code block. [text] keeps its line breaks and spaces. [language] is the language the author named, if any. */
    data class Code(
        val text: String,
        val language: String? = null,
    ) : ExternalBlock

    /** Content Thread does not show, such as an app's content. [kind] names it for the person, for example "App content". */
    data class Unsupported(
        val kind: String,
    ) : ExternalBlock

    data class Paragraph(
        val content: List<ExternalInline>,
    ) : ExternalBlock

    /** A heading, [level] 1 to 6. */
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

    /** A list. [start] is the first number of an ordered list and null for a bullet, task or decision list. */
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

    /** A Markdown table. Every row has one cell for each entry of [align]. */
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

    /**
     * A Jira table: cells hold blocks and may span rows and columns, and header cells can be anywhere (usually the
     * first row or the first column). [numbered] shows a row number before each row that is not all header cells.
     */
    data class BlockTable(
        val rows: List<BlockTableRow>,
        val numbered: Boolean,
    ) : ExternalBlock {
        init {
            require(rows.isNotEmpty() && rows.all { it.cells.isNotEmpty() }) { "A table has at least one row, and a row one cell" }
        }
    }

    /** A Jira panel: a box of blocks in the [tone] its author picked. */
    data class Panel(
        val tone: PanelTone,
        val blocks: List<ExternalBlock>,
    ) : ExternalBlock

    /** A Jira expand: blocks under a [title] that the person opens. Closed at first, as in Jira. */
    data class Expand(
        val title: String?,
        val blocks: List<ExternalBlock>,
    ) : ExternalBlock
}

/**
 * A list item. [checked] is null for a plain item and true or false for a task item, which is read-only. [decision]
 * marks a Jira decision.
 */
data class ExternalListItem(
    val checked: Boolean?,
    val blocks: List<ExternalBlock>,
    val decision: Boolean = false,
)

enum class CellAlign { None, Left, Center, Right }

data class TableCell(
    val content: List<ExternalInline>,
)

data class BlockTableRow(
    val cells: List<BlockTableCell>,
)

/** One cell of a [ExternalBlock.BlockTable]. [colspan] and [rowspan] are at least 1. */
data class BlockTableCell(
    val header: Boolean,
    val colspan: Int,
    val rowspan: Int,
    val blocks: List<ExternalBlock>,
) {
    init {
        require(colspan >= 1 && rowspan >= 1) { "A cell spans at least one row and one column" }
    }
}

/** The tones of Jira's panel types. Jira's "tip" is [Success]; a custom panel and an unknown type are [Neutral]. */
enum class PanelTone { Info, Note, Success, Warning, Error, Neutral }

/** Jira's status lozenge colors. An unknown color is [Neutral]. */
enum class StatusColor { Neutral, Purple, Blue, Red, Yellow, Green }

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

    /** A Jira status lozenge in a line of text. [text] is never empty. */
    data class Status(
        val text: String,
        val color: StatusColor,
    ) : ExternalInline {
        init {
            require(text.isNotEmpty()) { "A status has text" }
        }
    }
}
