package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.HTMLElement
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalView
import thread.domain.external.PullRequestList
import thread.domain.external.PullRequestListPage
import thread.domain.external.PullRequestRef
import thread.domain.external.PullRequestSummary
import thread.domain.external.RepositoryPullRequests
import thread.domain.external.pullRequestsByRepository
import thread.domain.workspace.gitHubReference
import thread.web.platform.readDeviceText
import thread.web.platform.writeDeviceText
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.PageHeader
import thread.web.presentation.Place
import thread.web.presentation.Router
import thread.web.presentation.View
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.topics.PlainTopicTag
import thread.web.presentation.topics.topicPlace

/**
 * The Pull requests page (v3 prs.html, adr/ADR-014-github-in-the-workspace.md): "Mine" and "Review requested" on the
 * left, the open pull request on the right, one at a time below 1000px. Read-only. Both lists are kept on this device;
 * CI and review state come from the pull requests opened here.
 */
@Composable
internal fun PrScreen(
    gitHub: GitHubModel,
    workspace: WorkspaceModel,
    router: Router,
    place: Place,
) {
    if (!gitHub.checked) return
    if (!gitHub.connected) {
        PageHeader("Pull requests")
        GitHubNotConnected(gitHub.name)
        return
    }
    val wide = rememberWide()
    val reviewTab = place.param(PR_TAB_PARAM) == PR_REVIEW_TAB
    val openRef = place.param(PR_ID_PARAM)?.let(PullRequestRef::parse)
    val scope = rememberCoroutineScope()

    var listRefresh by remember { mutableStateOf(0) }
    val mineView = rememberPullRequestList(gitHub, PullRequestList.Mine, listRefresh)
    val reviewView = rememberPullRequestList(gitHub, PullRequestList.ReviewRequested, listRefresh)
    val listView = if (reviewTab) reviewView else mineView
    val rows =
        listView
            ?.savedOrNull
            ?.value
            ?.items
            .orEmpty()

    // At 1000px and wider the first pull request of the list shows when none is picked (v3 shownId): the top row of the
    // repository groups (owner change #8).
    val shownRef =
        openRef ?: if (wide) {
            pullRequestsByRepository(rows)
                .firstOrNull()
                ?.pulls
                ?.firstOrNull()
                ?.ref
        } else {
            null
        }
    var pullRefresh by remember { mutableStateOf(0) }
    val pullView = rememberPullRequest(gitHub, shownRef, pullRefresh)
    var focusTitle by remember { mutableStateOf(false) }
    val titleShown = pullView?.savedOrNull != null
    LaunchedEffect(focusTitle, titleShown, shownRef) {
        if (focusTitle && titleShown) {
            delay(16)
            (document.getElementById(XD_TITLE_ID) as? HTMLElement)?.asDynamic()?.focus(js("({ preventScroll: true })"))
            focusTitle = false
        }
    }
    // Going back from a pull request (the back link or the browser) puts focus on its row, once the list shows again.
    var previousOpen by remember { mutableStateOf(openRef) }
    LaunchedEffect(openRef) {
        val was = previousOpen
        previousOpen = openRef
        if (was != null && openRef == null) {
            delay(16)
            (document.querySelector(".xi[data-id=\"${was.id}\"]") as? HTMLElement)?.focus()
        }
    }

    fun listPlace(review: Boolean): Place = if (review) View.Prs.place.with(PR_TAB_PARAM, PR_REVIEW_TAB) else View.Prs.place

    fun open(ref: PullRequestRef) {
        router.go(prPlace(ref, reviewTab))
        if (!wide) window.scrollTo(0.0, 0.0)
        focusTitle = true
    }

    PageHeader("Pull requests", "Read-only. Open pull requests from GitHub.")
    // The banner speaks for a view that shows its saved copy because of a problem (pull request first), so a list that got a
    // 401 still says why while the pull request on screen is fine. Else for the pull request on screen, else for the list.
    val bannerView =
        listOf(pullView, listView).firstOrNull { it is ExternalView.SavedCopy }
            ?: pullView?.takeIf { it.savedOrNull != null }
            ?: listView?.takeIf { it.savedOrNull != null }
    ViewBanner(bannerView, gitHub.name, "pull requests") { router.go(CONNECTIONS_PLACE) }
    Div({ classes("md", *(if (openRef != null) arrayOf("has-open") else emptyArray())) }) {
        Div({ classes("md__list") }) {
            PrTabs(
                reviewTab,
                mineView
                    ?.savedOrNull
                    ?.value
                    ?.items
                    ?.size,
                reviewView
                    ?.savedOrNull
                    ?.value
                    ?.items
                    ?.size,
            ) { toReview ->
                router.replace(listPlace(toReview))
                scope.launch {
                    delay(16)
                    (document.getElementById(if (toReview) "tab-review" else "tab-mine") as? HTMLElement)?.focus()
                }
            }
            Div({
                id("pl-panel")
                classes("md__list")
                attr("role", "tabpanel")
                attr("aria-labelledby", if (reviewTab) "tab-review" else "tab-mine")
            }) {
                ListPanel(listView, reviewTab, gitHub, workspace, shownRef, ::open) { listRefresh++ }
            }
        }
        Div({ classes("md__detail") }) {
            A(listPlace(reviewTab).hash, {
                classes("back", "md-back")
                onClick {
                    it.preventDefault()
                    router.go(listPlace(reviewTab))
                }
            }) {
                IconView(Icon.ChevLeft, 16)
                Text(if (reviewTab) " Review requested" else " Mine")
            }
            if (shownRef == null) {
                EmptyState(Icon.Pr, "No pull request selected", "Pick one from the list to read it here.")
            } else {
                PullRequestDetailView(gitHub, workspace, pullView, heading1 = false, onRefresh = { pullRefresh++ }, onGo = router::go)
            }
        }
    }
}

