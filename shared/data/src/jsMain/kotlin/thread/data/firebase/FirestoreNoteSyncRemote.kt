package thread.data.firebase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import thread.data.local.newJsObject
import thread.domain.account.AccountId
import thread.domain.note.DeviceId
import thread.domain.note.NewRevision
import thread.domain.note.NoteCopy
import thread.domain.note.NoteHead
import thread.domain.note.NoteId
import thread.domain.note.NoteRevision
import thread.domain.note.NoteSyncRejectedException
import thread.domain.note.NoteSyncRemote
import thread.domain.note.RemoteNote
import thread.domain.note.RevisionHistory
import thread.domain.note.RevisionId
import thread.domain.note.RichTextDocument
import thread.domain.note.RichTextFormatException
import thread.domain.note.RichTextJson
import thread.domain.note.notePreview

/**
 * [NoteSyncRemote] on Cloud Firestore for one account (adr/ADR-004-note-revisions-and-conflicts.md).
 *
 * - `users/{uid}/notes/{noteId}`: `{title, preview, headRevisionId, updatedAt, schemaVersion: 1, deletedAt?, copiedFrom?}`.
 *   `deletedAt` set means the Note is in Trash. `title` and `preview` follow the head version, for the Notes listing
 *   (adr/ADR-011-notes-in-the-workspace.md). The workspace store writes the other fields of this document (`inbox`,
 *   `discarded`, `createdAt`); writes here never name them, and merges keep them.
 * - `users/{uid}/notes/{noteId}/revisions/{revisionId}`: `{parentRevisionId, title, body, schemaVersion: 1, deviceId,
 *   createdAt, discardedAt?}`. `body` is the RichText JSON string. Revisions are deleted only after the Note was deleted
 *   forever, when its document is the tombstone `{purgedAt, schemaVersion: 1}` (adr/ADR-012-trash-and-search.md).
 *
 * Every write is one batch, applied to the local cache at once and acknowledged per document. A revision counts as
 * acknowledged when its document has no pending writes. Only app-owned content is written: no Jira, GitHub or other
 * external data.
 *
 * [rejectWritesForTesting] is a test hook (E3). When it returns true, a pushed revision carries a field the security
 * rules do not allow, so the server refuses the write and the real rejection path runs.
 */
class FirestoreNoteSyncRemote internal constructor(
    private val firestore: Firestore,
    private val accountId: AccountId,
    private val rejectWritesForTesting: () -> Boolean,
) : NoteSyncRemote {
    constructor(
        backend: FirebaseBackend,
        accountId: AccountId,
        rejectWritesForTesting: () -> Boolean = { false },
    ) : this(backend.firestore, accountId, rejectWritesForTesting)

    override fun observe(noteId: NoteId): Flow<RemoteNote> =
        callbackFlow {
            var note: DocumentSnapshot? = null
            var revisions: QuerySnapshot? = null
            val emitWhenComplete = {
                val noteSnapshot = note
                val revisionSnapshot = revisions
                if (noteSnapshot != null && revisionSnapshot != null) trySend(toRemoteNote(noteSnapshot, revisionSnapshot))
            }
            val stop = { error: FirestoreError ->
                close(NoteSyncRejectedException("Listening failed (${error.code})"))
                Unit
            }
            val options = newJsObject()
            options.includeMetadataChanges = true
            val stopNote =
                onDocumentSnapshot(noteRef(noteId), options, {
                    note = it
                    emitWhenComplete()
                }, stop)
            val stopRevisions =
                onCollectionSnapshot(revisionsRef(noteId), options, {
                    revisions = it
                    emitWhenComplete()
                }, stop)
            awaitClose {
                stopNote()
                stopRevisions()
            }
        }.conflate()

    override suspend fun push(
        noteId: NoteId,
        revision: NewRevision,
    ) {
        val batch = writeBatch(firestore)
        val data = revisionData(revision)
        if (rejectWritesForTesting()) data.rejectedForTesting = true
        batch.set(revisionRef(noteId, revision.id), data)
        batch.set(noteRef(noteId), headData(revision.id, revision.title, revision.body), merge())
        commit(batch)
    }

    override suspend fun resolveConflict(
        noteId: NoteId,
        keep: NoteHead?,
        discard: List<RevisionId>,
        copy: NoteCopy,
    ) {
        val batch = writeBatch(firestore)
        discard.forEach { id ->
            val change = newJsObject()
            change.discardedAt = serverTimestamp()
            batch.update(revisionRef(noteId, id), change)
        }
        if (keep != null) batch.set(noteRef(noteId), headData(keep.id, keep.title, keep.body), merge())
        val first = copy.firstRevision
        batch.set(revisionRef(copy.id, first.id), revisionData(first))
        val copyNote = headData(first.id, first.title, first.body)
        copyNote.deletedAt = if (copy.inTrash) serverTimestamp() else null
        val copiedFrom = newJsObject()
        copiedFrom.noteId = noteId.value
        copiedFrom.revisionId = copy.copiedFrom?.value
        copyNote.copiedFrom = copiedFrom
        batch.set(noteRef(copy.id), copyNote, merge())
        commit(batch)
    }

    override suspend fun setInTrash(
        noteId: NoteId,
        inTrash: Boolean,
    ) {
        val change = newJsObject()
        change.deletedAt = if (inTrash) serverTimestamp() else null
        change.updatedAt = serverTimestamp()
        change.schemaVersion = SCHEMA_VERSION
        val batch = writeBatch(firestore)
        batch.set(noteRef(noteId), change, merge())
        commit(batch)
    }

    override suspend fun deleteVersions(noteId: NoteId): Int {
        val versions =
            try {
                getDocsFromServer(revisionsRef(noteId)).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw NoteSyncRejectedException("Reading the versions failed (${errorCode(e)})", e)
            }
        versions.docs.toList().chunked(DELETE_BATCH_SIZE).forEach { chunk ->
            val batch = writeBatch(firestore)
            chunk.forEach { batch.delete(it.ref) }
            commit(batch)
        }
        return versions.docs.size
    }

    private suspend fun commit(batch: WriteBatch) {
        try {
            batch.commit().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Firestore retries network problems itself. A rejected commit is a refusal, for example by the rules.
            throw NoteSyncRejectedException("The server refused the write (${errorCode(e)})", e)
        }
    }

    private fun noteRef(noteId: NoteId) = doc(firestore, USERS, accountId.value, NOTES, noteId.value)

    private fun revisionsRef(noteId: NoteId) = collection(firestore, USERS, accountId.value, NOTES, noteId.value, REVISIONS)

    private fun revisionRef(
        noteId: NoteId,
        revisionId: RevisionId,
    ) = doc(firestore, USERS, accountId.value, NOTES, noteId.value, REVISIONS, revisionId.value)

    private fun revisionData(revision: NewRevision): dynamic {
        val data = newJsObject()
        data.parentRevisionId = revision.parentId?.value
        data.title = revision.title
        data.body = RichTextJson.encode(revision.body)
        data.schemaVersion = SCHEMA_VERSION
        data.deviceId = revision.deviceId.value
        data.createdAt = serverTimestamp()
        return data
    }

    private fun headData(
        head: RevisionId,
        title: String,
        body: RichTextDocument,
    ): dynamic {
        val data = newJsObject()
        data.title = title
        data.preview = notePreview(body)
        data.headRevisionId = head.value
        data.updatedAt = serverTimestamp()
        data.schemaVersion = SCHEMA_VERSION
        return data
    }

    private fun merge(): dynamic {
        val options = newJsObject()
        options.merge = true
        return options
    }

    private companion object {
        const val USERS = "users"
        const val NOTES = "notes"
        const val REVISIONS = "revisions"
        const val SCHEMA_VERSION = 1

        /** Firestore allows 500 writes in one batch. */
        const val DELETE_BATCH_SIZE = 400
    }
}

