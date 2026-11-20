package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalView
import thread.domain.external.JiraFilter
import thread.domain.external.JiraFilterId
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraIssueList
import thread.domain.external.JiraIssueSummary
import thread.domain.external.JiraStarredFilters
import thread.domain.note.LinkHref
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.Place
import thread.web.presentation.SelectBox
import thread.web.presentation.WorkspaceModel

private const val FILTER_PICK_ID = "jl-filter-pick"

/** What the Filters tab shows (D103): the starred filters, the filter on screen and its tickets. */
internal class FiltersShown(
    val starredView: ExternalView<List<JiraFilter>>?,
    val filterId: JiraFilterId?,
    val rowsView: ExternalView<JiraIssueList>?,
) {
    val starred: List<JiraFilter>? get() = starredView?.savedOrNull?.value

    val filterName: String? get() = starred?.firstOrNull { it.id == filterId }?.name

    /** The rows on screen. None while the starred filters cannot show, so no ticket opens beside an error. */
    val issues: List<JiraIssueSummary>
        get() =
            if (starred.isNullOrEmpty()) {
                emptyList()
            } else {
                rowsView
                    ?.savedOrNull
                    ?.value
                    ?.issues
                    .orEmpty()
            }
}

/**
 * Loads the starred filters and the tickets of the filter on screen (D103): [wanted] (from the address), else the one
 * this device showed last, else the first starred one. The starred list loads on open (not again within a minute) and
 * on Refresh; the filter's tickets also every five minutes while the page is visible, like Assigned to me (ADR-013).
 */
@Composable
internal fun rememberFilters(
    jira: JiraModel,
    wanted: JiraFilterId?,
    refresh: Int,
): FiltersShown {
    rememberListLoads(jira.starredFilters, JiraStarredFilters, refresh, 0, jira.version)
    val starredView = jira.starredFilters.view(JiraStarredFilters)
    val starred = starredView?.savedOrNull?.value
    val filterId = pickFilter(starred, wanted ?: jira.chosenFilter)
    if (filterId != null) {
        val auto = rememberAutoRefresh(filterId) { jira.problem == ExternalProblem.Unauthorized }
        rememberListLoads(jira.filterRows, filterId, refresh, auto, jira.version)
    }
    // The filter on screen is the one this device opens next time (Q4: kept on this device only).
    val known = starred != null
    LaunchedEffect(filterId, known) {
        if (known && filterId != null && filterId != jira.chosenFilter) jira.chooseFilter(filterId)
    }
    return FiltersShown(starredView, filterId, filterId?.let { jira.filterRows.view(it) })
}

/** [wanted] while the starred list is unknown or when it is starred; else the first starred filter. */
private fun pickFilter(
    starred: List<JiraFilter>?,
    wanted: JiraFilterId?,
): JiraFilterId? =
    when {
        starred == null -> wanted
        wanted != null && starred.any { it.id == wanted } -> wanted
        else -> starred.firstOrNull()?.id
    }

/** The Filters tab (D103): the "Starred filter" picker, then the filter's tickets as the usual rows. */
@Composable
internal fun FiltersPanel(
    filters: FiltersShown,
    jira: JiraModel,
    workspace: WorkspaceModel,
    shownKey: JiraIssueKey?,
    rowPlace: (JiraIssueKey) -> Place,
    onOpen: (JiraIssueKey) -> Unit,
    onPick: (JiraFilterId) -> Unit,
    onRefresh: () -> Unit,
) {
    val starredView = filters.starredView
    val starred = filters.starred
    when {
        starredView is ExternalView.Missing ->
            JiraListProblem(starredView.problem, jira, "Your starred filters load when you are back online.", onRefresh)
        starredView == null || starred == null -> P({ classes("muted") }) { Text("Loading your starred filters…") }
        starred.isEmpty() -> {
            CacheBar(starredView, "Refresh starred filters", onRefresh)
            EmptyState(Icon.Star, "No starred filters", "Star a filter in Jira and it shows here.")
        }
        else -> {
            FilterPicker(starred, filters.filterId, onPick)
            FilterRows(filters, jira, workspace, shownKey, rowPlace, onOpen, onRefresh)
        }
    }
}

@Composable
private fun FilterPicker(
    starred: List<JiraFilter>,
    chosen: JiraFilterId?,
    onPick: (JiraFilterId) -> Unit,
) {
    Div({ classes("field", "jl-pick") }) {
        Label(FILTER_PICK_ID, { classes("field__label") }) {
            IconView(Icon.Star, 14)
            Text("Starred filter")
        }
        SelectBox(
            value = chosen?.value.orEmpty(),
            options = starred.map { it.id.value to it.name },
            id = FILTER_PICK_ID,
            classes = listOf("input"),
        ) { raw -> JiraFilterId.parse(raw)?.let(onPick) }
    }
}

@Composable
private fun FilterRows(
    filters: FiltersShown,
    jira: JiraModel,
    workspace: WorkspaceModel,
    shownKey: JiraIssueKey?,
    rowPlace: (JiraIssueKey) -> Place,
    onOpen: (JiraIssueKey) -> Unit,
    onRefresh: () -> Unit,
) {
    val view = filters.rowsView
    val list = view?.savedOrNull?.value
    // The filter on the Jira site. Hidden with the SYNTHETIC fixtures, whose site does not exist.
    val inJira =
        filters.filterId?.let { id ->
            jira.site?.let { LinkHref.parse("https://${it.site.host}/issues/?filter=${id.value}") }
        }
    when {
        view == null || view == ExternalView.Loading -> P({ classes("muted") }) { Text("Loading the filter's tickets…") }
        view is ExternalView.Missing ->
            when (val problem = view.problem) {
                ExternalProblem.Failed(JQL_REFUSED) ->
                    EmptyState(
                        Icon.Alert,
                        "Jira could not run this filter",
                        "Its query may name a project, field or value that is gone, or one you cannot see. Fix it in Jira, then refresh.",
                    ) {
                        Div({ classes("empty__acts") }) {
                            OpenOutside(inJira, "Open in Jira", hidden = jira.isSynthetic)
                            RetryButton(onRefresh)
                        }
                    }
                ExternalProblem.NotFound ->
                    EmptyState(Icon.Search, "Filter not found", "It may have been deleted, or it is no longer shared with you.") {
                        RetryButton(onRefresh)
                    }
                else -> JiraListProblem(problem, jira, "This filter's tickets load when you are back online.", onRefresh)
            }
        list != null -> {
            CacheBar(view, "Refresh the filter", onRefresh)
            if (list.issues.isEmpty()) {
                EmptyState(Icon.Jira, "No tickets match this filter", "Tickets the filter finds in Jira show here.")
            } else {
                Ul({ classes("md__scroll", "tlist") }) {
                    list.issues.forEach { JiraRow(it, rowPlace(it.key), it.key == shownKey, jira, workspace, onOpen) }
                }
                if (!list.complete) {
                    P({ classes("muted", "xd__note") }) {
                        Text("Showing the first ${list.issues.size}. Open the filter in Jira for the rest.")
                    }
                    OpenOutside(inJira, "Open in Jira", hidden = jira.isSynthetic)
                }
            }
        }
    }
}

/** What JiraClient answers when Jira refuses to run a query (HTTP 400). */
private const val JQL_REFUSED = "http-400"
