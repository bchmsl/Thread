package thread.domain.note

import kotlin.jvm.JvmInline

/**
 * Rich text of a Note, schema version 1 (adr/ADR-003-editor-and-richtext.md).
 *
 * This is the stored model. It does not depend on an editor. Editor adapters map their own document format to and
 * from it, and [RichTextJson] stores it.
 *
 * A document always has at least one block. An empty note is one empty [RichTextBlock.Paragraph].
 */
data class RichTextDocument(
    val blocks: List<RichTextBlock>,
) {
    init {
        require(blocks.isNotEmpty()) { "A document has at least one block" }
    }

    companion object {
        val EMPTY: RichTextDocument = RichTextDocument(listOf(RichTextBlock.Paragraph(emptyList())))
    }
}

sealed interface RichTextBlock {
    data class Paragraph(
        val content: List<RichTextInline>,
    ) : RichTextBlock

    data class Heading(
        val level: Int,
        val content: List<RichTextInline>,
    ) : RichTextBlock {
        init {
            require(level in LEVELS) { "Heading level must be in $LEVELS, was $level" }
        }

        companion object {
            val LEVELS: IntRange = 1..3
        }
    }

    data class BulletList(
        val items: List<ListItem>,
    ) : RichTextBlock {
        init {
            require(items.isNotEmpty()) { "A list has at least one item" }
        }
    }

    data class OrderedList(
        val start: Int,
        val items: List<ListItem>,
    ) : RichTextBlock {
        init {
            require(items.isNotEmpty()) { "A list has at least one item" }
        }
    }

    /** A checklist. Its items are text, not Tasks (01-product-spec.md). */
    data class TaskList(
        val items: List<TaskItem>,
    ) : RichTextBlock {
        init {
            require(items.isNotEmpty()) { "A list has at least one item" }
        }
    }
}

/** A list item. It starts with a paragraph and may hold more blocks, such as a nested list. */
data class ListItem(
    val blocks: List<RichTextBlock>,
) {
    init {
        requireStartsWithParagraph(blocks)
    }
}

/** A checklist item. Same shape as [ListItem], plus a checked state. */
data class TaskItem(
    val checked: Boolean,
    val blocks: List<RichTextBlock>,
) {
    init {
        requireStartsWithParagraph(blocks)
    }
}

private fun requireStartsWithParagraph(blocks: List<RichTextBlock>) {
    require(blocks.firstOrNull() is RichTextBlock.Paragraph) { "A list item starts with a paragraph" }
}

sealed interface RichTextInline {
    /** A run of text with the same formatting. [text] is never empty. */
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val link: LinkHref? = null,
    ) : RichTextInline {
        init {
            require(text.isNotEmpty()) { "Text is never empty" }
        }
    }

    data object HardBreak : RichTextInline
}

/**
 * A link target that Notes allow: an `http://`, `https://` or `mailto:` address without spaces or control
 * characters. Other schemes, such as `javascript:` or `data:`, are refused. Create it with [parse].
 */
@JvmInline
value class LinkHref private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val ALLOWED_PREFIXES = listOf("https://", "http://", "mailto:")

        /** Returns the link for [raw] after trimming, or null when Notes do not allow it. */
        fun parse(raw: String): LinkHref? {
            val href = raw.trim()
            val prefix = ALLOWED_PREFIXES.firstOrNull { href.startsWith(it, ignoreCase = true) } ?: return null
            if (href.length == prefix.length) return null
            if (href.any { it.isWhitespace() || it.isISOControl() }) return null
            return LinkHref(href)
        }
    }
}
