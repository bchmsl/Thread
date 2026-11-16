package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import thread.domain.external.ConnectionStore
import thread.domain.external.ExternalLoader
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalRecordSource
import thread.domain.external.ExternalView
import thread.domain.external.JiraCloudId
import thread.domain.external.JiraFamilySource
import thread.domain.external.JiraIssue
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraIssueList
import thread.domain.external.JiraIssueSource
import thread.domain.external.JiraIssueSummary
import thread.domain.external.JiraList
import thread.domain.external.JiraRowSource
import thread.domain.external.JiraSearch
import thread.domain.external.JiraSite
import thread.domain.external.JiraSprints
import thread.domain.external.RateLimitGate
import thread.domain.external.RequestBudget
import thread.domain.external.Saved
import thread.domain.external.ShowRefresh
import thread.domain.workspace.ExternalProvider
import thread.domain.workspace.ExternalReference
import thread.domain.workspace.jiraReference
import thread.web.platform.isOnline
import thread.web.platform.nowMillis
import thread.web.platform.readDeviceFlag
import thread.web.platform.readDeviceText
import thread.web.platform.writeDeviceFlag
import thread.web.platform.writeDeviceText

/**
 * What the app reads Jira through (adr/ADR-013-jira-in-the-workspace.md). Main builds one set: the real provider, or
 * the SYNTHETIC fixtures when the emulator test hook turns them on. [name] is what the app calls the provider, for
 * example "Jira (synthetic)". Nothing here writes to Jira.
 */
class JiraServices(
    val isSynthetic: Boolean,
    val name: String,
    val connections: ConnectionStore,
    val issues: JiraIssueSource,
    val lists: ExternalRecordSource<JiraList, JiraIssueList>,
    val search: JiraSearch,
    /** Whether the site has sprints, for "Current sprint only" (D50). */
    val sprints: JiraSprints,
    /** Parents, children and siblings of a Topic's linked tickets, for Topic suggestions (D49 S4). */
    val family: JiraFamilySource,
    /** Ticket rows refreshed by key while a screen shows them (D67). */
    val rows: JiraRowSource,
    /** Deletes every saved Jira copy on this device. The connection stays. */
    val deleteSavedCopies: suspend () -> Unit,
) {
    /** Jira's rate limit, shared by tickets, lists and search, so no request goes out before it ends. */
    val rateLimit = RateLimitGate()
}

/** The site a connection reads. It never holds the e-mail or the token. */
internal class JiraSiteInfo(
    val site: JiraSite,
    val cloudId: JiraCloudId,
)

/**
 * Jira state shared by the Jira page, Topic details, Trash, search and Settings: whether Jira is connected, the
 * copies saved on this device (for titles and the "Not on this device yet" badge), and the last access problem.
 * Copies stay on this device (D08, D09); titles never go to Firestore (owner-review-queue Q6).
 */
