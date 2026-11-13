package thread.domain.external

import thread.domain.note.LinkHref
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
 * issue links, as Jira lists them.
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
) {
    fun summary(): JiraIssueSummary = JiraIssueSummary(key, type, title, status, updatedAtMillis)
}

/** One issue link: how it relates ("blocks", "is blocked by"), and the other issue as Jira names it. */
data class JiraRelated(
    val relation: String,
    val key: JiraIssueKey,
    val title: String?,
    val status: JiraStatus?,
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

/** One row of a Jira list or search result (v3 `jiraRow`). */
data class JiraIssueSummary(
    val key: JiraIssueKey,
    val type: String?,
    val title: String,
    val status: JiraStatus?,
    val updatedAtMillis: Long?,
)

/** Tickets in Jira's order. [complete] is false when Jira had more than Thread fetched. */
data class JiraIssueList(
    val issues: List<JiraIssueSummary>,
    val complete: Boolean,
)

/** The Jira lists Thread keeps a copy of on this device. */
enum class JiraList {
    /** Open tickets assigned to the connected account, latest change first. */
    Assigned,
}

/** Explicit search of everything in Jira the token can read (v3 Jira "Search"). Nothing is saved on this device. */
interface JiraSearch {
    /** Searches by key, words or label. A blank [query] is not sent. */
    suspend fun search(
        query: String,
        nowMillis: Long,
    ): ExternalResult<JiraIssueList>
}
