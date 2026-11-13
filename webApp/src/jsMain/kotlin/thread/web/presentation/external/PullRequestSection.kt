package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Article
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.DList
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.HTMLElement
import thread.domain.external.CiSummary
import thread.domain.external.ExternalLoader
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalView
import thread.domain.external.PullRequestDetail
import thread.domain.external.PullRequestList
import thread.domain.external.PullRequestListPage
import thread.domain.external.PullRequestRef
import thread.domain.external.PullRequestSummary
import thread.domain.external.ReviewerState
import thread.web.platform.isOnline
import thread.web.platform.nowMillis
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconView

private const val TITLE_ID = "pr-xd-title"

/**
 * GitHub pull requests in the M1-C page (G1-G3, adr/ADR-007): the v3 Mine and Review requested lists, and one pull
 * request with its reviews and CI state. Read-only; nothing here writes to GitHub.
 */
@Composable
internal fun PullRequestSection(
    services: ConnectionsServices,
    connectionVersion: Int,
    onGoToConnections: () -> Unit,
) {
    val listLoader =
        remember(services) { ExternalLoader(services.pullRequestLists, ::isOnline, ::nowMillis, services.searchRateLimit) }
    val detailLoader =
        remember(services) { ExternalLoader(services.pullRequests, ::isOnline, ::nowMillis, services.coreRateLimit) }
    var connected by remember(services) { mutableStateOf<Boolean?>(null) }
    var tab by remember(services) { mutableStateOf(PullRequestList.Mine) }
    var selected by remember(services) { mutableStateOf<PullRequestRef?>(null) }
    var listAttempt by remember(services) { mutableStateOf(0) }
    var detailAttempt by remember(services) { mutableStateOf(0) }
    val counts = remember(services) { mutableStateMapOf<PullRequestList, Int>() }

    LaunchedEffect(services, connectionVersion) {
        connected = orFallback(false) { services.pullRequestLists.isConnected() }
    }
    val listView = if (connected == true) rememberExternalView(listLoader, tab, listAttempt to connectionVersion) else null
    val detailView = rememberExternalView(detailLoader, selected, detailAttempt to connectionVersion)
    LaunchedEffect(listView) {
        listView?.savedOrNull?.let { counts[tab] = it.value.items.size }
    }
    // The title exists only once a copy is on screen, so a first open focuses it after "Loading", not before.
    val showsPullRequest = detailView?.savedOrNull != null
    LaunchedEffect(selected, showsPullRequest) { if (selected != null && showsPullRequest) focusElement(TITLE_ID) }

    Section({
        classes("xsec")
        id("prs")
        attr("aria-labelledby", "prs-h")
    }) {
        Header({ classes("xsec__head") }) {
            H2({
                classes("xsec__h")
                id("prs-h")
            }) { Text("Pull requests") }
            P({ classes("ph__sub") }) { Text("Read-only. Open pull requests from ${services.gitHubName}.") }
        }
        when (connected) {
            null -> P({ classes("muted") }) { Text("Checking the connection…") }
            false ->
                EmptyState(
                    Icon.Pr,
                    "${services.gitHubName} is not connected",
                    "Connect it in Connections to see your pull requests here. Thread only reads; it never changes anything in GitHub.",
                ) { GoToConnectionsButton(onGoToConnections) }
            true -> {
                ViewBanner(listView, services.gitHubName, "pull requests", onGoToConnections)
                Div({ classes("md", *(if (selected != null) arrayOf("has-open") else emptyArray())) }) {
                    Div({ classes("md__list") }) {
                        Tabs(tab, counts) {
                            tab = it
                            selected = null
                        }
                        Div({
                            classes("md__list")
                            id("pl-panel")
                            attr("role", "tabpanel")
                            attr("aria-labelledby", tabId(tab))
                        }) {
                            ListPanel(listView, tab, selected, services.gitHubName, onRefresh = { listAttempt++ }, onGoToConnections) {
                                selected = it
                                detailAttempt++
                            }
                        }
                    }
                    Div({ classes("md__detail") }) {
                        A("#/m1-connections", {
                            classes("back", "md-back")
                            onClick {
                                it.preventDefault()
                                selected = null
                            }
                        }) {
                            IconView(Icon.ChevLeft, 16)
                            Text(if (tab == PullRequestList.ReviewRequested) "Review requested" else "Mine")
                        }
                        if (selected == null) {
                            EmptyState(Icon.Pr, "No pull request selected", "Pick one from the list to read it here.")
                        } else {
                            // The list banner already explains a shared problem, such as being offline.
                            if (detailView?.problemOrNull != listView?.problemOrNull) {
                                ViewBanner(detailView, services.gitHubName, "pull requests", onGoToConnections)
                            }
                            PullRequestDetailPane(detailView, services, onRefresh = { detailAttempt++ }, onGoToConnections)
                        }
                    }
                }
            }
        }
    }
}