internal class JiraModel(
    val services: JiraServices,
    scope: CoroutineScope,
    private val probe: ShowRefreshProbe? = null,
) {
    val name: String get() = services.name
    val isSynthetic: Boolean get() = services.isSynthetic

    val issueLoader = ExternalLoader(services.issues, ::isOnline, ::nowMillis, services.rateLimit)
    private val listLoader = ExternalLoader(services.lists, ::isOnline, ::nowMillis, services.rateLimit)

    /** The assigned lists, shared by the Jira page and the background refresh (D51). */
    val lists = ExternalListFeed(listLoader, scope, ::nowMillis) { _, view -> observeList(view) }

    /** "Current sprint only" on the Jira page. Kept on this device, like the Jira connection and its copies (D50). */
    var currentSprintOnly: Boolean by mutableStateOf(readDeviceFlag(CURRENT_SPRINT_FLAG))
        private set

    /** Whether the connected site has sprints; null until known. The checkbox hides when it has none. */
    var sprints: Boolean? by mutableStateOf(null)
        private set

    /** The list "Assigned to me" shows: the current sprint only when that is on and the site has sprints. */
    val assignedList: JiraList
        get() = if (currentSprintOnly && sprints != false) JiraList.AssignedCurrentSprint else JiraList.Assigned

    /** False until this device was read once. */
    var checked: Boolean by mutableStateOf(false)
        private set

    /** The connected site, or null when Jira is not connected. */
    var site: JiraSiteInfo? by mutableStateOf(null)
        private set

    val connected: Boolean get() = site != null

    /** Tickets saved on this device, by key. */
    var saved: Map<String, Saved<JiraIssue>> by mutableStateOf(emptyMap())
        private set

    /**
     * Ticket rows by key, with when Jira sent them: the saved Assigned list, rows refreshed on show (D67, saved on this
     * device), and searches in this page load. The newest answer for a key wins.
     */
    private var rows: Map<String, Saved<JiraIssueSummary>> by mutableStateOf(emptyMap())

    /**
     * Keys Jira did not show in this page load: not found, or not shared with this account (a search cannot tell
     * which). Their Topic links stay; rows say so (D67). A later answer with the ticket clears it.
     */
    private var gone: Set<String> by mutableStateOf(emptySet())

    /**
     * Refresh on show (D67): ticket rows on screen are asked about in one `key in (...)` search per
     * [ROW_SEARCH_KEYS] keys, at most [ROW_SEARCHES_PER_MINUTE] searches a minute, and not again within a minute.
     */
    val rowRefresh =
        ShowRefresh<JiraIssueKey>(
            scope = scope,
            nowMillis = ::nowMillis,
            chunkSize = ROW_SEARCH_KEYS,
            budget = RequestBudget(ROW_SEARCHES_PER_MINUTE, 60_000L),
            conditions = {
                providerConditions(
                    isSynthetic,
                    probe?.withFixtures == true,
                    connected,
                    problem,
                    services.rateLimit.blockedUntil(nowMillis()),
                )
            },
        ) { keys -> refreshRows(keys) }

    /** The last access problem Jira answered with (expired, refused, rate limited). A fresh answer clears it. */
    var problem: ExternalProblem? by mutableStateOf(null)
        private set

    /** Changes when the connection changes, so open views load again. */
    var version: Int by mutableStateOf(0)
        private set

    private val notTicketsName = if (services.isSynthetic) "$NOT_TICKETS_NAME.synthetic" else NOT_TICKETS_NAME

    /**
     * Keys that look like tickets but that Jira did not show (not found or not allowed), with when it said so. Topic
     * suggestions ask about such a key at most once a day (D49): text like `UTF-8` looks like a key. Kept on this
     * device and cleared when the connection changes.
     */
    private var notTickets: Map<String, Long> by mutableStateOf(readNotTickets(notTicketsName))

    /** Reads the connection and the saved copies from this device. Storage errors read as nothing saved. */
    suspend fun reload() {
        val connection = orFallback(null) { services.connections.jira() }
        site = connection?.let { JiraSiteInfo(it.site, it.cloudId) }
        saved =
            if (connection == null) {
                emptyMap()
            } else {
                orFallback(emptyList()) { services.issues.allSaved() }.associateBy { it.value.key.value }
            }
        if (connection == null) sprints = null
        val assigned = if (connection == null) null else orFallback(null) { services.lists.readSaved(JiraList.Assigned) }
        val shownRows = if (connection == null) emptyList() else orFallback(emptyList()) { services.rows.allSavedRows() }
        rows = emptyMap()
        keepNewer(assigned?.let { list -> list.value.issues.map { Saved(it, list.savedAtMillis) } }.orEmpty() + shownRows)
        checked = true
    }

    /** After a connect, disconnect or token change: every open view loads again. */
    suspend fun connectionChanged() {
        problem = null
        rowRefresh.clear()
        gone = emptySet()
        lists.clear()
        sprints = null
        notTickets = emptyMap()
        writeDeviceText(notTicketsName, null)
        reload()
        version++
    }

    fun setCurrentSprintOnly(on: Boolean) {
        currentSprintOnly = on
        writeDeviceFlag(CURRENT_SPRINT_FLAG, on)
    }

    /** Learns whether the site has sprints: from this device, and from Jira at most once a day when it can ask. */
    suspend fun checkSprints() {
        if (!connected) return
        val mayAsk = isOnline() && services.rateLimit.blockedUntil(nowMillis()) == null && problem != ExternalProblem.Unauthorized
        orFallback(null) { services.sprints.hasSprints(nowMillis(), mayAsk) }?.let { sprints = it }
    }

    /** Records what loading a ticket showed: a new or deleted saved copy, and access problems. */
    fun observeTicket(
        key: JiraIssueKey,
        view: ExternalView<JiraIssue>,
    ) {
        when (view) {
            // A request is on its way: a row showing the same ticket need not ask too (D67).
            ExternalView.Loading, is ExternalView.Refreshing -> rowRefresh.noteAsked(key, nowMillis())
            is ExternalView.Fresh -> {
                problem = null
                rowRefresh.noteAsked(key, view.saved.savedAtMillis)
                gone = gone - key.value
                if (view.storedOnDevice) saved = saved + (key.value to view.saved)
            }
            is ExternalView.Missing -> {
                if (view.problem == ExternalProblem.NotFound) saved = saved - key.value
                if (view.problem == ExternalProblem.NotFound || view.problem == ExternalProblem.Forbidden) gone = gone + key.value
                note(view.problem)
            }
            is ExternalView.SavedCopy -> note(view.problem)
        }
    }

    /** Whether Jira did not show [key] in this page load: not found, or no access (D67). */
    fun isGone(key: String): Boolean = key in gone

    /** Asks Jira for the rows of [keys] on screen and shows the answers in place (D67). */
    private suspend fun refreshRows(keys: List<JiraIssueKey>) {
        probe?.runs?.add("jira:" + keys.joinToString("+") { it.value })
        val answer = services.rows.refreshRows(keys, keys.filter { it.value in gone }.toSet(), nowMillis())
        keepNewer(answer.rows)
        gone = gone - answer.rows.map { it.value.key.value }.toSet() + answer.refused.map { it.value }
        when (val stopped = answer.problem) {
            null -> problem = null
            is ExternalProblem.RateLimited -> {
                services.rateLimit.block(stopped.retryAtMillis)
                note(stopped)
            }
            else -> note(stopped)
        }
    }

    /** Takes [fetched] rows where they are newer than the row Thread has. */
    private fun keepNewer(fetched: List<Saved<JiraIssueSummary>>) {
        if (fetched.isEmpty()) return
        val next = rows.toMutableMap()
        for (row in fetched) {
            val key = row.value.key.value
            val current = next[key]
            if (current == null || current.savedAtMillis <= row.savedAtMillis) next[key] = row
        }
        rows = next
    }

    /** Whether Jira said less than a day ago that [key] is not a ticket it shows. */
    fun isNotATicket(
        key: JiraIssueKey,
        nowMillis: Long,
    ): Boolean = notTickets[key.value]?.let { nowMillis - it < NOT_A_TICKET_MILLIS } == true

    /** Remembers for a day that Jira does not show [key]. */
    fun markNotATicket(
        key: JiraIssueKey,
        nowMillis: Long,
    ) {
        notTickets = notTickets.filterValues { nowMillis - it < NOT_A_TICKET_MILLIS } + (key.value to nowMillis)
        writeDeviceText(notTicketsName, notTickets.entries.joinToString(",") { "${it.key}:${it.value}" })
    }

    /** Records what loading a list showed. */
    fun observeList(view: ExternalView<JiraIssueList>) {
        when (view) {
            is ExternalView.Fresh -> {
                problem = null
                val at = view.saved.savedAtMillis
                keepRows(view.saved.value.issues, at)
                // The list just brought these rows: rows on screen need not ask about them again (D67).
                view.saved.value.issues
                    .forEach { rowRefresh.noteAsked(it.key, at) }
            }
            is ExternalView.SavedCopy -> note(view.problem)
            is ExternalView.Missing -> note(view.problem)
            else -> Unit
        }
    }

    /** Remembers rows Jira sent at [atMillis], such as search results. Search rows are never saved. */
    fun keepRows(
        issues: List<JiraIssueSummary>,
        atMillis: Long = nowMillis(),
    ) {
        keepNewer(issues.map { Saved(it, atMillis) })
        gone = gone - issues.map { it.key.value }.toSet()
    }

    fun note(problem: ExternalProblem) {
        when (problem) {
            ExternalProblem.Unauthorized, ExternalProblem.Forbidden, is ExternalProblem.RateLimited -> this.problem = problem
            else -> Unit
        }
    }

    /** The newest row Thread knows for [key]: the saved ticket or a row, whichever Jira sent last. */
    fun summary(key: String): JiraIssueSummary? {
        val ticket = saved[key]
        val row = rows[key]
        return if (ticket == null || (row != null && row.savedAtMillis > ticket.savedAtMillis)) row?.value else ticket.value.summary()
    }

    /** Every ticket Thread knows on this device, saved tickets first. */
    fun known(): List<JiraIssueSummary> =
        saved.keys.mapNotNull(::summary) + rows.values.filter { it.value.key.value !in saved }.map { it.value }

    /** Whether [key] can be shown now: saved here, or Jira is reachable (v3 jiraAvailable). */
    fun canShow(key: String): Boolean = connected && (key in saved || isOnline())

    /** The workspace reference of [key] on the connected site, or null when Jira is not connected. */
    fun reference(key: JiraIssueKey): ExternalReference? = site?.let { jiraReference(it.cloudId, it.site, key) }

    /** Whether [reference] is a ticket of the connected site. A ticket of another site cannot be opened here. */
    fun isHere(reference: ExternalReference): Boolean =
        reference.provider == ExternalProvider.Jira && reference.instance == site?.cloudId?.value
}

