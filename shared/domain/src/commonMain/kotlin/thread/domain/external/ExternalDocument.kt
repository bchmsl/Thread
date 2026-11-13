package thread.domain.external

import thread.domain.note.RichTextBlock

/**
 * Rich text from Jira, shown read-only (adr/ADR-006-jira-access-path.md).
 *
 * It reuses the RichText v1 blocks of Notes and adds the two kinds of block that Notes do not have: code, and a
 * placeholder for content Thread does not show. It is never stored as a Note and never written to the server. An
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
}