private fun tabId(list: PullRequestList): String = if (list == PullRequestList.ReviewRequested) "tab-review" else "tab-mine"

@Composable
private fun Tabs(
    current: PullRequestList,
    counts: Map<PullRequestList, Int>,
    onPick: (PullRequestList) -> Unit,
) {
    Div({
        classes("tabs")
        attr("role", "tablist")
        attr("aria-label", "Pull request lists")
    }) {
        for (list in PullRequestList.entries) {
            Button({
                classes("tab")
                type(ButtonType.Button)
                id(tabId(list))
                attr("role", "tab")
                attr("aria-selected", (list == current).toString())
                attr("aria-controls", "pl-panel")
                tabIndex(if (list == current) 0 else -1)
                onClick { onPick(list) }
                onKeyDown {
                    if (it.key != "ArrowLeft" && it.key != "ArrowRight") return@onKeyDown
                    it.preventDefault()
                    val step = if (it.key == "ArrowRight") 1 else -1
                    val all = PullRequestList.entries
                    val next = all[(all.indexOf(current) + step + all.size) % all.size]
                    onPick(next)
                    (document.getElementById(tabId(next)) as? HTMLElement)?.focus()
                }
            }) {
                Text(if (list == PullRequestList.ReviewRequested) "Review requested" else "Mine")
                counts[list]?.let { Span({ classes("n") }) { Text(it.toString()) } }
            }
        }
    }
}

@Composable
private fun ListPanel(
    view: ExternalView<PullRequestListPage>?,
    list: PullRequestList,
    selected: PullRequestRef?,
    provider: String,
    onRefresh: () -> Unit,
    onGoToConnections: () -> Unit,
    onPick: (PullRequestRef) -> Unit,
) {
    val page = view?.savedOrNull?.value
    when {
        view == null || view == ExternalView.Loading -> P({ classes("muted") }) { Text("Loading pull requests…") }
        view is ExternalView.Missing ->
            if (view.problem == ExternalProblem.Offline) {
                EmptyState(Icon.CloudOff, "Nothing saved on this device yet", "Pull requests load when you are back online.")
            } else {
                MissingState(
                    view.problem,
                    provider,
                    "pull requests",
                    "Not found",
                    "The search found nothing it could read.",
                    onGoToConnections,
                )
                if (view.problem != ExternalProblem.NotConnected) RetryButton(onRefresh)
            }
        page != null -> {
            CacheBar(view, onRefresh)
            if (page.items.isEmpty()) {
                val review = list == PullRequestList.ReviewRequested
                EmptyState(
                    Icon.Pr,
                    if (review) "No review requests" else "No open pull requests",
                    if (review) "Pull requests that ask for your review show here." else "Your open pull requests show here.",
                )
            } else {
                Ul({ classes("md__scroll") }) {
                    for (item in page.items) Row(item, mine = list == PullRequestList.Mine, current = item.ref == selected, onPick)
                }
            }
            if (page.isIncomplete) {
                P({ classes("muted", "xd__note") }) { Text("GitHub says this search timed out, so the list may be incomplete.") }
            } else if (page.totalCount > page.items.size) {
                P({ classes("muted", "xd__note") }) { Text("Showing ${page.items.size} of ${page.totalCount}.") }
            }
        }
    }
}

@Composable
private fun Row(
    item: PullRequestSummary,
    mine: Boolean,
    current: Boolean,
    onPick: (PullRequestRef) -> Unit,
) {
    Li {
        A("#/m1-connections", {
            classes("xi")
            attr("data-id", item.ref.id)
            if (current) attr("aria-current", "true")
            onClick {
                it.preventDefault()
                onPick(item.ref)
            }
        }) {
            Span({ classes("xi__top") }) {
                OpenPill()
                if (item.isDraft) Span({ classes("jst") }) { Text("Draft") }
                Span { Text("${item.ref.owner}/${item.ref.repo}") }
            }
            Span({ classes("xi__title") }) {
                Text(item.title + " ")
                Span({ classes("xi__num") }) { Text("#${item.ref.number}") }
            }
            Span({ classes("xi__meta") }) {
                Span { Text(if (mine) "You" else item.author ?: "Someone") }
                item.updatedAtMillis?.let { Span { Text("Updated ${Times.whenText(it)}") } }
            }
        }
    }
}

