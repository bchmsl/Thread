package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.coroutines.delay
import thread.domain.external.ConnectionStore
import thread.domain.external.ExternalLoader
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalRecordSource
import thread.domain.external.ExternalView
import thread.domain.external.JiraCloudId
import thread.domain.external.JiraIssue
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraIssueList
import thread.domain.external.JiraIssueSource
import thread.domain.external.JiraIssueSummary
import thread.domain.external.JiraList
import thread.domain.external.JiraSearch
import thread.domain.external.JiraSite
import thread.domain.external.RateLimitGate
import thread.domain.external.Saved
import thread.domain.workspace.ExternalProvider
import thread.domain.workspace.ExternalReference
import thread.domain.workspace.jiraReference
import thread.web.platform.isOnline
import thread.web.platform.nowMillis

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
) {
    val name: String get() = services.name
    val isSynthetic: Boolean get() = services.isSynthetic

    val issueLoader = ExternalLoader(services.issues, ::isOnline, ::nowMillis, services.rateLimit)
    val listLoader = ExternalLoader(services.lists, ::isOnline, ::nowMillis, services.rateLimit)

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

    /** Rows of the saved Assigned list and of searches in this page load, by key, for tickets not opened here. */
    private var rows: Map<String, JiraIssueSummary> by mutableStateOf(emptyMap())

    /** The last access problem Jira answered with (expired, refused, rate limited). A fresh answer clears it. */
    var problem: ExternalProblem? by mutableStateOf(null)
        private set

    /** Changes when the connection changes, so open views load again. */
    var version: Int by mutableStateOf(0)
        private set

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
        val assigned = if (connection == null) null else orFallback(null) { services.lists.readSaved(JiraList.Assigned) }
        rows =
            assigned
                ?.value
                ?.issues
                .orEmpty()
                .associateBy { it.key.value }
        checked = true
    }

    /** After a connect, disconnect or token change: every open view loads again. */
    suspend fun connectionChanged() {
        problem = null
        reload()
        version++
    }

    /** Records what loading a ticket showed: a new or deleted saved copy, and access problems. */
    fun observeTicket(
        key: JiraIssueKey,
        view: ExternalView<JiraIssue>,
    ) {
        when (view) {
            is ExternalView.Fresh -> {
                problem = null
                if (view.storedOnDevice) saved = saved + (key.value to view.saved)
            }
            is ExternalView.Missing -> {
                if (view.problem == ExternalProblem.NotFound) saved = saved - key.value
                note(view.problem)
            }
            is ExternalView.SavedCopy -> note(view.problem)
            else -> Unit
        }
    }

    /** Records what loading a list showed. */
    fun observeList(view: ExternalView<JiraIssueList>) {
        when (view) {
            is ExternalView.Fresh -> {
                problem = null
                keepRows(view.saved.value.issues)
            }
            is ExternalView.SavedCopy -> note(view.problem)
            is ExternalView.Missing -> note(view.problem)
            else -> Unit
        }
    }

    /** Remembers search result rows for this page load. They are never saved. */
    fun keepRows(issues: List<JiraIssueSummary>) {
        rows = rows + issues.associateBy { it.key.value }
    }

    fun note(problem: ExternalProblem) {
        when (problem) {
            ExternalProblem.Unauthorized, ExternalProblem.Forbidden, is ExternalProblem.RateLimited -> this.problem = problem
            else -> Unit
        }
    }

    /** The newest row Thread knows for [key]: the saved ticket, else a list or search row. */
    fun summary(key: String): JiraIssueSummary? = saved[key]?.value?.summary() ?: rows[key]

    /** Every ticket Thread knows on this device, saved tickets first. */
    fun known(): List<JiraIssueSummary> = saved.values.map { it.value.summary() } + rows.values.filter { it.key.value !in saved }

    /** Whether [key] can be shown now: saved here, or Jira is reachable (v3 jiraAvailable). */
    fun canShow(key: String): Boolean = connected && (key in saved || isOnline())

    /** The workspace reference of [key] on the connected site, or null when Jira is not connected. */
    fun reference(key: JiraIssueKey): ExternalReference? = site?.let { jiraReference(it.cloudId, it.site, key) }

    /** Whether [reference] is a ticket of the connected site. A ticket of another site cannot be opened here. */
    fun isHere(reference: ExternalReference): Boolean =
        reference.provider == ExternalProvider.Jira && reference.instance == site?.cloudId?.value
}

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
