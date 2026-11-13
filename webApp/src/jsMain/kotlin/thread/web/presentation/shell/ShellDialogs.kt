package thread.web.presentation.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.SearchInput
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import thread.domain.note.NoteSaveState
import thread.domain.workspace.RefusedChange
import thread.web.presentation.Icon
import thread.web.presentation.IconButton
import thread.web.presentation.IconView
import thread.web.presentation.Kbd
import thread.web.presentation.ModalDialog
import thread.web.presentation.SyncProblem
import thread.web.presentation.View
import thread.web.presentation.syncText

/**
 * The sync popover (v3 renderSync), driven by the real save and sync states. A change the server refused also gets
 * Retry, Copy and Discard (owner-review-queue Q14).
 */
@Composable
internal fun SyncPanel(
    state: NoteSaveState,
    problems: List<SyncProblem>,
    usesEmulators: Boolean,
    onRetry: (RefusedChange) -> Unit,
    onCopy: (String) -> Unit,
    onDiscard: (RefusedChange) -> Unit,
    onClose: () -> Unit,
) {
    val text = syncText(state)
    ModalDialog(classes = listOf("dlg--pop", "dlg--sync"), onClose = onClose) { titleId ->
        Div({ classes("syncp") }) {
            Header({ classes("focus__head") }) {
                H2({
                    classes("focus__title")
                    id(titleId)
                }) {
                    IconView(text.icon, 18)
                    Text(" " + text.text)
                }
                IconButton(Icon.X, "Close") { onClose() }
            }
            P({ classes("syncp__text") }) {
                Text("Saved locally means it is safe on this device. Synced means the server confirmed it.")
            }
            if (usesEmulators) {
                P({ classes("syncp__text") }) {
                    Text("Test mode: the server is the local Firebase emulator on this Mac, not the cloud.")
                }
            }
            if (problems.isNotEmpty()) {
                H3({ classes("focus__h") }) { Text("Needs attention") }
                Ul({ classes("syncp__list") }) {
                    problems.forEach { problem ->
                        Li {
                            Div({ classes("syncp__item") }) {
                                val place = problem.place
                                if (place != null) {
                                    A(place.hash, { onClick { onClose() } }) {
                                        IconView(Icon.Notes, 16)
                                        Text(" " + problem.label)
                                    }
                                } else {
                                    Span {
                                        IconView(Icon.Alert, 16)
                                        Text(" " + problem.label)
                                    }
                                }
                                problem.refused?.let { refused ->
                                    Div({ classes("syncp__acts") }) {
                                        SyncAction("Retry", "Retry: ${refused.subject}") { onRetry(refused.change) }
                                        SyncAction("Copy", "Copy: ${refused.subject}") { onCopy(refused.copyText) }
                                        SyncAction("Discard", "Discard: ${refused.subject}") { onDiscard(refused.change) }
                                    }
                                }
                            }
                            Span({ classes("sync", "sync--bad") }) { Text(syncText(problem.state).text) }
                        }
                    }
                }
            }
        }
    }
}

/** A small text button under a refused change. [name] starts with the visible [label], so speech input finds it. */
@Composable
private fun SyncAction(
    label: String,
    name: String,
    onClick: () -> Unit,
) {
    Button({
        classes("btn", "btn--text", "btn--sm")
        type(ButtonType.Button)
        attr("aria-label", name)
        onClick { onClick() }
    }) { Text(label) }
}

/** The narrow views sheet (v3 open-views), a bottom dialog with every view plus search and sync status. */
@Composable
internal fun ViewsSheet(
    current: View,
    inboxCount: Int,
    onSearch: () -> Unit,
    onSync: () -> Unit,
    onClose: () -> Unit,
) {
    ModalDialog(classes = listOf("dlg--bottom"), onClose = onClose) { titleId ->
        Div({ classes("dlg__in", "views") }) {
            H2({
                classes("dlg__title")
                id(titleId)
            }) { Text("Go to") }
            Nav({ attr("aria-label", "All views") }) {
                Ul({ classes("views__list") }) {
                    View.entries.forEach { view ->
                        Li {
                            A(view.place.hash, {
                                classes("views__a")
                                if (view == current) {
                                    attr("aria-current", "page")
                                    attr("autofocus", "")
                                }
                                onClick { onClose() }
                            }) {
                                IconView(view.icon, 20)
                                Span { Text(view.label) }
                                if (view == View.Inbox && inboxCount > 0) Span({ classes("lv__count") }) { Text(inboxCount.toString()) }
                            }
                        }
                    }
                }
            }
            Div({ classes("views__tools") }) {
                Button({
                    type(ButtonType.Button)
                    classes("btn", "btn--line")
                    onClick { onSearch() }
                }) {
                    IconView(Icon.Search, 16)
                    Text(" Search")
                }
                Button({
                    type(ButtonType.Button)
                    classes("btn", "btn--line")
                    onClick { onSync() }
                }) {
                    IconView(Icon.CloudCheck, 16)
                    Text(" Sync status")
                }
            }
        }
    }
}

