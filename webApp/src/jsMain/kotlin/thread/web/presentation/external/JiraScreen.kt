package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.SearchInput
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.HTMLElement
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalResult
import thread.domain.external.ExternalView
import thread.domain.external.JiraFilterId
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraIssueSummary
import thread.domain.external.JiraList
import thread.web.platform.isOnline
import thread.web.platform.mediaMatches
import thread.web.platform.nowMillis
import thread.web.platform.observeMedia
import thread.web.presentation.CardKind
import thread.web.presentation.CardMark
import thread.web.presentation.CheckBox
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.JiraTypeName
import thread.web.presentation.PageHeader
import thread.web.presentation.Place
import thread.web.presentation.Router
import thread.web.presentation.View
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.itemCard
import thread.web.presentation.topics.PlainTopicTag
import kotlin.math.ceil

internal const val JIRA_TAB_PARAM = "tab"
internal const val JIRA_KEY_PARAM = "key"
internal const val JIRA_QUERY_PARAM = "q"
internal const val JIRA_FILTER_PARAM = "filter"
private const val SEARCH_TAB = "search"
private const val FILTERS_TAB = "filters"
private const val WIDE_QUERY = "(min-width: 1000px)"

/** The Jira page's tabs, in order (D103). [param] is the `tab=` value; Assigned to me has none. */
private enum class JiraTab(
    val param: String?,
    val tabId: String,
    val label: String,
) {
    Assigned(null, "tab-assigned", "Assigned to me"),
    Filters(FILTERS_TAB, "tab-filters", "Filters"),
    Search(SEARCH_TAB, "tab-search", "Search"),
    ;

    companion object {
        fun of(param: String?): JiraTab = entries.firstOrNull { it.param != null && it.param == param } ?: Assigned
    }
}

/** `#/jira?key=KEY`: the Jira page with that ticket open. */
internal fun jiraPlace(key: JiraIssueKey): Place = View.Jira.place.with(JIRA_KEY_PARAM, key.value)

/** `#/jira?tab=search&q=…`. */
internal fun jiraSearchPlace(query: String): Place =
    View.Jira.place
        .with(JIRA_TAB_PARAM, SEARCH_TAB)
        .with(JIRA_QUERY_PARAM, query)

/** Whether the window is at least 1000px wide, where lists and details sit side by side (v3). */
@Composable
internal fun rememberWide(): Boolean {
    var wide by remember { mutableStateOf(mediaMatches(WIDE_QUERY)) }
    DisposableEffect(Unit) {
        val stop = observeMedia(WIDE_QUERY) { wide = it }
        onDispose { stop() }
    }
    return wide
}

/** What the Search tab shows (v3 jira.js `page`): results of the last search, or why it did not run. */
private class SearchState {
    var running by mutableStateOf(false)
    var results by mutableStateOf<List<JiraIssueSummary>?>(null)
    var complete by mutableStateOf(true)
    var message by mutableStateOf("")
    var ranFor by mutableStateOf<String?>(null)
}

/**
 * The Jira page (v3 jira.html, adr/ADR-013-jira-in-the-workspace.md): "Assigned to me", Filters (D103) and Search on
 * the left, the open ticket on the right, one at a time below 1000px. Read-only. The assigned list and the filters are
 * kept on this device; search results are not.
 */
