package thread.data.github

import thread.domain.external.CellAlign
import thread.domain.external.ExternalBlock
import thread.domain.external.ExternalDocument
import thread.domain.external.ExternalInline
import thread.domain.external.ExternalListItem
import thread.domain.external.TableCell
import thread.domain.note.LinkHref

/**
 * Parses a pull request description, written in GitHub Markdown, into [ExternalDocument] (adr/ADR-007, D57).
 *
 * It follows CommonMark plus GitHub's additions: tables, task lists, strikethrough, bare links, `#123` and `@name`
 * links, and line breaks kept as GitHub keeps them in pull requests. Nothing here produces HTML. Raw HTML in the
 * Markdown stays visible text, except three things GitHub never shows as text: comments are hidden, `<br>` is a line
 * break and `<img>` is an image. Images become a link "Image: <alt>", because the app does not load pictures from
 * other sites. Every link passes [LinkHref.parse] or stays plain text. Sizes and nesting are capped, so a hostile
 * description cannot stall the screen; the screen says what it did not show.
 */
internal object GitHubMarkdown {
    /** GitHub's own limit for a description. Text after it is not shown. */
    const val MAX_CHARS: Int = 65_536
    const val MAX_BLOCKS: Int = 2_000
    const val MAX_TABLE_ROWS: Int = 100
    const val MAX_TABLE_COLUMNS: Int = 20
    const val MAX_DEPTH: Int = 16

    const val REST_OF_DESCRIPTION: String = "The rest of this description"
    const val REST_OF_TABLE: String = "The rest of this table"
    const val DEEPLY_NESTED: String = "Deeply nested content"

    /** [owner] and [repo] name the repository that `#123` points into. */
    fun document(
        markdown: String?,
        owner: String,
        repo: String,
    ): ExternalDocument {
        if (markdown.isNullOrBlank()) return ExternalDocument.EMPTY
        return MarkdownParser(owner, repo).parse(markdown)
    }
}

// ---------------------------------------------------------------- blocks

private sealed interface Node

private class Para(
    val text: String,
) : Node

private class Head(
    val level: Int,
    val text: String,
) : Node

private class CodeNode(
    val text: String,
) : Node

private class QuoteNode(
    val children: List<Node>,
) : Node

private class ListNode(
    val start: Int?,
    val items: List<ItemNode>,
) : Node

private class ItemNode(
    val checked: Boolean?,
    val children: List<Node>,
)

private data object RuleNode : Node

private class TableNode(
    val align: List<CellAlign>,
    val header: List<String>,
    val rows: List<List<String>>,
    val cut: Boolean,
) : Node

private class NoteNode(
    val kind: String,
) : Node

private class Fence(
    val indent: Int,
    val char: Char,
    val length: Int,
)

/** A list item marker: a bullet, or a number with its delimiter. [offset] is where the item's content starts. */
private class Marker(
    val ordered: Boolean,
    val char: Char,
    val number: Int,
    val offset: Int,
    val rest: String,
    val empty: Boolean,
)

