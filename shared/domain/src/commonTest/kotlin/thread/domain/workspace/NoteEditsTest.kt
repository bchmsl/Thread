package thread.domain.workspace

import thread.domain.note.NoteFilter
import thread.domain.note.NoteId
import thread.domain.note.NoteSummary
import thread.domain.note.NoteTopicLink
import thread.domain.note.filterNotes
import thread.domain.note.inboxNotes
import thread.domain.note.linkedTopicIds
import thread.domain.note.newNote
import thread.domain.note.noteTopicLinkId
import thread.domain.note.notesForTopic
import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.topic.TopicId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Every Note, Topic and link here is SYNTHETIC test data.
class NoteEditsTest {
    private val topicA = TopicId("t-a")
    private val topicB = TopicId("t-b")
    private val noteId = NoteId("n-1")

    private fun changed(result: EditResult): WorkspaceEdit = assertIs<EditResult.Changed>(result).edit

    /** Applies [writes] to the listing and links the way the store would. */
    private class Workspace(
        var note: NoteSummary,
        var links: List<NoteTopicLink> = emptyList(),
    ) {
        fun apply(writes: List<WorkspaceWrite>) {
            writes.forEach { write ->
                when (write) {
                    is WorkspaceWrite.PutNote -> note = write.note
                    is WorkspaceWrite.PutNoteLink -> links = links.filterNot { it.id == write.link.id } + write.link
                    else -> error("Unexpected write $write")
                }
            }
        }
    }

    @Test
    fun aNoteMadeOutsideATopicWaitsInInboxAndOneMadeInATopicIsLinkedThere() {
        val loose = newNote(noteId, null)
        assertTrue(loose.inbox, "D39: made outside a Topic, the Note lands in Inbox")
        assertEquals(listOf<WorkspaceWrite>(WorkspaceWrite.PutNote(loose, isNew = true)), createNoteWrites(loose, null))

        val filed = newNote(noteId, topicA)
        assertEquals(false, filed.inbox, "D39: made in a Topic, the Note is not in Inbox")
        val writes = createNoteWrites(filed, topicA)
        assertEquals(WorkspaceWrite.PutNote(filed, isNew = true), writes[0])
        assertEquals(WorkspaceWrite.PutNoteLink(NoteTopicLink(topicA, noteId, linked = true)), writes[1])
    }

    @Test
    fun linkIdsAreMadeFromThePairSoLinkingTwiceWritesOneDocument() {
        assertEquals("t-a_n-1", noteTopicLinkId(topicA, noteId))
        val first = NoteTopicLink(topicA, noteId)
        val again = NoteTopicLink(topicA, noteId)
        assertEquals(first.id, again.id)
        val workspace = Workspace(newNote(noteId, null))
        workspace.apply(changed(linkNote(workspace.note, workspace.links, topicA)).writes)
        workspace.apply(listOf(WorkspaceWrite.PutNoteLink(again)))
        assertEquals(1, workspace.links.size)
        assertEquals(listOf(topicA), linkedTopicIds(noteId, workspace.links))
    }

    @Test
    fun aNoteCanBelongToSeveralTopicsAndLinkingFilesItOutOfInbox() {
        val workspace = Workspace(newNote(noteId, null))
        val edit = changed(setNoteTopics(workspace.note, workspace.links, listOf(topicA, topicB)))
        workspace.apply(edit.writes)
        assertEquals(listOf(topicA, topicB), linkedTopicIds(noteId, workspace.links))
        assertEquals(false, workspace.note.inbox)

        workspace.apply(edit.undo)
        assertEquals(emptyList(), linkedTopicIds(noteId, workspace.links))
        assertEquals(true, workspace.note.inbox, "Undo puts the Note back in Inbox")
    }

    @Test
    fun unlinkingNeverDeletesTheNoteAndKeepsItsOtherTopics() {
        val workspace = Workspace(newNote(noteId, topicA).copy(title = "Synthetic note"))
        workspace.apply(createNoteWrites(workspace.note, topicA))
        workspace.apply(changed(linkNote(workspace.note, workspace.links, topicB)).writes)
        val before = workspace.note

        val edit = changed(unlinkNote(workspace.note, workspace.links, topicA))
        assertTrue(edit.writes.all { it is WorkspaceWrite.PutNoteLink }, "Unlinking writes only the link")
        workspace.apply(edit.writes)
        assertEquals(before, workspace.note, "The Note itself is unchanged")
        assertEquals(listOf(topicB), linkedTopicIds(noteId, workspace.links))
        assertEquals(NoteTopicLink(topicA, noteId, linked = false), workspace.links.first { it.topicId == topicA })

        workspace.apply(changed(unlinkNote(workspace.note, workspace.links, topicB)).writes)
        assertEquals(false, workspace.note.inbox, "Unlinking every Topic leaves the Note standalone, not in Inbox")
        assertEquals(emptyList(), notesForTopic(topicB, listOf(workspace.note), workspace.links))
    }