@Composable
private fun PrTabs(
    reviewTab: Boolean,
    mineCount: Int?,
    reviewCount: Int?,
    onSelect: (review: Boolean) -> Unit,
) {
    Div({
        classes("tabs")
        attr("role", "tablist")
        attr("aria-label", "Pull request lists")
    }) {
        listOf(false, true).forEach { isReview ->
            val selected = isReview == reviewTab
            val count = if (isReview) reviewCount else mineCount
            Button({
                classes("tab")
                type(ButtonType.Button)
                id(if (isReview) "tab-review" else "tab-mine")
                attr("role", "tab")
                attr("aria-selected", selected.toString())
                attr("aria-controls", "pl-panel")
                tabIndex(if (selected) 0 else -1)
                onClick { onSelect(isReview) }
                onKeyDown { event ->
                    if (event.key == "ArrowLeft" || event.key == "ArrowRight") {
                        event.preventDefault()
                        onSelect(!isReview)
                    }
                }
            }) {
                Text(if (isReview) "Review requested" else "Mine")
                if (count != null) {
                    Text(" ")
                    Span({ classes("n") }) { Text(count.toString()) }
                }
            }
        }
    }
}

@Composable
private fun ListPanel(
    view: ExternalView<PullRequestListPage>?,
    reviewTab: Boolean,
    gitHub: GitHubModel,
    workspace: WorkspaceModel,
    shownRef: PullRequestRef?,
    onOpen: (PullRequestRef) -> Unit,
    onRefresh: () -> Unit,
) {
    val page = view?.savedOrNull?.value
    when {
        view == null || view == ExternalView.Loading -> P({ classes("muted") }) { Text("Loading pull requests…") }
        view is ExternalView.Missing ->
            when (view.problem) {
                ExternalProblem.Offline ->
                    EmptyState(Icon.CloudOff, "Nothing saved on this device yet", "Pull requests load when you are back online.")
                ExternalProblem.NotConnected -> GitHubNotConnected(gitHub.name)
                else -> {
                    val message = describe(view.problem, gitHub.name, "pull requests", null, Times.now())
                    EmptyState(message.icon, message.title, message.text) {
                        if (view.problem == ExternalProblem.Unauthorized || view.problem == ExternalProblem.Forbidden) {
                            A(CONNECTIONS_PLACE.hash, { classes("btn", "btn--line") }) { Text("Open Settings") }
                        } else {
                            RetryButton(onRefresh)
                        }
                    }
                }
            }
        page != null -> {
            CacheBar(view, "Refresh the list", onRefresh)
            if (page.items.isEmpty()) {
                if (reviewTab) {
                    EmptyState(Icon.Pr, "No review requests", "Pull requests that ask for your review show here.")
                } else {
                    EmptyState(Icon.Pr, "No open pull requests", "Your open pull requests show here.")
                }
            } else {
                val list = if (reviewTab) PullRequestList.ReviewRequested else PullRequestList.Mine
                Div({ classes("md__scroll", "prgroups") }) {
                    pullRequestsByRepository(page.items).forEachIndexed { index, group ->
                        key(list, group.repo.lowercase()) {
                            RepositoryGroup(group, list, index) {
                                group.pulls.forEach { pull ->
                                    key(pull.ref.id) {
                                        PrRow(
                                            pull,
                                            prPlace(pull.ref, reviewTab),
                                            pull.ref == shownRef,
                                            !reviewTab,
                                            page.viewer,
                                            gitHub,
                                            workspace,
                                            onOpen,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (page.totalCount > page.items.size || page.isIncomplete) {
                    P({ classes("muted", "xd__note") }) {
                        Text("Showing ${page.items.size} of ${maxOf(page.totalCount, page.items.size)}. Open GitHub to see the rest.")
                    }
                }
            }
        }
    }
}

/**
 * One repository's pull requests in a list (owner change #8): a heading with `owner/name` and the count, which opens
 * and closes the group like Today's groups (D53). This device remembers it per list and repository.
 */
@Composable
private fun RepositoryGroup(
    group: RepositoryPullRequests,
    list: PullRequestList,
    index: Int,
    rows: @Composable () -> Unit,
) {
    val key = "${list.name.lowercase()}.${group.repo.lowercase()}"
    val id = "prg-${list.name.lowercase()}-$index"
    var open by remember(key) { mutableStateOf(readDeviceText("$PR_GROUP_OPEN_NAME.$key") != "0") }
    val owner = group.repo.substringBefore('/')
    val name = group.repo.substringAfter('/')
    Section({
        classes("tgroup", "prgroup")
        if (!open) classes("is-closed")
        attr("aria-labelledby", id)
    }) {
        H2({
            classes("tgroup__head")
            id(id)
        }) {
            Button({
                type(ButtonType.Button)
                classes("tgroup__toggle")
                attr("aria-expanded", open.toString())
                attr("aria-controls", "$id-b")
                onClick {
                    open = !open
                    writeDeviceText("$PR_GROUP_OPEN_NAME.$key", if (open) "1" else "0")
                }
            }) {
                Span({ classes("chev") }) { IconView(Icon.ChevDown, 18) }
                Span({ classes("prgroup__name") }) {
                    Span({ classes("prgroup__owner") }) { Text("$owner/") }
                    Text(name)
                }
                Span({ classes("n") }) { Text(group.pulls.size.toString()) }
            }
        }
        Ul({
            classes("prgroup__body")
            id("$id-b")
            if (!open) attr("hidden", "")
        }) { if (open) rows() }
    }
}

/** Where this device keeps whether a repository group is open, per list and repository: `1` or `0`. Not synced. */
private const val PR_GROUP_OPEN_NAME = "thread.prs.open"

/**
 * v3 `X.prRow`, in a repository group (owner change #8): state and the assignees; title and number; CI and review
 * state; the author (Review requested only: Mine is always yours) and Topics. The repository is the group's heading. CI
 * and review state show once the pull request was opened on this device (one list request cannot carry them, ADR-007).
 */
@Composable
private fun PrRow(
    pull: PullRequestSummary,
    to: Place,
    current: Boolean,
    mine: Boolean,
    viewer: String?,
    gitHub: GitHubModel,
    workspace: WorkspaceModel,
    onOpen: (PullRequestRef) -> Unit,
) {
    val topics = gitHubReference(pull.ref)?.let { workspace.topicsLinkedTo(it.id) }.orEmpty()
    val detail = gitHub.detail(pull.ref.id)
    Li {
        A(to.hash, {
            classes("xi")
            attr("data-id", pull.ref.id)
            if (current) attr("aria-current", "true")
            onClick {
                it.preventDefault()
                onOpen(pull.ref)
            }
        }) {
            Span({ classes("xi__top") }) {
                StatePill(pull.isDraft)
                Assignees(pull.assignees, viewer)
            }
            Span({ classes("xi__title") }) {
                Text("${pull.title} ")
                Span({ classes("xi__num") }) { Text("#${pull.ref.number}") }
            }
            Span({ classes("xi__meta") }) {
                if (detail != null) {
                    CiIndicator(detail.ci)
                    ReviewIndicator(detail.review)
                } else {
                    Span({ classes("dl-badge") }) {
                        IconView(Icon.Cloud, 13)
                        Text("Open to load CI and reviews")
                    }
                }
            }
            Span({ classes("xi__meta") }) {
                if (!mine) Span({ classes("xi__by") }) { Text("by ${pull.author ?: "someone"}") }
                if (topics.isEmpty()) Span { Text("Not linked") } else topics.forEach { PlainTopicTag(it) }
            }
        }
    }
}

/**
 * One pull request as its own page (v3 item.html?kind=pr): from a Topic on narrow screens or "Open as page". The back
 * link returns to the Topic it came from, else to Pull requests.
 */
@Composable
internal fun PrItemScreen(
    gitHub: GitHubModel,
    workspace: WorkspaceModel,
    router: Router,
    place: Place,
) {
    val ref = prRefOf(place)
    val fromTopicId = place.param("from")?.takeIf { it.startsWith("topic:") }?.removePrefix("topic:")
    val fromTopic = fromTopicId?.let(workspace::topic)
    if (fromTopic != null) {
        A(topicPlace(fromTopic).hash, { classes("back") }) {
            IconView(Icon.ChevLeft, 16)
            Text(" ${fromTopic.name}")
        }
    } else {
        A(View.Prs.place.hash, { classes("back") }) {
            IconView(Icon.ChevLeft, 16)
            Text(" Pull requests")
        }
    }
    if (!gitHub.checked) return
    if (ref == null) {
        EmptyState(Icon.Pr, "Pull request not found", "It may be closed or not shared with you.")
        return
    }
    var refresh by remember { mutableStateOf(0) }
    val view = rememberPullRequest(gitHub, if (gitHub.connected) ref else null, refresh)
    val titleShown = view?.savedOrNull != null
    LaunchedEffect(ref, titleShown) {
        if (titleShown) {
            delay(16)
            (document.getElementById(XD_TITLE_ID) as? HTMLElement)?.focus()
        }
    }
    if (view?.savedOrNull != null) ViewBanner(view, gitHub.name, "pull requests") { router.go(CONNECTIONS_PLACE) }
    PullRequestDetailView(gitHub, workspace, view, heading1 = true, onRefresh = { refresh++ }, onGo = router::go)
}
