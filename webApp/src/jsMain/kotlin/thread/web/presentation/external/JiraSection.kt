package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.DDescription
import org.jetbrains.compose.web.dom.DList
import org.jetbrains.compose.web.dom.DTerm
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput
import org.jetbrains.compose.web.dom.Ul
import thread.domain.external.ExternalLoader
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalView
import thread.domain.external.JiraIssue
import thread.domain.external.JiraIssueKey
import thread.domain.external.Saved
import thread.web.platform.isOnline
import thread.web.platform.nowMillis
import thread.web.presentation.Icon
import thread.web.presentation.IconView

private const val TITLE_ID = "jira-xd-title"

/**
 * Jira in the M1-C page (J1-J4, adr/ADR-006): open a ticket by key, read it like the v3 Jira detail, and the tickets
 * saved on this device. Read-only; nothing here writes to Jira. v3 also has Assigned to me and search; they are not
 * part of this probe.
 */
@Composable
internal fun JiraSection(
    services: ConnectionsServices,
    connectionVersion: Int,
    onGoToConnections: () -> Unit,
) {
    val loader = remember(services) { ExternalLoader(services.jira, ::isOnline, ::nowMillis, services.jiraRateLimit) }
    var connected by remember(services) { mutableStateOf<Boolean?>(null) }
    var saved by remember(services) { mutableStateOf<List<Saved<JiraIssue>>>(emptyList()) }
    var savedVersion by remember(services) { mutableStateOf(0) }
    var selected by remember(services) { mutableStateOf<JiraIssueKey?>(null) }
    var attempt by remember(services) { mutableStateOf(0) }
    val view = rememberExternalView(loader, selected, attempt to connectionVersion)

    LaunchedEffect(services, connectionVersion, savedVersion) {
        val isConnected = orFallback(false) { services.jira.isConnected() }
        connected = isConnected
        saved = if (isConnected) orFallback(emptyList()) { services.jira.allSaved() } else emptyList()
    }
    // A fresh answer adds the ticket to the saved list; a ticket that is gone leaves it.
    LaunchedEffect(view) {
        if (view is ExternalView.Fresh || view is ExternalView.Missing) savedVersion++
    }
    // The title exists only once a copy is on screen, so a first open focuses it after "Loading", not before.
    val showsTicket = view?.savedOrNull != null
    LaunchedEffect(selected, showsTicket) { if (selected != null && showsTicket) focusElement(TITLE_ID) }

    Section({
        classes("xsec")
        id("jira")
        attr("aria-labelledby", "jira-h")
    }) {
        Header({ classes("xsec__head") }) {
            H2({
                classes("xsec__h")
                id("jira-h")
            }) { Text(services.jiraName) }
            P({ classes("ph__sub") }) { Text("Read-only. Tickets you open are saved on this device.") }
        }
        when (connected) {
            null -> P({ classes("muted") }) { Text("Checking the connection…") }
            false ->
                EmptyState(
                    Icon.Jira,
                    "${services.jiraName} is not connected",
                    "Connect it in Connections to see tickets here. Thread only reads; it never changes anything in Jira.",
                ) { GoToConnectionsButton(onGoToConnections) }
            true -> {
                if (selected != null) ViewBanner(view, services.jiraName, "tickets", onGoToConnections)
                Div({ classes("md", *(if (selected != null) arrayOf("has-open") else emptyArray())) }) {
                    Div({ classes("md__list") }) {
                        OpenByKey { key ->
                            selected = key
                            attempt++
                        }
                        SavedList(saved, selected) { key ->
                            selected = key
                            attempt++
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
                            Text("Saved tickets")
                        }
                        if (selected == null) {
                            EmptyState(Icon.Jira, "No ticket selected", "Open a ticket by its key, or pick a saved one.")
                        } else {
                            JiraDetail(view, services, onRefresh = { attempt++ }, onGoToConnections)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenByKey(onOpen: (JiraIssueKey) -> Unit) {
    var text by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    Form(attrs = {
        classes("addtask")
        attr("role", "search")
        attr("autocomplete", "off")
        onSubmit {
            it.preventDefault()
            val key = JiraIssueKey.parse(text)
            invalid = key == null
            if (key != null) onOpen(key)
        }
    }) {
        Label("jira-key", { classes("vh") }) { Text("Ticket key") }
        TextInput(text) {
            classes("input")
            id("jira-key")
            placeholder("Ticket key, such as ABC-123")
            attr("spellcheck", "false")
            attr("autocapitalize", "characters")
            attr("aria-describedby", "jira-key-hint")
            if (invalid) attr("aria-invalid", "true")
            onInput {
                text = it.value
                invalid = false
            }
        }
        Button({
            classes("btn", "btn--line")
            type(ButtonType.Submit)
        }) {
            IconView(Icon.Search, 16)
            Text("Open")
        }
    }
    P({
        classes("field__hint", *(if (invalid) arrayOf("field__err") else emptyArray()))
        id("jira-key-hint")
        attr("aria-live", "polite")
    }) {
        Text(if (invalid) "Type a ticket key, such as ABC-123." else "Opening a ticket saves it on this device.")
    }
}

@Composable
private fun SavedList(
    saved: List<Saved<JiraIssue>>,
    selected: JiraIssueKey?,
    onPick: (JiraIssueKey) -> Unit,
) {
    H3({ classes("xlist__h") }) {
        Text("Saved on this device")
        Span({ classes("n") }) { Text(saved.size.toString()) }
    }
    if (saved.isEmpty()) {
        P({ classes("muted", "xlist__empty") }) { Text("Nothing saved yet. Tickets you open show here, also offline.") }
        return
    }
    Ul({ classes("md__scroll") }) {
        for (copy in saved) {
            val issue = copy.value
            Li {
                A("#/m1-connections", {
                    classes("xi")
                    attr("data-key", issue.key.value)
                    if (issue.key == selected) attr("aria-current", "true")
                    onClick {
                        it.preventDefault()
                        onPick(issue.key)
                    }
                }) {
                    Span({ classes("xi__top") }) {
                        Span({ classes("xi__key") }) { Text(issue.key.value) }
                        issue.type?.let { Span { Text(it) } }
                        issue.status?.let { Span({ classes("jst") }) { Text(it.name) } }
                    }
                    Span({ classes("xi__title") }) { Text(issue.title) }
                    Span({ classes("xi__meta") }) {
                        Span({ classes("dl-badge") }) {
                            IconView(Icon.Device, 13)
                            Text("Saved ${Times.whenText(copy.savedAtMillis)}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JiraDetail(
    view: ExternalView<JiraIssue>?,
    services: ConnectionsServices,
    onRefresh: () -> Unit,
    onGoToConnections: () -> Unit,
) {
    val copy = view?.savedOrNull
    when {
        view == null || view == ExternalView.Loading -> P({ classes("muted") }) { Text("Loading the ticket…") }
        view is ExternalView.Missing ->
            Div {
                MissingState(
                    view.problem,
                    services.jiraName,
                    "tickets",
                    "Ticket not found",
                    "It may not exist, or it is not shared with you. A saved copy of it was deleted.",
                    onGoToConnections,
                )
                if (view.problem != ExternalProblem.NotConnected) RetryButton(onRefresh)
            }
        copy != null -> {
            CacheBar(view, onRefresh)
            JiraArticle(copy.value, onDevice = view.isOnDevice, hideOutsideLinks = services.isSynthetic)
        }
    }
}

@Composable
internal fun RetryButton(onClick: () -> Unit) {
    Button({
        classes("btn", "btn--text", "btn--sm")
        type(ButtonType.Button)
        onClick { onClick() }
    }) {
        IconView(Icon.Refresh, 15)
        Text("Try again")
    }
}

@Composable
private fun JiraArticle(
    issue: JiraIssue,
    onDevice: Boolean,
    hideOutsideLinks: Boolean,
) {
    org.jetbrains.compose.web.dom.Article({
        classes("xd")
        attr("aria-labelledby", TITLE_ID)
    }) {
        Header({ classes("xd__head") }) {
            P({ classes("xd__kicker") }) {
                Span({ classes("xd__key") }) { Text(issue.key.value) }
                issue.type?.let { Span { Text(it) } }
                issue.status?.let { Span({ classes("jst") }) { Text(it.name) } }
                if (onDevice) {
                    Span({ classes("dl-badge") }) {
                        IconView(Icon.Device, 13)
                        Text("Saved on this device")
                    }
                }
            }
            H2({
                classes("xd__title")
                id(TITLE_ID)
                tabIndex(-1)
            }) { Text(issue.title) }
            if (!hideOutsideLinks && issue.webUrl != null) {
                Div({ classes("xd__acts") }) { OpenOutside(issue.webUrl, "Open in Jira", hidden = false) }
            }
        }
        Div({ classes("xd__grid") }) {
            Div({ classes("xd__main") }) {
                H3({ classes("xd__h") }) { Text("Description") }
                if (issue.description.blocks.isEmpty()) {
                    P({ classes("muted") }) { Text("No description.") }
                } else {
                    Div({ classes("desc") }) { ExternalDocumentView(issue.description) }
                }
                H3({ classes("xd__h") }) {
                    Text("Comments")
                    Span({ classes("n") }) { Text(issue.commentTotal.toString()) }
                }
                Comments(issue)
            }
            DList({ classes("fields") }) {
                Field("Status", issue.status?.name)
                Field("Priority", issue.priority)
                Field("Assignee", issue.assignee)
                Field("Reporter", issue.reporter)
                Field("Sprint", issue.sprint)
                Field("Labels", issue.labels.joinToString(", ").ifEmpty { null })
                Field("Updated", issue.updatedAtMillis?.let { Times.whenText(it) })
            }
        }
        ReadOnlyNote("Jira")
    }
}

@Composable
private fun Comments(issue: JiraIssue) {
    if (issue.comments.isEmpty()) {
        P({ classes("muted") }) { Text("No comments.") }
        return
    }
    if (issue.commentTotal > issue.comments.size) {
        P({ classes("muted", "xd__note") }) {
            Text("Showing the newest ${issue.comments.size} of ${issue.commentTotal}. Open the ticket in Jira to read older ones.")
        }
    }
    Ul({ classes("cmts") }) {
        for (comment in issue.comments) {
            Li({ classes("cmt") }) {
                Span({
                    classes("cmt__av")
                    attr("aria-hidden", "true")
                }) { Text(initials(comment.author)) }
                Div {
                    P({ classes("cmt__who") }) {
                        Text(comment.author ?: "Someone")
                        comment.createdAtMillis?.let { Span({ classes("cmt__at") }) { Text(Times.whenText(it)) } }
                    }
                    Div({ classes("cmt__x", "desc") }) { ExternalDocumentView(comment.body) }
                }
            }
        }
    }
}

@Composable
internal fun Field(
    label: String,
    value: String?,
) {
    DTerm { Text(label) }
    DDescription({ if (value == null) classes("muted") }) { Text(value ?: "None") }
}
