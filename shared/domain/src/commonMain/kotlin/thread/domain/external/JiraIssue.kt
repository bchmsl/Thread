package thread.domain.external

import thread.domain.note.LinkHref
import thread.domain.time.LocalDay
import thread.domain.topic.TopicId
import kotlin.jvm.JvmInline

/** A Jira issue key such as `ABC-123`. Create it with [parse]. */
@JvmInline
value class JiraIssueKey private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val KEY = Regex("^[A-Z][A-Z0-9_]{0,49}-[1-9][0-9]{0,9}$")

        /** Accepts any letter case and surrounding spaces. Returns null for anything that is not a key. */
        fun parse(raw: String): JiraIssueKey? {
            val key = raw.trim().uppercase()
            return if (KEY.matches(key)) JiraIssueKey(key) else null
        }
    }
}

/** Jira's own status categories, which every workflow status belongs to. */
enum class JiraStatusCategory {
    ToDo,
    InProgress,
    Done,
    Unknown,
}

data class JiraStatus(
    val name: String,
    val category: JiraStatusCategory,
)

/**
 * A Jira issue as Thread shows it (v3 Jira detail). Read-only. Person fields hold display names.
 *
 * [comments] are the newest comments, oldest first. [commentTotal] counts all comments in Jira. [related] are its
 * issue links, as Jira lists them. [parentKey] is its parent (an Epic, or the ticket a Subtask belongs to). [attachments],
 * [subtasks], [fixVersions] and [dueDate] are in Jira's order (D75). A copy saved before Thread asked for a field has
 * none until the next refresh.
 */
data class JiraIssue(
    val key: JiraIssueKey,
    val type: String?,
    val title: String,
    val status: JiraStatus?,
    val priority: String?,
    val assignee: String?,
    val reporter: String?,
    val sprint: String?,
    val labels: List<String>,
    val updatedAtMillis: Long?,
    val description: ExternalDocument,
    val comments: List<JiraComment>,
    val commentTotal: Int,
    val webUrl: LinkHref?,
    val related: List<JiraRelated> = emptyList(),
    val parentKey: JiraIssueKey? = null,
    val attachments: List<JiraAttachment> = emptyList(),
    val subtasks: List<JiraIssueSummary> = emptyList(),
    val fixVersions: List<String> = emptyList(),
    val dueDate: LocalDay? = null,
) {
    fun summary(): JiraIssueSummary = JiraIssueSummary(key, type, title, status, updatedAtMillis, parentKey)
}

/**
 * A file attached to a Jira issue: its name, size in bytes, type and when it was added (D75). Thread never fetches the
 * file. [openUrl] opens it on the Jira site in a new tab, where Jira asks the person to sign in if needed.
 */
data class JiraAttachment(
    val id: String,
    val filename: String,
    val sizeBytes: Long?,
    val mimeType: String?,
    val createdAtMillis: Long?,
    val openUrl: LinkHref?,
)

/** One issue link: how it relates ("blocks", "is blocked by"), and the other issue as Jira names it. */
data class JiraRelated(
    val relation: String,
    val key: JiraIssueKey,
    val title: String?,
    val status: JiraStatus?,
    val type: String? = null,
)

data class JiraComment(
    val id: String,
    val author: String?,
    val createdAtMillis: Long?,
    val body: ExternalDocument,
)

/** Jira issues: the provider's API and the copies saved on this device. */
interface JiraIssueSource : ExternalRecordSource<JiraIssueKey, JiraIssue> {
    /** Every issue saved on this device, newest save first. */
    suspend fun allSaved(): List<Saved<JiraIssue>>
}

/**
 * One row of a Jira list or search result (v3 `jiraRow`). [parentKey] is the ticket's parent; only the Topic
 * suggestion search (D49 S4) and an opened ticket (D64) carry it, so list rows have none.
 */
data class JiraIssueSummary(
    val key: JiraIssueKey,
    val type: String?,
    val title: String,
    val status: JiraStatus?,
    val updatedAtMillis: Long?,
    val parentKey: JiraIssueKey? = null,
)

/**
 * The tickets around a Topic's linked [keys] (D49 S4, adr/ADR-017-topic-suggestions.md): rows of the [keys]
 * themselves, of their children, of their parents and of their parents' other children, each with its parent, as
 * Jira showed them. Kept on this device per Topic; never synced.
 */
data class JiraFamily(
    val keys: List<JiraIssueKey>,
    val rows: List<JiraIssueSummary>,
)

/** The [JiraFamily] of a Topic's linked tickets: Jira's search, and the last answer saved on this device per Topic. */
interface JiraFamilySource {
    /** The answer saved for [topicId], or null. */
    suspend fun readSaved(topicId: TopicId): Saved<JiraFamily>?

    /** Asks Jira about [keys] and saves the answer for [topicId] as fetched at [nowMillis]. */
    suspend fun refresh(
        topicId: TopicId,
        keys: List<JiraIssueKey>,
        nowMillis: Long,
    ): ExternalResult<Refreshed<JiraFamily>>
}

/** Tickets in Jira's order. [complete] is false when Jira had more than Thread fetched. */
data class JiraIssueList(
    val issues: List<JiraIssueSummary>,
    val complete: Boolean,
)

/** The Jira lists Thread keeps a copy of on this device. Each one is saved apart (D50). */
enum class JiraList {
    /** Open tickets of every issue type assigned to the connected account, latest change first. */
    Assigned,

    /** The same, only those in an active sprint ("Current sprint only", D50). */
    AssignedCurrentSprint,
}

/** Whether the connected Jira site has sprints, so "Current sprint only" can work there (D50). */
interface JiraSprints {
    /**
     * True when the site has Jira Software's Sprint field. Uses the answer saved on this device while it is less than
     * a day old; otherwise asks Jira when [mayAsk], and else falls back to an older saved answer. Null when unknown.
     */
    suspend fun hasSprints(
        nowMillis: Long,
        mayAsk: Boolean,
    ): Boolean?
}

/** Explicit search of everything in Jira the token can read (v3 Jira "Search"). Nothing is saved on this device. */
interface JiraSearch {
    /** Searches by key, words or label. A blank [query] is not sent. */
    suspend fun search(
        query: String,
        nowMillis: Long,
    ): ExternalResult<JiraIssueList>
}
