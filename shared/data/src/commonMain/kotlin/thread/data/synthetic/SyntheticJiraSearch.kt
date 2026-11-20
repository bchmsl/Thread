package thread.data.synthetic

import thread.data.jira.JiraQuery

/*
 * SYNTHETIC Jira tickets for the Jira page lists and search (Phase 10). Every key, title, label and person is made
 * up. The search here reads only the JQL that Thread itself sends; it is not a JQL parser.
 */

/**
 * One SYNTHETIC ticket as the list and search answers show it. [mine] means assigned to the synthetic account
 * ([PERSON_A]); [assignee] is who it is assigned to, null for nobody. [parent] is its parent's key, for the Topic
 * suggestion search (D49 S4). [points] are its story points on the sprint board (D103 B). Which sprints it is in is
 * kept by [SyntheticJiraSearch.sprints].
 */
internal data class SyntheticTicket(
    val key: String,
    val type: String,
    val title: String,
    val status: String,
    val category: String,
    val labels: List<String>,
    val mine: Boolean,
    val updated: String,
    val text: String = "",
    val parent: String? = null,
    val priority: String = "Medium",
    val assignee: String? = if (mine) PERSON_A else PERSON_C,
    val points: Double? = null,
)

/** SYNTHETIC people: display names only. A is the synthetic account itself. */
internal const val PERSON_A = "Synthetic Person A"
internal const val PERSON_B = "Synthetic Person B"
internal const val PERSON_C = "Synthetic Person C"

/**
 * A SYNTHETIC sprint (D103 B) as the Sprint field shows it. [rank] is its tickets in the board's rank order. Board 7
 * has settings; board 8 answers 401 "scope does not match", like a token without the board scopes.
 */
internal class SyntheticSprint(
    val id: Int,
    val name: String,
    val state: String,
    val boardId: Int,
    val start: String,
    val end: String,
    val goal: String?,
    val rank: List<String>,
)