private class MarkdownParser(
    private val owner: String,
    private val repo: String,
) {
    private val definitions = HashMap<String, String>()
    private var blockCount = 0
    private var full = false

    fun parse(markdown: String): ExternalDocument {
        var text = markdown
        val cut = text.length > GitHubMarkdown.MAX_CHARS
        if (cut) {
            var end = GitHubMarkdown.MAX_CHARS
            if (text[end - 1].isHighSurrogate()) end--
            text = text.substring(0, end)
        }
        val lines =
            text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u0000', '�')
                .split('\n')
                .map(::expandIndent)
        val blocks = convert(blocks(lines, depth = 0)).toMutableList()
        if (cut || full) blocks += ExternalBlock.Unsupported(GitHubMarkdown.REST_OF_DESCRIPTION)
        return ExternalDocument(blocks)
    }

    /** Counts one more block. False once the budget is spent: parsing stops and the screen says so. */
    private fun count(): Boolean {
        if (full) return false
        if (++blockCount > GitHubMarkdown.MAX_BLOCKS) full = true
        return !full
    }

    private fun blocks(
        lines: List<String>,
        depth: Int,
    ): List<Node> {
        val out = mutableListOf<Node>()
        var i = 0
        while (i < lines.size && !full) {
            val line = lines[i]
            i =
                when {
                    line.isBlank() -> i + 1
                    indentOf(line) >= 4 -> indentedCode(lines, i, out)
                    fenceOf(line) != null -> fenced(lines, i, out)
                    isCommentStart(line) -> comment(lines, i, out)
                    atxHeading(line) != null -> {
                        if (count()) out += atxHeading(line)!!
                        i + 1
                    }
                    isRule(line) -> {
                        if (count()) out += RuleNode
                        i + 1
                    }
                    quoteStart(line) != null -> quote(lines, i, depth, out)
                    listMarker(line) != null -> list(lines, i, depth, out)
                    else -> paragraph(lines, i, out)
                }
        }
        return out
    }

    private fun indentedCode(
        lines: List<String>,
        start: Int,
        out: MutableList<Node>,
    ): Int {
        val body = mutableListOf<String>()
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            if (!line.isBlank() && indentOf(line) < 4) break
            body += if (line.length > 4) line.substring(4) else ""
            i++
        }
        while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.lastIndex)
        if (count()) out += CodeNode(body.joinToString("\n"))
        return i
    }

    private fun fenced(
        lines: List<String>,
        start: Int,
        out: MutableList<Node>,
    ): Int {
        val fence = fenceOf(lines[start])!!
        val body = mutableListOf<String>()
        var i = start + 1
        while (i < lines.size) {
            val line = lines[i++]
            if (closesFence(line, fence)) break
            body += dropSpaces(line, fence.indent)
        }
        if (count()) out += CodeNode(body.joinToString("\n"))
        return i
    }

    /** An HTML comment is hidden, as on GitHub. Text after its end on the same line stays. */
    private fun comment(
        lines: List<String>,
        start: Int,
        out: MutableList<Node>,
    ): Int {
        var i = start
        var from = lines[start].indexOf("<!--") + 4
        while (i < lines.size) {
            val line = lines[i]
            val end = line.indexOf("-->", from)
            if (end >= 0) {
                val rest = line.substring(end + 3).trim()
                if (rest.isNotEmpty() && count()) out += Para(rest)
                return i + 1
            }
            from = 0
            i++
        }
        return i
    }

    private fun quote(
        lines: List<String>,
        start: Int,
        depth: Int,
        out: MutableList<Node>,
    ): Int {
        val inner = mutableListOf<String>()
        var i = start
        while (i < lines.size) {
            val line = lines[i]
            val content = quoteStart(line)
            when {
                content != null -> inner += line.substring(content)
                // A lazy line continues the quote's paragraph.
                line.isBlank() || inner.last().isBlank() || interrupts(line) -> break
                else -> inner += line
            }
            i++
        }
        if (!count()) return i
        out += if (depth >= GitHubMarkdown.MAX_DEPTH) NoteNode(GitHubMarkdown.DEEPLY_NESTED) else QuoteNode(blocks(inner, depth + 1))
        return i
    }

    private fun list(
        lines: List<String>,
        start: Int,
        depth: Int,
        out: MutableList<Node>,
    ): Int {
        val first = listMarker(lines[start])!!
        if (!count()) return lines.size
        val items = mutableListOf<ItemNode>()
        var i = start
        while (i < lines.size && !full) {
            var next = i
            while (next < lines.size && lines[next].isBlank()) next++
            if (next > i) {
                // Blank lines between items keep the list going when the next item follows.
                val following = lines.getOrNull(next)?.let(::listMarker)
                if (following == null || following.ordered != first.ordered || following.char != first.char) break
                i = next
            }
            val marker = listMarker(lines[i]) ?: break
            if (marker.ordered != first.ordered || marker.char != first.char || isRule(lines[i])) break
            if (items.isNotEmpty() && !count()) break
            val content = mutableListOf(marker.rest)
            var lastBlank = marker.empty
            i++
            while (i < lines.size) {
                val line = lines[i]
                if (line.isBlank()) {
                    // An item that starts empty ends at a blank line.
                    if (marker.empty && content.size == 1) break
                    content += ""
                    lastBlank = true
                } else if (indentOf(line) >= marker.offset) {
                    content += line.substring(marker.offset)
                    lastBlank = false
                } else if (!lastBlank && !interrupts(line) && listMarker(line) == null) {
                    content += line.trim()
                } else {
                    break
                }
                i++
            }
            while (content.size > 1 && content.last().isBlank()) content.removeAt(content.lastIndex)
            val checked = taskState(content[0])
            if (checked != null) content[0] = content[0].substring(3).trimStart()
            val children =
                if (depth >= GitHubMarkdown.MAX_DEPTH) listOf(NoteNode(GitHubMarkdown.DEEPLY_NESTED)) else blocks(content, depth + 1)
            items += ItemNode(checked, children)
        }
        if (items.isNotEmpty()) out += ListNode(if (first.ordered) first.number else null, items)
        return i
    }

    /** A paragraph, a setext heading or a table. Link reference definitions at its start are kept and not shown. */
    private fun paragraph(
        lines: List<String>,
        start: Int,
        out: MutableList<Node>,
    ): Int {
        var i = start
        while (i < lines.size && definition(lines[i])) i++
        if (i > start) return i
        if (i + 1 < lines.size && tableAlign(lines[i], lines[i + 1]) != null) return table(lines, i, out)
        val text = mutableListOf(lines[i].trim())
        i++
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) break
            val level = setextLevel(line)
            if (level != null) {
                if (count()) out += Head(level, text.joinToString("\n"))
                return i + 1
            }
            if (interrupts(line)) break
            if (i + 1 < lines.size && tableAlign(line, lines[i + 1]) != null) break
            text += line.trim()
            i++
        }
        if (count()) out += Para(text.joinToString("\n"))
        return i
    }

    private fun table(
        lines: List<String>,
        start: Int,
        out: MutableList<Node>,
    ): Int {
        val align = tableAlign(lines[start], lines[start + 1])!!
        val columns = minOf(align.size, GitHubMarkdown.MAX_TABLE_COLUMNS)
        val rows = mutableListOf<List<String>>()
        var cut = align.size > columns
        var i = start + 2
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank() || interrupts(line)) break
            if (rows.size < GitHubMarkdown.MAX_TABLE_ROWS) rows += fit(splitRow(line), columns) else cut = true
            i++
        }
        if (count()) out += TableNode(align.take(columns), fit(splitRow(lines[start]), columns), rows, cut)
        return i
    }

    private fun definition(line: String): Boolean {
        val indent = indentOf(line)
        if (indent > 3 || indent >= line.length || line[indent] != '[') return false
        val close = line.indexOf("]:", indent)
        if (close < 0) return false
        val label = line.substring(indent + 1, close)
        if (label.isBlank() || label.length > MAX_LABEL || '[' in label || ']' in label) return false
        val rest = line.substring(close + 2).trim()
        if (rest.isEmpty()) return false
        val destination: String
        val title: String
        if (rest.startsWith('<')) {
            val end = rest.indexOf('>')
            if (end < 0) return false
            destination = rest.substring(1, end)
            title = rest.substring(end + 1).trim()
        } else {
            val end = rest.indexOf(' ')
            destination = if (end < 0) rest else rest.substring(0, end)
            title = if (end < 0) "" else rest.substring(end).trim()
        }
        if (title.isNotEmpty() && !isTitle(title)) return false
        definitions.getOrPut(normalizeLabel(label)) { decodeEntities(unescape(destination)) }
        return true
    }

    private fun convert(nodes: List<Node>): List<ExternalBlock> = nodes.flatMap(::convert)

    private fun convert(node: Node): List<ExternalBlock> =
        when (node) {
            is Para -> inlines(node.text).let { if (it.isEmpty()) emptyList() else listOf(ExternalBlock.Paragraph(it)) }
            is Head -> inlines(node.text).let { if (it.isEmpty()) emptyList() else listOf(ExternalBlock.Heading(node.level, it)) }
            is CodeNode -> listOf(ExternalBlock.Code(node.text))
            is QuoteNode -> listOf(ExternalBlock.Quote(convert(node.children)))
            is ListNode ->
                listOf(
                    ExternalBlock.MarkdownList(node.start, node.items.map { ExternalListItem(it.checked, convert(it.children)) }),
                )
            RuleNode -> listOf(ExternalBlock.Rule)
            is TableNode -> {
                val table = ExternalBlock.Table(node.align, node.header.map(::cell), node.rows.map { row -> row.map(::cell) })
                if (node.cut) listOf(table, ExternalBlock.Unsupported(GitHubMarkdown.REST_OF_TABLE)) else listOf(table)
            }
            is NoteNode -> listOf(ExternalBlock.Unsupported(node.kind))
        }

    private fun cell(text: String): TableCell = TableCell(inlines(text))

    private fun inlines(text: String): List<ExternalInline> = InlineParser(text, definitions, owner, repo).inlines()
}

