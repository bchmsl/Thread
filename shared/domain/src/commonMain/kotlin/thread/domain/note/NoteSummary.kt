package thread.domain.note

import thread.domain.topic.TopicId

/**
 * A Note as the workspace lists it (adr/ADR-011-notes-in-the-workspace.md). Its content lives in its revisions and
 * syncs through [NoteSyncer]; this is the listing: the newest version's title and preview, and where the Note is filed.
 *
 * - [inbox]: the Note waits in Inbox (D39). Linking it to a Topic or keeping it standalone clears the flag.
 * - [inTrash]: moved to Trash. Its text and versions stay.
 * - [discarded]: taken back right after it was made (the Undo of its toast). It leaves every view, Trash included.
 * - [pendingWrites]: this device changed the listing and the server has not acknowledged it yet.
 * - [fromConflict]: the Note was made from the version not kept in a conflict (D41).
 */
data class NoteSummary(
    val id: NoteId,
    val title: String = "",
    val preview: String = "",
    val inbox: Boolean = false,
    val inTrash: Boolean = false,
    val discarded: Boolean = false,
    val createdAtMillis: Long? = null,
    val updatedAtMillis: Long? = null,
    val pendingWrites: Boolean = false,
    val fromConflict: Boolean = false,
    /** When it went to Trash, for the Trash page. */
    val deletedAtMillis: Long? = null,
)

/**
 * One Topic link of a Note (04-data-and-sync.md, TopicNoteLink). Its id is made from the pair, so linking the same
 * pair twice, on one device or two, writes the same document. Unlinking sets [linked] to false: neither the Note nor
 * the Topic is deleted, and the link document stays.
 */
data class NoteTopicLink(
    val topicId: TopicId,
    val noteId: NoteId,
    val linked: Boolean = true,
) {
    val id: String get() = noteTopicLinkId(topicId, noteId)
}

/** The document id of the link between [topicId] and [noteId]. Ids never contain `_`. */
fun noteTopicLinkId(
    topicId: TopicId,
    noteId: NoteId,
): String = "${topicId.value}_${noteId.value}"

/** Shown for a Note without a title. */
const val UNTITLED_NOTE: String = "Untitled note"