internal object SyntheticJiraSearch {
    val tickets: List<SyntheticTicket> =
        listOf(
            SyntheticTicket(
                "SYN-101",
                "Story",
                "SYNTHETIC: Show Jira tickets read-only in Thread",
                "In Review",
                "indeterminate",
                listOf("synthetic", "m1-probe"),
                mine = true,
                updated = "2026-11-12T16:42:10.123+0400",
                parent = "SYN-110",
                priority = "High",
                points = 5.0,
            ),
            SyntheticTicket(
                "SYN-102",
                "Bug",
                "SYNTHETIC: A finished ticket without a description",
                "Done",
                "done",
                emptyList(),
                mine = false,
                updated = "2026-11-02T09:00:00.000+0000",
                priority = "Low",
                points = 1.0,
            ),
            SyntheticTicket(
                "SYN-103",
                "Task",
                "SYNTHETIC: Offline banner copy for the Jira page",
                "To Do",
                "new",
                listOf("synthetic", "copy"),
                mine = true,
                updated = "2026-11-12T09:10:00.000+0400",
                text = "Write the offline banner text. Keep it short.",
                parent = "SYN-110",
            ),
            SyntheticTicket(
                "SYN-104",
                "Bug",
                "SYNTHETIC: Rate limit message when Jira slows down",
                "In Progress",
                "indeterminate",
                listOf("synthetic", "errors"),
                mine = true,
                updated = "2026-11-11T17:20:00.000+0400",
                text = "Show when the next refresh can run. Never retry in a loop.",
                priority = "Highest",
                points = 3.0,
            ),
            SyntheticTicket(
                "SYN-105",
                "Story",
                "SYNTHETIC: Link one ticket to several Topics",
                "To Do",
                "new",
                listOf("synthetic", "links"),
                mine = true,
                updated = "2026-11-10T12:00:00.000+0400",
                text = "One ticket can belong to several Topics. Nothing is copied.",
                parent = "SYN-110",
            ),
            SyntheticTicket(
                "SYN-106",
                "Story",
                "SYNTHETIC: Search tickets by key, words or label",
                "In Progress",
                "indeterminate",
                listOf("synthetic", "search"),
                mine = false,
                updated = "2026-11-09T15:45:00.000+0400",
                text = "Search all of Jira that the account can see.",
                points = 8.0,
            ),
            SyntheticTicket(
                "SYN-107",
                "Task",
                "SYNTHETIC: Keep saved copies readable after the token expires",
                "Done",
                "done",
                listOf("synthetic"),
                mine = true,
                updated = "2026-11-08T10:30:00.000+0400",
                text = "Saved copies stay readable. Reconnect to refresh them.",
                points = 2.0,
            ),
            SyntheticTicket(
                "SYN-108",
                "Story",
                "SYNTHETIC: Payment screen copy review",
                "To Do",
                "new",
                listOf("payments"),
                mine = false,
                updated = "2026-11-07T11:00:00.000+0400",
                text = "Review the payment screen texts with the writer.",
                points = 5.0,
            ),
            // Every issue type shows in "Assigned to me" (D50): a sub-task, an epic and a site's own custom type.
            SyntheticTicket(
                "SYN-109",
                "Sub-task",
                "SYNTHETIC: Check the empty state wording",
                "In Progress",
                "indeterminate",
                listOf("synthetic"),
                mine = true,
                updated = "2026-11-12T11:00:00.000+0400",
                text = "A sub-task of a synthetic story.",
                parent = "SYN-101",
            ),
            SyntheticTicket(
                "SYN-110",
                "Epic",
                "SYNTHETIC: Workspace polish epic",
                "To Do",
                "new",
                listOf("synthetic"),
                mine = true,
                updated = "2026-11-06T10:00:00.000+0400",
                text = "Groups the polish stories.",
            ),
            SyntheticTicket(
                "SYN-111",
                "Synthetic Incident",
                "SYNTHETIC: Custom issue type row",
                "Triage",
                "new",
                listOf("synthetic"),
                mine = true,
                updated = "2026-11-11T09:00:00.000+0400",
                text = "An issue type this site made itself.",
            ),
            // Owner changes #9 and #10 (D63, D64): the other common type names, and a parent that is easy to leave
            // unlinked. Not assigned to the synthetic account, so "Assigned to me" does not change.
            SyntheticTicket(
                "SYN-112",
                "Bug",
                "SYNTHETIC: Link picker loses focus after Undo",
                "To Do",
                "new",
                listOf("synthetic"),
                mine = false,
                updated = "2026-11-05T10:00:00.000+0400",
                text = "A bug under a story.",
                parent = "SYN-105",
                priority = "Low",
                assignee = PERSON_B,
                points = 1.0,
            ),
            SyntheticTicket(
                "SYN-113",
                "Defect",
                "SYNTHETIC: Sync badge shows the wrong count",
                "In Progress",
                "indeterminate",
                listOf("synthetic"),
                mine = false,
                updated = "2026-11-05T09:00:00.000+0400",
                text = "A site's Defect type.",
                priority = "High",
                assignee = PERSON_B,
                points = 2.0,
            ),
            SyntheticTicket(
                "SYN-114",
                "Subtask",
                "SYNTHETIC: Write the banner tests",
                "To Do",
                "new",
                listOf("synthetic"),
                mine = false,
                updated = "2026-11-04T09:00:00.000+0400",
                text = "A subtask, as newer sites name the type.",
                parent = "SYN-103",
            ),
        )

    /**
     * SYNTHETIC tickets that only the sprint board (D103 B), a ticket lookup and a key search see, so the lists,
     * filters, search and Topic suggestions built on [tickets] keep their answers. None is the synthetic account's.
     */
    private val boardOnly: List<SyntheticTicket> =
        listOf(
            SyntheticTicket(
                "SYN-115",
                "Story",
                "SYNTHETIC: Let a long sprint board card title wrap to three lines and then stop, so a column of cards " +
                    "stays easy to scan even when someone writes a whole sentence as the title",
                "To Do",
                "new",
                listOf("synthetic"),
                mine = false,
                updated = "2026-11-17T10:00:00.000+0400",
                text = "A long title.",
                parent = "SYN-110",
                priority = "Lowest",
                assignee = null,
                points = 13.0,
            ),
            SyntheticTicket(
                "SYN-116",
                "Task",
                "SYNTHETIC: Column counts in the board head",
                "In Review",
                "indeterminate",
                listOf("synthetic"),
                mine = false,
                updated = "2026-11-18T12:00:00.000+0400",
                text = "Each column says how many tickets it holds.",
                assignee = PERSON_B,
                points = 2.0,
            ),
            SyntheticTicket(
                "SYN-117",
                "Story",
                "SYNTHETIC: Sprint dates and days left",
                "Done",
                "done",
                listOf("synthetic"),
                mine = false,
                updated = "2026-11-17T16:00:00.000+0400",
                text = "The board head shows the sprint dates.",
                assignee = PERSON_B,
                points = 3.0,
            ),
            SyntheticTicket(
                "SYN-118",
                "Task",
                "SYNTHETIC: Only my issues switch",
                "To Do",
                "new",
                listOf("synthetic"),
                mine = false,
                updated = "2026-11-16T15:00:00.000+0400",
                text = "Shows only the account's own cards.",
            ),
            SyntheticTicket(
                "SYN-119",
                "Bug",
                "SYNTHETIC: Mobile list jumps after a refresh",
                "In Progress",
                "indeterminate",
                listOf("mobile"),
                mine = false,
                updated = "2026-11-19T09:00:00.000+0400",
                text = "The mobile list scrolls to the top after a refresh.",
                priority = "High",
                assignee = PERSON_B,
                points = 3.0,
            ),
            SyntheticTicket(
                "SYN-120",
                "Story",
                "SYNTHETIC: Mobile empty state picture",
                "To Do",
                "new",
                listOf("mobile"),
                mine = false,
                updated = "2026-11-18T09:00:00.000+0400",
                text = "A picture for the empty mobile list.",
                assignee = null,
            ),
        )