/** The device setting behind "Current sprint only" (D50). */
private const val CURRENT_SPRINT_FLAG = "thread.jira.currentSprintOnly"

/** Where [JiraModel.isNotATicket] keeps its keys on this device: `KEY:millis` pairs, comma separated. */
private const val NOT_TICKETS_NAME = "thread.jira.notTickets"

private const val NOT_A_TICKET_MILLIS = 24 * 60 * 60 * 1_000L

/** Keys in one refresh-on-show search: one page of Jira's enhanced search (JiraClient.SEARCH_PAGE). */
private const val ROW_SEARCH_KEYS = 50

/**
 * Refresh-on-show searches a minute (D67). A Topic needs one; a split to find a key Jira refuses happens once per
 * key and page load, and counts as one here.
 */
private const val ROW_SEARCHES_PER_MINUTE = 6

/** The stored pairs; anything unreadable is left out. Keys are checked with [JiraIssueKey.parse]. */
private fun readNotTickets(name: String): Map<String, Long> =
    readDeviceText(name)
        ?.split(',')
        ?.mapNotNull { pair ->
            val key = JiraIssueKey.parse(pair.substringBefore(':'))?.value ?: return@mapNotNull null
            val at = pair.substringAfter(':', "").toLongOrNull() ?: return@mapNotNull null
            key to at
        }?.toMap()
        .orEmpty()