    @Test
    fun settingTheSameTopicsChangesNothing() {
        val workspace = Workspace(newNote(noteId, topicA))
        workspace.apply(createNoteWrites(workspace.note, topicA))
        assertEquals(EditResult.Unchanged, setNoteTopics(workspace.note, workspace.links, listOf(topicA)))
        assertEquals(EditResult.Unchanged, unlinkNote(workspace.note, workspace.links, topicB))
    }

    @Test
    fun triageLinksNotesAndFilesTasksTogetherAndUndoPutsThemBack() {
        val task = Task(TaskId("k1"), "Synthetic task", inbox = true)
        val workspace = Workspace(newNote(noteId, null))
        val edit = changed(triageInbox(listOf(task), listOf(workspace.note), workspace.links, topicA))
        val taskWrite = edit.writes.filterIsInstance<WorkspaceWrite.PutTask>().single()
        assertEquals(topicA, taskWrite.task.topicId)
        assertEquals(false, taskWrite.task.inbox)
        workspace.apply(edit.writes.filterNot { it is WorkspaceWrite.PutTask })
        assertEquals(listOf(topicA), linkedTopicIds(noteId, workspace.links))
        assertEquals(false, workspace.note.inbox)

        workspace.apply(edit.undo.filterNot { it is WorkspaceWrite.PutTask })
        assertEquals(true, workspace.note.inbox)
        assertEquals(emptyList(), linkedTopicIds(noteId, workspace.links))
    }

    @Test
    fun keepingStandaloneOnlyClearsTheInboxFlag() {
        val note = newNote(noteId, null)
        val edit = changed(triageInbox(emptyList(), listOf(note), emptyList(), null))
        assertEquals(listOf<WorkspaceWrite>(WorkspaceWrite.PutNote(note.copy(inbox = false), before = note)), edit.writes)
    }

    @Test
    fun discardingHidesTheNoteEverywhereWithoutDeletingIt() {
        val note = newNote(noteId, null)
        val write = assertIs<WorkspaceWrite.PutNote>(discardNote(note).single())
        assertTrue(write.note.discarded)
        assertEquals(emptyList(), inboxNotes(listOf(write.note)))
        assertEquals(emptyList(), discardNote(write.note), "Discarding twice writes nothing")
    }

    @Test
    fun aConflictCopyGetsTheSameTopics() {
        val links = listOf(NoteTopicLink(topicA, noteId), NoteTopicLink(topicB, noteId, linked = false))
        val copy = NoteId("conflict-r2")
        val expected = listOf<WorkspaceWrite>(WorkspaceWrite.PutNoteLink(NoteTopicLink(topicA, copy)))
        assertEquals(expected, copyNoteLinks(noteId, copy, links))
    }

    @Test
    fun theIndexFiltersByTopicAndTextAndShowsNewestFirst() {
        val linked = NoteSummary(NoteId("n-a"), title = "Synthetic release plan", updatedAtMillis = 1)
        val loose = NoteSummary(NoteId("n-b"), preview = "synthetic checklist for release", updatedAtMillis = 2)
        val trashed = NoteSummary(NoteId("n-c"), title = "Synthetic old", inTrash = true)
        val notes = listOf(linked, loose, trashed)
        val topicsOf = { id: NoteId -> if (id == linked.id) listOf(topicA) else emptyList() }
        assertEquals(listOf(loose, linked), filterNotes(notes, NoteFilter.All, "", topicsOf))
        assertEquals(listOf(linked), filterNotes(notes, NoteFilter.InTopic, "", topicsOf))
        assertEquals(listOf(loose), filterNotes(notes, NoteFilter.Standalone, "", topicsOf))
        assertEquals(listOf(loose, linked), filterNotes(notes, NoteFilter.All, " RELEASE ", topicsOf))
        assertEquals(listOf(loose), filterNotes(notes, NoteFilter.All, "checklist", topicsOf))
    }
}
