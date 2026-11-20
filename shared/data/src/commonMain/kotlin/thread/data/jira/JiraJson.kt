package thread.data.jira

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import thread.data.external.ExternalTime
import thread.data.external.arr
import thread.data.external.asArray
import thread.data.external.asObject
import thread.data.external.bool
import thread.data.external.copy
import thread.data.external.int
import thread.data.external.long
import thread.data.external.obj
import thread.data.external.objects
import thread.data.external.pick
import thread.data.external.putIfNotNull
import thread.data.external.str
import thread.domain.external.JiraAttachment
import thread.domain.external.JiraBoardCard
import thread.domain.external.JiraBoardConfig
import thread.domain.external.JiraBoardConfigColumn
import thread.domain.external.JiraBoardId
import thread.domain.external.JiraColumnLimits
import thread.domain.external.JiraColumnsNote
import thread.domain.external.JiraComment
import thread.domain.external.JiraFamily
import thread.domain.external.JiraFilter
import thread.domain.external.JiraFilterId
import thread.domain.external.JiraIssue
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraIssueList
import thread.domain.external.JiraIssueSummary
import thread.domain.external.JiraRelated
import thread.domain.external.JiraSite
import thread.domain.external.JiraSprint
import thread.domain.external.JiraSprintBoard
import thread.domain.external.JiraSprintId
import thread.domain.external.JiraSprintList
import thread.domain.external.JiraStatus
import thread.domain.external.JiraStatusCategory
import thread.domain.note.LinkHref
import thread.domain.time.LocalDay

/**
 * Reads Jira REST v3 answers (adr/ADR-006).
 *
 * [bundle] cuts the issue and comment answers down to what Thread shows: no account ids (also none in the mentions of
 * the description and comments), e-mails, avatars or other fields. Of an attachment it keeps the name, size, type and
 * date, not who added it or its file links (D75). The bundle is what the device stores, and [issue] reads it, so a
 * fresh answer and a saved copy go through the same code.
 */
internal object JiraJson {
    /** The schema type of Jira Software's Sprint field. Its field id differs per site, so `/field` finds it. */
    const val SPRINT_SCHEMA: String = "com.pyxis.greenhopper.jira:gh-sprint"

    /**
     * An opened ticket's fields. Of `parent` only the key is kept, to nest linked tickets in a Topic (D64). Attachments,
     * sub-tasks, fix versions and the due date come in the same answer (D75).
     */
    const val ISSUE_FIELDS: String =
        "summary,issuetype,status,priority,assignee,reporter,labels,updated,description,issuelinks,parent," +
            "attachment,subtasks,fixVersions,duedate"

    /** The fields of a list or search row (v3 `jiraRow`). */
    const val SUMMARY_FIELDS: String = "summary,issuetype,status,updated"

    /** The row fields and the parent, for the Topic suggestion search (D49 S4). Only the parent's key is kept. */
    const val FAMILY_FIELDS: String = "$SUMMARY_FIELDS,parent"

    private val ATTACHMENT_ID = Regex("^[0-9]{1,20}$")

    /** A custom field id, the only kind Thread puts in a `fields` list it did not write itself. */
    private val CUSTOM_FIELD = Regex("^customfield_[0-9]{1,18}$")

    /** A status id as Jira gives it: digits only. */
    private val STATUS_ID = Regex("^[0-9]{1,20}$")

    /** The schema type of team-managed projects' "Story point estimate" field. */
    private const val STORY_POINTS_SCHEMA = "com.pyxis.greenhopper.jira:jsw-story-points"

    /** A sprint board card's fields (D103 B), plus the story points field when there is one. */
    const val BOARD_FIELDS: String = "summary,issuetype,status,priority,assignee,parent,updated"

    fun sprintFieldId(fields: JsonElement): String? =
        fields
            .asArray()
            .orEmpty()
            .mapNotNull { it.asObject() }
            .firstOrNull { it.obj("schema")?.str("custom") == SPRINT_SCHEMA }
            ?.str("id")