@Composable
internal fun JiraScreen(
    jira: JiraModel,
    workspace: WorkspaceModel,
    router: Router,
    place: Place,
) {
    if (!jira.checked) return
    if (!jira.connected) {
        PageHeader(jira.name)
        JiraNotConnected(jira.name)
        return
    }
    val wide = rememberWide()
    val tab = JiraTab.of(place.param(JIRA_TAB_PARAM))
    val searchTab = tab == JiraTab.Search
    val query = place.param(JIRA_QUERY_PARAM).orEmpty()
    val openKey = place.param(JIRA_KEY_PARAM)?.let(JiraIssueKey::parse)
    val search = remember { SearchState() }
    val scope = rememberCoroutineScope()

    var listRefresh by remember { mutableStateOf(0) }
    // "Current sprint only" picks the list; each one is loaded and saved apart (D50).
    val listKey = jira.assignedList
    val listAuto = rememberAutoRefresh(listKey) { jira.problem == ExternalProblem.Unauthorized }
    LaunchedEffect(jira.version) { jira.checkSprints() }
    rememberListLoads(jira.lists, listKey, listRefresh, listAuto, jira.version)
    val listView = jira.lists.view(listKey)
    val assigned = listView?.savedOrNull?.value

    // The Filters tab loads its filters only while it shows (D103).
    var filtersRefresh by remember { mutableStateOf(0) }
    val filters =
        if (tab == JiraTab.Filters) {
            rememberFilters(jira, place.param(JIRA_FILTER_PARAM)?.let(JiraFilterId::parse), filtersRefresh)
        } else {
            null
        }

    fun runSearch(text: String) {
        val q = text.trim()
        search.ranFor = q
        search.results = null
        val blocked = searchBlockedReason(q, jira)
        if (blocked != null) {
            search.message = blocked
            return
        }
        search.message = ""
        search.running = true
        scope.launch {
            try {
                when (val result = jira.services.search.search(q, nowMillis())) {
                    is ExternalResult.Ok -> {
                        jira.keepRows(result.value.issues)
                        search.results = result.value.issues
                        search.complete = result.value.complete
                    }
                    is ExternalResult.Problem -> {
                        val problem =
                            if (result.problem == ExternalProblem.Unreachable &&
                                !isOnline()
                            ) {
                                ExternalProblem.Offline
                            } else {
                                result.problem
                            }
                        if (problem is ExternalProblem.RateLimited) jira.services.rateLimit.block(problem.retryAtMillis)
                        jira.note(problem)
                        search.message = searchProblemText(problem)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                search.message = searchProblemText(ExternalProblem.Failed("client-error"))
            } finally {
                search.running = false
            }
        }
    }
    // A search address (from ⌘K or a reload) runs the search once.
    LaunchedEffect(searchTab, query) {
        if (searchTab && query.isNotBlank() && search.ranFor != query.trim()) runSearch(query)
    }

    val rows: List<JiraIssueSummary> =
        when (tab) {
            JiraTab.Assigned -> assigned?.issues.orEmpty()
            JiraTab.Filters -> filters?.issues.orEmpty()
            JiraTab.Search -> search.results.orEmpty()
        }
    // At 1000px and wider the first ticket of the list shows when none is picked (v3 shownKey).
    val shownKey = openKey ?: if (wide) rows.firstOrNull()?.key else null
    var ticketRefresh by remember { mutableStateOf(0) }
    val ticketView = rememberJiraTicket(jira, shownKey, ticketRefresh)
    // Opening a ticket moves focus to its title once it is on screen.
    var focusTitle by remember { mutableStateOf(false) }
    val titleShown = ticketView?.savedOrNull != null
    LaunchedEffect(focusTitle, titleShown, shownKey) {
        if (focusTitle && titleShown) {
            delay(16)
            (document.getElementById(XD_TITLE_ID) as? HTMLElement)?.asDynamic()?.focus(js("({ preventScroll: true })"))
            focusTitle = false
        }
    }

    // Going back from a ticket (the back link or the browser) puts focus on its row, once the list shows again.
    var previousOpen by remember { mutableStateOf(openKey) }
    LaunchedEffect(openKey) {
        val was = previousOpen
        previousOpen = openKey
        if (was != null && openKey == null) {
            delay(16)
            (document.querySelector(".icard__main[data-key=\"${was.value}\"]") as? HTMLElement)?.focus()
        }
    }

    fun listPlace(to: JiraTab): Place {
        var at = View.Jira.place.with(JIRA_TAB_PARAM, to.param)
        when (to) {
            JiraTab.Assigned -> Unit
            JiraTab.Filters -> filters?.filterId?.let { at = at.with(JIRA_FILTER_PARAM, it.value) }
            JiraTab.Search -> if (query.isNotEmpty()) at = at.with(JIRA_QUERY_PARAM, query)
        }
        return at
    }

    fun rowPlace(key: JiraIssueKey): Place = listPlace(tab).with(JIRA_KEY_PARAM, key.value)

    fun open(key: JiraIssueKey) {
        router.go(rowPlace(key))
        if (!wide) window.scrollTo(0.0, 0.0)
        focusTitle = true
    }

    PageHeader(jira.name, "Read-only. Tickets you open are saved on this device.")
    // The banner speaks for a view that shows its saved copy because of a problem (ticket first), so a list that got a
    // 401 still says why while the ticket on screen is fine. Else for the ticket on screen, else for the list.
    val listViews = if (filters != null) listOf(filters.rowsView, filters.starredView) else listOf(listView)
    val bannerView =
        (listOf(ticketView) + listViews).firstOrNull { it is ExternalView.SavedCopy }
            ?: ticketView?.takeIf { it.savedOrNull != null }
            ?: listViews.firstOrNull { it?.savedOrNull != null }
    ViewBanner(bannerView, jira.name, "tickets") { router.go(CONNECTIONS_PLACE) }
    Div({ classes("md", "md--wide", *(if (openKey != null) arrayOf("has-open") else emptyArray())) }) {
        Div({ classes("md__list") }) {
            Tabs(tab, assigned?.issues?.size) { to ->
                router.replace(listPlace(to))
                scope.launch {
                    delay(16)
                    (document.getElementById(to.tabId) as? HTMLElement)?.focus()
                    if (to == JiraTab.Search && search.results == null) (document.getElementById("jq") as? HTMLElement)?.focus()
                }
            }
            Div({
                id("jl-panel")
                classes("md__list")
                attr("role", "tabpanel")
                attr("aria-labelledby", tab.tabId)
            }) {
                when (tab) {
                    JiraTab.Assigned -> AssignedPanel(listView, jira, workspace, shownKey, ::rowPlace, ::open) { listRefresh++ }
                    JiraTab.Filters ->
                        if (filters != null) {
                            FiltersPanel(
                                filters = filters,
                                jira = jira,
                                workspace = workspace,
                                shownKey = shownKey,
                                rowPlace = ::rowPlace,
                                onOpen = ::open,
                                onPick = { id -> router.replace(listPlace(JiraTab.Filters).with(JIRA_FILTER_PARAM, id.value)) },
                                onRefresh = { filtersRefresh++ },
                            )
                        }
                    JiraTab.Search ->
                        SearchPanel(search, query, jira, workspace, shownKey, ::rowPlace, ::open) { text ->
                            router.replace(listPlace(JiraTab.Search).with(JIRA_QUERY_PARAM, text.trim().ifEmpty { null }))
                            runSearch(text)
                        }
                }
            }
        }
        Div({ classes("md__detail") }) {
            A(listPlace(tab).hash, {
                classes("back", "md-back")
                onClick {
                    it.preventDefault()
                    router.go(listPlace(tab))
                }
            }) {
                IconView(Icon.ChevLeft, 16)
                Text(
                    when (tab) {
                        JiraTab.Assigned -> " Assigned to me"
                        JiraTab.Filters -> " " + (filters?.filterName ?: "Filters")
                        JiraTab.Search -> " Search results"
                    },
                )
            }
            if (shownKey == null) {
                when {
                    searchTab -> EmptyState(Icon.Jira, "Search for a ticket", "Pick one from the list to read it here.")
                    // A filter with no tickets on screen (none match, or Jira could not run it) has nothing to pick.
                    tab == JiraTab.Filters && rows.isEmpty() ->
                        EmptyState(
                            Icon.Jira,
                            "No tickets to show",
                            if (filters?.starred.orEmpty().size > 1) {
                                "Pick another starred filter to see its tickets."
                            } else {
                                "Tickets from your starred filters show here."
                            },
                        )
                    else -> EmptyState(Icon.Jira, "No ticket selected", "Pick one from the list to read it here.")
                }
            } else {
                JiraTicket(
                    jira = jira,
                    workspace = workspace,
                    view = ticketView,
                    heading1 = false,
                    relatedHref = { rowPlace(it).hash },
                    onOpenRelated = { key ->
                        open(key)
                        true
                    },
                    onRefresh = { ticketRefresh++ },
                    onGo = router::go,
                )
            }
        }
    }
}

/** The tab list: arrows move between tabs and wrap, Home and End go to the first and last (WAI-ARIA tabs). */
@Composable
private fun Tabs(
    current: JiraTab,
    assignedCount: Int?,
    onSelect: (JiraTab) -> Unit,
) {
    val tabs = JiraTab.entries
    Div({
        classes("tabs")
        attr("role", "tablist")
        attr("aria-label", "Jira lists")
    }) {
        tabs.forEachIndexed { index, tab ->
            val selected = tab == current
            Button({
                classes("tab")
                type(ButtonType.Button)
                id(tab.tabId)
                attr("role", "tab")
                attr("aria-selected", selected.toString())
                attr("aria-controls", "jl-panel")
                tabIndex(if (selected) 0 else -1)
                onClick { onSelect(tab) }
                onKeyDown { event ->
                    val next =
                        when (event.key) {
                            "ArrowLeft" -> tabs[(index + tabs.size - 1) % tabs.size]
                            "ArrowRight" -> tabs[(index + 1) % tabs.size]
                            "Home" -> tabs.first()
                            "End" -> tabs.last()
                            else -> null
                        }
                    if (next != null) {
                        event.preventDefault()
                        onSelect(next)
                    }
                }
            }) {
                Text(tab.label)
                if (tab == JiraTab.Assigned && assignedCount != null) {
                    Text(" ")
                    Span({ classes("n") }) { Text(assignedCount.toString()) }
                }
            }
        }
    }
}

@Composable
private fun AssignedPanel(
    view: ExternalView<thread.domain.external.JiraIssueList>?,
    jira: JiraModel,
    workspace: WorkspaceModel,
    shownKey: JiraIssueKey?,
    rowPlace: (JiraIssueKey) -> Place,
    onOpen: (JiraIssueKey) -> Unit,
    onRefresh: () -> Unit,
) {
    val list = view?.savedOrNull?.value
    val sprintOnly = jira.assignedList == JiraList.AssignedCurrentSprint
    // Shown when the site has sprints; also while that is unknown and the filter is on, so it can be turned off.
    if (jira.sprints == true || (jira.currentSprintOnly && jira.sprints == null)) SprintFilter(jira)
    when {
        view == null || view == ExternalView.Loading -> P({ classes("muted") }) { Text("Loading your tickets…") }
        view is ExternalView.Missing ->
            JiraListProblem(view.problem, jira, "Your assigned tickets load when you are back online.", onRefresh)
        list != null -> {
            CacheBar(view, "Refresh the list", onRefresh)
            if (list.issues.isEmpty() && sprintOnly) {
                EmptyState(
                    Icon.Jira,
                    "Nothing assigned to you in the current sprint",
                    "Open tickets assigned to you in an active sprint show here.",
                )
            } else if (list.issues.isEmpty()) {
                EmptyState(Icon.Jira, "Nothing assigned to you", "Open tickets assigned to you show here.")
            } else {
                Ul({ classes("md__scroll", "tlist") }) {
                    list.issues.forEach { JiraRow(it, rowPlace(it.key), it.key == shownKey, jira, workspace, onOpen) }
                }
                if (!list.complete) {
                    P({ classes("muted", "xd__note") }) { Text("Showing the latest ${list.issues.size}. Search finds the others.") }
                }
            }
        }
    }
}

/** Why a Jira list has nothing to show. [offlineText] says when it loads. */
@Composable
internal fun JiraListProblem(
    problem: ExternalProblem,
    jira: JiraModel,
    offlineText: String,
    onRefresh: () -> Unit,
) {
    when (problem) {
        ExternalProblem.Offline -> EmptyState(Icon.CloudOff, "Nothing saved on this device yet", offlineText)
        ExternalProblem.NotConnected -> JiraNotConnected(jira.name)
        else -> {
            val message = describe(problem, jira.name, "tickets", null, Times.now())
            EmptyState(message.icon, message.title, message.text) {
                if (problem == ExternalProblem.Unauthorized || problem == ExternalProblem.Forbidden) {
                    A(CONNECTIONS_PLACE.hash, { classes("btn", "btn--line") }) { Text("Open Settings") }
                } else {
                    RetryButton(onRefresh)
                }
            }
        }
    }
}

/** "Current sprint only" (D50): the same synced choice as Settings > Jira (D84). */
@Composable
private fun SprintFilter(jira: JiraModel) {
    Div({ classes("jl-filter") }) {
        Label {
            CheckBox(jira.currentSprintOnly, label = null, id = "jl-sprint") { jira.setCurrentSprintOnly(it) }
            Text(" Current sprint only")
        }
    }
}

@Composable
private fun SearchPanel(
    search: SearchState,
    query: String,
    jira: JiraModel,
    workspace: WorkspaceModel,
    shownKey: JiraIssueKey?,
    rowPlace: (JiraIssueKey) -> Place,
    onOpen: (JiraIssueKey) -> Unit,
    onSearch: (String) -> Unit,
) {
    var text by remember(query) { mutableStateOf(query) }
    Form(attrs = {
        classes("addtask")
        attr("role", "search")
        attr("autocomplete", "off")
        onSubmit {
            it.preventDefault()
            onSearch(text)
        }
    }) {
        Label("jq", { classes("vh") }) { Text("Search Jira") }
        SearchInput(text) {
            classes("input")
            id("jq")
            attr("name", "q")
            placeholder("Key, words or label")
            onInput { text = it.value }
        }
        Button({
            classes("btn", "btn--line")
            type(ButtonType.Submit)
            if (search.running) disabled()
        }) {
            IconView(Icon.Search, 16)
            Text(" Search")
        }
    }
    val results = search.results
    P({
        classes("muted")
        style { fontSize(13.px) }
        attr("aria-live", "polite")
    }) {
        Text(
            when {
                search.running -> "Searching Jira…"
                search.message.isNotEmpty() -> search.message
                results != null -> resultsText(results.size, search.complete)
                else -> "Searches all of Jira that you can see. Needs a connection."
            },
        )
    }
    when {
        results == null || search.running -> Unit
        results.isEmpty() -> EmptyState(Icon.Search, "No tickets match", "Try a ticket key such as ABC-123, or fewer words.")
        else ->
            Ul(
                { classes("md__scroll", "tlist") },
            ) { results.forEach { JiraRow(it, rowPlace(it.key), it.key == shownKey, jira, workspace, onOpen) } }
    }
}

private fun resultsText(
    count: Int,
    complete: Boolean,
): String {
    val found = if (count == 1) "1 ticket found." else "$count tickets found."
    val more = if (complete) "" else " Jira has more; add words to narrow it."
    return "$found$more Opening one saves it on this device."
}

/** v3 `X.jiraRow`: key, type, status, title, its Topics, and whether a copy is on this device. */
@Composable
internal fun JiraRow(
    issue: JiraIssueSummary,
    to: Place,
    current: Boolean,
    jira: JiraModel,
    workspace: WorkspaceModel,
    onOpen: (JiraIssueKey) -> Unit,
) {
    val topics = jira.reference(issue.key)?.let { workspace.topicsLinkedTo(it.id) }.orEmpty()
    Li({
        itemCard(CardKind.Jira)
        if (current) classes("is-current")
    }) {
        CardMark(CardKind.Jira, issue.type)
        A(to.hash, {
            classes("icard__main")
            attr("data-key", issue.key.value)
            if (current) attr("aria-current", "true")
            onClick {
                it.preventDefault()
                onOpen(issue.key)
            }
        }) {
            Span({ classes("xi__top") }) {
                // The card's stub shows the type's icon (D63, D68); screen readers hear the type here, in the link.
                JiraTypeName(issue.type)
                Span({ classes("xi__key") }) { Text(issue.key.value) }
                issue.status?.let { JiraStatusLozenge(it) }
            }
            Span({ classes("xi__title") }) { Text(issue.title) }
            Span({ classes("xi__meta") }) {
                if (topics.isEmpty()) Span { Text("Not linked") } else topics.forEach { PlainTopicTag(it) }
                if (issue.key.value !in jira.saved) {
                    Span({ classes("dl-badge") }) {
                        IconView(Icon.Cloud, 13)
                        Text("Not on this device yet")
                    }
                }
            }
        }
    }
}

/** Why a search cannot be sent now, or null when it can (v3 `blockedReason`). */
private fun searchBlockedReason(
    query: String,
    jira: JiraModel,
): String? {
    if (query.isEmpty()) return "Type a key, words or a label first."
    if (!isOnline()) return searchProblemText(ExternalProblem.Offline)
    if (jira.problem == ExternalProblem.Unauthorized) return searchProblemText(ExternalProblem.Unauthorized)
    val blockedUntil = jira.services.rateLimit.blockedUntil(nowMillis())
    if (blockedUntil != null) return searchProblemText(ExternalProblem.RateLimited(blockedUntil))
    return null
}

private fun searchProblemText(problem: ExternalProblem): String =
    when (problem) {
        ExternalProblem.Offline ->
            "Jira search needs a connection. Tickets saved on this device are still in Assigned to me and in ⌘K search."
        ExternalProblem.Unauthorized -> "Jira access expired. Reconnect Jira to search."
        ExternalProblem.NotConnected -> "Jira is not connected. Connect it in Settings to search."
        ExternalProblem.Forbidden -> "Jira refused the search. Check the token's permissions."
        is ExternalProblem.RateLimited -> {
            val minutes = ceil((problem.retryAtMillis - nowMillis()) / 60_000.0).toInt().coerceAtLeast(1)
            "Jira is limiting requests. Try again in about ${if (minutes == 1) "a minute" else "$minutes minutes"}."
        }
        ExternalProblem.Unreachable -> "Jira did not answer. The network failed, or the browser blocked the request."
        ExternalProblem.NotFound -> "No tickets match."
        is ExternalProblem.Failed -> "The search failed. Jira answered with an error (${problem.code})."
    }
