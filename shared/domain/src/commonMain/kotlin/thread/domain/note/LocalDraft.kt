package thread.domain.note

import kotlin.jvm.JvmInline

@JvmInline
value class NoteId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A note id is not blank" }
    }

    override fun toString(): String = value
}

/**
 * The newest content of a Note on this device. It may not be synced yet.
 *
 * [baseRevisionId] is the newest server-acknowledged revision this content was edited from, or null before the
 * Note first synced (adr/ADR-004-note-revisions-and-conflicts.md). The next revision this device writes uses it as
 * its parent.
 */
data class LocalDraft(
    val noteId: NoteId,
    val title: String,
    val body: RichTextDocument,
    val baseRevisionId: RevisionId? = null,
) {
    /** True when title and body are the same. The base revision is not compared. */
    fun hasSameContentAs(other: LocalDraft): Boolean = title == other.title && body == other.body

    /** True when title and body are the same as [revision]'s. */
    fun hasSameContentAs(revision: NoteRevision): Boolean = title == revision.title && body == revision.body
}

/**
 * Device-local, durable storage for [LocalDraft]s. Platform code implements it (IndexedDB on the web).
 *
 * Implementations start the write before their first suspension point. A flush that runs while the page is being
 * hidden or closed then reaches storage even if no later task runs.
 */
interface LocalDraftStore {
    /** Returns the stored draft, or null when there is none. Throws [LocalDraftStoreException] when reading fails. */
    suspend fun read(noteId: NoteId): LocalDraft?

    /**
     * Stores [draft], replacing the previous one for the same Note. Returns only after the storage reports the write
     * as durably committed. Throws [LocalDraftStoreException] when it is not.
     */
    suspend fun write(draft: LocalDraft)
}

class LocalDraftStoreException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