    /**
     * The site's story points field (D103 B): a number field named "Story Points" (company-managed projects), else the
     * "Story point estimate" field of team-managed projects. Null when there is none.
     */
    fun storyPointsFieldId(fields: JsonElement): String? {
        val numbers =
            fields
                .asArray()
                .orEmpty()
                .mapNotNull { it.asObject() }
                .filter { field -> field.str("id")?.let(CUSTOM_FIELD::matches) == true && field.obj("schema")?.str("type") == "number" }
        val chosen =
            numbers.firstOrNull { it.str("name").equals("Story Points", ignoreCase = true) }
                ?: numbers.firstOrNull { it.obj("schema")?.str("custom") == STORY_POINTS_SCHEMA }
                ?: numbers.firstOrNull { it.str("name").equals("Story point estimate", ignoreCase = true) }
        return chosen?.str("id")
    }

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
                    putIfNotNull("description", AdfMapper.forStorage(fields["description"]))
                    val links = fields.objects("issuelinks").mapNotNull(::issueLink)
                    if (links.isNotEmpty()) put("issuelinks", JsonArray(links))
                    putIfNotNull("parent", fields.obj("parent")?.pick("key"))
                    val sprints = sprintFieldId?.let { fields.objects(it) }
                    if (!sprints.isNullOrEmpty()) put("sprint", JsonArray(sprints.map { it.pick("name", "state") }))
                    val attachments = fields.objects("attachment").mapNotNull(::attachment)
                    if (attachments.isNotEmpty()) put("attachment", JsonArray(attachments))
                    val subtasks = fields.objects("subtasks").mapNotNull(::summaryBundle)
                    if (subtasks.isNotEmpty()) put("subtasks", JsonArray(subtasks))
                    val versions = fields.objects("fixVersions").mapNotNull { it.str("name")?.let(::JsonPrimitive) }
                    if (versions.isNotEmpty()) put("fixVersions", JsonArray(versions))
                    copy(fields, "duedate")
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
                                putIfNotNull("body", AdfMapper.forStorage(comment["body"]))
                            }
                        }
                    put("comments", JsonArray(kept))
                },
            )
        }
    }

    /** One linked issue: the relation as this issue reads it ("is blocked by"), its key, title, status and type. */
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
            putIfNotNull("issuetype", fields?.obj("issuetype")?.pick("name"))
        }
    }

    /** One attachment: its id (digits only, as Jira gives it), name, size, type and date. */
    private fun attachment(file: JsonObject): JsonObject? {
        val id = file.str("id")?.takeIf { ATTACHMENT_ID.matches(it) } ?: return null
        if (file.str("filename").isNullOrBlank()) return null
        return buildJsonObject {
            put("id", JsonPrimitive(id))
            copy(file, "filename")
            copy(file, "size")
            copy(file, "mimeType")
            copy(file, "created")
        }
    }

    /** One page of the enhanced search, its issues cut down to the row fields. */
    fun searchPage(page: JsonElement): SearchPage? = searchPage(page, ::summaryBundle)

    /** One page of the enhanced search, its issues cut down by [cut]. */
    fun searchPage(
        page: JsonElement,
        cut: (JsonObject) -> JsonObject?,
    ): SearchPage? {
        val raw = page.asObject() ?: return null
        val issues = raw.arr("issues") ?: return null
        val token = raw.str("nextPageToken")
        return SearchPage(
            issues = issues.mapNotNull { it.asObject()?.let(cut) },
            nextPageToken = token,
            isLast = raw.bool("isLast") ?: (token == null),
        )
    }

    /**
     * The active sprints named by the Sprint field ([sprintFieldId]) of the account's tickets (D103 B), with the keys of
     * those tickets per sprint: `{sprints: [{id, name, boardId, startDate, endDate, goal, mine: [keys]}],
     * siteHasSprints: true}`. Closed and future sprints are left out.
     */
    fun mySprintsBundle(
        issues: List<JsonObject>,
        sprintFieldId: String,
    ): JsonObject {
        val sprints = linkedMapOf<String, JsonObject>()
        val mine = mutableMapOf<String, MutableSet<String>>()
        for (issue in issues) {
            val key = issue.str("key")?.let(JiraIssueKey::parse) ?: continue
            for (sprint in issue.obj("fields")?.objects(sprintFieldId).orEmpty()) {
                if (sprint.str("state") != "active") continue
                val id = idText(sprint, "id")?.let(JiraSprintId::parse) ?: continue
                val name = sprint.str("name")?.takeIf { it.isNotBlank() } ?: continue
                sprints.getOrPut(id.value) {
                    buildJsonObject {
                        put("id", JsonPrimitive(id.value))
                        put("name", JsonPrimitive(name))
                        idText(sprint, "boardId")?.let(JiraBoardId::parse)?.let { put("boardId", JsonPrimitive(it.value)) }
                        copy(sprint, "startDate")
                        copy(sprint, "endDate")
                        sprint.str("goal")?.takeIf { it.isNotBlank() }?.let { put("goal", JsonPrimitive(it)) }
                    }
                }
                mine.getOrPut(id.value) { linkedSetOf() } += key.value
            }
        }
        val kept =
            sprints.map { (id, sprint) ->
                JsonObject(sprint + ("mine" to JsonArray(mine[id].orEmpty().map(::JsonPrimitive))))
            }
        return buildJsonObject {
            put("sprints", JsonArray(kept))
            put("siteHasSprints", JsonPrimitive(true))
        }
    }

    /** The saved form for a site without a Sprint field. */
    fun noSprintsBundle(): JsonObject =
        buildJsonObject {
            put("sprints", JsonArray(emptyList()))
            put("siteHasSprints", JsonPrimitive(false))
        }

    /** A saved sprint list, the sprint with most of the account's tickets first, then by name. */
    fun sprintList(data: JsonObject): JiraSprintList =
        JiraSprintList(
            sprints =
                data
                    .objects("sprints")
                    .mapNotNull(::readSprint)
                    .distinctBy { it.id }
                    .sortedWith(compareByDescending<JiraSprint> { it.myKeys.size }.thenBy { it.name.lowercase() }),
            siteHasSprints = data.bool("siteHasSprints") ?: true,
        )

    private fun readSprint(sprint: JsonObject): JiraSprint? {
        val id = sprint.str("id")?.let(JiraSprintId::parse) ?: return null
        val name = sprint.str("name")?.takeIf { it.isNotBlank() } ?: return null
        return JiraSprint(
            id = id,
            name = name,
            boardId = sprint.str("boardId")?.let(JiraBoardId::parse),
            startMillis = ExternalTime.parseMillis(sprint.str("startDate")),
            endMillis = ExternalTime.parseMillis(sprint.str("endDate")),
            goal = sprint.str("goal")?.takeIf { it.isNotBlank() },
            myKeys =
                sprint
                    .arr("mine")
                    .orEmpty()
                    .mapNotNull(::issueKey)
                    .toSet(),
        )
    }

    /** An id Jira sends as a number or as text. */
    private fun idText(
        from: JsonObject,
        key: String,
    ): String? = from.long(key)?.toString() ?: from.str(key)

    /**
     * One board card (D103 B): key, title, type (with whether it is a sub-task), status with its id, priority, assignee
     * name, parent key and title, updated, and [pointsField]'s number as `points`. No account ids or avatars.
     */
    fun boardCardBundle(
        issue: JsonObject,
        pointsField: String?,
    ): JsonObject? {
        val fields = issue.obj("fields") ?: return null
        if (issue.str("key") == null || fields.str("summary") == null) return null
        return buildJsonObject {
            copy(issue, "key")
            put(
                "fields",
                buildJsonObject {
                    copy(fields, "summary")
                    putIfNotNull("issuetype", fields.obj("issuetype")?.pick("name", "subtask"))
                    putIfNotNull(
                        "status",
                        fields.obj("status")?.let { status ->
                            buildJsonObject {
                                status.str("id")?.takeIf(STATUS_ID::matches)?.let { put("id", JsonPrimitive(it)) }
                                copy(status, "name")
                                putIfNotNull("statusCategory", status.obj("statusCategory")?.pick("key"))
                            }
                        },
                    )
                    putIfNotNull("priority", fields.obj("priority")?.pick("name"))
                    putIfNotNull("assignee", fields.obj("assignee")?.pick("displayName"))
                    putIfNotNull(
                        "parent",
                        fields.obj("parent")?.let { parent ->
                            buildJsonObject {
                                copy(parent, "key")
                                parent.obj("fields")?.let { copy(it, "summary") }
                            }
                        },
                    )
                    copy(fields, "updated")
                    pointsField
                        ?.let { fields[it] as? JsonPrimitive }
                        ?.takeIf { !it.isString && it.doubleOrNull != null }
                        ?.let { put("points", it) }
                },
            )
        }
    }

    private fun boardCard(bundle: JsonObject): JiraBoardCard? {
        val key = bundle.str("key")?.let(JiraIssueKey::parse) ?: return null
        val fields = bundle.obj("fields") ?: return null
        val parent = fields.obj("parent")
        return JiraBoardCard(
            key = key,
            type = fields.obj("issuetype")?.str("name"),
            isSubtask = fields.obj("issuetype")?.bool("subtask") == true,
            title = fields.str("summary") ?: return null,
            status = fields.obj("status")?.let(::readStatus),
            statusId = fields.obj("status")?.str("id"),
            priority = fields.obj("priority")?.str("name"),
            assignee = fields.obj("assignee")?.str("displayName"),
            parentKey = parent?.str("key")?.let(JiraIssueKey::parse),
            parentTitle = parent?.str("summary"),
            storyPoints = (fields["points"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull,
            updatedAtMillis = ExternalTime.parseMillis(fields.str("updated")),
        )
    }

    /**
     * A board's settings answer cut down to `{name, columns: [{name, statuses: [ids], min, max}], constraintType,
     * estimationField}`: no filter, location, owner or links. Null when it has no column settings.
     */
    fun boardConfigBundle(answer: JsonElement): JsonObject? {
        val raw = answer.asObject() ?: return null
        val columnConfig = raw.obj("columnConfig") ?: return null
        val columns =
            columnConfig.objects("columns").mapNotNull { column ->
                val name = column.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                buildJsonObject {
                    put("name", JsonPrimitive(name))
                    val statuses = column.objects("statuses").mapNotNull { idText(it, "id")?.takeIf(STATUS_ID::matches) }
                    put("statuses", JsonArray(statuses.map(::JsonPrimitive)))
                    column.int("min")?.takeIf { it >= 0 }?.let { put("min", JsonPrimitive(it)) }
                    column.int("max")?.takeIf { it >= 0 }?.let { put("max", JsonPrimitive(it)) }
                }
            }
        return buildJsonObject {
            raw.str("name")?.takeIf { it.isNotBlank() }?.let { put("name", JsonPrimitive(it)) }
            put("columns", JsonArray(columns))
            copy(columnConfig, "constraintType")
            raw
                .obj("estimation")
                ?.takeIf { it.str("type") == "field" }
                ?.obj("field")
                ?.str("fieldId")
                ?.takeIf(CUSTOM_FIELD::matches)
                ?.let { put("estimationField", JsonPrimitive(it)) }
        }
    }

    fun boardConfig(data: JsonObject): JiraBoardConfig =
        JiraBoardConfig(
            name = data.str("name"),
            columns =
                data.objects("columns").mapNotNull { column ->
                    JiraBoardConfigColumn(
                        name = column.str("name") ?: return@mapNotNull null,
                        statusIds =
                            column
                                .arr("statuses")
                                .orEmpty()
                                .mapNotNull(::string)
                                .toSet(),
                        min = column.int("min"),
                        max = column.int("max"),
                    )
                },
            limits =
                when (data.str("constraintType")) {
                    "none" -> JiraColumnLimits.None
                    "issueCountExclSubs" -> JiraColumnLimits.IssueCountExcludingSubtasks
                    else -> JiraColumnLimits.IssueCount
                },
        )

    /** The board's estimate field, when it estimates with a custom number field such as Story Points. */
    fun estimationField(config: JsonObject): String? = config.str("estimationField")?.takeIf(CUSTOM_FIELD::matches)

    /**
     * A saved sprint board: `{issues: [cards], complete, config?: board settings, columnsNote?: "scopes" or
     * "unavailable"}`.
     */
    fun sprintBoardBundle(
        issues: List<JsonObject>,
        complete: Boolean,
        config: JsonObject?,
        note: JiraColumnsNote?,
    ): JsonObject =
        buildJsonObject {
            put("issues", JsonArray(issues))
            put("complete", JsonPrimitive(complete))
            putIfNotNull("config", config)
            when (note) {
                JiraColumnsNote.NeedsScopes -> put("columnsNote", JsonPrimitive("scopes"))
                JiraColumnsNote.Unavailable -> put("columnsNote", JsonPrimitive("unavailable"))
                null -> Unit
            }
        }

    fun sprintBoard(data: JsonObject): JiraSprintBoard {
        val config = data.obj("config")?.let(::boardConfig)
        return JiraSprintBoard(
            cards = data.objects("issues").mapNotNull(::boardCard).distinctBy { it.key },
            complete = data.bool("complete") ?: true,
            config = config,
            columnsNote =
                when {
                    config != null -> null
                    data.str("columnsNote") == "scopes" -> JiraColumnsNote.NeedsScopes
                    else -> JiraColumnsNote.Unavailable
                },
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
                    putIfNotNull("parent", fields.obj("parent")?.pick("key"))
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
            parentKey = fields.obj("parent")?.str("key")?.let(JiraIssueKey::parse),
        )
    }

    /** A saved list: `{issues: [cut-down issues], complete}`. */
    fun issueList(data: JsonObject): JiraIssueList =
        JiraIssueList(
            issues = data.objects("issues").mapNotNull(::summary),
            complete = data.bool("complete") ?: true,
        )

    /** A saved family (D49 S4): `{keys: [linked keys asked about], issues: [cut-down issues with their parent]}`. */
    fun familyBundle(
        keys: List<JiraIssueKey>,
        issues: List<JsonObject>,
    ): JsonObject =
        buildJsonObject {
            put("keys", JsonArray(keys.map { JsonPrimitive(it.value) }))
            put("issues", JsonArray(issues))
        }

    fun family(data: JsonObject): JiraFamily =
        JiraFamily(
            keys = data.arr("keys").orEmpty().mapNotNull(::issueKey),
            rows = data.objects("issues").mapNotNull(::summary),
        )

    private fun issueKey(element: JsonElement): JiraIssueKey? = string(element)?.let(JiraIssueKey::parse)

    /**
     * The starred filters answer (`GET /filter/favourite`, a plain array) cut down to `{filters: [{id, name, jql}]}`,
     * in Jira's order: no owner, share permissions or links. Null when the answer is not an array.
     */
    fun starredFilters(answer: JsonElement): JsonObject? {
        val filters = answer.asArray() ?: return null
        val kept = filters.mapNotNull { it.asObject()?.let(::filterBundle) }
        return buildJsonObject { put("filters", JsonArray(kept)) }
    }

    /** A saved starred list. */
    fun filters(data: JsonObject): List<JiraFilter> = data.objects("filters").mapNotNull(::readFilter).distinctBy { it.id }

    /** One filter answer (`GET /filter/{id}`), or null when it has no usable id or name. */
    fun filter(answer: JsonElement): JiraFilter? = answer.asObject()?.let(::filterBundle)?.let(::readFilter)

    private fun filterBundle(filter: JsonObject): JsonObject? {
        val id = (filter.str("id") ?: filter.long("id")?.toString())?.let(JiraFilterId::parse) ?: return null
        val name = filter.str("name")?.takeIf { it.isNotBlank() } ?: return null
        return buildJsonObject {
            put("id", JsonPrimitive(id.value))
            put("name", JsonPrimitive(name))
            copy(filter, "jql")
        }
    }

    private fun readFilter(filter: JsonObject): JiraFilter? {
        val id = filter.str("id")?.let(JiraFilterId::parse) ?: return null
        val name = filter.str("name")?.takeIf { it.isNotBlank() } ?: return null
        return JiraFilter(id, name, filter.str("jql"))
    }

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
            labels = fields.arr("labels").orEmpty().mapNotNull(::string),
            updatedAtMillis = ExternalTime.parseMillis(fields.str("updated")),
            description = AdfMapper.document(fields["description"]),
            comments = comments,
            commentTotal = maxOf(page?.int("total") ?: 0, comments.size),
            webUrl = LinkHref.parse("https://${site.host}/browse/${key.value}"),
            related = fields.objects("issuelinks").mapNotNull(::related),
            parentKey = fields.obj("parent")?.str("key")?.let(JiraIssueKey::parse),
            attachments = fields.objects("attachment").mapNotNull { attachment(it, site) },
            subtasks = fields.objects("subtasks").mapNotNull(::summary),
            fixVersions =
                fields
                    .arr("fixVersions")
                    .orEmpty()
                    .mapNotNull(::string)
                    .filter { it.isNotBlank() },
            dueDate = fields.str("duedate")?.let(LocalDay::parse),
        )
    }

    /** Opens on the Jira site with the person's own Jira sign-in. Thread never fetches the file (D75). */
    private fun attachment(
        file: JsonObject,
        site: JiraSite,
    ): JiraAttachment? {
        val id = file.str("id")?.takeIf { ATTACHMENT_ID.matches(it) } ?: return null
        val name = file.str("filename")?.takeIf { it.isNotBlank() } ?: return null
        return JiraAttachment(
            id = id,
            filename = name,
            sizeBytes = file.long("size")?.takeIf { it >= 0 },
            mimeType = file.str("mimeType")?.takeIf { it.isNotBlank() },
            createdAtMillis = ExternalTime.parseMillis(file.str("created")),
            openUrl = LinkHref.parse("https://${site.host}/rest/api/3/attachment/content/$id"),
        )
    }

    private fun string(element: JsonElement): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun related(link: JsonObject): JiraRelated? {
        val key = link.str("key")?.let(JiraIssueKey::parse) ?: return null
        val relation = link.str("relation") ?: return null
        return JiraRelated(relation, key, link.str("summary"), link.obj("status")?.let(::readStatus), link.obj("issuetype")?.str("name"))
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
