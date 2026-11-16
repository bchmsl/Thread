package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Article
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.DDescription
import org.jetbrains.compose.web.dom.DList
import org.jetbrains.compose.web.dom.DTerm
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalView
import thread.domain.external.JiraIssue
import thread.domain.external.JiraIssueKey
import thread.domain.topic.Topic
import thread.domain.workspace.ExternalReference
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconButton
import thread.web.presentation.IconView
import thread.web.presentation.LocalToasts
import thread.web.presentation.Place
import thread.web.presentation.TopicCreation
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.notes.topicsPhrase
import thread.web.presentation.topics.TopicTag
import thread.web.presentation.topics.TopicsPickerDialog
import thread.web.presentation.topics.topicPlace
import thread.web.presentation.topics.topicProblemText

/** The id of a ticket's title (v3 `xd-title`). Opening a ticket moves focus there. */
internal const val XD_TITLE_ID = "xd-title"

/** Where Settings lists the connections (v3 `settings.html#connections`). */
internal val CONNECTIONS_PLACE: Place = Place.of("settings", "connections")

/**
 * One Jira ticket (v3 `X.jiraDetail`), read-only: the copy on screen and when it is from, the Topics it is linked to,
 * Link to Topic, Create Topic from ticket, Open in Jira, then description, comments, related issues and fields.
 * [heading1] on the ticket's own page. A related ticket links to [relatedHref]; [onOpenRelated] may open it in place
 * instead and returns true when it did.
 */
@Composable
internal fun JiraTicket(
    jira: JiraModel,
    workspace: WorkspaceModel,
    view: ExternalView<JiraIssue>?,
    heading1: Boolean,
    relatedHref: (JiraIssueKey) -> String,
    onOpenRelated: (JiraIssueKey) -> Boolean,
    onRefresh: () -> Unit,
    onGo: (Place) -> Unit,
) {
    val copy = view?.savedOrNull
    when {
        !jira.connected -> JiraNotConnected(jira.name)
        view == null || view == ExternalView.Loading -> P({ classes("muted") }) { Text("Loading the ticket…") }
        view is ExternalView.Missing -> MissingTicket(view.problem, jira.name, onRefresh)
        copy != null -> {
            CacheBar(view, "Refresh this ticket", onRefresh)
            JiraArticle(copy.value, view.isOnDevice, jira, workspace, heading1, relatedHref, onOpenRelated, onGo)
        }
    }
}

/** v3 `X.notConnected('jira')`. */
@Composable
internal fun JiraNotConnected(name: String) {
    EmptyState(
        Icon.Jira,
        "$name is not connected",
        "Connect it in Settings to see your tickets here. Thread only reads; it never changes anything in Jira.",
    ) { A(CONNECTIONS_PLACE.hash, { classes("btn", "btn--line") }) { Text("Open Settings") } }
}

