package thread.web.presentation.topics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.HTMLElement
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraStatus
import thread.domain.external.NestedTicket
import thread.domain.external.nestUnderParents
import thread.domain.topic.Topic
import thread.domain.workspace.ExternalProvider
import thread.domain.workspace.ExternalReference
import thread.web.presentation.Icon
import thread.web.presentation.IconButton
import thread.web.presentation.IconView
import thread.web.presentation.LocalToasts
import thread.web.presentation.Place
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.external.CONNECTIONS_PLACE
import thread.web.presentation.external.GoneBadge
import thread.web.presentation.external.JiraModel
import thread.web.presentation.external.JiraStatusLozenge
import thread.web.presentation.external.JiraTicket
import thread.web.presentation.external.JiraTypeIcon
import thread.web.presentation.external.RefreshJiraOnShow
import thread.web.presentation.external.ViewBanner
import thread.web.presentation.external.XD_TITLE_ID
import thread.web.presentation.external.jiraItemPlace
import thread.web.presentation.external.rememberJiraTicket
import thread.web.presentation.external.savedOrNull

/**
 * The Jira tickets linked to the Topic (v3 topic.js `extSection('jira')`). Each row opens the ticket in the side panel
 * at 1000px and wider, else as its own page. Unlinking removes only the link; the ticket is not changed. A ticket from
 * another Jira site than the connected one shows its key only.
 */
@Composable
internal fun TopicJiraSection(
    workspace: WorkspaceModel,
    jira: JiraModel,
    topic: Topic,
    open: Boolean,
    onToggle: () -> Unit,
    openKey: String?,
    suggestions: TopicSuggestions,
    onOpen: (JiraIssueKey) -> Boolean,
) {
    val toasts = LocalToasts.current
    var linking by remember(topic.id) { mutableStateOf(false) }
    val references = workspace.externalsOf(topic.id, ExternalProvider.Jira)
    if (!jira.connected) {
        TopicSection("jira", "Jira", references.size.toString(), open, onToggle) {
            P({ classes("sect__empty") }) {
                Text("Jira is not connected. ")
                A(CONNECTIONS_PLACE.hash, { classes("lnk") }) { Text("Connect in Settings") }
            }
        }
        return
    }
    TopicSection(
        "jira",
        "Jira",
        references.size.toString(),
        open,
        onToggle,
        actions = {
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text", "btn--sm")
                id("td-link-jira")
                onClick { linking = true }
            }) {
                IconView(Icon.Link, 15)
                Text(" Link")
            }
        },
    ) {
        // Linked and suggested rows show a status: Jira is asked for the latest while they show (D67). Only keys Jira
        // showed before are suggested here, and the open ticket asks on its own.
        val shownKeys =
            (
                references.filter(jira::isHere).mapNotNull { JiraIssueKey.parse(it.key) } +
                    suggestions.jira.mapNotNull { it.jiraKey }.filter { jira.summary(it.value) != null }
            ).distinct().filter { it.value != openKey }
        RefreshJiraOnShow(jira, shownKeys)
        if (references.isEmpty()) {
            P({ classes("sect__empty") }) { Text("Nothing linked. Link a ticket here or from its page.") }
        } else {
            LinkedTickets(workspace, jira, topic, references, suggestions.jiraParentOf, openKey, onOpen)
        }
        SuggestedGroup(
            sectionKey = "jira",
            icon = Icon.Jira,
            kind = "tickets",
            workspace = workspace,
            topic = topic,
            rows = suggestions.jira,
            dismissed = suggestions.jiraDismissed,
            href = { row -> row.jiraKey?.let { jiraItemPlace(it, topic.id.value).hash } ?: "#" },
            onOpen = { row -> row.jiraKey?.let(onOpen) ?: false },
        )
    }
    if (linking) {
        val linked = references.map { it.key }.toSet()
        // Tickets that can be shown here: saved on this device, or listed while online (v3 jiraAvailable).
        val items =
            jira
                .known()
                .filter { it.key.value !in linked && jira.canShow(it.key.value) }
                .map { PickItem(it.key.value, Icon.Jira, "${it.key.value} ${it.title}", it.status?.name.orEmpty()) }
        ItemPickerDialog(
            title = "Link a Jira ticket",
            items = items,
            empty = "Nothing else is available on this device. Search Jira or open Pull requests to find more.",
            onPick = { picked ->
                linking = false
                val reference = JiraIssueKey.parse(picked)?.let(jira::reference) ?: return@ItemPickerDialog
                val edit = workspace.linkToTopic(reference, topic.id) ?: return@ItemPickerDialog
                toasts.show("Linked", undo = { workspace.undo(edit) })
            },
            onCancel = { linking = false },
        )
    }
}

/** A linked ticket and the linked tickets under it (D64). */
private class TicketNode(
    val row: NestedTicket<ExternalReference>,
    val children: List<TicketNode>,
)

/** [rows] as [nestUnderParents] lists them (each row followed by the rows under it) turned into a tree. */
private fun treeOf(rows: List<NestedTicket<ExternalReference>>): List<TicketNode> {
    var index = 0

    fun level(depth: Int): List<TicketNode> {
        val nodes = mutableListOf<TicketNode>()
        while (index < rows.size && rows[index].depth == depth) {
            val row = rows[index++]
            nodes += TicketNode(row, level(depth + 1))
        }
        return nodes
    }
    return level(0)
}

/**
 * The linked rows, each under its linked parent as nested lists (owner change #10, D64): an Epic, then its Stories,
 * then their Subtasks. [parentOf] is what this device knows of each ticket's parent. Unlinking also keeps the ticket
 * from being suggested in the Topic again (D49, Q79).
 */
