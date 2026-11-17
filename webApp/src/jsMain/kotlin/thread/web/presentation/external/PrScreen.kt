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
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.HTMLElement
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalView
import thread.domain.external.GitHubPerson
import thread.domain.external.PullRequestGroup
import thread.domain.external.PullRequestGroupKey
import thread.domain.external.PullRequestList
import thread.domain.external.PullRequestListPage
import thread.domain.external.PullRequestRef
import thread.domain.external.PullRequestSummary
import thread.domain.external.pullRequestGroupsByRepository
import thread.domain.external.pullRequestsByAuthor
import thread.domain.external.pullRequestsByReview
import thread.domain.external.pullRequestsByTopic
import thread.domain.workspace.MyPrsGroupBy
import thread.domain.workspace.ReviewPrsGroupBy
import thread.domain.workspace.gitHubReference
import thread.web.platform.readDeviceText
import thread.web.platform.writeDeviceText
import thread.web.presentation.CardKind
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.KindMark
import thread.web.presentation.PageHeader
import thread.web.presentation.Place
import thread.web.presentation.Router
import thread.web.presentation.SectionCard
import thread.web.presentation.View
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.itemCard
import thread.web.presentation.topics.HexSize
import thread.web.presentation.topics.PlainTopicTag
import thread.web.presentation.topics.TopicHex
import thread.web.presentation.topics.TopicHexEmpty

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
    val groups = pullRequestGroups(rows, reviewTab, gitHub, workspace)

    // At 1000px and wider the first pull request of the list shows when none is picked (v3 shownId): the top row of the
    // groups the list shows (owner changes #8 and #25).
    val shownRef =
        openRef ?: if (wide) {
            groups
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
            (document.querySelector(".icard__main[data-id=\"${was.id}\"]") as? HTMLElement)?.focus()
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
    Div({ classes("md", "md--wide", *(if (openRef != null) arrayOf("has-open") else emptyArray())) }) {
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
                ListPanel(listView, groups, reviewTab, gitHub, workspace, shownRef, ::open) { listRefresh++ }
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

/**
 * The list's groups, as Settings picks them for each list (D62, D93): by repository, Topic, author or review status.
 * Review status is what this device saved (Q4): nothing is loaded to group.
 */
private fun pullRequestGroups(
    rows: List<PullRequestSummary>,
    reviewTab: Boolean,
    gitHub: GitHubModel,
    workspace: WorkspaceModel,
): List<PullRequestGroup> {
    val reviewOf = { ref: PullRequestRef -> gitHub.detail(ref.id)?.review }
    return if (reviewTab) {
        when (workspace.prefs.prsReviewGroupBy) {
            ReviewPrsGroupBy.Repository -> pullRequestGroupsByRepository(rows)
            ReviewPrsGroupBy.Author -> pullRequestsByAuthor(rows)
            ReviewPrsGroupBy.ReviewStatus -> pullRequestsByReview(rows, reviewOf)
        }
    } else {
        when (workspace.prefs.prsMineGroupBy) {
            MyPrsGroupBy.Repository -> pullRequestGroupsByRepository(rows)
            MyPrsGroupBy.Topic -> pullRequestsByTopic(rows) { ref -> linkedTopics(ref, workspace) }
            MyPrsGroupBy.ReviewStatus -> pullRequestsByReview(rows, reviewOf)
        }
    }
}

private fun linkedTopics(
    ref: PullRequestRef,
    workspace: WorkspaceModel,
) = gitHubReference(ref)?.let { workspace.topicsLinkedTo(it.id) }.orEmpty()

@Composable
private fun ListPanel(
    view: ExternalView<PullRequestListPage>?,
    groups: List<PullRequestGroup>,
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
                    groups.forEachIndexed { index, group ->
                        val groupKey = groupDeviceKey(group.key)
                        key(list, groupKey) {
                            PrGroup(group, list, groupKey, index, page.viewer) {
                                // Rows of an open group that show CI and reviews ask GitHub for the latest (D67). The
                                // list itself brings the rest; the pull request open next to it asks on its own.
                                RefreshPullsOnShow(
                                    gitHub,
                                    group.pulls.map { it.ref }.filter { it != shownRef && gitHub.detail(it.id) != null },
                                )
                                group.pulls.forEach { pull ->
                                    key(pull.ref.id) {
                                        PrRow(
                                            pull,
                                            prPlace(pull.ref, reviewTab),
                                            pull.ref == shownRef,
                                            !reviewTab,
                                            page.viewer,
                                            RowParts.of(group.key),
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
 * One group of a list (owner changes #8 and #25), as a pull request section card (D69): what the group holds, by its
 * mark and name, and the count, which opens and closes the group like Today's groups (D53). This device remembers it
 * per list and group.
 */
@Composable
private fun PrGroup(
    group: PullRequestGroup,
    list: PullRequestList,
    groupKey: String,
    index: Int,
    viewer: String?,
    rows: @Composable () -> Unit,
) {
    val key = "${list.name.lowercase()}.$groupKey"
    val id = "prg-${list.name.lowercase()}-$index"
    var open by remember(key) { mutableStateOf(readDeviceText("$PR_GROUP_OPEN_NAME.$key") != "0") }
    val by = group.key
    SectionCard(
        kind = CardKind.PullRequest,
        headingId = id,
        bodyId = "$id-b",
        title = groupTitle(by, viewer),
        count = group.pulls.size.toString(),
        open = open,
        onToggle = {
            open = !open
            writeDeviceText("$PR_GROUP_OPEN_NAME.$key", if (open) "1" else "0")
        },
        mark = groupMark(by),
        attrs = { classes("prgroup") },
        titleContent = { GroupTitle(by, viewer) },
    ) { Ul({ classes("tlist") }) { rows() } }
}

/** A group's heading text: the repository, Topic, author or review state, or the name of the group for the rest. */
private fun groupTitle(
    by: PullRequestGroupKey,
    viewer: String?,
): String =
    when (by) {
        is PullRequestGroupKey.Repository -> by.repo
        is PullRequestGroupKey.OneTopic -> by.topic.name
        PullRequestGroupKey.SeveralTopics -> "Several Topics"
        PullRequestGroupKey.NoTopic -> "No Topic"
        is PullRequestGroupKey.Author -> if (by.person.isLogin(viewer)) "You" else by.person.login
        PullRequestGroupKey.NoAuthor -> "Unknown author"
        is PullRequestGroupKey.Review -> reviewLabel(by.state)
        PullRequestGroupKey.NotLoaded -> "Not loaded yet"
    }

@Composable
private fun GroupTitle(
    by: PullRequestGroupKey,
    viewer: String?,
) {
    when (by) {
        is PullRequestGroupKey.Repository -> {
            Span({ classes("prgroup__owner") }) { Text("${by.repo.substringBefore('/')}/") }
            Text(by.repo.substringAfter('/'))
        }
        // As Today's Topic groups (D90): the Topic's hex, or an empty one for No Topic.
        is PullRequestGroupKey.OneTopic ->
            Span({ classes("tgroup__topic") }) {
                TopicHex(by.topic.name, by.topic.color, HexSize.Xs)
                Span { Text(by.topic.name) }
            }
        PullRequestGroupKey.NoTopic ->
            Span({ classes("tgroup__topic") }) {
                TopicHexEmpty(HexSize.Xs)
                Span { Text(groupTitle(by, viewer)) }
            }
        is PullRequestGroupKey.Author -> PersonHeading(by.person, viewer)
        else -> Text(groupTitle(by, viewer))
    }
}

private fun groupMark(by: PullRequestGroupKey): Icon =
    when (by) {
        is PullRequestGroupKey.Repository -> Icon.Repo
        is PullRequestGroupKey.OneTopic, PullRequestGroupKey.SeveralTopics, PullRequestGroupKey.NoTopic -> Icon.Topics
        is PullRequestGroupKey.Author, PullRequestGroupKey.NoAuthor -> Icon.User
        is PullRequestGroupKey.Review -> reviewIcon(by.state)
        PullRequestGroupKey.NotLoaded -> Icon.Cloud
    }

/**
 * The name this device keeps a group's open state under, inside its list. A repository keeps the name it had before
 * D93 (`owner/name`); a Topic, author or review state starts with a word and a colon, which a repository name cannot
 * hold; the groups for the rest are one word with neither, so a login such as `none` never shares one.
 */
internal fun groupDeviceKey(by: PullRequestGroupKey): String =
    when (by) {
        is PullRequestGroupKey.Repository -> by.repo.lowercase()
        is PullRequestGroupKey.OneTopic -> "topic:${by.topic.id.value}"
        PullRequestGroupKey.SeveralTopics -> "severaltopics"
        PullRequestGroupKey.NoTopic -> "notopic"
        is PullRequestGroupKey.Author -> "author:${by.person.login.lowercase()}"
        PullRequestGroupKey.NoAuthor -> "noauthor"
        is PullRequestGroupKey.Review -> "review:${by.state.name.lowercase()}"
        PullRequestGroupKey.NotLoaded -> "notloaded"
    }

/**
 * What a row repeats of its group, left out (D93): the repository shows on the row unless the group is one; the
 * author hides in an author group; the Topics hide in a group of one Topic or none.
 */
internal data class RowParts(
    val repo: Boolean,
    val author: Boolean,
    val topics: Boolean,
) {
    companion object {
        fun of(by: PullRequestGroupKey) =
            RowParts(
                repo = by !is PullRequestGroupKey.Repository,
                author = by !is PullRequestGroupKey.Author && by != PullRequestGroupKey.NoAuthor,
                topics = by !is PullRequestGroupKey.OneTopic && by != PullRequestGroupKey.NoTopic,
            )
    }
}

/** Where this device keeps whether a group is open, per list and group: `1` or `0`. Not synced. */
private const val PR_GROUP_OPEN_NAME = "thread.prs.open"

/**
 * v3 `X.prRow` as a pull request card, in a group (owner changes #8, #13 and #25): state, the repository and the
 * author; title and number; CI and review state; Topics. [parts] leaves out what the group's heading already says.
 * Mine hides the author when it is the viewer (D69): Mine searches `author:@me`, so an unknown viewer is the author
 * too. CI and review state show once the pull request was opened on this device (one list request cannot carry them,
 * ADR-007).
 */
@Composable
private fun PrRow(
    pull: PullRequestSummary,
    to: Place,
    current: Boolean,
    mine: Boolean,
    viewer: String?,
    parts: RowParts,
    gitHub: GitHubModel,
    workspace: WorkspaceModel,
    onOpen: (PullRequestRef) -> Unit,
) {
    val topics = linkedTopics(pull.ref, workspace)
    val detail = gitHub.detail(pull.ref.id)
    val author = rowAuthor(pull.author, mine, viewer)?.takeIf { parts.author }
    Li({
        itemCard(CardKind.PullRequest)
        if (current) classes("is-current")
    }) {
        KindMark(CardKind.PullRequest)
        A(to.hash, {
            classes("icard__main")
            attr("data-id", pull.ref.id)
            if (current) attr("aria-current", "true")
            onClick {
                it.preventDefault()
                onOpen(pull.ref)
            }
        }) {
            Span({ classes("xi__top") }) {
                StatePill(pull.isDraft)
                if (parts.repo) {
                    Span({
                        classes("xi__repo")
                        attr("title", "${pull.ref.owner}/${pull.ref.repo}")
                    }) { Text(pull.ref.repo) }
                }
                author?.let { Author(it, viewer) }
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
            if (parts.topics) {
                Span({ classes("xi__meta") }) {
                    if (topics.isEmpty()) Span { Text("Not linked") } else topics.forEach { PlainTopicTag(it) }
                }
            }
        }
    }
}

/**
 * The author a row shows (Q2 of D69). Mine hides it when it is the viewer, or when the viewer is not known yet: Mine
 * searches `author:@me`, so its author is the viewer. Any other author shows, on either list.
 */
internal fun rowAuthor(
    author: GitHubPerson?,
    mine: Boolean,
    viewer: String?,
): GitHubPerson? = author?.takeUnless { mine && (viewer == null || it.isLogin(viewer)) }
