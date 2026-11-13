package thread.domain.search

import thread.domain.note.NoteSummary
import thread.domain.task.Task
import thread.domain.topic.Topic

/** What global search found, at most [SEARCH_GROUP_LIMIT] per group (v3 `searchAll`). */
data class SearchResults(
    val topics: List<Topic>,
    val tasks: List<Task>,
    val notes: List<NoteSummary>,
) {
    val isEmpty: Boolean get() = topics.isEmpty() && tasks.isEmpty() && notes.isEmpty()
}

/**
 * Searches the workspace on this device, ignoring case (v3 `searchAll`, adr/ADR-012-trash-and-search.md). Topics match
 * on name, next step or goal; Tasks on title, completed ones included; Notes on title or preview, so text beyond the
 * preview is not found. Each list keeps the order it is given in. Returns null for a blank [query].
 *
 * [topics], [tasks] and [notes] are what the views show: nothing in Trash, no Topic in Trash or what went with it.
 */
fun searchWorkspace(
    query: String,
    topics: List<Topic>,
    tasks: List<Task>,
    notes: List<NoteSummary>,
): SearchResults? {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return null

    fun String.has() = lowercase().contains(q)
    return SearchResults(
        topics = topics.filter { it.name.has() || it.nextStep.has() || it.goal.has() }.take(SEARCH_GROUP_LIMIT),
        tasks = tasks.filter { !it.deleted && !it.discarded && it.title.has() }.take(SEARCH_GROUP_LIMIT),
        notes = notes.filter { !it.inTrash && !it.discarded && (it.title.has() || it.preview.has()) }.take(SEARCH_GROUP_LIMIT),
    )
}

/** Most results per group (v3 `searchAll`). */
const val SEARCH_GROUP_LIMIT: Int = 6