@Composable
private fun LinkedTickets(
    workspace: WorkspaceModel,
    jira: JiraModel,
    topic: Topic,
    references: List<ExternalReference>,
    parentOf: (JiraIssueKey) -> JiraIssueKey?,
    openKey: String?,
    onOpen: (JiraIssueKey) -> Boolean,
) {
    val rows = nestUnderParents(references, { if (jira.isHere(it)) JiraIssueKey.parse(it.key) else null }, parentOf)
    Ul({ classes("tlist", "jtree") }) {
        treeOf(rows).forEach { LinkedTicket(it, workspace, jira, topic, openKey, onOpen) }
    }
}

@Composable
private fun LinkedTicket(
    node: TicketNode,
    workspace: WorkspaceModel,
    jira: JiraModel,
    topic: Topic,
    openKey: String?,
    onOpen: (JiraIssueKey) -> Boolean,
) {
    val toasts = LocalToasts.current
    val reference = node.row.item
    val issueKey = JiraIssueKey.parse(reference.key)
    val here = jira.isHere(reference) && issueKey != null
    val summary = if (here) jira.summary(reference.key) else null
    val isOpen = here && openKey == reference.key
    key(reference.id) {
        Li({ classes("jnode") }) {
            Div({ classes("xrow", *(if (isOpen) arrayOf("is-open") else emptyArray())) }) {
                Span({ classes("xrow__icon") }) { JiraTypeIcon(summary?.type, 18) }
                if (here && issueKey != null) {
                    A(jiraItemPlace(issueKey, topic.id.value).hash, {
                        classes("xrow__main")
                        attr("data-key", reference.key)
                        if (isOpen) attr("aria-current", "true")
                        onClick { if (onOpen(issueKey)) it.preventDefault() }
                    }) {
                        RowText(
                            reference.key,
                            summary?.status,
                            summary?.title,
                            here = true,
                            parentNotLinked = node.row.parentNotLinked,
                            gone = jira.isGone(reference.key),
                        )
                    }
                } else {
                    Div({ classes("xrow__main") }) { RowText(reference.key, null, null, here = false, parentNotLinked = null) }
                }
                IconButton(Icon.Unlink, "Unlink ${reference.key} from this Topic", small = true) {
                    val edit = workspace.unlinkFromTopic(reference, topic.id) ?: return@IconButton
                    toasts.show(
                        "Unlinked from ${topic.name} and not suggested there. The ticket is unchanged.",
                        undo = { workspace.undo(edit) },
                    )
                }
            }
            if (node.children.isNotEmpty()) {
                Ul({ classes("tlist", "jtree__kids") }) {
                    node.children.forEach { LinkedTicket(it, workspace, jira, topic, openKey, onOpen) }
                }
            }
        }
    }
}

/** [parentNotLinked]: the ticket's parent when the Topic does not link it, named so the row says where it belongs. */
@Composable
private fun RowText(
    key: String,
    status: JiraStatus?,
    title: String?,
    here: Boolean,
    parentNotLinked: JiraIssueKey?,
    gone: Boolean = false,
) {
    Span({ classes("xrow__key") }) {
        B { Text(key) }
        // Jira did not show the ticket now: the saved status may be out of date, so it is not shown (D67).
        if (gone) {
            GoneBadge()
        } else if (status != null) {
            JiraStatusLozenge(status)
        }
        if (parentNotLinked != null) Span({ classes("xrow__in") }) { Text("in ${parentNotLinked.value}") }
    }
    when {
        title != null -> Span({ classes("xrow__title") }) { Text(title) }
        gone -> Unit
        here ->
            Span({ classes("xrow__snip") }) {
                Span({ classes("dl-badge") }) {
                    IconView(Icon.Cloud, 13)
                    Text("Not on this device yet")
                }
            }
        else -> Span({ classes("xrow__snip") }) { Text("From another Jira site. Connect that site to read it.") }
    }
}

/**
 * The v3 side panel: a linked ticket next to the Topic at 1000px and wider, with "Back to sections" and "Open as
 * page". A related ticket opens in the same panel.
 */
@Composable
internal fun TopicSidePanel(
    workspace: WorkspaceModel,
    jira: JiraModel,
    topic: Topic,
    key: JiraIssueKey,
    onOpen: (JiraIssueKey) -> Unit,
    onClose: () -> Unit,
    onGo: (Place) -> Unit,
) {
    var refresh by remember { mutableStateOf(0) }
    val view = rememberJiraTicket(jira, key, refresh)
    // The panel's title takes focus once the ticket is on screen (v3 td-open-ext).
    val titleShown = view?.savedOrNull != null
    LaunchedEffect(key, titleShown) {
        if (titleShown) {
            delay(16)
            (document.getElementById(XD_TITLE_ID) as? HTMLElement)?.focus()
        }
    }
    Div({
        classes("side-panel")
        attr("role", "region")
        attr("aria-label", "Linked item")
    }) {
        Div({ classes("side-panel__bar") }) {
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text", "btn--sm")
                id("td-panel-close")
                onClick { onClose() }
            }) {
                IconView(Icon.ChevLeft, 15)
                Text(" Back to sections")
            }
            A(jiraItemPlace(key, topic.id.value).hash, { classes("btn", "btn--text", "btn--sm") }) {
                Text("Open as page ")
                IconView(Icon.External, 14)
            }
        }
        if (view != null) ViewBanner(view, jira.name, "tickets") { onGo(CONNECTIONS_PLACE) }
        JiraTicket(
            jira = jira,
            workspace = workspace,
            view = view,
            heading1 = false,
            relatedHref = { jiraItemPlace(it, topic.id.value).hash },
            onOpenRelated = { related ->
                onOpen(related)
                true
            },
            onRefresh = { refresh++ },
            onGo = onGo,
        )
    }
}
