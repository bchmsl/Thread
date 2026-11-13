package thread.data.jira

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import thread.data.external.ExternalTime
import thread.data.external.arr
import thread.data.external.asArray
import thread.data.external.asObject
import thread.data.external.bool
import thread.data.external.copy
import thread.data.external.int
import thread.data.external.obj
import thread.data.external.objects
import thread.data.external.pick
import thread.data.external.putIfNotNull
import thread.data.external.str
import thread.domain.external.JiraComment
import thread.domain.external.JiraIssue
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraIssueList
import thread.domain.external.JiraIssueSummary
import thread.domain.external.JiraRelated
import thread.domain.external.JiraSite
import thread.domain.external.JiraStatus
import thread.domain.external.JiraStatusCategory
import thread.domain.note.LinkHref

/**
 * Reads Jira REST v3 answers (adr/ADR-006).
 *
 * [bundle] cuts the issue and comment answers down to what Thread shows: no account ids, e-mails, avatars or other
 * fields. The bundle is what the device stores, and [issue] reads it, so a fresh answer and a saved copy go through
 * the same code.
 */
internal object JiraJson {
    /** The schema type of Jira Software's Sprint field. Its field id differs per site, so `/field` finds it. */
    const val SPRINT_SCHEMA: String = "com.pyxis.greenhopper.jira:gh-sprint"

    const val ISSUE_FIELDS: String = "summary,issuetype,status,priority,assignee,reporter,labels,updated,description,issuelinks"

    /** The fields of a list or search row (v3 `jiraRow`). */
    const val SUMMARY_FIELDS: String = "summary,issuetype,status,updated"

    fun sprintFieldId(fields: JsonElement): String? =
        fields
            .asArray()
            .orEmpty()
            .mapNotNull { it.asObject() }
            .firstOrNull { it.obj("schema")?.str("custom") == SPRINT_SCHEMA }
            ?.str("id")

    /** [comments] is a comment page fetched newest first; the bundle keeps it oldest first. */
    fun bundle(
        issue: JsonElement,
        comments: JsonElement,
        sprintFieldId: String?,
    ): JsonObject? {
        val raw = issue.asObject() ?: return null
        val fields = raw.obj("fields") ?: return null
        val page = comments.asObject() ?: return null
        return buildJsonObject {
            copy(raw, "key")
            put(
                "fields",
                buildJsonObject {
                    copy(fields, "summary")
                    putIfNotNull("issuetype", fields.obj("issuetype")?.pick("name"))
                    putIfNotNull("status", fields.obj("status")?.let(::status))
                    putIfNotNull("priority", fields.obj("priority")?.pick("name"))
                    putIfNotNull("assignee", fields.obj("assignee")?.pick("displayName"))
                    putIfNotNull("reporter", fields.obj("reporter")?.pick("displayName"))
                    copy(fields, "labels")
                    copy(fields, "updated")
                    copy(fields, "description")
                    val links = fields.objects("issuelinks").mapNotNull(::issueLink)
                    if (links.isNotEmpty()) put("issuelinks", JsonArray(links))
                    val sprints = sprintFieldId?.let { fields.objects(it) }
                    if (!sprints.isNullOrEmpty()) put("sprint", JsonArray(sprints.map { it.pick("name", "state") }))
                },
            )
            put(
                "comments",
                buildJsonObject {
                    copy(page, "total")
                    val kept =
                        page.objects("comments").reversed().map { comment ->
                            buildJsonObject {
                                copy(comment, "id")
                                putIfNotNull("author", comment.obj("author")?.pick("displayName"))
                                copy(comment, "created")
                                copy(comment, "body")
                            }
                        }
                    put("comments", JsonArray(kept))
                },
            )
        }
    }

    /** One linked issue: the relation as this issue reads it ("is blocked by"), its key, title and status. */
    private fun issueLink(link: JsonObject): JsonObject? {
        val type = link.obj("type") ?: return null
        val outward = link.obj("outwardIssue")
        val other = outward ?: link.obj("inwardIssue") ?: return null
        val relation = (if (outward != null) type.str("outward") else type.str("inward")) ?: type.str("name") ?: return null
        val fields = other.obj("fields")
        return buildJsonObject {
            put("relation", JsonPrimitive(relation))
            copy(other, "key")
            if (fields != null) copy(fields, "summary")
            putIfNotNull("status", fields?.obj("status")?.let(::status))
        }
    }

    /** One page of the enhanced search, its issues cut down to the row fields. */
    fun searchPage(page: JsonElement): SearchPage? {
        val raw = page.asObject() ?: return null
        val issues = raw.arr("issues") ?: return null
        val token = raw.str("nextPageToken")
        return SearchPage(
            issues = issues.mapNotNull { it.asObject()?.let(::summaryBundle) },
            nextPageToken = token,
            isLast = raw.bool("isLast") ?: (token == null),
        )
    }