private fun indentOf(line: String): Int {
    var k = 0
    while (k < line.length && line[k] == ' ') k++
    return k
}

/** Tabs in the leading whitespace become spaces to the next multiple of 4, so indentation can be counted in spaces. */
private fun expandIndent(line: String): String {
    var k = 0
    while (k < line.length && (line[k] == ' ' || line[k] == '\t')) k++
    if (line.lastIndexOf('\t', k - 1) < 0) return line
    val out = StringBuilder()
    for (c in line.substring(0, k)) {
        if (c == '\t') repeat(4 - out.length % 4) { out.append(' ') } else out.append(c)
    }
    return out.append(line.substring(k)).toString()
}

private fun dropSpaces(
    line: String,
    count: Int,
): String = line.substring(minOf(count, indentOf(line)))

private fun fenceOf(line: String): Fence? {
    val indent = indentOf(line)
    if (indent > 3 || indent >= line.length) return null
    val c = line[indent]
    if (c != '`' && c != '~') return null
    var n = 0
    while (indent + n < line.length && line[indent + n] == c) n++
    if (n < 3) return null
    if (c == '`' && line.indexOf('`', indent + n) >= 0) return null
    return Fence(indent, c, n)
}

private fun closesFence(
    line: String,
    fence: Fence,
): Boolean {
    val indent = indentOf(line)
    if (indent > 3) return false
    var n = 0
    while (indent + n < line.length && line[indent + n] == fence.char) n++
    return n >= fence.length && line.substring(indent + n).isBlank()
}

private fun isCommentStart(line: String): Boolean {
    val indent = indentOf(line)
    return indent <= 3 && line.startsWith("<!--", indent)
}

private fun atxHeading(line: String): Head? {
    val indent = indentOf(line)
    if (indent > 3) return null
    var n = 0
    while (indent + n < line.length && line[indent + n] == '#') n++
    if (n !in 1..6) return null
    val after = indent + n
    if (after < line.length && line[after] != ' ') return null
    var text = line.substring(after).trim()
    val stripped = text.trimEnd('#')
    if (stripped.isEmpty()) {
        text = ""
    } else if (stripped.endsWith(' ')) {
        text = stripped.trimEnd()
    }
    return Head(n, text)
}

private fun isRule(line: String): Boolean {
    val indent = indentOf(line)
    if (indent > 3 || indent >= line.length) return false
    val c = line[indent]
    if (c != '-' && c != '*' && c != '_') return false
    var marks = 0
    for (k in indent until line.length) {
        when (line[k]) {
            c -> marks++
            ' ' -> Unit
            else -> return false
        }
    }
    return marks >= 3
}

private fun setextLevel(line: String): Int? {
    if (indentOf(line) > 3) return null
    val text = line.trim()
    return when {
        text.isEmpty() -> null
        text.all { it == '=' } -> 1
        text.all { it == '-' } -> 2
        else -> null
    }
}

/** The index where the quote's content starts, or null when [line] does not start a block quote. */
private fun quoteStart(line: String): Int? {
    val indent = indentOf(line)
    if (indent > 3 || indent >= line.length || line[indent] != '>') return null
    val after = indent + 1
    return if (after < line.length && line[after] == ' ') after + 1 else after
}

private fun listMarker(line: String): Marker? {
    val indent = indentOf(line)
    if (indent > 3 || indent >= line.length) return null
    var p = indent
    val c = line[p]
    val ordered: Boolean
    val char: Char
    var number = 0
    if (c == '-' || c == '+' || c == '*') {
        ordered = false
        char = c
        p++
    } else if (c in '0'..'9') {
        while (p < line.length && line[p] in '0'..'9' && p - indent < 10) p++
        if (p - indent > 9 || p >= line.length || (line[p] != '.' && line[p] != ')')) return null
        number = line.substring(indent, p).toInt()
        ordered = true
        char = line[p]
        p++
    } else {
        return null
    }
    if (p < line.length && line[p] != ' ') return null
    var spaces = 0
    while (p + spaces < line.length && line[p + spaces] == ' ') spaces++
    if (p + spaces >= line.length) return Marker(ordered, char, number, p + 1, "", empty = true)
    val offset = if (spaces > 4) p + 1 else p + spaces
    return Marker(ordered, char, number, offset, line.substring(offset), empty = false)
}