/** One search result. [lead] draws the icon or sticker in front of the label. */
internal class SearchOption(
    val label: String,
    val sub: String,
    val lead: @Composable () -> Unit,
    val onPick: () -> Unit,
)

internal class SearchGroup(
    val title: String,
    val options: List<SearchOption>,
)

/**
 * Global search (D24, D40; v3 openSearch). An empty query lists the views under "Go to". [search] returns the
 * groups for a query; empty groups are left out.
 */
@Composable
internal fun SearchPalette(
    initialQuery: String,
    goTo: List<SearchOption>,
    search: (String) -> List<SearchGroup>,
    noResults: @Composable (query: String) -> Unit,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var selected by remember { mutableStateOf(0) }
    val groups =
        if (query.isBlank()) listOf(SearchGroup("Go to", goTo)) else search(query).filter { it.options.isNotEmpty() }
    val flat = groups.flatMap { it.options }
    LaunchedEffect(query) { selected = 0 }
    val active = selected.coerceIn(0, (flat.size - 1).coerceAtLeast(0))

    // Reads the state, not [active]: two keys can arrive before the next recomposition.
    fun current() = selected.coerceIn(0, (flat.size - 1).coerceAtLeast(0))
    LaunchedEffect(active, query) {
        document.getElementById("opt-$active")?.asDynamic()?.scrollIntoView(js("({ block: 'nearest' })"))
    }

    ModalDialog(classes = listOf("dlg--palette"), label = "Search", onClose = onClose) {
        Div({ classes("pal") }) {
            Div({ classes("pal__bar") }) {
                IconView(Icon.Search, 20)
                SearchInput(query) {
                    classes("pal__input")
                    placeholder("Search Topics, Tasks, Notes, Jira and PRs")
                    attr("aria-label", "Search this device")
                    attr("role", "combobox")
                    attr("aria-expanded", "true")
                    attr("aria-controls", "pal-results")
                    attr("aria-autocomplete", "list")
                    attr("autocomplete", "off")
                    attr("autofocus", "")
                    if (flat.isNotEmpty()) attr("aria-activedescendant", "opt-$active")
                    onInput {
                        query = it.value
                        onQueryChange(it.value)
                    }
                    onKeyDown { event ->
                        when (event.key) {
                            "ArrowDown" -> {
                                event.preventDefault()
                                selected = (current() + 1).coerceAtMost(flat.size - 1)
                            }

                            "ArrowUp" -> {
                                event.preventDefault()
                                selected = (current() - 1).coerceAtLeast(0)
                            }

                            "Enter" -> {
                                event.preventDefault()
                                flat.getOrNull(current())?.onPick?.invoke()
                            }
                        }
                    }
                }
                Kbd("Esc")
            }
            Div({
                classes("pal__results")
                id("pal-results")
                attr("role", "listbox")
                attr("aria-label", "Results")
            }) {
                if (flat.isEmpty()) {
                    noResults(query)
                } else {
                    var index = 0
                    groups.forEach { group ->
                        Div({
                            classes("pal__group")
                            attr("role", "group")
                            attr("aria-label", group.title)
                        }) {
                            H3({ classes("pal__gtitle") }) { Text(group.title) }
                            group.options.forEach { option ->
                                val optionIndex = index++
                                Button({
                                    type(ButtonType.Button)
                                    classes("pal__opt")
                                    attr("role", "option")
                                    id("opt-$optionIndex")
                                    attr("aria-selected", (optionIndex == active).toString())
                                    onClick { option.onPick() }
                                }) {
                                    Span({ classes("pal__lead") }) { option.lead() }
                                    Span({ classes("pal__label") }) { Text(option.label) }
                                    Span({ classes("pal__sub") }) { Text(option.sub) }
                                }
                            }
                        }
                    }
                }
            }
            P({ classes("pal__foot") }) {
                Text("Searches this device: Topics, Tasks, Notes and downloaded Jira and pull request details.")
            }
        }
    }
}
