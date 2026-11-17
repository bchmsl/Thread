package thread.domain.search

import thread.domain.calendar.Meeting
import thread.domain.calendar.SeriesKey
import thread.domain.calendar.hasEnded
import thread.domain.external.JiraIssueSummary
import thread.domain.external.PullRequestSummary
import thread.domain.note.NoteSummary
import thread.domain.task.Task
import thread.domain.time.WallTime
import thread.domain.topic.Topic
import thread.domain.topic.topicInfo

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
 * on name, goal or Additional information ([topicInfo]); Tasks on title, completed ones included; Notes on title or preview, so text beyond the
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
        topics = topics.filter { it.name.has() || it.goal.has() || topicInfo(it).has() }.take(SEARCH_GROUP_LIMIT),
        tasks = tasks.filter { !it.deleted && !it.discarded && it.title.has() }.take(SEARCH_GROUP_LIMIT),
        notes = notes.filter { !it.inTrash && !it.discarded && (it.title.has() || it.preview.has()) }.take(SEARCH_GROUP_LIMIT),
    )
}

/** Most results per group (v3 `searchAll`). */
const val SEARCH_GROUP_LIMIT: Int = 6

/**
 * Searches the Jira tickets saved on this device on key or title, ignoring case (v3 `searchAll`, "Jira (downloaded)").
 * Search of tickets not on this device is a separate step on the Jira page. Returns null for a blank [query].
 */
fun searchJiraCopies(
    query: String,
    issues: List<JiraIssueSummary>,
): List<JiraIssueSummary>? {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return null
    return issues
        .filter {
            it.key.value
                .lowercase()
                .contains(q) ||
                it.title.lowercase().contains(q)
        }.take(SEARCH_GROUP_LIMIT)
}

/**
 * Searches the pull requests saved on this device on title, number (`#12` or `12`) or `owner/repo`, ignoring case
 * (v3 `searchAll`, "Pull requests (downloaded)"). Returns null for a blank [query].
 */
fun searchPullRequestCopies(
    query: String,
    pullRequests: List<PullRequestSummary>,
): List<PullRequestSummary>? {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return null
    val number = q.removePrefix("#").toIntOrNull()
    return pullRequests
        .filter {
            it.title.lowercase().contains(q) ||
                it.ref.id
                    .lowercase()
                    .contains(q) ||
                it.ref.number == number
        }.take(SEARCH_GROUP_LIMIT)
}

/** Most occurrences of one repeating meeting in the results, so a daily standup does not fill the list. */
const val SEARCH_SERIES_LIMIT: Int = 3

/**
 * Searches the meetings on this device, yesterday to 14 days ahead (D78 P5b, adr/ADR-020-calendar-meetings-sync.md),
 * on title or location, ignoring case. The join link is never matched: it may hold a passcode. Nearest to [now] first:
 * those not over by start, then those over, the latest first. At most [SEARCH_SERIES_LIMIT] per series and
 * [SEARCH_GROUP_LIMIT] in all. Returns null for a blank [query].
 */
fun searchMeetings(
    query: String,
    meetings: List<Meeting>,
    now: WallTime,
): List<Meeting>? {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return null

    fun String?.has() = orEmpty().lowercase().contains(q)
    val (over, ahead) = meetings.filter { it.title.has() || it.location.has() }.partition { hasEnded(it.time, now) }
    val nearest =
        ahead.sortedWith(compareBy<Meeting>({ it.time.startsAt }, { it.displayTitle }, { it.id.value })) +
            over.sortedWith(LATEST_END_FIRST.then(compareBy({ it.displayTitle }, { it.id.value })))
    val perSeries = mutableMapOf<SeriesKey, Int>()
    return nearest
        .filter { meeting ->
            val shown = perSeries[meeting.seriesKey] ?: 0
            perSeries[meeting.seriesKey] = shown + 1
            shown < SEARCH_SERIES_LIMIT
        }.take(SEARCH_GROUP_LIMIT)
}

private val LATEST_END_FIRST: Comparator<Meeting> = compareByDescending { it.time.endsAt }
