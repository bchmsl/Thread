package thread.domain.workspace

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import thread.domain.note.NoteId
import thread.domain.note.NoteSaveState
import thread.domain.note.NoteSummary
import thread.domain.note.NoteTopicLink
import thread.domain.reminder.Reminder
import thread.domain.task.Task
import thread.domain.time.DEFAULT_WORKSPACE_ZONE
import thread.domain.topic.Topic
import thread.domain.topic.TopicStatus

/** How the Topics page opens (v3 Settings, "Topics opens as"). The page also remembers the last one picked. */
enum class TopicsView(
    val id: String,
) {
    List("list"),
    Board("board"),
    ;

    companion object {
        fun fromId(id: String?): TopicsView? = entries.firstOrNull { it.id == id }
    }
}

/** What quick capture makes (D35). */
enum class CaptureKind(
    val id: String,
) {
    Task("task"),
    Note("note"),
    Topic("topic"),
    ;

    companion object {
        fun fromId(id: String?): CaptureKind? = entries.firstOrNull { it.id == id }
    }
}

/** The workspace preferences stored with the account (04-data-and-sync.md, "Preferences"). */
data class WorkspacePrefs(
    val topicsView: TopicsView = TopicsView.List,
    val captureType: CaptureKind = CaptureKind.Task,
    /** Settings, "Keep completed tasks in Today". On by default, as in v3. */
    val keepCompleted: Boolean = true,
    /** The IANA time zone that defines today, overdue and reminder times. No screen changes it in the pilot. */
    val timeZone: String = DEFAULT_WORKSPACE_ZONE,
)

/**
 * One document change. A [WorkspaceStore] applies a list of them together or not at all.
 *
 * `before` is the value the change was made from. When it is set, the store writes only the fields that differ from
 * it, so a change made at the same time on another device to a different field is kept. When it is null, every field
 * is written (a new document, or the seed).
 */
sealed interface WorkspaceWrite {
    /** Saves [topic]. [isNew] marks its first write, which also stores when it was created. */
    data class PutTopic(
        val topic: Topic,
        val isNew: Boolean = false,
        val before: Topic? = null,
    ) : WorkspaceWrite

    /** Saves [status]. Deleting a status is a [TopicStatus.deleted] write, so Undo can bring it back. */
    data class PutStatus(
        val status: TopicStatus,
        val before: TopicStatus? = null,
    ) : WorkspaceWrite

    data class PutPrefs(
        val prefs: WorkspacePrefs,
        val before: WorkspacePrefs? = null,
    ) : WorkspaceWrite

    /** Saves [task]. [isNew] marks its first write, which also stores when it was created. */
    data class PutTask(
        val task: Task,
        val isNew: Boolean = false,
        val before: Task? = null,
    ) : WorkspaceWrite

    /** Saves [reminder]. [isNew] marks its first write, which also stores when it was created. */
    data class PutReminder(
        val reminder: Reminder,
        val isNew: Boolean = false,
        val before: Reminder? = null,
    ) : WorkspaceWrite

    /**
     * Saves where [note] is filed: its Inbox flag, and whether it was discarded. [isNew] marks its first write, which
     * also stores when it was made. The title, preview, versions and Trash state belong to Note sync (NoteSyncRemote)
     * and are never written here.
     */
    data class PutNote(
        val note: NoteSummary,
        val isNew: Boolean = false,
        val before: NoteSummary? = null,
    ) : WorkspaceWrite

    /** Saves one Topic link of a Note. Unlinking writes [NoteTopicLink.linked] false; nothing is deleted. */
    data class PutNoteLink(
        val link: NoteTopicLink,
    ) : WorkspaceWrite

    /** Saves a record that Topics link to: ids and the canonical URL only (adr/ADR-013-jira-in-the-workspace.md). */
    data class PutExternalRef(
        val reference: ExternalReference,
    ) : WorkspaceWrite

    /** Saves one Topic link to an external record. Unlinking writes [TopicExternalLink.linked] false. */
    data class PutExternalLink(
        val link: TopicExternalLink,
    ) : WorkspaceWrite

    /**
     * Deletes one Topic, Task, Reminder or Note forever (D43, adr/ADR-012-trash-and-search.md). The document stays
     * as a tombstone that no later write can change, so a stale offline edit from another device cannot bring it back.
     * The store leaves tombstones out of [WorkspaceData].
     */
    data class DeleteForever(
        val kind: TrashKind,
        val id: String,
        /** Shown when the server refuses the change. */
        val title: String,
    ) : WorkspaceWrite
}