/** Copies refresh when opened and then every five minutes while the page is visible and online (ADR-013). */
internal const val JIRA_REFRESH_MILLIS = 5 * 60 * 1_000L

/**
 * A number that grows every [JIRA_REFRESH_MILLIS] while the page is visible and online, to reload a view. It stops
 * while [accessExpired]: after a 401 the token is known to be wrong, so nothing goes out on its own until the person
 * connects again, opens another item or presses Refresh (04 "revoked external access": no retry loop).
 */
@Composable
internal fun rememberAutoRefresh(
    key: Any?,
    accessExpired: () -> Boolean,
): Int {
    var tick by remember(key) { mutableStateOf(0) }
    LaunchedEffect(key) {
        while (true) {
            delay(JIRA_REFRESH_MILLIS)
            if (!accessExpired() && isOnline() && document.asDynamic().visibilityState == "visible") tick++
        }
    }
    return tick
}

/** Loads ticket [key] (null: nothing) and keeps [jira] up to date with what it showed. [refresh] reloads it. */
@Composable
internal fun rememberJiraTicket(
    jira: JiraModel,
    key: JiraIssueKey?,
    refresh: Int,
): ExternalView<JiraIssue>? {
    val auto = rememberAutoRefresh(key) { jira.problem == ExternalProblem.Unauthorized }
    val view = rememberExternalView(jira.issueLoader, key, listOf(refresh, auto, jira.version))
    LaunchedEffect(view) { if (key != null && view != null) jira.observeTicket(key, view) }
    return view
}
