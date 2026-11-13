package thread.web.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import thread.domain.note.NoteSaveState
import thread.domain.topic.StatusId
import thread.domain.topic.Topic
import thread.domain.topic.TopicId
import thread.domain.topic.TopicStatus
import thread.domain.topic.newTopic
import thread.domain.topic.topicsInOrder
import thread.domain.topic.visibleStatuses
import thread.domain.workspace.EditProblem
import thread.domain.workspace.EditResult
import thread.domain.workspace.WorkspaceData
import thread.domain.workspace.WorkspaceEdit
import thread.domain.workspace.WorkspacePrefs
import thread.domain.workspace.WorkspaceStore
import thread.domain.workspace.WorkspaceWrite
import thread.domain.workspace.savePrefs
import thread.domain.workspace.seedWrites
import thread.domain.workspace.workspaceSaveStates
import thread.web.presentation.topics.topicPlace

internal sealed interface TopicCreation {
    data class Created(
        val topic: Topic,
    ) : TopicCreation

    data class Refused(
        val problem: EditProblem,
    ) : TopicCreation
}

/**
 * The signed-in account's Topics, statuses and preferences for the screens (adr/ADR-009-workspace-entities-and-sync.md).
 * State comes from [WorkspaceStore.observe]; screens change it only through writes, so what they show is always what
 * the device stored.
 */
internal class WorkspaceModel(
    private val store: WorkspaceStore,
    private val newId: (prefix: String) -> String,
) {
    /** Null until the first answer from the device cache or the server. */
    var data: WorkspaceData? by mutableStateOf(null)
        private set

    var readFailed: Boolean by mutableStateOf(false)
        private set

    var refused: List<WorkspaceWrite> by mutableStateOf(emptyList())
        private set

    val loaded: Boolean get() = data != null

    /** Every stored status, deleted ones included: the input of the status edits. */
    val storedStatuses: List<TopicStatus> get() = data?.statuses.orEmpty()

    /** The statuses in workspace order. */
    val statuses: List<TopicStatus> get() = visibleStatuses(storedStatuses)

    /** The Topics in workspace order: by status, then oldest first. */
    val topics: List<Topic> get() = topicsInOrder(data?.topics.orEmpty(), statuses)

    val prefs: WorkspacePrefs get() = data?.prefs ?: WorkspacePrefs()

    fun topic(id: String): Topic? = data?.topics?.firstOrNull { it.id.value == id }

    /** Follows the store until cancelled. Seeds the four default statuses once the server says there are none. */
    suspend fun run() {
        try {
            coroutineScope {
                launch { store.refused.collect { refused = it } }
                var seeded = false
                store.observe().collect { next ->
                    data = next
                    if (next.needsSeed && !seeded) {
                        seeded = true
                        store.write(seedWrites())
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            readFailed = true
        }
    }

    /** Writes [result] when it changes something. Returns the edit, for Undo. */
    fun apply(result: EditResult): WorkspaceEdit? {
        val edit = (result as? EditResult.Changed)?.edit ?: return null
        store.write(edit.writes)
        return edit
    }

    fun undo(edit: WorkspaceEdit) {
        store.write(edit.undo)
    }

    /** The color slot of the last Topic made here. Quick captures follow each other before the store shows them. */
    private var lastColorSlot = -1

    /** Creates a Topic named [name] in the first Planned status, or says why it cannot. */
    fun createTopic(name: String): TopicCreation {
        if (statuses.isEmpty()) return TopicCreation.Refused(EditProblem.NotReady)
        val slot = maxOf(data?.topics?.size ?: 0, lastColorSlot + 1)
        val topic =
            newTopic(TopicId(newId("t-")), name, statuses, slot)
                ?: return TopicCreation.Refused(if (name.isBlank()) EditProblem.BlankName else EditProblem.NameTooLong)
        lastColorSlot = slot
        store.write(listOf(WorkspaceWrite.PutTopic(topic, isNew = true)))
        return TopicCreation.Created(topic)
    }

    fun newStatusId(): StatusId = StatusId(newId("s-"))

    fun setPrefs(next: WorkspacePrefs) {
        savePrefs(data, next)?.let { store.write(listOf(it)) }
    }

    /** What the status line chip and the sync panel show for the workspace. */
    fun syncReport(online: Boolean): SyncReport {
        val current = data
        val states = workspaceSaveStates(current, refused.size, online) + if (readFailed) listOf(NoteSaveState.SyncFailed) else emptyList()
        val problems =
            buildList {
                if (readFailed) add(SyncProblem("Topics could not load. Reload the page to try again.", NoteSaveState.SyncFailed, null))
                refused.forEach { add(refusedProblem(it)) }
                val unreadable = current?.unreadable ?: 0
                if (unreadable > 0) {
                    val what = if (unreadable == 1) "1 Topic or status" else "$unreadable Topics or statuses"
                    add(
                        SyncProblem(
                            "$what could not be read. A newer version of Thread may have saved them.",
                            NoteSaveState.SyncFailed,
                            null,
                        ),
                    )
                }
            }
        return SyncReport(states, problems)
    }

    private fun refusedProblem(write: WorkspaceWrite): SyncProblem =
        when (write) {
            is WorkspaceWrite.PutTopic ->
                SyncProblem(
                    "Topic “${write.topic.name}” was not saved. The server refused the change.",
                    NoteSaveState.SyncFailed,
                    topicPlace(write.topic),
                )
            is WorkspaceWrite.PutStatus ->
                SyncProblem(
                    "Status “${write.status.name}” was not saved. The server refused the change.",
                    NoteSaveState.SyncFailed,
                    Place.of("settings", "statuses"),
                )
            is WorkspaceWrite.PutPrefs ->
                SyncProblem(
                    "Preferences were not saved. The server refused the change.",
                    NoteSaveState.SyncFailed,
                    Place.of("settings", "prefs"),
                )
        }
}
