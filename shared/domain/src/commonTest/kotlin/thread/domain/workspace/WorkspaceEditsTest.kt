package thread.domain.workspace

import thread.domain.note.NoteSaveState
import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.StatusCategory
import thread.domain.topic.StatusId
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import thread.domain.topic.TopicStatus
import thread.domain.topic.categoryOf
import thread.domain.topic.statusForNewTopic
import thread.domain.topic.statusOf
import thread.domain.topic.topicsInOrder
import thread.domain.topic.visibleStatuses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Every Topic and status here is SYNTHETIC test data.
class WorkspaceEditsTest {
    private val planned = SEED_STATUSES[0]
    private val active = SEED_STATUSES[1]
    private val paused = SEED_STATUSES[2]
    private val done = SEED_STATUSES[3]

    /** A tiny in-memory store: applies writes the way the real store's documents would end up. */
    private class Workspace(
        var statuses: List<TopicStatus>,
        var topics: List<Topic>,
    ) {
        fun apply(writes: List<WorkspaceWrite>) {
            writes.forEach { write ->
                when (write) {
                    is WorkspaceWrite.PutStatus -> statuses = statuses.filterNot { it.id == write.status.id } + write.status
                    is WorkspaceWrite.PutTopic -> topics = topics.filterNot { it.id == write.topic.id } + write.topic
                    is WorkspaceWrite.PutPrefs, is WorkspaceWrite.PutTask, is WorkspaceWrite.PutReminder,
                    is WorkspaceWrite.PutNote, is WorkspaceWrite.PutNoteLink,
                    -> Unit
                }
            }
        }

        val visible get() = visibleStatuses(statuses)
    }

    private fun topic(
        id: String,
        status: TopicStatus,
    ) = Topic(TopicId(id), "Topic $id", TopicColor.Peach, status.id)

    private fun EditResult.edit(): WorkspaceEdit = assertIs<EditResult.Changed>(this).edit

    private fun EditResult.problem(): EditProblem = assertIs<EditResult.Refused>(this).problem

    @Test
    fun renamingActiveToBuildingKeepsItsBehavior() {
        // Acceptance example 5 (01-product-spec.md).
        val ws = Workspace(SEED_STATUSES, listOf(topic("a", active), topic("b", planned)))
        ws.apply(renameStatus(ws.statuses, active.id, "Building").edit().writes)

        val a = ws.topics.first { it.id.value == "a" }
        assertEquals("Building", statusOf(a, ws.visible).name)
        assertEquals(StatusCategory.Active, categoryOf(a, ws.visible))
        assertEquals(active.id, a.statusId)
        assertEquals(listOf("Planned", "Building", "Paused", "Done"), ws.visible.map { it.name })
        assertEquals(1, ws.topics.count { categoryOf(it, ws.visible) == StatusCategory.Active })
    }

    @Test
    fun categoriesDecideBehaviorNotNames() {
        val ws = Workspace(SEED_STATUSES, listOf(topic("a", paused)))
        ws.apply(changeStatusCategory(ws.statuses, paused.id, StatusCategory.Active).edit().writes)
        ws.apply(renameStatus(ws.statuses, planned.id, "Someday").edit().writes)
        assertEquals(StatusCategory.Active, categoryOf(ws.topics.single(), ws.visible))
        assertEquals("Paused", statusOf(ws.topics.single(), ws.visible).name)
        assertEquals(planned.id, statusForNewTopic(ws.visible).id)
    }

    @Test
    fun aStatusNameIsTrimmedNotBlankShortAndUnique() {
        assertEquals(EditProblem.BlankName, renameStatus(SEED_STATUSES, active.id, "   ").problem())
        assertEquals(EditProblem.NameTooLong, renameStatus(SEED_STATUSES, active.id, "x".repeat(25)).problem())
        assertEquals(EditProblem.DuplicateName, renameStatus(SEED_STATUSES, active.id, " done ").problem())
        assertEquals(EditResult.Unchanged, renameStatus(SEED_STATUSES, active.id, " Active "))
        // Changing only the letter case of its own name is fine.
        val write = renameStatus(SEED_STATUSES, active.id, "ACTIVE").edit().writes.single()
        assertEquals("ACTIVE", (write as WorkspaceWrite.PutStatus).status.name)
        assertEquals(EditProblem.UnknownStatus, renameStatus(SEED_STATUSES, StatusId("s-x"), "X").problem())
    }

