package thread.domain.workspace

import thread.domain.note.NoteId
import thread.domain.note.NoteSummary
import thread.domain.note.NoteTopicLink
import thread.domain.note.linkedTopicIds
import thread.domain.task.Task
import thread.domain.topic.TopicId

/*
 * Note filing edits (adr/ADR-011-notes-in-the-workspace.md): Topic links and the Inbox flag. Each returns the writes
 * and the writes that take them back, for Undo. A link is a document per Topic and Note pair; unlinking marks it
 * unlinked, so the Note is never deleted (04-data-and-sync.md, "Link deletion does not delete source content").
 */

/** The writes that store a new Note's listing and, when it is made in a Topic, its link there (D39). */
fun createNoteWrites(
    note: NoteSummary,
    topicId: TopicId?,
): List<WorkspaceWrite> =
    buildList {
        add(WorkspaceWrite.PutNote(note, isNew = true))
        if (topicId != null) add(WorkspaceWrite.PutNoteLink(NoteTopicLink(topicId, note.id)))
    }

/**
 * Takes back a Note made a moment ago (the Undo of its "added" toast). It leaves every view, Trash included. Its
 * documents stay, because nothing is hard-deleted.
 */
fun discardNote(note: NoteSummary): List<WorkspaceWrite> =
    if (note.discarded) emptyList() else listOf(WorkspaceWrite.PutNote(note.copy(discarded = true), before = note))

/**
 * Makes [topicIds] the Topics of [note] (v3 "Change Topics", many to many). Topics no longer picked are unlinked;
 * the Note stays. Linking at least one Topic files the Note out of Inbox; unlinking every Topic leaves it standalone,
 * not back in Inbox (v3 `setNoteTopics`).
 */
fun setNoteTopics(
    note: NoteSummary,
    links: List<NoteTopicLink>,
    topicIds: List<TopicId>,
): EditResult {
    val current = linkedTopicIds(note.id, links)
    val wanted = topicIds.distinct()
    val added = wanted - current.toSet()
    val removed = current - wanted.toSet()
    val fileOut = wanted.isNotEmpty() && note.inbox
    if (added.isEmpty() && removed.isEmpty() && !fileOut) return EditResult.Unchanged
    val writes = mutableListOf<WorkspaceWrite>()
    val undo = mutableListOf<WorkspaceWrite>()
    added.forEach { topicId ->
        writes += link(topicId, note.id, true)
        undo += link(topicId, note.id, false)
    }
    removed.forEach { topicId ->
        writes += link(topicId, note.id, false)
        undo += link(topicId, note.id, true)
    }
    if (fileOut) {
        val after = note.copy(inbox = false)
        writes += WorkspaceWrite.PutNote(after, before = note)
        undo += WorkspaceWrite.PutNote(note, before = after)
    }
    return EditResult.Changed(WorkspaceEdit(writes, undo))
}

/** Links [note] to one more Topic (Topic Details, "Link existing"). */
fun linkNote(
    note: NoteSummary,
    links: List<NoteTopicLink>,
    topicId: TopicId,
): EditResult = setNoteTopics(note, links, linkedTopicIds(note.id, links) + topicId)

/** Unlinks [note] from [topicId] (Topic Details). The Note stays in Notes, with its other Topics. */
fun unlinkNote(
    note: NoteSummary,
    links: List<NoteTopicLink>,
    topicId: TopicId,
): EditResult = setNoteTopics(note, links, linkedTopicIds(note.id, links) - topicId)

/**
 * Files Inbox items (03-architecture.md, TriageInboxItem). With [topicId] each Task joins that Topic and each Note adds
 * it to its Topics; without one they are kept standalone. Both leave Inbox. Nothing is copied or deleted (D22). Undo
 * puts each item back as it was.
 */
fun triageInbox(
    tasks: List<Task>,
    notes: List<NoteSummary>,
    links: List<NoteTopicLink>,
    topicId: TopicId?,
): EditResult {
    val writes = mutableListOf<WorkspaceWrite>()
    val undo = mutableListOf<WorkspaceWrite>()
    (triageTasks(tasks, topicId) as? EditResult.Changed)?.edit?.let {
        writes += it.writes
        undo += it.undo
    }
    notes.forEach { note ->
        if (topicId != null && topicId !in linkedTopicIds(note.id, links)) {
            writes += link(topicId, note.id, true)
            undo += link(topicId, note.id, false)
        }
        if (note.inbox) {
            val after = note.copy(inbox = false)
            writes += WorkspaceWrite.PutNote(after, before = note)
            undo += WorkspaceWrite.PutNote(note, before = after)
        }
    }
    return if (writes.isEmpty()) EditResult.Unchanged else EditResult.Changed(WorkspaceEdit(writes, undo))
}

/**
 * Links the Note made from one side of a conflict to the same Topics as the original (D41: "Keep both" saves a second
 * Note with the same Topics). The copy in Trash gets them too, so Restore brings it back where it was. The link ids are
 * made from the pairs, so two devices that resolve the same way write the same links.
 */
fun copyNoteLinks(
    from: NoteId,
    to: NoteId,
    links: List<NoteTopicLink>,
): List<WorkspaceWrite> = linkedTopicIds(from, links).map { topicId -> link(topicId, to, true) }

private fun link(
    topicId: TopicId,
    noteId: NoteId,
    linked: Boolean,
) = WorkspaceWrite.PutNoteLink(NoteTopicLink(topicId, noteId, linked))