/** Whether [line] starts a block that ends a paragraph before it (CommonMark). */
private fun interrupts(line: String): Boolean {
    if (indentOf(line) >= 4) return false
    if (fenceOf(line) != null || isCommentStart(line) || atxHeading(line) != null || isRule(line) || quoteStart(line) != null) return true
    val marker = listMarker(line) ?: return false
    return !marker.empty && (!marker.ordered || marker.number == 1)
}

/** True or false for a task list item (`[x]` or `[ ]` and a space), null for a plain item. */
private fun taskState(first: String): Boolean? {
    if (first.length < 3 || first[0] != '[' || first[2] != ']') return null
    if (first.length > 3 && first[3] != ' ') return null
    return when (first[1]) {
        ' ' -> false
        'x', 'X' -> true
        else -> null
    }
}

private fun splitRow(line: String): List<String> {
    var row = line.trim()
    if (row.startsWith('|')) row = row.substring(1)
    if (row.endsWith('|') && !row.endsWith("\\|")) row = row.dropLast(1)
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var k = 0
    while (k < row.length) {
        val c = row[k]
        if (c == '\\' && k + 1 < row.length && row[k + 1] == '|') {
            cell.append('|')
            k += 2
            continue
        }
        if (c == '|') {
            cells += cell.toString().trim()
            cell.clear()
        } else {
            cell.append(c)
        }
        k++
    }
    cells += cell.toString().trim()
    return cells
}

private fun fit(
    cells: List<String>,
    columns: Int,
): List<String> = if (cells.size >= columns) cells.take(columns) else cells + List(columns - cells.size) { "" }

/** The column alignments when [header] and [delimiter] start a table, else null. */
private fun tableAlign(
    header: String,
    delimiter: String,
): List<CellAlign>? {
    if (indentOf(header) > 3 || indentOf(delimiter) > 3) return null
    if ('|' !in header && '|' !in delimiter) return null
    val row = delimiter.trim()
    if (row.isEmpty() || row.any { it != '|' && it != '-' && it != ':' && it != ' ' }) return null
    val align =
        splitRow(row).map { spec ->
            val dashes = spec.trim(':')
            if (dashes.isEmpty() || dashes.any { it != '-' }) return null
            val left = spec.startsWith(':')
            val right = spec.endsWith(':')
            when {
                left && right -> CellAlign.Center
                left -> CellAlign.Left
                right -> CellAlign.Right
                else -> CellAlign.None
            }
        }
    return align.takeIf { splitRow(header).size == it.size }
}

private fun isTitle(text: String): Boolean {
    if (text.length < 2) return false
    val open = text.first()
    val close = text.last()
    return (open == '"' && close == '"') || (open == '\'' && close == '\'') || (open == '(' && close == ')')
}

private fun normalizeLabel(label: String): String =
    label
        .trim()
        .split(WHITESPACE)
        .joinToString(" ")
        .lowercase()

private val WHITESPACE = Regex("\\s+")
private const val MAX_LABEL = 999

// ---------------------------------------------------------------- inlines

private sealed interface Tok

private class Run(
    val text: String,
    val code: Boolean = false,
    var link: LinkHref? = null,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var strike: Boolean = false,
) : Tok

private data object BreakTok : Tok

/** A run of `*`, `_` or `~` that may open or close emphasis (CommonMark "delimiter run"). [pos] is its token index. */
private class Delim(
    val char: Char,
    var count: Int,
    val canOpen: Boolean,
    val canClose: Boolean,
    val pos: Int,
) : Tok {
    val original: Int = count
    var prev: Delim? = null
    var next: Delim? = null
}

private class Parsed(
    val toks: List<Tok>,
    val end: Int,
)

private const val MAX_LINK_DEPTH = 4
private const val MAX_DESTINATION = 2_048
private const val MAX_EMPHASIS_STEPS = 1_000_000
private const val BOLD = 0
private const val ITALIC = 1
private const val STRIKE = 2