    /** SYNTHETIC sprints (D103 B): one closed and two active ones on boards 7 and 8. */
    val sprints: List<SyntheticSprint> =
        listOf(
            SyntheticSprint(
                id = 36,
                name = "SYN Sprint 6",
                state = "closed",
                boardId = 7,
                start = "2026-11-02T09:00:00.000Z",
                end = "2026-11-13T17:00:00.000Z",
                goal = "SYNTHETIC goal: Finish the M1 probe.",
                rank = listOf("SYN-101", "SYN-104"),
            ),
            SyntheticSprint(
                id = 37,
                name = "SYN Sprint 7",
                state = "active",
                boardId = 7,
                start = "2026-11-16T09:00:00.000Z",
                end = "2026-11-30T17:00:00.000Z",
                goal =
                    "SYNTHETIC goal: Ship the read-only sprint board and the starred filters, finish the offline banner " +
                        "work, and leave the mobile list for the next sprint unless the review asks for it.",
                rank =
                    listOf(
                        "SYN-104",
                        "SYN-101",
                        "SYN-106",
                        "SYN-109",
                        "SYN-113",
                        "SYN-115",
                        "SYN-112",
                        "SYN-116",
                        "SYN-118",
                        "SYN-114",
                        "SYN-111",
                        "SYN-102",
                        "SYN-117",
                    ),
            ),
            SyntheticSprint(
                id = 41,
                name = "SYN Mobile Sprint 3",
                state = "active",
                boardId = 8,
                start = "2026-11-18T09:00:00.000Z",
                end = "2026-12-02T17:00:00.000Z",
                goal = null,
                rank = listOf("SYN-119", "SYN-108", "SYN-120", "SYN-107"),
            ),
        )

    /** Status ids, as the board settings map them (D103 B). Triage is on no column of board 7. */
    private val STATUS_IDS: Map<String, String> =
        mapOf("To Do" to "10001", "In Progress" to "10002", "In Review" to "10003", "Done" to "10004", "Triage" to "10005")

    private val SPRINT = Regex("^sprint = ([0-9]+) ORDER BY Rank ASC$")

    /** Whether [ticket] is in a sprint that is not completed, as `sprint in openSprints()` reads it. */
    private fun inOpenSprint(ticket: SyntheticTicket): Boolean = sprints.any { it.state != "closed" && ticket.key in it.rank }

    /** The SYNTHETIC starred filters' own queries (D103). Matched exactly; this is not a JQL parser. */
    const val FILTER_MY_OPEN_BUGS: String =
        "assignee = currentUser() AND issuetype in (Bug, Defect) AND statusCategory != Done ORDER BY priority DESC, updated DESC"
    const val FILTER_TEAM_IN_PROGRESS: String = "project = SYN AND statusCategory = \"In Progress\" ORDER BY updated DESC"
    const val FILTER_EVERYTHING: String = "project = SYN ORDER BY updated DESC"

    /** A query Jira refuses (400): it names a field the site does not have. */
    const val FILTER_BROKEN: String = "project = SYN AND \"Synthetic Team\" = Mobile ORDER BY updated DESC"

    private val FILTERS: Map<String, (SyntheticTicket) -> Boolean> =
        mapOf(
            FILTER_MY_OPEN_BUGS to { it.mine && it.type in setOf("Bug", "Defect") && it.category != "done" },
            FILTER_TEAM_IN_PROGRESS to { it.category == "indeterminate" },
            FILTER_EVERYTHING to { true },
        )