private fun toRemoteNote(
    note: DocumentSnapshot,
    revisions: QuerySnapshot,
): RemoteNote {
    val decoded = revisions.docs.map(::decodeRevision)
    val noteData: dynamic = if (note.exists()) note.data(estimatedTimestamps()) else null
    return RemoteNote(
        exists = noteData != null && noteData.purgedAt == null,
        inTrash = noteData != null && noteData.deletedAt != null,
        history = RevisionHistory(decoded.filterNotNull()),
        fromServer = !note.metadata.fromCache && !revisions.metadata.fromCache,
        unreadableRevisions = decoded.count { it == null },
    )
}

/** The revision in [snapshot], or null when this app cannot read it (another schema version, or damaged). */
private fun decodeRevision(snapshot: DocumentSnapshot): NoteRevision? {
    val data: dynamic = snapshot.data(estimatedTimestamps())
    if (data == null || data.schemaVersion != 1) return null
    val title: dynamic = data.title
    val body: dynamic = data.body
    val deviceId: dynamic = data.deviceId
    val parent: dynamic = data.parentRevisionId
    if (!isNonBlankString(deviceId) || jsTypeOf(title) != "string" || jsTypeOf(body) != "string") return null
    if (parent != null && !isNonBlankString(parent)) return null
    val document =
        try {
            RichTextJson.decode(body.unsafeCast<String>())
        } catch (e: RichTextFormatException) {
            return null
        }
    val createdAt: dynamic = data.createdAt
    return NoteRevision(
        id = RevisionId(snapshot.id),
        parentId = if (parent == null) null else RevisionId(parent.unsafeCast<String>()),
        title = title.unsafeCast<String>(),
        body = document,
        deviceId = DeviceId(deviceId.unsafeCast<String>()),
        editedAtMillis = if (createdAt == null) null else createdAt.unsafeCast<Timestamp>().toMillis().toLong(),
        acknowledged = !snapshot.metadata.hasPendingWrites,
        discarded = data.discardedAt != null,
    )
}

private fun isNonBlankString(value: dynamic): Boolean = jsTypeOf(value) == "string" && value.unsafeCast<String>().isNotBlank()

/** Pending server timestamps read as the device's estimate instead of null, so Trash and order show at once. */
private fun estimatedTimestamps(): dynamic {
    val options = newJsObject()
    options.serverTimestamps = "estimate"
    return options
}
