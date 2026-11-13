package thread.domain.note

import thread.domain.topic.TopicId

/*
 * Which Notes show where (adr/ADR-011-notes-in-the-workspace.md). The Note content rules are in NoteSyncer; these
 * work on the listing and the Topic links only.
 */

/** A new Note's listing. Made outside a Topic, it waits in Inbox; made in a Topic, it is linked there instead (D39). */
fun newNote(
    id: NoteId,
    topicId: TopicId?,
): NoteSummary = NoteSummary(id = id, inbox = topicId == null)

/** Notes that are not in Trash and not discarded: the ones every view works with. */
fun liveNotes(notes: List<NoteSummary>): List<NoteSummary> = notes.filter { !it.inTrash && !it.discarded }

/** Newest change first (v3 Notes index). A Note whose time is not known yet is the newest. */
fun notesInOrder(notes: List<NoteSummary>): List<NoteSummary> = notes.sortedWith(UPDATE_ORDER)

/** The Topics [noteId] is linked to, in link order. Links to Topics that are gone are left out by the caller. */
fun linkedTopicIds(
    noteId: NoteId,
    links: List<NoteTopicLink>,
): List<TopicId> = links.filter { it.linked && it.noteId == noteId }.map { it.topicId }.distinct()

/** The live Notes linked to [topicId], newest change first (v3 `notesForTopic`). */
fun notesForTopic(
    topicId: TopicId,
    notes: List<NoteSummary>,
    links: List<NoteTopicLink>,
): List<NoteSummary> {
    val ids = links.filter { it.linked && it.topicId == topicId }.map { it.noteId }.toSet()
    return notesInOrder(liveNotes(notes).filter { it.id in ids })
}

/** Notes waiting in Inbox, newest first. */
fun inboxNotes(notes: List<NoteSummary>): List<NoteSummary> =
    liveNotes(notes).filter { it.inbox }.sortedWith(compareBy<NoteSummary> { it.createdAtMillis ?: Long.MAX_VALUE }.reversed())

/** The Notes index filter chips (v3 notes.js). */
enum class NoteFilter {
    All,
    InTopic,
    Standalone,
}

/**
 * The Notes index: live Notes that pass [filter] and contain [query] in their title or preview, newest change first.
 * [topicIdsOf] gives a Note's linked Topics that still exist.
 */
fun filterNotes(
    notes: List<NoteSummary>,
    filter: NoteFilter,
    query: String,
    topicIdsOf: (NoteId) -> List<TopicId>,
): List<NoteSummary> {
    val q = query.trim().lowercase()
    return notesInOrder(
        liveNotes(notes).filter { note ->
            val linked = topicIdsOf(note.id).isNotEmpty()
            val passes =
                when (filter) {
                    NoteFilter.All -> true
                    NoteFilter.InTopic -> linked
                    NoteFilter.Standalone -> !linked
                }
            passes && (q.isEmpty() || note.title.lowercase().contains(q) || note.preview.lowercase().contains(q))
        },
    )
}

private val UPDATE_ORDER: Comparator<NoteSummary> =
    compareByDescending<NoteSummary> { it.updatedAtMillis ?: Long.MAX_VALUE }.thenBy { it.id.value }