private class InlineParser(
    private val s: String,
    private val definitions: Map<String, String>,
    private val owner: String,
    private val repo: String,
) {
    /** The start of each code span's opening backticks, to the index after its closing backticks. */
    private val codeEnds = HashMap<Int, Int>()

    /** Each `[` to its matching `]`, outside code spans and escapes. */
    private val bracketClose = HashMap<Int, Int>()

    /** Where each `-->` starts, found once, so many comment openers cannot each scan the rest of the text. */
    private val commentCloses: List<Int> by lazy {
        val out = ArrayList<Int>()
        var k = s.indexOf("-->")
        while (k >= 0) {
            out += k
            k = s.indexOf("-->", k + 1)
        }
        out
    }
    private var steps = 0

    init {
        scanCode()
        scanBrackets()
    }

    fun inlines(): List<ExternalInline> = flatten(parse(0, s.length, 0))

    private fun scanCode() {
        val failed = HashSet<Int>()
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '`' -> {
                    val n = run(i, '`', s.length)
                    val close = if (n in failed) -1 else findRun(i + n, n)
                    if (close >= 0) {
                        codeEnds[i] = close + n
                        i = close + n
                    } else {
                        failed += n
                        i += n
                    }
                }
                else -> i++
            }
        }
    }

    private fun findRun(
        from: Int,
        n: Int,
    ): Int {
        var j = from
        while (j < s.length) {
            if (s[j] == '`') {
                val m = run(j, '`', s.length)
                if (m == n) return j
                j += m
            } else {
                j++
            }
        }
        return -1
    }

    private fun scanBrackets() {
        val open = ArrayList<Int>()
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                '`' -> {
                    i = (codeEnds[i] ?: (i + run(i, '`', s.length))) - 1
                }
                '[' -> open += i
                ']' -> if (open.isNotEmpty()) bracketClose[open.removeAt(open.lastIndex)] = i
            }
            i++
        }
    }

    private fun run(
        i: Int,
        c: Char,
        end: Int,
    ): Int {
        var n = 1
        while (i + n < end && s[i + n] == c) n++
        return n
    }

    private fun parse(
        start: Int,
        end: Int,
        depth: Int,
    ): List<Tok> {
        val toks = ArrayList<Tok>()
        val delims = ArrayList<Delim>()
        val text = StringBuilder()

        fun flush() {
            if (text.isNotEmpty()) toks += Run(text.toString())
            text.clear()
        }

        var i = start
        while (i < end) {
            val c = s[i]
            if (c == '*' || c == '_' || c == '~') {
                val n = run(i, c, end)
                flush()
                val delim = delimiter(c, i, n, toks.size)
                toks += delim
                if (delim.canOpen || delim.canClose) delims += delim
                i += n
                continue
            }
            val parsed =
                when (c) {
                    '\\' -> escape(i, end)
                    '\n' -> Parsed(listOf(BreakTok), i + 1)
                    '`' -> code(i, end)
                    '!' -> if (i + 1 < end && s[i + 1] == '[' && depth < MAX_LINK_DEPTH) link(i + 1, end, depth, image = true) else null
                    '[' -> if (depth < MAX_LINK_DEPTH) link(i, end, depth, image = false) else null
                    '<' -> angle(i, end)
                    '@' -> mention(i, end)
                    '#' -> issue(i, end)
                    '&' -> entity(i, end)?.let { Parsed(listOf(Run(it.first)), it.second) }
                    'h', 'H', 'w', 'W' -> bareLink(i, end)
                    else -> null
                }
            if (parsed == null) {
                text.append(c)
                i++
            } else {
                flush()
                toks.addAll(parsed.toks)
                i = parsed.end
            }
        }
        flush()
        return applyEmphasis(toks, emphasis(delims))
    }

    private fun delimiter(
        c: Char,
        i: Int,
        n: Int,
        pos: Int,
    ): Delim {
        val before = if (i > 0) s[i - 1] else '\n'
        val after = if (i + n < s.length) s[i + n] else '\n'
        val left = !after.isWhitespace() && (!punct(after) || before.isWhitespace() || punct(before))
        val right = !before.isWhitespace() && (!punct(before) || after.isWhitespace() || punct(after))
        return when (c) {
            '_' -> Delim(c, n, left && (!right || punct(before)), right && (!left || punct(after)), pos)
            '~' -> Delim(c, n, left && n <= 2, right && n <= 2, pos)
            else -> Delim(c, n, left, right, pos)
        }
    }

    /** CommonMark's "process emphasis": pairs openers with closers. Returns (opener, closer, style) token ranges. */
    private fun emphasis(delims: List<Delim>): List<IntArray> {
        val ranges = ArrayList<IntArray>()
        for (k in delims.indices) {
            delims[k].prev = delims.getOrNull(k - 1)
            delims[k].next = delims.getOrNull(k + 1)
        }
        val floors = HashMap<Int, Int>()
        var closer = delims.firstOrNull()
        while (closer != null) {
            if (!closer.canClose || closer.count == 0) {
                closer = closer.next
                continue
            }
            val key =
                closer.char.code * 16 + (if (closer.canOpen) 8 else 0) + (if (closer.char == '~') closer.count else closer.original % 3)
            val floor = floors[key] ?: -1
            var opener = closer.prev
            while (opener != null && opener.pos > floor) {
                if (++steps > MAX_EMPHASIS_STEPS) return ranges
                if (opener.char == closer.char && opener.canOpen && opener.count > 0 && fits(opener, closer)) break
                opener = opener.prev
            }
            if (opener != null && opener.pos > floor) {
                val use =
                    if (closer.char == '~') {
                        closer.count
                    } else if (opener.count >= 2 && closer.count >= 2) {
                        2
                    } else {
                        1
                    }
                ranges +=
                    intArrayOf(
                        opener.pos,
                        closer.pos,
                        if (closer.char == '~') {
                            STRIKE
                        } else if (use == 2) {
                            BOLD
                        } else {
                            ITALIC
                        },
                    )
                opener.count -= use
                closer.count -= use
                opener.next = closer
                closer.prev = opener
                if (opener.count == 0) unlink(opener)
                if (closer.count == 0) {
                    val next = closer.next
                    unlink(closer)
                    closer = next
                }
            } else {
                floors[key] = closer.prev?.pos ?: -1
                val next = closer.next
                if (!closer.canOpen) unlink(closer)
                closer = next
            }
        }
        return ranges
    }

    private fun fits(
        opener: Delim,
        closer: Delim,
    ): Boolean {
        if (closer.char == '~') return opener.count == closer.count
        if (!opener.canClose && !closer.canOpen) return true
        if ((opener.original + closer.original) % 3 != 0) return true
        return opener.original % 3 == 0 && closer.original % 3 == 0
    }

    private fun unlink(delim: Delim) {
        delim.prev?.next = delim.next
        delim.next?.prev = delim.prev
    }

    /** Styles every token strictly between each opener and closer; leftover delimiter characters become text. */
    private fun applyEmphasis(
        toks: List<Tok>,
        ranges: List<IntArray>,
    ): List<Tok> {
        val marks = Array(3) { IntArray(toks.size + 1) }
        for (range in ranges) {
            marks[range[2]][range[0] + 1]++
            marks[range[2]][range[1]]--
        }
        val depth = IntArray(3)
        val out = ArrayList<Tok>(toks.size)
        for (k in toks.indices) {
            for (style in 0..2) depth[style] += marks[style][k]
            when (val tok = toks[k]) {
                is Run -> {
                    if (depth[BOLD] > 0) tok.bold = true
                    if (depth[ITALIC] > 0) tok.italic = true
                    if (depth[STRIKE] > 0) tok.strike = true
                    out += tok
                }
                is Delim ->
                    if (tok.count > 0) {
                        out +=
                            Run(
                                tok.char.toString().repeat(tok.count),
                                bold = depth[BOLD] > 0,
                                italic = depth[ITALIC] > 0,
                                strike =
                                    depth[STRIKE] > 0,
                            )
                    }
                BreakTok -> out += tok
            }
        }
        return out
    }

    private fun escape(
        i: Int,
        end: Int,
    ): Parsed {
        if (i + 1 < end) {
            val next = s[i + 1]
            if (next == '\n') return Parsed(listOf(BreakTok), i + 2)
            if (asciiPunct(next)) return Parsed(listOf(Run(next.toString())), i + 2)
        }
        return Parsed(listOf(Run("\\")), i + 1)
    }

    private fun code(
        i: Int,
        end: Int,
    ): Parsed {
        val n = run(i, '`', end)
        val close = codeEnds[i]
        if (close == null || close > end) return Parsed(listOf(Run("`".repeat(n))), i + n)
        var text = s.substring(i + n, close - n).replace('\n', ' ')
        if (text.length >= 2 && text.first() == ' ' && text.last() == ' ' && text.isNotBlank()) text = text.substring(1, text.length - 1)
        return Parsed(if (text.isEmpty()) emptyList() else listOf(Run(text, code = true)), close)
    }

    private fun link(
        open: Int,
        end: Int,
        depth: Int,
        image: Boolean,
    ): Parsed? {
        val close = bracketClose[open] ?: return null
        if (close >= end) return null
        val target =
            (if (close + 1 < end && s[close + 1] == '(') destination(close + 2, end) else null)
                ?: reference(open, close, end)
                ?: return null
        val href = LinkHref.parse(target.first)
        val inner = parse(open + 1, close, depth + 1)
        if (image) {
            val alt = inner.filterIsInstance<Run>().joinToString("") { it.text }.trim()
            return Parsed(listOf(Run(imageLabel(alt), link = href)), target.second)
        }
        // An unsafe or relative link keeps its text and loses every link inside it.
        inner.forEach { if (it is Run) it.link = href }
        return Parsed(inner, target.second)
    }

    /** `(destination "title")` after `]`: the decoded destination and the index after `)`, or null. */
    private fun destination(
        from: Int,
        end: Int,
    ): Pair<String, Int>? {
        val limit = minOf(end, from + MAX_DESTINATION)
        var p = skipSpace(from, limit)
        val raw: String
        if (p < limit && s[p] == '<') {
            var q = p + 1
            while (q < limit && s[q] != '>' && s[q] != '<' && s[q] != '\n') q += if (s[q] == '\\') 2 else 1
            if (q >= limit || s[q] != '>') return null
            raw = s.substring(p + 1, q)
            p = q + 1
        } else {
            val begin = p
            var parens = 0
            while (p < limit) {
                val c = s[p]
                if (c == '\\' && p + 1 < limit && asciiPunct(s[p + 1])) {
                    p += 2
                    continue
                }
                if (c.isWhitespace() || c.isISOControl()) break
                if (c == '(') parens++
                if (c == ')') {
                    if (parens == 0) break
                    parens--
                }
                p++
            }
            if (parens != 0 || p > limit) return null
            raw = s.substring(begin, minOf(p, limit))
        }
        p = skipSpace(p, limit)
        if (p < limit && (s[p] == '"' || s[p] == '\'' || s[p] == '(')) {
            val closing = if (s[p] == '(') ')' else s[p]
            var q = p + 1
            while (q < limit && s[q] != closing) q += if (s[q] == '\\') 2 else 1
            if (q >= limit) return null
            p = skipSpace(q + 1, limit)
        }
        if (p >= limit || s[p] != ')') return null
        return decodeEntities(unescape(raw)) to p + 1
    }

    private fun skipSpace(
        from: Int,
        limit: Int,
    ): Int {
        var p = from
        while (p < limit && s[p].isWhitespace()) p++
        return p
    }

    /** `[text][label]`, `[text][]` and `[text]` when a definition has the label. */
    private fun reference(
        open: Int,
        close: Int,
        end: Int,
    ): Pair<String, Int>? {
        if (definitions.isEmpty() || close - open > MAX_LABEL) return null
        if (close + 1 < end && s[close + 1] == '[') {
            val labelClose = bracketClose[close + 1]
            if (labelClose != null && labelClose < end) {
                if (labelClose - close > MAX_LABEL) return null
                val label = s.substring(close + 2, labelClose).ifBlank { s.substring(open + 1, close) }
                return definitions[normalizeLabel(label)]?.let { it to labelClose + 1 }
            }
        }
        return definitions[normalizeLabel(s.substring(open + 1, close))]?.let { it to close + 1 }
    }

    /** `<`: a comment (hidden), an autolink, `<br>` or `<img>`. Other HTML stays text. */
    private fun angle(
        i: Int,
        end: Int,
    ): Parsed? {
        if (s.startsWith("<!--", i)) {
            val found = commentCloses.binarySearch(i + 4).let { if (it < 0) -it - 1 else it }
            val close = commentCloses.getOrElse(found) { -1 }
            return if (close < 0 || close + 3 > end) null else Parsed(emptyList(), close + 3)
        }
        val limit = minOf(end, i + MAX_DESTINATION)
        var q = i + 1
        while (q < limit && s[q] != '>' && s[q] != '<' && !s[q].isWhitespace()) q++
        if (q < limit && s[q] == '>') {
            val inner = s.substring(i + 1, q)
            if (SCHEME.matches(inner)) return Parsed(listOf(Run(inner, link = LinkHref.parse(inner))), q + 1)
            if (EMAIL.matches(inner)) return Parsed(listOf(Run(inner, link = LinkHref.parse("mailto:$inner"))), q + 1)
        }
        if (s.startsWith("<br", i, ignoreCase = true)) {
            var k = i + 3
            while (k < limit && s[k] == ' ') k++
            if (k < limit && s[k] == '/') k++
            if (k < limit && s[k] == '>') return Parsed(listOf(BreakTok), k + 1)
        }
        if (s.startsWith("<img", i, ignoreCase = true) &&
            i + 4 < limit &&
            (s[i + 4].isWhitespace() || s[i + 4] == '/' || s[i + 4] == '>')
        ) {
            val close = tagEnd(i + 4, limit) ?: return null
            val attributes = attributes(s.substring(i + 4, close))
            val href = attributes["src"]?.let { LinkHref.parse(decodeEntities(it)) }
            return Parsed(listOf(Run(imageLabel(decodeEntities(attributes["alt"].orEmpty()).trim()), link = href)), close + 1)
        }
        return null
    }

    private fun tagEnd(
        from: Int,
        limit: Int,
    ): Int? {
        var quote: Char? = null
        for (k in from until limit) {
            val c = s[k]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return k
                c == '<' -> return null
            }
        }
        return null
    }

    /** `@name`: a link to the GitHub account. A team (`@org/team`) and an email address stay text. */
    private fun mention(
        i: Int,
        end: Int,
    ): Parsed? {
        val before = if (i > 0) s[i - 1] else ' '
        if (before.isLetterOrDigit() || before in "_.-+@/`") return null
        var j = i + 1
        while (j < end && j - i <= 39 && (isAsciiLetterOrDigit(s[j]) || s[j] == '-')) j++
        val name = s.substring(i + 1, j)
        if (name.isEmpty() || name.length > 39 || name.startsWith('-') || name.endsWith('-') || "--" in name) return null
        val after = if (j < end) s[j] else ' '
        if (after.isLetterOrDigit() || after in "_-/@") return null
        return Parsed(listOf(Run("@$name", link = LinkHref.parse("https://github.com/$name"))), j)
    }

    /** `#123`: a link to that issue or pull request in this repository. */
    private fun issue(
        i: Int,
        end: Int,
    ): Parsed? {
        val before = if (i > 0) s[i - 1] else ' '
        if (before.isLetterOrDigit() || before in "_&#/") return null
        var j = i + 1
        while (j < end && s[j] in '0'..'9' && j - i <= 10) j++
        val digits = s.substring(i + 1, j)
        val after = if (j < end) s[j] else ' '
        if (digits.isEmpty() || digits.length > 9 || after.isLetterOrDigit() || after == '_') return null
        val number = digits.trimStart('0')
        if (number.isEmpty()) return null
        return Parsed(listOf(Run("#$digits", link = LinkHref.parse("https://github.com/$owner/$repo/issues/$number"))), j)
    }

    /** A bare `https://`, `http://` or `www.` link (GitHub's extended autolinks). */
    private fun bareLink(
        i: Int,
        end: Int,
    ): Parsed? {
        val before = if (i > 0) s[i - 1] else ' '
        if (!before.isWhitespace() && before !in "*_~(") return null
        val prefix =
            when {
                s.startsWith("https://", i, ignoreCase = true) -> 8
                s.startsWith("http://", i, ignoreCase = true) -> 7
                s.startsWith("www.", i, ignoreCase = true) -> 4
                else -> return null
            }
        var j = i + prefix
        while (j < end && j - i < MAX_DESTINATION && !s[j].isWhitespace() && s[j] != '<') j++
        val url = trimLinkEnd(s.substring(i, j))
        val host = url.substring(minOf(prefix, url.length)).takeWhile { it != '/' && it != '?' && it != '#' && it != ':' }
        if (host.isEmpty() || '.' !in host || host.startsWith('.') || host.endsWith('.')) return null
        if (host.any { !isAsciiLetterOrDigit(it) && it != '.' && it != '-' && it != '_' }) return null
        val href = LinkHref.parse(if (prefix == 4) "http://$url" else url)
        return Parsed(listOf(Run(url, link = href)), i + url.length)
    }

    private fun entity(
        i: Int,
        end: Int,
    ): Pair<String, Int>? = entityAt(s, i, end)
}

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]{1,31}:[^\\s<>]*$")
private val EMAIL =
    Regex(
        "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$",
    )