@Composable
private fun MissingTicket(
    problem: ExternalProblem,
    name: String,
    onRefresh: () -> Unit,
) {
    when (problem) {
        ExternalProblem.NotConnected -> JiraNotConnected(name)
        ExternalProblem.NotFound -> EmptyState(Icon.Jira, "Ticket not found", "It may not be shared with you.")
        ExternalProblem.Offline ->
            EmptyState(
                Icon.CloudOff,
                "Not saved on this device",
                "This one was never opened here, so there is no copy to show offline. Connect to the internet to load it.",
            )
        else -> {
            val message = describe(problem, name, "tickets", null, Times.now())
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
    jira: JiraModel,
    workspace: WorkspaceModel,
    heading1: Boolean,
    relatedHref: (JiraIssueKey) -> String,
    onOpenRelated: (JiraIssueKey) -> Boolean,
    onGo: (Place) -> Unit,
) {
    val reference = jira.reference(issue.key)
    Article({
        classes("xd")
        attr("aria-labelledby", XD_TITLE_ID)
    }) {
        Header({ classes("xd__head") }) {
            P({ classes("xd__kicker") }) {
                Span({ classes("xd__key") }) { Text(issue.key.value) }
                issue.type?.let { Span { Text(it) } }
                issue.status?.let { JiraStatusLozenge(it) }
                if (onDevice) {
                    Span({ classes("dl-badge") }) {
                        IconView(Icon.Device, 13)
                        Text("Saved on this device")
                    }
                }
            }
            val titleAttrs: org.jetbrains.compose.web.attributes.AttrsScope<*>.() -> Unit = {
                classes("xd__title")
                id(XD_TITLE_ID)
                tabIndex(-1)
            }
            if (heading1) H1(titleAttrs) { Text(issue.title) } else H2(titleAttrs) { Text(issue.title) }
            if (reference != null) {
                TopicLinks(reference, workspace, "ticket")
                TicketActions(issue, reference, jira, workspace, onGo)
            }
        }
        Div({ classes("xd__grid") }) {
            Div({ classes("xd__main") }) {
                H3({ classes("xd__h") }) { Text("Description") }
                if (issue.description.blocks.isEmpty()) {
                    P({ classes("muted") }) { Text("No description.") }
                } else {
                    Div({ classes("desc") }) { ExternalDocumentView(issue.description, DocumentSource.Jira) }
                }
                H3({ classes("xd__h") }) {
                    Text("Comments ")
                    Span({ classes("n") }) { Text(issue.commentTotal.toString()) }
                }
                Comments(issue)
                H3({ classes("xd__h") }) { Text("Related issues") }
                RelatedIssues(issue, jira, relatedHref, onOpenRelated)
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

/** v3 `X.links`: the Topics the record belongs to, each with Unlink. Unlinking never changes the [what] itself. */
@Composable
internal fun TopicLinks(
    reference: ExternalReference,
    workspace: WorkspaceModel,
    what: String,
) {
    val toasts = LocalToasts.current
    val topics = workspace.topicsLinkedTo(reference.id)
    Div({ classes("xd__links") }) {
        Span { Text(if (topics.isEmpty()) "Not linked to a Topic yet." else "Linked to") }
        topics.forEach { topic ->
            Span({ classes("lchip") }) {
                TopicTag(topic)
                IconButton(Icon.X, "Unlink ${topic.name}", small = true) {
                    val edit = workspace.unlinkFromTopic(reference, topic.id) ?: return@IconButton
                    toasts.show("Unlinked from ${topic.name}. The $what is unchanged.", undo = { workspace.undo(edit) })
                }
            }
        }
    }
}

@Composable
private fun TicketActions(
    issue: JiraIssue,
    reference: ExternalReference,
    jira: JiraModel,
    workspace: WorkspaceModel,
    onGo: (Place) -> Unit,
) {
    val toasts = LocalToasts.current
    var picking by remember(reference.id) { mutableStateOf(false) }
    val key = issue.key.value
    Div({ classes("xd__acts") }) {
        Button({
            classes("btn", "btn--primary")
            type(ButtonType.Button)
            onClick { picking = true }
        }) { Text("Link to Topic") }
        Button({
            classes("btn", "btn--line")
            type(ButtonType.Button)
            onClick {
                when (val created = workspace.createTopicLinkedTo(issue.title, reference)) {
                    is TopicCreation.Refused -> toasts.show(topicProblemText(created.problem))
                    is TopicCreation.Created ->
                        toasts.show(
                            "Created Topic “${created.topic.name}” and linked $key",
                            open = { onGo(topicPlace(created.topic)) },
                            openLabel = "Open Topic",
                        )
                }
            }
        }) {
            IconView(Icon.Plus, 16)
            Text(" Create Topic from ticket")
        }
        OpenOutside(issue.webUrl, "Open in Jira", hidden = jira.isSynthetic)
    }
    if (picking) {
        val current = workspace.topicsLinkedTo(reference.id).map { it.id }
        TopicsPickerDialog(
            workspace = workspace,
            title = "Link $key to Topics",
            hint = "One ticket can belong to several Topics. Nothing is copied.",
            confirmLabel = "Save links",
            selected = current,
            onPick = { picked ->
                picking = false
                val edit = workspace.setTopicsOf(reference, picked) ?: return@TopicsPickerDialog
                val names = picked.mapNotNull { workspace.topic(it.value)?.name }
                val single: Topic? = if (picked.size == 1) workspace.topic(picked[0].value) else null
                toasts.show(
                    if (picked.isEmpty()) "Removed all Topic links from $key" else "Linked $key to ${topicsPhrase(names)}",
                    undo = { workspace.undo(edit) },
                    open = single?.let { topic -> { onGo(topicPlace(topic)) } },
                    openLabel = "Open Topic",
                )
            },
            onCancel = { picking = false },
        )
    }
}

/** v3 related issues: a link when the ticket can be shown here, else its key and why not. */
@Composable
private fun RelatedIssues(
    issue: JiraIssue,
    jira: JiraModel,
    relatedHref: (JiraIssueKey) -> String,
    onOpenRelated: (JiraIssueKey) -> Boolean,
) {
    if (issue.related.isEmpty()) {
        P({ classes("muted") }) { Text("None.") }
        return
    }
    Ul({ classes("rel") }) {
        issue.related.forEach { related ->
            val title = related.title ?: jira.summary(related.key.value)?.title
            Li {
                Span({ classes("rel__type") }) { Text(related.relation) }
                if (title != null && jira.canShow(related.key.value)) {
                    A(relatedHref(related.key), {
                        classes("lnk")
                        attr("data-key", related.key.value)
                        onClick { if (onOpenRelated(related.key)) it.preventDefault() }
                    }) {
                        B { Text(related.key.value) }
                        Text(" $title")
                    }
                } else {
                    Span {
                        B { Text(related.key.value) }
                        Text(" ")
                        Span({ classes("muted") }) { Text(if (title != null) "$title (not on this device)" else "not shared with you") }
                    }
                }
            }
        }
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
                    Div({ classes("cmt__x", "desc") }) { ExternalDocumentView(comment.body, DocumentSource.Jira) }
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
