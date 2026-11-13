package thread.domain.note

import kotlin.jvm.JvmInline

/** The id of one saved version of a Note. Made on the device that writes it, so a retried write never duplicates. */
@JvmInline
value class RevisionId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A revision id is not blank" }
    }

    override fun toString(): String = value
}

/** A random id for one browser profile or app install. Not tied to the person. */
@JvmInline
value class DeviceId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A device id is not blank" }
    }

    override fun toString(): String = value
}

/**
 * One saved version of a Note, as this device sees it on the server (adr/ADR-004-note-revisions-and-conflicts.md).
 *
 * Revisions are never changed after they are written, except that a conflict resolution marks one [discarded].
 * [parentId] is the revision the content was edited from, or null for a Note's first version.
 *
 * [acknowledged] is false while this device's own write of the revision is still waiting for the server.
 * [editedAtMillis] is the server time of the write (estimated by the device while it is not acknowledged), or null
 * when unknown.
 */
data class NoteRevision(
    val id: RevisionId,
    val parentId: RevisionId?,
    val title: String,
    val body: RichTextDocument,
    val deviceId: DeviceId,
    val editedAtMillis: Long?,
    val acknowledged: Boolean,
    val discarded: Boolean,
)

/**
 * All known revisions of one Note.
 *
 * A leaf is a revision that is not discarded and that no other revision, discarded or not, uses as its parent. One
 * leaf means one current version. More than one leaf means two devices edited from the same version: a conflict.
 * A discarded revision's children still count, so discarding one side of a conflict never brings an older version
 * back as a leaf.
 */
class RevisionHistory(
    val revisions: List<NoteRevision>,
) {
    private val byId: Map<RevisionId, NoteRevision> = revisions.associateBy { it.id }
    private val parents: Set<RevisionId> = revisions.mapNotNullTo(mutableSetOf()) { it.parentId }

    /** The current versions, newest first. */
    val leaves: List<NoteRevision> =
        revisions
            .filter { !it.discarded && it.id !in parents }
            .sortedByDescending { it.editedAtMillis ?: Long.MAX_VALUE }

    operator fun get(id: RevisionId): NoteRevision? = byId[id]

    /** [id]'s revision and its known ancestors, nearest first. Stops at a missing parent. */
    fun ancestorsOrSelf(id: RevisionId): List<NoteRevision> {
        val chain = mutableListOf<NoteRevision>()
        val seen = mutableSetOf<RevisionId>()
        var current = byId[id]
        while (current != null && seen.add(current.id)) {
            chain += current
            current = current.parentId?.let(byId::get)
        }
        return chain
    }
}
