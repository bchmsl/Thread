package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import thread.domain.external.ConnectionStore
import thread.domain.external.ExternalLoader
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalRecordSource
import thread.domain.external.ExternalView
import thread.domain.external.PullRequestDetail
import thread.domain.external.PullRequestList
import thread.domain.external.PullRequestListPage
import thread.domain.external.PullRequestRef
import thread.domain.external.PullRequestSource
import thread.domain.external.PullRequestSummary
import thread.domain.external.RateLimitGate
import thread.domain.external.Saved
import thread.domain.external.summary
import thread.domain.workspace.ExternalReference
import thread.domain.workspace.gitHubReference
import thread.web.platform.isOnline
import thread.web.platform.nowMillis

/**
 * What the app reads GitHub through (adr/ADR-014-github-in-the-workspace.md). Main builds one set: the real provider,
 * or the SYNTHETIC fixtures when the emulator test hook turns them on. [name] is what the app calls the provider, for
 * example "GitHub (synthetic)". Nothing here writes to GitHub.
 */
class GitHubServices(
    val isSynthetic: Boolean,
    val name: String,
    val connections: ConnectionStore,
    val lists: ExternalRecordSource<PullRequestList, PullRequestListPage>,
    val pulls: PullRequestSource,
    /** Deletes every saved pull request copy on this device. The connection stays. */
    val deleteSavedCopies: suspend () -> Unit,
) {
    /** GitHub search has its own limit (30 requests a minute), apart from the core API (ADR-007). */
    val searchRateLimit = RateLimitGate()

    /** The core API: pull requests, reviews, statuses and check runs. */
    val coreRateLimit = RateLimitGate()
}

/**
 * GitHub state shared by the Pull requests page, Today, Topic details, Trash, search and Settings: whether GitHub is
 * connected, the pull requests saved on this device, the rows of the saved lists, and the last access problem. Copies
 * stay on this device (D08, D09); titles never go to Firestore (owner-review-queue Q6).
 */