    @Test
    fun addingAStatusPutsItLastAndUndoDeletesIt() {
        val ws = Workspace(SEED_STATUSES, emptyList())
        val edit = addStatus(ws.statuses, StatusId("s-qa"), " Waiting on QA ", StatusCategory.Paused).edit()
        ws.apply(edit.writes)
        assertEquals(listOf("Planned", "Active", "Paused", "Done", "Waiting on QA"), ws.visible.map { it.name })
        assertEquals(StatusCategory.Paused, ws.visible.last().category)
        ws.apply(edit.undo)
        assertEquals(SEED_STATUSES, ws.visible)
        assertTrue(ws.statuses.single { it.id.value == "s-qa" }.deleted)
    }

    @Test
    fun addingADuplicateNameIsRefused() {
        assertEquals(EditProblem.DuplicateName, addStatus(SEED_STATUSES, StatusId("s-x"), "paused", StatusCategory.Paused).problem())
        assertEquals(EditProblem.BlankName, addStatus(SEED_STATUSES, StatusId("s-x"), " ", StatusCategory.Paused).problem())
        assertFailsWith<IllegalArgumentException> { addStatus(SEED_STATUSES, active.id, "Another", StatusCategory.Active) }
    }

    @Test
    fun aDeletedStatusNameCanBeUsedAgain() {
        val stored = SEED_STATUSES + TopicStatus(StatusId("s-old"), "Review", StatusCategory.Active, 4, deleted = true)
        val write = addStatus(stored, StatusId("s-new"), "Review", StatusCategory.Active).edit().writes.single()
        assertEquals(5, (write as WorkspaceWrite.PutStatus).status.sortOrder)
    }

    @Test
    fun movingAStatusChangesTheOrderAndUndoRestoresIt() {
        val ws = Workspace(SEED_STATUSES, emptyList())
        val edit = moveStatus(ws.statuses, done.id, -1).edit()
        assertEquals(2, edit.writes.size)
        ws.apply(edit.writes)
        assertEquals(listOf("Planned", "Active", "Done", "Paused"), ws.visible.map { it.name })
        ws.apply(edit.undo)
        assertEquals(SEED_STATUSES, ws.visible)
    }

    @Test
    fun theFirstStatusCannotMoveUpOrTheLastDown() {
        assertEquals(EditProblem.CannotMove, moveStatus(SEED_STATUSES, planned.id, -1).problem())
        assertEquals(EditProblem.CannotMove, moveStatus(SEED_STATUSES, done.id, 1).problem())
    }

    @Test
    fun movingRenumbersFromGappedOrders() {
        val gapped = listOf(planned.copy(sortOrder = 10), active.copy(sortOrder = 20), done.copy(sortOrder = 30))
        val ws = Workspace(gapped, emptyList())
        ws.apply(moveStatus(ws.statuses, planned.id, 2).edit().writes)
        assertEquals(listOf("Active", "Done", "Planned"), ws.visible.map { it.name })
    }

    @Test
    fun deletingAUsedStatusNeedsAReplacement() {
        val topics = listOf(topic("a", paused), topic("b", paused), topic("c", active))
        assertEquals(EditProblem.ReplacementNeeded, deleteStatus(SEED_STATUSES, topics, paused.id, null).problem())
        assertEquals(EditProblem.BadReplacement, deleteStatus(SEED_STATUSES, topics, paused.id, paused.id).problem())
        assertEquals(EditProblem.BadReplacement, deleteStatus(SEED_STATUSES, topics, paused.id, StatusId("s-x")).problem())
    }

    @Test
    fun deletingAUsedStatusMovesItsTopicsAndDeletesNone() {
        val ws = Workspace(SEED_STATUSES, listOf(topic("a", paused), topic("b", paused), topic("c", active)))
        val edit = deleteStatus(ws.statuses, ws.topics, paused.id, active.id).edit()
        ws.apply(edit.writes)
        assertEquals(listOf("Planned", "Active", "Done"), ws.visible.map { it.name })
        assertEquals(3, ws.topics.size)
        assertTrue(ws.topics.all { it.statusId == active.id })

        ws.apply(edit.undo)
        assertEquals(SEED_STATUSES, ws.visible)
        assertEquals(
            listOf("a" to paused.id, "b" to paused.id, "c" to active.id),
            ws.topics.sortedBy { it.id.value }.map {
                it.id.value to
                    it.statusId
            },
        )
    }

    @Test
    fun anUnusedStatusDeletesWithoutAReplacement() {
        val ws = Workspace(SEED_STATUSES, listOf(topic("a", active)))
        ws.apply(deleteStatus(ws.statuses, ws.topics, paused.id, null).edit().writes)
        assertEquals(listOf("Planned", "Active", "Done"), ws.visible.map { it.name })
    }

    @Test
    fun theLastStatusCannotBeDeleted() {
        assertEquals(EditProblem.LastStatus, deleteStatus(listOf(active), emptyList(), active.id, null).problem())
    }