private fun imageLabel(alt: String): String = if (alt.isEmpty()) "Image" else "Image: $alt"

/** Trailing punctuation and an unmatched `)` are not part of a bare link (GFM). */
private fun trimLinkEnd(url: String): String {
    var out = url
    while (out.isNotEmpty()) {
        val last = out.last()
        out =
            when {
                last in "?!.,:*_~'\"" -> out.dropLast(1)
                last == ')' && out.count { it == ')' } > out.count { it == '(' } -> out.dropLast(1)
                else -> return out
            }
    }
    return out
}

private fun attributes(tag: String): Map<String, String> {
    val out = HashMap<String, String>()
    var k = 0
    while (k < tag.length) {
        while (k < tag.length && (tag[k].isWhitespace() || tag[k] == '/')) k++
        val start = k
        while (k < tag.length && !tag[k].isWhitespace() && tag[k] != '=' && tag[k] != '/') k++
        val name = tag.substring(start, k).lowercase()
        if (name.isEmpty()) {
            k++
            continue
        }
        while (k < tag.length && tag[k].isWhitespace()) k++
        var value = ""
        if (k < tag.length && tag[k] == '=') {
            k++
            while (k < tag.length && tag[k].isWhitespace()) k++
            if (k < tag.length && (tag[k] == '"' || tag[k] == '\'')) {
                val close = tag.indexOf(tag[k], k + 1).let { if (it < 0) tag.length else it }
                value = tag.substring(k + 1, close)
                k = close + 1
            } else {
                val begin = k
                while (k < tag.length && !tag[k].isWhitespace()) k++
                value = tag.substring(begin, k)
            }
        }
        out.getOrPut(name) { value }
    }
    return out
}