internal class GitHubModel(
    val services: GitHubServices,
    scope: CoroutineScope,
) {
    val name: String get() = services.name
    val isSynthetic: Boolean get() = services.isSynthetic

    private val listLoader = ExternalLoader(services.lists, ::isOnline, ::nowMillis, services.searchRateLimit)

    /** "My pull requests" and "Review requested", shared by their pages, Today and the background refresh (D51). */
    val lists = ExternalListFeed(listLoader, scope, ::nowMillis, ::observeList)
    val pullLoader = ExternalLoader(services.pulls, ::isOnline, ::nowMillis, services.coreRateLimit)

    /** False until this device was read once. */
    var checked: Boolean by mutableStateOf(false)
        private set

    var connected: Boolean by mutableStateOf(false)
        private set

    /** Pull requests saved on this device, by `owner/repo#number`. */
    var saved: Map<String, Saved<PullRequestDetail>> by mutableStateOf(emptyMap())
        private set

    /** Rows of the saved lists, by id, for pull requests not opened here. */
    private var rows: Map<String, PullRequestSummary> by mutableStateOf(emptyMap())

    /** The ids in the saved "Review requested" list, newest first. */
    var reviewRequested: List<PullRequestRef> by mutableStateOf(emptyList())
        private set

    /** The last access problem GitHub answered with (expired, refused, rate limited). A fresh answer clears it. */
    var problem: ExternalProblem? by mutableStateOf(null)
        private set

    /** Changes when the connection changes, so open views load again. */
    var version: Int by mutableStateOf(0)
        private set

    /** Reads the connection, the saved pull requests and the saved lists from this device. Storage errors read as nothing. */
    suspend fun reload() {
        connected = orFallback(null) { services.connections.gitHub() } != null
        saved = if (!connected) emptyMap() else orFallback(emptyList()) { services.pulls.allSaved() }.associateBy { it.value.ref.id }
        val lists =
            if (!connected) {
                emptyList()
            } else {
                PullRequestList.entries.map { list -> list to orFallback(null) { services.lists.readSaved(list) }?.value }
            }
        rows = lists.flatMap { it.second?.items.orEmpty() }.associateBy { it.ref.id }
        reviewRequested =
            lists
                .firstOrNull { it.first == PullRequestList.ReviewRequested }
                ?.second
                ?.items
                .orEmpty()
                .map { it.ref }
        checked = true
    }

    /** After a connect, disconnect or token change: every open view loads again. */
    suspend fun connectionChanged() {
        problem = null
        lists.clear()
        reload()
        version++
    }

    /** Records what loading a pull request showed: a new or deleted saved copy, and access problems. */
    fun observePull(
        ref: PullRequestRef,
        view: ExternalView<PullRequestDetail>,
    ) {
        when (view) {
            is ExternalView.Fresh -> {
                problem = null
                if (view.storedOnDevice) saved = saved + (ref.id to view.saved)
            }
            is ExternalView.Missing -> {
                if (view.problem == ExternalProblem.NotFound) saved = saved - ref.id
                note(view.problem)
            }
            is ExternalView.SavedCopy -> note(view.problem)
            else -> Unit
        }
    }

    /** Records what loading [list] showed. */
    fun observeList(
        list: PullRequestList,
        view: ExternalView<PullRequestListPage>,
    ) {
        when (view) {
            is ExternalView.Fresh -> {
                problem = null
                val items = view.saved.value.items
                rows = rows + items.associateBy { it.ref.id }
                if (list == PullRequestList.ReviewRequested) reviewRequested = items.map { it.ref }
            }
            is ExternalView.SavedCopy -> note(view.problem)
            is ExternalView.Missing -> note(view.problem)
            else -> Unit
        }
    }

    fun note(problem: ExternalProblem) {
        when (problem) {
            ExternalProblem.Unauthorized, ExternalProblem.Forbidden, is ExternalProblem.RateLimited -> this.problem = problem
            else -> Unit
        }
    }

    /** The saved pull request [id] with its reviews and CI, or null when it was never opened here. */
    fun detail(id: String): PullRequestDetail? = saved[id]?.value

    /** The newest row Thread knows for [id]: the saved pull request, else a list row. */
    fun summary(id: String): PullRequestSummary? = saved[id]?.value?.summary() ?: rows[id]

    /** Every pull request Thread knows on this device, saved ones first. */
    fun known(): List<PullRequestSummary> = saved.values.map { it.value.summary() } + rows.values.filter { it.ref.id !in saved }

    /** Whether [id] can be shown now: saved here, or GitHub is reachable (v3 prAvailable). */
    fun canShow(id: String): Boolean = connected && (id in saved || isOnline())

    /** The workspace reference of [ref], or null when GitHub is not connected. */
    fun reference(ref: PullRequestRef): ExternalReference? = if (connected) gitHubReference(ref) else null
}

/** Loads pull request [ref] (null: nothing) and keeps [gitHub] up to date with what it showed. [refresh] reloads it. */
@Composable
internal fun rememberPullRequest(
    gitHub: GitHubModel,
    ref: PullRequestRef?,
    refresh: Int,
): ExternalView<PullRequestDetail>? {
    val auto = rememberAutoRefresh(ref) { gitHub.problem == ExternalProblem.Unauthorized }
    val view = rememberExternalView(gitHub.pullLoader, ref, listOf(refresh, auto, gitHub.version))
    LaunchedEffect(view) { if (ref != null && view != null) gitHub.observePull(ref, view) }
    return view
}

/**
 * Shows [list] from the shared feed: opening it loads it unless it asked less than a minute ago, and a change of
 * [refresh] (the Refresh button) always loads it. The background refresh keeps it current meanwhile (D51).
 */
@Composable
internal fun rememberPullRequestList(
    gitHub: GitHubModel,
    list: PullRequestList,
    refresh: Int,
): ExternalView<PullRequestListPage>? {
    val auto = rememberAutoRefresh(list) { gitHub.problem == ExternalProblem.Unauthorized }
    rememberListLoads(gitHub.lists, list, refresh, auto, gitHub.version)
    return gitHub.lists.view(list)
}