@Composable
private fun PullRequestDetailPane(
    view: ExternalView<PullRequestDetail>?,
    services: ConnectionsServices,
    onRefresh: () -> Unit,
    onGoToConnections: () -> Unit,
) {
    val copy = view?.savedOrNull
    when {
        view == null || view == ExternalView.Loading -> P({ classes("muted") }) { Text("Loading the pull request…") }
        view is ExternalView.Missing ->
            Div {
                MissingState(
                    view.problem,
                    services.gitHubName,
                    "pull requests",
                    "Pull request not found",
                    "It may be closed or not shared with you.",
                    onGoToConnections,
                )
                if (view.problem != ExternalProblem.NotConnected) RetryButton(onRefresh)
            }
        copy != null -> {
            CacheBar(view, onRefresh)
            PullRequestArticle(copy.value, hideOutsideLinks = services.isSynthetic)
        }
    }
}

@Composable
private fun PullRequestArticle(
    pr: PullRequestDetail,
    hideOutsideLinks: Boolean,
) {
    val repo = "${pr.ref.owner}/${pr.ref.repo}"
    Article({
        classes("xd")
        attr("aria-labelledby", TITLE_ID)
    }) {
        Header({ classes("xd__head") }) {
            P({ classes("xd__kicker") }) {
                OpenPill()
                if (pr.isDraft) Span({ classes("jst") }) { Text("Draft") }
                Span { Text(repo) }
                Span {
                    IconView(Icon.Branch, 14)
                    Text(" ${pr.headBranch} → ${pr.baseBranch}")
                }
            }
            H2({
                classes("xd__title")
                id(TITLE_ID)
                tabIndex(-1)
            }) {
                Text(pr.title + " ")
                Span({ classes("xi__num") }) { Text("#${pr.ref.number}") }
            }
            P({ classes("xd__kicker") }) {
                CiIndicator(pr.ci)
                ReviewIndicator(pr.review)
            }
            if (!hideOutsideLinks && pr.webUrl != null) {
                Div({ classes("xd__acts") }) { OpenOutside(pr.webUrl, "Open in GitHub", hidden = false) }
            }
        }
        Div({ classes("xd__grid") }) {
            Div({ classes("xd__main") }) {
                H3({ classes("xd__h") }) { Text("Description") }
                val paragraphs =
                    pr.body
                        .split(Regex("\\n\\s*\\n"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                if (paragraphs.isEmpty()) {
                    P({ classes("muted") }) { Text("No description.") }
                } else {
                    // Plain text: Thread does not render GitHub Markdown, and never inserts HTML from GitHub.
                    Div({ classes("desc", "desc--plain") }) { paragraphs.forEach { P { Text(it) } } }
                }
                H3({ classes("xd__h") }) { Text("Reviewers") }
                if (pr.reviewers.isEmpty()) {
                    P({ classes("muted") }) { Text("No reviewers yet.") }
                } else {
                    Ul({ classes("revs") }) {
                        for (reviewer in pr.reviewers) {
                            Li {
                                Span { Text(reviewer.name) }
                                Span({ classes("muted") }) { Text(reviewerLabel(reviewer.state)) }
                            }
                        }
                    }
                }
            }
            DList({ classes("fields") }) {
                Field("Repository", repo)
                Field("Author", pr.author)
                Field("Branch", "${pr.headBranch} → ${pr.baseBranch}")
                Field("Updated", pr.updatedAtMillis?.let { Times.whenText(it) })
                Field("CI sources", ciSources(pr.ci))
            }
        }
        ReadOnlyNote("GitHub")
    }
}

private fun reviewerLabel(state: ReviewerState): String =
    when (state) {
        ReviewerState.Requested -> "Review requested"
        ReviewerState.Approved -> "Approved"
        ReviewerState.ChangesRequested -> "Changes requested"
        ReviewerState.Commented -> "Commented"
        ReviewerState.Dismissed -> "Dismissed"
    }

/** Which CI sources the token could read (G3). The state above comes from the readable ones only. */
private fun ciSources(ci: CiSummary): String {
    fun read(readable: Boolean) = if (readable) "read" else "not readable"
    return "Statuses ${read(ci.statusesReadable)}, check runs ${read(ci.checksReadable)}"
}