private fun flatten(toks: List<Tok>): List<ExternalInline> {
    val out = ArrayList<ExternalInline>()
    var style: Run? = null
    val text = StringBuilder()

    fun emit() {
        val run = style ?: return
        if (text.isNotEmpty()) out += ExternalInline.Text(text.toString(), run.bold, run.italic, run.strike, run.code, run.link)
        text.clear()
        style = null
    }

    for (tok in toks) {
        when (tok) {
            BreakTok -> {
                emit()
                out += ExternalInline.HardBreak
            }
            is Run -> {
                if (tok.text.isEmpty()) continue
                val current = style
                val same =
                    current != null &&
                        current.bold == tok.bold &&
                        current.italic == tok.italic &&
                        current.strike == tok.strike &&
                        current.code == tok.code &&
                        current.link == tok.link
                if (!same) emit()
                if (style == null) style = tok
                text.append(tok.text)
            }
            is Delim -> Unit
        }
    }
    emit()
    while (out.firstOrNull() == ExternalInline.HardBreak) out.removeAt(0)
    while (out.lastOrNull() == ExternalInline.HardBreak) out.removeAt(out.lastIndex)
    return out
}

private fun asciiPunct(c: Char): Boolean = c in ASCII_PUNCTUATION

private fun punct(c: Char): Boolean = asciiPunct(c) || c.category in PUNCTUATION