    @Test
    fun statusEditsWaitUntilStatusesAreStored() {
        // An empty cache may only mean the server has not answered: writing the seed could overwrite real statuses.
        assertEquals(EditProblem.NotReady, renameStatus(emptyList(), active.id, "Building").problem())
        assertEquals(EditProblem.NotReady, addStatus(emptyList(), StatusId("s-new"), "Waiting", StatusCategory.Paused).problem())
        assertEquals(EditProblem.NotReady, moveStatus(emptyList(), done.id, -1).problem())
        assertEquals(EditProblem.NotReady, changeStatusCategory(emptyList(), paused.id, StatusCategory.Active).problem())
        assertEquals(EditProblem.NotReady, deleteStatus(emptyList(), emptyList(), paused.id, null).problem())
    }

    @Test
    fun boardLanesFollowTheWorkspaceOrderAfterAMove() {
        val ws = Workspace(SEED_STATUSES, listOf(topic("a", planned), topic("b", done)))
        ws.apply(moveStatus(ws.statuses, done.id, -3).edit().writes)
        assertEquals(listOf("Done", "Planned", "Active", "Paused"), ws.visible.map { it.name })
        assertEquals(listOf("b", "a"), topicsInOrder(ws.topics, ws.visible).map { it.id.value })
    }

    @Test
    fun movingATopicWritesItAndUndoMovesItBack() {
        val t = topic("a", planned)
        val edit = moveTopic(t, active.id, SEED_STATUSES).edit()
        val write = edit.writes.single() as WorkspaceWrite.PutTopic
        val undo = edit.undo.single() as WorkspaceWrite.PutTopic
        assertEquals(active.id, write.topic.statusId)
        assertEquals(t, undo.topic)
        // Each write names the value it was made from, so the store writes only the status field.
        assertEquals(t, write.before)
        assertEquals(write.topic, undo.before)
        assertEquals(EditResult.Unchanged, moveTopic(t, planned.id, SEED_STATUSES))
        assertEquals(EditProblem.UnknownStatus, moveTopic(t, StatusId("s-gone"), SEED_STATUSES).problem())
    }

    @Test
    fun editingATopicTrimsAndNeedsAName() {
        val t = topic("a", planned)
        val after = (editTopic(t, "  New name ", " A goal ", TopicColor.Sky).edit().writes.single() as WorkspaceWrite.PutTopic).topic
        assertEquals(Triple("New name", "A goal", TopicColor.Sky), Triple(after.name, after.goal, after.color))
        assertEquals(EditProblem.BlankName, editTopic(t, "  ", "", t.color).problem())
        assertEquals(EditResult.Unchanged, editTopic(t, t.name, "", t.color))
    }

    @Test
    fun pickUpFieldsAreTrimmed() {
        val t = topic("a", planned)
        val after = (editPickUp(t, nextStep = "  Call the backend team ").edit().writes.single() as WorkspaceWrite.PutTopic).topic
        assertEquals("Call the backend team", after.nextStep)
        assertEquals("", after.leftOff)
        assertEquals(EditResult.Unchanged, editPickUp(t, leftOff = "   "))
    }

    @Test
    fun theFirstPreferencesSaveWritesEveryField() {
        val board = WorkspacePrefs(topicsView = TopicsView.Board)
        assertEquals(WorkspaceWrite.PutPrefs(board), savePrefs(WorkspaceData(), board))
        assertEquals(WorkspaceWrite.PutPrefs(board), savePrefs(null, board))
        assertEquals(null, savePrefs(WorkspaceData(), WorkspacePrefs()))

        val stored = WorkspaceData(prefs = board, prefsStored = true)
        val note = board.copy(captureType = CaptureKind.Note)
        assertEquals(WorkspaceWrite.PutPrefs(note, before = board), savePrefs(stored, note))
        assertEquals(null, savePrefs(stored, board))
    }

    @Test
    fun workspaceSaveStatesClaimOnlyWhatTheServerConfirmed() {
        val clean = WorkspaceData()
        assertEquals(emptyList(), workspaceSaveStates(clean, refusedCount = 0, online = true))
        assertEquals(listOf(NoteSaveState.Syncing), workspaceSaveStates(clean.copy(pendingWrites = true), 0, online = true))
        assertEquals(listOf(NoteSaveState.Offline), workspaceSaveStates(clean.copy(pendingWrites = true), 0, online = false))
        assertEquals(listOf(NoteSaveState.SyncFailed), workspaceSaveStates(clean, refusedCount = 1, online = true))
        assertEquals(listOf(NoteSaveState.SyncFailed), workspaceSaveStates(clean.copy(unreadable = 2), 0, online = true))
        assertEquals(emptyList(), workspaceSaveStates(null, 0, online = false))
    }
}
