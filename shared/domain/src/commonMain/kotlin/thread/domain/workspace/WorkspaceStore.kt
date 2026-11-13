package thread.domain.workspace

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import thread.domain.note.NoteSaveState
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
}

/** What this device knows of the workspace: its own changes included, acknowledged or not. */
data class WorkspaceData(
    /** Every stored status, deleted ones included. Empty before the workspace is seeded. */
    val statuses: List<TopicStatus> = emptyList(),
    val topics: List<Topic> = emptyList(),
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
 * Topics, statuses and preferences of one signed-in account (adr/ADR-009-workspace-entities-and-sync.md). Platform
 * code implements it (Firestore on the web). The store's local copy is this device's only copy and outbox: there is
 * no second queue.
 */
interface WorkspaceStore {
    /** The device's copy. Emits again on every local change and every server change or acknowledgement. */
    fun observe(): Flow<WorkspaceData>

    /**
     * Applies [writes] together to the device's copy at once, also offline, and sends them when the device is online.
     * Never waits for the server. Writes the server refuses are taken back out of the copy and listed in [refused].
     */
    fun write(writes: List<WorkspaceWrite>)

    /** Writes the server refused since this store was made, oldest first. */
    val refused: StateFlow<List<WorkspaceWrite>>
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