private fun isAsciiLetterOrDigit(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

private const val ASCII_PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

private val PUNCTUATION =
    setOf(
        CharCategory.CONNECTOR_PUNCTUATION,
        CharCategory.DASH_PUNCTUATION,
        CharCategory.START_PUNCTUATION,
        CharCategory.END_PUNCTUATION,
        CharCategory.INITIAL_QUOTE_PUNCTUATION,
        CharCategory.FINAL_QUOTE_PUNCTUATION,
        CharCategory.OTHER_PUNCTUATION,
        CharCategory.MATH_SYMBOL,
        CharCategory.CURRENCY_SYMBOL,
        CharCategory.MODIFIER_SYMBOL,
        CharCategory.OTHER_SYMBOL,
    )

/** Removes the backslash before ASCII punctuation. */
private fun unescape(text: String): String {
    if ('\\' !in text) return text
    val out = StringBuilder()
    var k = 0
    while (k < text.length) {
        if (text[k] == '\\' && k + 1 < text.length && asciiPunct(text[k + 1])) k++
        out.append(text[k])
        k++
    }
    return out.toString()
}

private fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    val out = StringBuilder()
    var k = 0
    while (k < text.length) {
        val entity = if (text[k] == '&') entityAt(text, k, text.length) else null
        if (entity != null) {
            out.append(entity.first)
            k = entity.second
        } else {
            out.append(text[k])
            k++
        }
    }
    return out.toString()
}

/** `&name;`, `&#123;` or `&#x1F600;` at [i]: the text it stands for and the index after `;`, or null. */
private fun entityAt(
    s: String,
    i: Int,
    end: Int,
): Pair<String, Int>? {
    // A bounded search: a long run of `&` without `;` must not scan the rest of the text each time.
    val limit = minOf(end, i + 33)
    var semi = i + 1
    while (semi < limit && s[semi] != ';') semi++
    if (semi >= limit) return null
    val body = s.substring(i + 1, semi)
    val text =
        when {
            body.startsWith("#x") || body.startsWith("#X") ->
                body
                    .substring(2)
                    .takeIf { it.length in 1..6 && it.all(::isHexDigit) }
                    ?.toInt(16)
                    ?.let(::codePoint)
            body.startsWith("#") ->
                body
                    .substring(1)
                    .takeIf {
                        it.length in 1..7 &&
                            it.all { c ->
                                c in '0'..'9'
                            }
                    }?.toInt()
                    ?.let(::codePoint)
            else -> NAMED_ENTITIES[body]
        }
    return text?.let { it to semi + 1 }
}

private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

private fun codePoint(value: Int): String {
    if (value == 0 || value > 0x10FFFF || value in 0xD800..0xDFFF) return "�"
    if (value < 0x10000) return value.toChar().toString()
    val v = value - 0x10000
    return charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
}

private val NAMED_ENTITIES =
    mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
        "hellip" to "…",
        "mdash" to "—",
        "ndash" to "–",
        "larr" to "←",
        "rarr" to "→",
        "uarr" to "↑",
        "darr" to "↓",
        "times" to "×",
        "middot" to "·",
        "bull" to "•",
        "laquo" to "«",
        "raquo" to "»",
        "deg" to "°",
    )
