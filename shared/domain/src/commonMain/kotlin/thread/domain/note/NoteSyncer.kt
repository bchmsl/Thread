package thread.domain.note

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What sync knows about one Note, for its status line and banners (02-ux-design.md, "Quiet autosave indicator"). */
data class NoteSyncState(
    /** The server state was seen at least once, from the device's local copy or from the server. */
    val loaded: Boolean = false,
    /** The newest local content is on the server: a server-acknowledged revision has exactly this content. */
    val synced: Boolean = false,
    /** A revision was written and the server has not acknowledged it yet. */
    val pushing: Boolean = false,
    /** The server refused a write. Edits stay on this device; nothing is written until [NoteSyncer.retry]. */
    val rejected: Boolean = false,
    /** Another current version of the Note, made on another device, or null. */
    val conflict: NoteRevision? = null,
    val inTrash: Boolean = false,
    /** The server has versions this app cannot read. Writing is paused, so nothing is saved over them. */
    val unreadable: Boolean = false,
)

/** The choices of the conflict view (v3 notes page, D41). */
enum class ConflictChoice {
    KeepThisDevice,
    KeepOther,
    KeepBoth,
}

/**
 * Syncs one Note between [drafts] (this device) and [remote] (the server), following
 * adr/ADR-004-note-revisions-and-conflicts.md.
 *
 * - Each write is a new revision with a new id and the device's base revision as its parent. Only one write waits
 *   for the server at a time. Edits made meanwhile go into the next revision.
 * - A revision becomes the base only after the server acknowledged it, so [NoteSyncState.synced] never claims more
 *   than the server confirmed.
 * - When the server has a newer version that already contains everything this device has, it replaces the local
 *   content ([onContentReplaced]). When this device has edits the server lacks, they are written as a revision of
 *   their own. If another device also edited, that makes a conflict that every device sees and that the person
 *   resolves. Nothing is overwritten silently.
 * - A refused write stops writing until [retry]. The text stays in [drafts].
 *
 * [hasLocalWork] is false when the local content is not the person's work, for example on a device that had no
 * stored draft. Such content never competes with the server's: the server's newest version replaces it, and it is
 * written only when the server confirmed that the Note has no versions yet. The first [localEdited] makes it the
 * person's work.
 *
 * Call [localEdited] after every [DraftAutosaver.edit] that changed the content. Not thread-safe: use it from one
 * thread, with a [scope] on a single-threaded dispatcher (the UI thread).
 */
