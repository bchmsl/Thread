package thread.domain.note

import kotlinx.coroutines.flow.Flow

/**
 * The server side of Note sync for one signed-in account (adr/ADR-002-local-store-and-sync.md). Platform code
 * implements it (Firestore on the web).
 *
 * Writes are applied to the device's local copy of the server data at once, also offline, and sent when the device
 * is online. The suspend functions return only when the server acknowledged the write, so they can wait for a long
 * time offline. They throw [NoteSyncRejectedException] when the server refuses the write; the local copy then drops
 * the write again.
 */
interface NoteSyncRemote {
    /** The server state of [noteId] as this device knows it, including its own writes that are not acknowledged. */
    fun observe(noteId: NoteId): Flow<RemoteNote>

    /** Writes [revision] and makes it the Note's newest version. */
    suspend fun push(
        noteId: NoteId,
        revision: NewRevision,
    )

    /**
     * Resolves a conflict in one atomic write: marks [discard] as discarded, makes [keep] the Note's newest version,
     * and saves [copy] as a separate Note (adr/ADR-004-note-revisions-and-conflicts.md, D41).
     */
    suspend fun resolveConflict(
        noteId: NoteId,
        keep: RevisionId?,
        discard: List<RevisionId>,
        copy: NoteCopy,
    )

    /** Moves the Note to Trash, or back out of it. Revisions are kept. */
    suspend fun setInTrash(
        noteId: NoteId,
        inTrash: Boolean,
    )
}

/**
 * One Note on the server as this device sees it.
 *
 * [fromServer] is false while the state comes only from the device's local copy (offline, or before the first
 * answer). [unreadableRevisions] counts revisions this app cannot read, for example from a newer RichText version.
 */
data class RemoteNote(
    val exists: Boolean,
    val inTrash: Boolean,
    val history: RevisionHistory,
    val fromServer: Boolean,
    val unreadableRevisions: Int = 0,
)

/** A revision this device is about to write. */
data class NewRevision(
    val id: RevisionId,
    val parentId: RevisionId?,
    val title: String,
    val body: RichTextDocument,
    val deviceId: DeviceId,
)

/**
 * A Note made from one side of a conflict: in Trash for the version not kept, or a live Note for "Keep both".
 * [id] is derived from the version it copies where possible, so two devices that resolve the same way write the
 * same Note.
 */
data class NoteCopy(
    val id: NoteId,
    val firstRevision: NewRevision,
    val inTrash: Boolean,
    val copiedFrom: RevisionId?,
)

/** The server refused a write, for example because of the security rules. Retrying the same write will not help. */
class NoteSyncRejectedException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