    private fun summaryBundle(issue: JsonObject): JsonObject? {
        val fields = issue.obj("fields") ?: return null
        if (issue.str("key") == null || fields.str("summary") == null) return null
        return buildJsonObject {
            copy(issue, "key")
            put(
                "fields",
                buildJsonObject {
                    copy(fields, "summary")
                    putIfNotNull("issuetype", fields.obj("issuetype")?.pick("name"))
                    putIfNotNull("status", fields.obj("status")?.let(::status))
                    copy(fields, "updated")
                },
            )
        }
    }

    /** A row from a cut-down issue, or null when the key or title is missing. */
    fun summary(bundle: JsonObject): JiraIssueSummary? {
        val key = bundle.str("key")?.let(JiraIssueKey::parse) ?: return null
        val fields = bundle.obj("fields") ?: return null
        return JiraIssueSummary(
            key = key,
            type = fields.obj("issuetype")?.str("name"),
            title = fields.str("summary") ?: return null,
            status = fields.obj("status")?.let(::readStatus),
            updatedAtMillis = ExternalTime.parseMillis(fields.str("updated")),
        )
    }

    /** A saved list: `{issues: [cut-down issues], complete}`. */
    fun issueList(data: JsonObject): JiraIssueList =
        JiraIssueList(
            issues = data.objects("issues").mapNotNull(::summary),
            complete = data.bool("complete") ?: true,
        )

    private fun status(status: JsonObject): JsonObject =
        buildJsonObject {
            copy(status, "name")
            putIfNotNull("statusCategory", status.obj("statusCategory")?.pick("key"))
        }

    /** The issue in [bundle], or null when the required key or summary is missing. */
    fun issue(
        bundle: JsonObject,
        site: JiraSite,
    ): JiraIssue? {
        val key = bundle.str("key")?.let(JiraIssueKey::parse) ?: return null
        val fields = bundle.obj("fields") ?: return null
        val title = fields.str("summary") ?: return null
        val page = bundle.obj("comments")
        val comments = page?.objects("comments").orEmpty().mapNotNull(::comment)
        return JiraIssue(
            key = key,
            type = fields.obj("issuetype")?.str("name"),
            title = title,
            status = fields.obj("status")?.let(::readStatus),
            priority = fields.obj("priority")?.str("name"),
            assignee = fields.obj("assignee")?.str("displayName"),
            reporter = fields.obj("reporter")?.str("displayName"),
            sprint = sprint(fields.objects("sprint")),
            labels = fields.arr("labels").orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { label -> label.isString }?.content },
            updatedAtMillis = ExternalTime.parseMillis(fields.str("updated")),
            description = AdfMapper.document(fields["description"]),
            comments = comments,
            commentTotal = maxOf(page?.int("total") ?: 0, comments.size),
            webUrl = LinkHref.parse("https://${site.host}/browse/${key.value}"),
            related = fields.objects("issuelinks").mapNotNull(::related),
        )
    }

    private fun related(link: JsonObject): JiraRelated? {
        val key = link.str("key")?.let(JiraIssueKey::parse) ?: return null
        val relation = link.str("relation") ?: return null
        return JiraRelated(relation, key, link.str("summary"), link.obj("status")?.let(::readStatus))
    }

    private fun readStatus(status: JsonObject): JiraStatus? {
        val name = status.str("name") ?: return null
        val category =
            when (status.obj("statusCategory")?.str("key")) {
                "new" -> JiraStatusCategory.ToDo
                "indeterminate" -> JiraStatusCategory.InProgress
                "done" -> JiraStatusCategory.Done
                else -> JiraStatusCategory.Unknown
            }
        return JiraStatus(name, category)
    }

    /** The active sprint, else the next one, else the last one listed. */
    private fun sprint(sprints: List<JsonObject>): String? {
        val chosen =
            sprints.firstOrNull { it.str("state") == "active" }
                ?: sprints.firstOrNull { it.str("state") == "future" }
                ?: sprints.lastOrNull()
        return chosen?.str("name")
    }

    private fun comment(comment: JsonObject): JiraComment? {
        val id = comment.str("id") ?: return null
        return JiraComment(
            id = id,
            author = comment.obj("author")?.str("displayName"),
            createdAtMillis = ExternalTime.parseMillis(comment.str("created")),
            body = AdfMapper.document(comment["body"]),
        )
    }
}

/** One page of the enhanced JQL search. */
internal class SearchPage(
    val issues: List<JsonObject>,
    val nextPageToken: String?,
    val isLast: Boolean,
)
