package thread.data.synthetic

/*
 * SYNTHETIC Jira tickets for the Jira page lists and search (Phase 10). Every key, title, label and person is made
 * up. The search here reads only the JQL that Thread itself sends; it is not a JQL parser.
 */

/**
 * One SYNTHETIC ticket as the list and search answers show it. [mine] means assigned to the synthetic account;
 * [inOpenSprint] means it is in a started, not completed sprint (`sprint in openSprints()`). [parent] is its parent's
 * key, for the Topic suggestion search (D49 S4).
 */
internal class SyntheticTicket(
    val key: String,
    val type: String,
    val title: String,
    val status: String,
    val category: String,
    val labels: List<String>,
    val mine: Boolean,
    val updated: String,
    val text: String = "",
    val inOpenSprint: Boolean = false,
    val parent: String? = null,
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
                inOpenSprint = true,
                parent = "SYN-110",
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
                inOpenSprint = true,
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
                inOpenSprint = true,
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
                inOpenSprint = true,
            ),
        )

    private const val OPEN_SPRINTS = "sprint in openSprints()"
    private val KEY = Regex("key = \"([A-Z][A-Z0-9_]*-[0-9]+)\"")
    private val TEXT = Regex("text ~ \"([^\"]*)\"")
    private val LABEL = Regex("labels = \"([^\"]*)\"")
    private val IN_LIST = Regex("^(key|parent) in \\(([^)]*)\\)")
    private val QUOTED_KEY = Regex("\"([A-Z][A-Z0-9_]*-[0-9]+)\"")

    /** The status and body of a `GET /rest/api/3/search/jql` answer for [query] (the URL query string). */
    fun answer(query: String): Pair<Int, String> {
        val params = query.split('&').associate { it.substringBefore('=') to percentDecode(it.substringAfter('=', "")) }
        val jql = params["jql"] ?: return 400 to error("The JQL query is missing.")
        val pageSize = params["maxResults"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50
        val start = params["nextPageToken"]?.removePrefix("synthetic-page-")?.toIntOrNull() ?: 0
        val inList = IN_LIST.find(jql)
        val found =
            when {
                inList != null -> {
                    val (field, list) = inList.destructured
                    val keys = QUOTED_KEY.findAll(list).map { it.groupValues[1] }.toList()
                    keys.firstOrNull { key -> tickets.none { it.key == key } }?.let { missing ->
                        return 400 to error("An issue with key '$missing' does not exist for field '$field'.")
                    }
                    tickets.filter { (if (field == "key") it.key else it.parent) in keys }
                }
                jql.startsWith("assignee = currentUser()") ->
                    tickets.filter { it.mine && it.category != "done" && (OPEN_SPRINTS !in jql || it.inOpenSprint) }
                else -> {
                    val key = KEY.find(jql)?.groupValues?.get(1)
                    if (key != null) {
                        tickets.filter { it.key == key }.ifEmpty {
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
        val page = found.drop(start).take(pageSize)
        val next = start + pageSize
        val isLast = next >= found.size
        val withParent = params["fields"].orEmpty().split(',').contains("parent")
        val issues = page.joinToString(",") { row(it, withParent) }
        val token = if (isLast) "" else ""","nextPageToken":"synthetic-page-$next""""
        return 200 to """{"issues":[$issues],"isLast":$isLast$token}"""
    }

    /** The issue answer for the tickets that have no hand-written fixture. */
    fun issue(key: String): String? {
        val ticket = tickets.firstOrNull { it.key == key } ?: return null
        val description =
            if (ticket.text.isEmpty()) {
                "null"
            } else {
                """{"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"${ticket.text}"}]}]}"""
            }
        val assignee = if (ticket.mine) """{"displayName":"Synthetic Person A"}""" else """{"displayName":"Synthetic Person C"}"""
        val labels = ticket.labels.joinToString(",") { "\"$it\"" }
        return """
            {"id":"${ticket.key.hashCode()}","key":"${ticket.key}",
             "fields":{
               "summary":"${ticket.title}",
               "issuetype":{"name":"${ticket.type}"},
               "status":{"name":"${ticket.status}","statusCategory":{"key":"${ticket.category}"}},
               "priority":{"name":"Medium"},"assignee":$assignee,"reporter":{"displayName":"Synthetic Person B"},
               "labels":[$labels],"updated":"${ticket.updated}","description":$description,"issuelinks":[]
             }}
            """.trimIndent()
    }

    /** A search row. The parent is there only when the search asks for it, with more than Thread keeps, as Jira sends it. */
    private fun row(
        ticket: SyntheticTicket,
        withParent: Boolean,
    ): String {
        val parent =
            ticket.parent
                ?.takeIf { withParent }
                ?.let { key ->
                    val title = tickets.first { it.key == key }.title
                    ""","parent":{"id":"${key.hashCode()}","key":"$key","fields":{"summary":"$title"}}"""
                }.orEmpty()
        return """{"id":"${ticket.key.hashCode()}","key":"${ticket.key}","fields":{"summary":"${ticket.title}",""" +
            """"issuetype":{"name":"${ticket.type}","iconUrl":"https://synthetic.example.test/icon"},""" +
            """"status":{"name":"${ticket.status}","statusCategory":{"key":"${ticket.category}"}},"updated":"${ticket.updated}"$parent}}"""
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