    private const val OPEN_SPRINTS = "sprint in openSprints()"
    private val KEY = Regex("key = \"([A-Z][A-Z0-9_]*-[0-9]+)\"")
    private val TEXT = Regex("text ~ \"([^\"]*)\"")
    private val LABEL = Regex("labels = \"([^\"]*)\"")
    private val IN_LIST = Regex("^(key|parent) in \\(([^)]*)\\)")
    private val QUOTED_KEY = Regex("\"([A-Z][A-Z0-9_]*-[0-9]+)\"")

    /**
     * The tickets at fixture [revision] (D67): from 1, SYN-103 moved to In Progress, so a row shows a new status
     * between two calls.
     */
    private fun ticketsAt(revision: Int): List<SyntheticTicket> =
        if (revision < 1) {
            tickets
        } else {
            tickets.map { ticket ->
                if (ticket.key != "SYN-103") {
                    ticket
                } else {
                    ticket.copy(status = "In Progress", category = "indeterminate", updated = "2026-11-16T09:00:00.000+0400")
                }
            }
        }

    /** Every ticket a key can find: [tickets] and the board's own. */
    private fun knownAt(revision: Int): List<SyntheticTicket> = ticketsAt(revision) + boardOnly

    /** The status and body of a `GET /rest/api/3/search/jql` answer for [query] (the URL query string). */
    fun answer(
        query: String,
        revision: Int = 0,
    ): Pair<Int, String> {
        val tickets = ticketsAt(revision)
        val known = knownAt(revision)
        val params = query.split('&').associate { it.substringBefore('=') to percentDecode(it.substringAfter('=', "")) }
        val jql = params["jql"] ?: return 400 to error("The JQL query is missing.")
        val pageSize = params["maxResults"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
        val start = params["nextPageToken"]?.removePrefix("synthetic-page-")?.toIntOrNull() ?: 0
        if (jql == FILTER_BROKEN) return 400 to error("Field 'Synthetic Team' does not exist or you do not have permission to view it.")
        val inList = IN_LIST.find(jql)
        val filter = FILTERS[jql]
        val sprint = SPRINT.matchEntire(jql)
        val found =
            if (sprint != null) {
                // A sprint's tickets in the board's rank order (D103 B).
                val id = sprint.groupValues[1]
                val chosen =
                    sprints.firstOrNull { it.id.toString() == id }
                        ?: return 400 to error("Sprint with id $id does not exist or you do not have permission to view it.")
                chosen.rank.mapNotNull { key -> known.firstOrNull { it.key == key } }
            } else {
                when {
                    filter != null -> tickets.filter(filter)
                    inList != null -> {
                        val (field, list) = inList.destructured
                        val keys = QUOTED_KEY.findAll(list).map { it.groupValues[1] }.toList()
                        keys.firstOrNull { key -> known.none { it.key == key } }?.let { missing ->
                            return 400 to error("An issue with key '$missing' does not exist for field '$field'.")
                        }
                        if (field == "key") known.filter { it.key in keys } else tickets.filter { it.parent in keys }
                    }
                    jql == JiraQuery.MY_OPEN_SPRINTS -> tickets.filter { it.mine && inOpenSprint(it) }
                    jql.startsWith("assignee = currentUser()") ->
                        tickets.filter { it.mine && it.category != "done" && (OPEN_SPRINTS !in jql || inOpenSprint(it)) }
                    else -> {
                        val key = KEY.find(jql)?.groupValues?.get(1)
                        if (key != null) {
                            known.filter { it.key == key }.ifEmpty {
                                return 400 to error("An issue with key '$key' does not exist for field 'key'.")
                            }
                        } else {
                            val words =
                                TEXT
                                    .find(jql)
                                    ?.groupValues
                                    ?.get(1)
                                    ?.lowercase()
                                    ?.split(' ')
                                    ?.filter { it.isNotBlank() }
                                    .orEmpty()
                            val label = LABEL.find(jql)?.groupValues?.get(1)
                            tickets.filter { ticket ->
                                val haystack = (ticket.title + " " + ticket.text + " " + ticket.labels.joinToString(" ")).lowercase()
                                (words.isNotEmpty() && words.all { it in haystack }) || (label != null && label in ticket.labels)
                            }
                        }
                    }
                }.sortedByDescending { it.updated }
            }
        val page = found.drop(start).take(pageSize)
        val next = start + pageSize
        val isLast = next >= found.size
        val fields = params["fields"].orEmpty().split(',').toSet()
        val issues = page.joinToString(",") { row(it, fields) }
        val token = if (isLast) "" else ""","nextPageToken":"synthetic-page-$next""""
        return 200 to """{"issues":[$issues],"isLast":$isLast$token}"""
    }

    /** The issue answer for the tickets that have no hand-written fixture. */
    fun issue(
        key: String,
        revision: Int = 0,
    ): String? {
        val ticket = knownAt(revision).firstOrNull { it.key == key } ?: return null
        val description =
            if (ticket.text.isEmpty()) {
                "null"
            } else {
                """{"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"${ticket.text}"}]}]}"""
            }
        val assignee = ticket.assignee?.let { """{"displayName":"$it"}""" } ?: "null"
        val labels = ticket.labels.joinToString(",") { "\"$it\"" }
        val parent = ticket.parent?.let { ""","parent":${parentObject(it)}""" }.orEmpty()
        return """
            {"id":"${ticket.key.hashCode()}","key":"${ticket.key}",
             "fields":{
               "summary":"${ticket.title}",
               "issuetype":{"name":"${ticket.type}"},
               "status":{"name":"${ticket.status}","statusCategory":{"key":"${ticket.category}"}},
               "priority":{"name":"${ticket.priority}"},"assignee":$assignee,"reporter":{"displayName":"$PERSON_B"},
               "labels":[$labels],"updated":"${ticket.updated}","description":$description,"issuelinks":[]$parent
             }}
            """.trimIndent()
    }

    /**
     * A search row with the asked [fields], with more than Thread keeps, as Jira sends it: the row fields always, the
     * parent, priority, assignee (with an account id), Sprint field and story points only when asked.
     */
    private fun row(
        ticket: SyntheticTicket,
        fields: Set<String>,
    ): String {
        val extra =
            buildString {
                if ("parent" in fields && ticket.parent != null) append(""","parent":${parentObject(ticket.parent)}""")
                if ("priority" in fields) {
                    append(""","priority":{"name":"${ticket.priority}","iconUrl":"https://synthetic.example.test/p"}""")
                }
                if ("assignee" in fields) {
                    val person = ticket.assignee?.let { """{"accountId":"synthetic-account-${it.last()}","displayName":"$it"}""" }
                    append(""","assignee":${person ?: "null"}""")
                }
                if (SyntheticFixtures.SPRINT_FIELD in fields) {
                    val mine = sprints.filter { ticket.key in it.rank }.joinToString(",", transform = ::sprintObject)
                    append(""","${SyntheticFixtures.SPRINT_FIELD}":[$mine]""")
                }
                if (SyntheticFixtures.POINTS_FIELD in fields) append(""","${SyntheticFixtures.POINTS_FIELD}":${ticket.points ?: "null"}""")
            }
        val subtask = ticket.type in setOf("Sub-task", "Subtask")
        val statusId = STATUS_IDS[ticket.status]?.let { """"id":"$it",""" }.orEmpty()
        return """{"id":"${ticket.key.hashCode()}","key":"${ticket.key}","fields":{"summary":"${ticket.title}",""" +
            """"issuetype":{"name":"${ticket.type}","subtask":$subtask,"iconUrl":"https://synthetic.example.test/icon"},""" +
            """"status":{$statusId"name":"${ticket.status}","statusCategory":{"key":"${ticket.category}"}},""" +
            """"updated":"${ticket.updated}"$extra}}"""
    }

    /** A sprint as the Sprint field sends it. */
    private fun sprintObject(sprint: SyntheticSprint): String {
        val goal = sprint.goal?.let { "\"$it\"" } ?: "\"\""
        return """{"id":${sprint.id},"name":"${sprint.name}","state":"${sprint.state}","boardId":${sprint.boardId},""" +
            """"goal":$goal,"startDate":"${sprint.start}","endDate":"${sprint.end}"}"""
    }

    /** A parent as Jira sends it, with more than Thread keeps. */
    private fun parentObject(key: String): String {
        val title = tickets.first { it.key == key }.title
        return """{"id":"${key.hashCode()}","key":"$key","fields":{"summary":"$title"}}"""
    }

    private fun error(message: String): String = """{"errorMessages":["$message"],"warningMessages":[]}"""
}

/** Decodes `%XX` escapes as UTF-8, as [thread.data.http.urlEncode] writes them. */
internal fun percentDecode(value: String): String {
    val bytes = mutableListOf<Byte>()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        val escaped = if (c == '%' && i + 2 < value.length) value.substring(i + 1, i + 3).toIntOrNull(16) else null
        if (escaped != null) {
            bytes += escaped.toByte()
            i += 3
        } else {
            bytes += c.toString().encodeToByteArray().toList()
            i += 1
        }
    }
    return bytes.toByteArray().decodeToString()
}