/** What can go to Trash. Jira tickets and pull requests never do. */
enum class TrashKind {
    Topic,
    Task,
    Reminder,
    Note,
}

/** What this device knows of the workspace: its own changes included, acknowledged or not. */
data class WorkspaceData(
    /** Every stored status, deleted ones included. Empty before the workspace is seeded. */
    val statuses: List<TopicStatus> = emptyList(),
    val topics: List<Topic> = emptyList(),
    /** Every stored Task, those in Trash and discarded ones included. */
    val tasks: List<Task> = emptyList(),
    /** Every stored Reminder, those in Trash included. */
    val reminders: List<Reminder> = emptyList(),
    /** Every stored Note listing, those in Trash and discarded ones included. */
    val notes: List<NoteSummary> = emptyList(),
    /** Every stored Note to Topic link, unlinked ones included. */
    val noteLinks: List<NoteTopicLink> = emptyList(),
    /** Every stored record that Topics link to. */
    val externalRefs: List<ExternalReference> = emptyList(),
    /** Every stored Topic link to an external record, unlinked ones included. */
    val externalLinks: List<TopicExternalLink> = emptyList(),
    /** Notes deleted forever. Their documents are tombstones; their saved versions may still need deleting. */
    val purgedNotes: List<NoteId> = emptyList(),
    val prefs: WorkspacePrefs = WorkspacePrefs(),
    /** True when the account has a stored preferences document. Until then [prefs] are the defaults. */
    val prefsStored: Boolean = false,
    /** True only when the server confirmed that the account has no status at all: a new workspace to seed. */
    val needsSeed: Boolean = false,
    /** True while a change made on this device is not acknowledged by the server. */
    val pendingWrites: Boolean = false,
    /** Stored documents this version of the app cannot read. They are left alone and not shown. */
    val unreadable: Int = 0,
)

/**
 * A change the server refused (owner-review-queue Q14). It stays on this device, also after a reload, until the person
 * retries or discards it: a failed sync never drops content silently.
 *
 * [unconfirmed] marks a change sent before a reload whose answer the reload lost, and that the stored data does not
 * show: the server refused it, or another device changed the same field since ([unconfirmedChanges]).
 */
data class RefusedChange(
    val id: String,
    val writes: List<WorkspaceWrite>,
    val unconfirmed: Boolean = false,
)

/**
 * Topics, statuses, Tasks, Reminders, Note listings and links, and preferences of one signed-in account
 * (adr/ADR-009-workspace-entities-and-sync.md, adr/ADR-010-tasks-reminders-and-dates.md,
 * adr/ADR-011-notes-in-the-workspace.md). Platform
 * code implements it (Firestore on the web). The store's local copy is this device's only copy and outbox: there is
 * no second queue. The store also remembers each change on this device until the server answers, only so a refusal
 * is never lost.
 */
interface WorkspaceStore {
    /** The device's copy. Emits again on every local change and every server change or acknowledgement. */
    fun observe(): Flow<WorkspaceData>

    /**
     * Applies [writes] together to the device's copy at once, also offline, and sends them when the device is online.
     * Never waits for the server. Writes the server refuses are taken back out of the copy and listed in [refused].
     */
    fun write(writes: List<WorkspaceWrite>)

    /** Changes the server refused, oldest first. They stay listed, also after a reload, until [retry] or [discard]. */
    val refused: StateFlow<List<RefusedChange>>

    /** Sends [change] again as a new write. If the server refuses it again, it is listed again. */
    fun retry(change: RefusedChange)

    /** Forgets [change] on this device, once the person copied it or does not need it. */
    fun discard(change: RefusedChange)

    /** Lists [change] again: the Undo of [discard]. */
    fun keep(change: RefusedChange)
}

/**
 * The save states the workspace adds to the status line chip (adr/ADR-009-workspace-entities-and-sync.md). A refused
 * write or an unreadable document is Sync failed. A change the server has not acknowledged is Syncing, or Saved
 * locally, offline. Nothing pending adds no state, so the chip shows All synced when everything else agrees.
 */
fun workspaceSaveStates(
    data: WorkspaceData?,
    refusedCount: Int,
    online: Boolean,
): List<NoteSaveState> =
    buildList {
        if (refusedCount > 0 || (data?.unreadable ?: 0) > 0) add(NoteSaveState.SyncFailed)
        if (data?.pendingWrites == true) add(if (online) NoteSaveState.Syncing else NoteSaveState.Offline)
    }