class NoteSyncer(
    private val drafts: DraftAutosaver,
    private val remote: NoteSyncRemote,
    private val deviceId: DeviceId,
    private val scope: CoroutineScope,
    hasLocalWork: Boolean,
    private val newRevisionId: () -> RevisionId,
    private val onContentReplaced: (LocalDraft) -> Unit,
    private val pushDebounce: Duration = DEFAULT_PUSH_DEBOUNCE,
    private val pushMaxWait: Duration = DEFAULT_PUSH_MAX_WAIT,
) {
    private val noteId: NoteId = drafts.current.noteId
    private var untouched = !hasLocalWork

    private var history: RevisionHistory? = null
    private var fromServer = false
    private var inTrash = false
    private var unreadable = false
    private var conflict: NoteRevision? = null
    private var rejected = false

    // The revision written and not yet acknowledged, and whether the device's local copy has shown it yet.
    private var inFlight: RevisionId? = null
    private var inFlightSeen = false

    private var listener: Job? = null
    private var debounceJob: Job? = null
    private var maxWaitJob: Job? = null

    private val mutableState = MutableStateFlow(NoteSyncState())
    val state: StateFlow<NoteSyncState> = mutableState.asStateFlow()

    /** Starts listening to the server. Call once. */
    fun start() {
        check(listener == null) { "Already started" }
        listen()
    }

    /** Stops listening and cancels pending timers. A write already sent stays queued in the device's local copy. */
    fun stop() {
        listener?.cancel()
        cancelTimers()
    }

    /** Schedules a write of the newest local content. */
    fun localEdited() {
        untouched = false
        publish()
        debounceJob?.cancel()
        debounceJob =
            scope.launch {
                delay(pushDebounce)
                flush()
            }
        if (maxWaitJob == null) {
            maxWaitJob =
                scope.launch {
                    delay(pushMaxWait)
                    flush()
                }
        }
    }

    /** Writes the newest local content now if the server does not have it. */
    fun flush() {
        cancelTimers()
        val known = history ?: return
        if (needsPush(known) && !untouched) push()
    }

    /** Clears a refused write and tries again with the newest local content. */
    fun retry() {
        rejected = false
        if (listener?.isActive != true) listen()
        history?.let(::reconcile)
        flush()
        publish()
    }

    /**
     * Resolves the shown conflict as the person chose (D41). Returns the id of the Note made from the version not
     * kept (in Trash) or, for [ConflictChoice.KeepBoth], of the second Note. Returns null when there is no conflict
     * or this device's newest write is not in its local copy yet.
     */
    fun resolveConflict(choice: ConflictChoice): NoteId? {
        val known = history ?: return null
        val other = conflict ?: return null
        val pending = inFlight
        if (pending != null && known[pending] == null) return null
        val local = drafts.current
        val mine = pending ?: local.baseRevisionId
        val copy: NoteCopy
        if (choice == ConflictChoice.KeepOther) {
            // This device's version is its newest local content, which can be newer than its last revision.
            copy =
                NoteCopy(
                    id = NoteId("$CONFLICT_COPY_PREFIX${newRevisionId()}"),
                    firstRevision = NewRevision(newRevisionId(), null, local.title, local.body, deviceId),
                    inTrash = true,
                    copiedFrom = mine,
                )
            val discard = known.leaves.filter { it.id != other.id }.map { it.id }
            inFlight = null
            val keep = NoteHead(other.id, other.title, other.body)
            write { remote.resolveConflict(noteId, keep = keep, discard = discard, copy = copy) }
            replaceContent(other)
        } else {
            // The copy's ids and content come only from the other version, so two devices that resolve the same way
            // write the same copy instead of two.
            val keepBoth = choice == ConflictChoice.KeepBoth
            val title = if (keepBoth) "${other.title.ifBlank { UNTITLED }} $OTHER_DEVICE_COPY_SUFFIX" else other.title
            copy =
                NoteCopy(
                    id = NoteId("$CONFLICT_COPY_PREFIX${other.id}"),
                    firstRevision = NewRevision(other.id, null, title, other.body, other.deviceId),
                    inTrash = !keepBoth,
                    copiedFrom = other.id,
                )
            // The listing follows the kept version. Newer local content is written as its own revision after this.
            val kept = mine?.let { id -> known[id]?.let { NoteHead(id, it.title, it.body) } ?: NoteHead(id, local.title, local.body) }
            write { remote.resolveConflict(noteId, keep = kept, discard = listOf(other.id), copy = copy) }
        }
        untouched = false
        conflict = null
        publish()
        return copy.id
    }

    /** Moves the Note to Trash, or restores it. Its content and versions stay. */
    fun setInTrash(trash: Boolean) {
        inTrash = trash
        write { remote.setInTrash(noteId, trash) }
        publish()
    }

    private fun listen() {
        listener =
            scope.launch {
                try {
                    remote.observe(noteId).collect(::onRemote)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // For example permission denied after the account changed. Shown as a sync failure.
                    rejected = true
                    publish()
                }
            }
    }

    private fun onRemote(note: RemoteNote) {
        val known = note.history
        history = known
        fromServer = note.fromServer
        inTrash = note.inTrash
        unreadable = note.unreadableRevisions > 0
        trackOwnWrite(known)
        reconcile(known)
        publish()
    }

    private fun trackOwnWrite(known: RevisionHistory) {
        val pending = inFlight
        if (pending == null) {
            // After a reload, this device's last write can still wait in the local copy. Wait for it instead of
            // writing the same content again.
            known.revisions
                .firstOrNull { it.deviceId == deviceId && !it.acknowledged && !it.discarded }
                ?.let {
                    inFlight = it.id
                    inFlightSeen = true
                }
            return
        }
        val revision = known[pending]
        when {
            revision == null ->
                if (inFlightSeen) {
                    // The local copy dropped the write: the server refused it.
                    inFlight = null
                    rejected = true
                }
            revision.acknowledged -> {
                inFlight = null
                if (!revision.discarded) drafts.rebase(pending)
            }
            else -> inFlightSeen = true
        }
    }

    private fun reconcile(known: RevisionHistory) {
        conflict = null
        if (unreadable) return
        val leaves = known.leaves
        val mine = findOwnVersion(known, leaves)
        conflict = if (mine != null) leaves.firstOrNull { it != mine } else null
        if (!needsPush(known)) return
        if (untouched) {
            // Only a Note the server confirmed as new gets its first version from content that is not the person's.
            if (fromServer && known.revisions.isEmpty()) push()
            return
        }
        // Local edits the server lacks. Without a current version of its own, this device writes at once, so a
        // conflict shows on every device. Otherwise the edit timers decide.
        if (mine == null || (debounceJob?.isActive != true && maxWaitJob?.isActive != true)) push()
    }

    /**
     * The current version this device's content is based on, or null when there is none. Adopts a server version
     * as the base, or as the new local content, where that loses nothing.
     */
    private fun findOwnVersion(
        known: RevisionHistory,
        leaves: List<NoteRevision>,
    ): NoteRevision? {
        val local = drafts.current
        val pending = leaves.firstOrNull { it.id == inFlight }
        val base = leaves.firstOrNull { it.id == local.baseRevisionId }
        if (untouched) {
            // Content that is not the person's work shows the server's version. When no version is current (two
            // devices discarded each other's), the newest one still shows.
            val shown =
                pending ?: base ?: leaves.firstOrNull() ?: known.revisions.maxByOrNull { it.editedAtMillis ?: Long.MAX_VALUE }
            if (shown != null && !local.hasSameContentAs(shown)) {
                replaceContent(shown)
            } else if (shown != null && shown.acknowledged) {
                drafts.rebase(shown.id)
            }
            return shown
        }
        if (inFlight != null) return pending ?: base
        if (base != null) return base
        // Same content as a current version: nothing to write, that version is the base.
        leaves.firstOrNull { local.hasSameContentAs(it) }?.let {
            drafts.rebase(it.id)
            return it
        }
        return leaves.firstOrNull { includesLocalContent(known, it, local) }?.also(::replaceContent)
    }

    /**
     * True when [leaf] already has all of this device's content: the local content equals [leaf] or one of its
     * ancestors, or it equals a base revision that a conflict resolution discarded (its content went to another
     * Note).
     */
    private fun includesLocalContent(
        known: RevisionHistory,
        leaf: NoteRevision,
        local: LocalDraft,
    ): Boolean {
        if (known.ancestorsOrSelf(leaf.id).any { local.hasSameContentAs(it) }) return true
        val base = local.baseRevisionId?.let(known::get)
        return base != null && base.discarded && local.hasSameContentAs(base)
    }

    /**
     * True when the server lacks the local content as a current version. A discarded base counts as lacking it:
     * when no other version replaced it, the content is written again so it stays current.
     */
    private fun needsPush(known: RevisionHistory): Boolean {
        if (inFlight != null || rejected || unreadable) return false
        val local = drafts.current
        val base = local.baseRevisionId?.let(known::get)
        return base == null || base.discarded || !local.hasSameContentAs(base)
    }

    private fun push() {
        cancelTimers()
        val local = drafts.current
        val id = newRevisionId()
        inFlight = id
        inFlightSeen = false
        val revision = NewRevision(id, local.baseRevisionId, local.title, local.body, deviceId)
        publish()
        scope.launch {
            try {
                // The acknowledgement is read from the server state (onRemote). That also covers a write that was
                // queued before a reload, when no call is waiting for it.
                remote.push(noteId, revision)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (inFlight == id) {
                    inFlight = null
                    rejected = true
                    publish()
                }
            }
        }
    }

    private fun write(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                rejected = true
                publish()
            }
        }
    }

    /** Shows [revision]'s content. It becomes the base only when acknowledged: this device's own write may not be. */
    private fun replaceContent(revision: NoteRevision) {
        val base = if (revision.acknowledged) revision.id else drafts.current.baseRevisionId
        val draft = LocalDraft(noteId, revision.title, revision.body, base)
        drafts.edit(draft)
        onContentReplaced(draft)
    }

    private fun cancelTimers() {
        debounceJob?.cancel()
        maxWaitJob?.cancel()
        debounceJob = null
        maxWaitJob = null
    }

    private fun publish() {
        val known = history
        val local = drafts.current
        val base = local.baseRevisionId?.let { known?.get(it) }
        mutableState.value =
            NoteSyncState(
                loaded = known != null,
                synced = inFlight == null && base != null && base.acknowledged && !base.discarded && local.hasSameContentAs(base),
                pushing = inFlight != null,
                rejected = rejected,
                conflict = conflict,
                inTrash = inTrash,
                unreadable = unreadable,
            )
    }

    companion object {
        val DEFAULT_PUSH_DEBOUNCE: Duration = 2.seconds
        val DEFAULT_PUSH_MAX_WAIT: Duration = 10.seconds

        /** Prefix of the id of a Note made from one side of a conflict. */
        const val CONFLICT_COPY_PREFIX: String = "conflict-"

        /** Added to the title of the second Note that "Keep both" makes. */
        const val OTHER_DEVICE_COPY_SUFFIX: String = "(other device copy)"

        private const val UNTITLED = "Untitled note"
    }
}
