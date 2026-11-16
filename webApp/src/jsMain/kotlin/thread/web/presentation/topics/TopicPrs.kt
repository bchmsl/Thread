package thread.web.presentation.topics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import thread.domain.external.PullRequestRef
import thread.domain.topic.Topic
import thread.domain.workspace.ExternalProvider
import thread.domain.workspace.ExternalReference
import thread.domain.workspace.gitHubReference
import thread.domain.workspace.pullRequestRefOf
import thread.web.presentation.CardKind
import thread.web.presentation.Icon
import thread.web.presentation.IconButton
import thread.web.presentation.IconView
import thread.web.presentation.KindMark
import thread.web.presentation.LocalToasts
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.external.CONNECTIONS_PLACE
import thread.web.presentation.external.CiIndicator
import thread.web.presentation.external.GitHubModel
import thread.web.presentation.external.GoneBadge
import thread.web.presentation.external.RefreshPullsOnShow
import thread.web.presentation.external.ReviewIndicator
import thread.web.presentation.external.prPlace
import thread.web.presentation.itemCard
import thread.web.presentation.onPlainClick
import thread.web.presentation.scopedId

/**
 * The pull requests linked to the Topic (v3 topic.js `extSection('pr')`). Each row opens the pull request as a card
 * (D70); [openId] is the one a card shows now. Unlinking removes only the link; GitHub is not changed.
 */
@Composable
internal fun TopicPrSection(
    workspace: WorkspaceModel,
    gitHub: GitHubModel,
    topic: Topic,
    open: Boolean,
    onToggle: () -> Unit,
    openId: String?,
    suggestions: TopicSuggestions,
    onOpen: (PullRequestRef) -> Unit,
) {
    val toasts = LocalToasts.current
    val linkId = scopedId("td-link-pr")
    var linking by remember(topic.id) { mutableStateOf(false) }
    val references = workspace.externalsOf(topic.id, ExternalProvider.GitHub)
    if (!gitHub.connected) {
        TopicSection("prs", CardKind.PullRequest, "Pull requests", references.size.toString(), open, onToggle) {
            P({ classes("sect__empty") }) {
                Text("GitHub is not connected. ")
                A(CONNECTIONS_PLACE.hash, { classes("lnk") }) { Text("Connect in Settings") }
            }
        }
        return
    }
    TopicSection(
        "prs",
        CardKind.PullRequest,
        "Pull requests",
        references.size.toString(),
        open,
        onToggle,
        actions = {
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text", "btn--sm")
                id(linkId)
                onClick { linking = true }
            }) {
                IconView(Icon.Link, 15)
                Text(" Link")
            }
        },
    ) {
        if (references.isEmpty()) {
            P({ classes("sect__empty") }) { Text("Nothing linked. Link a pull request here or from its page.") }
        } else {
            LinkedPulls(workspace, gitHub, topic, references, openId, onOpen)
        }
        SuggestedGroup(
            sectionKey = "prs",
            card = CardKind.PullRequest,
            kind = "pull requests",
            workspace = workspace,
            topic = topic,
            rows = suggestions.pulls,
            dismissed = suggestions.pullsDismissed,
            href = { row -> row.pull?.let { prPlace(it, review = it in gitHub.reviewRequested).hash } ?: "#" },
            onOpen = { row -> row.pull?.let(onOpen) },
        )
    }
    if (linking) {
        val linked = references.map { it.id }.toSet()
        // Pull requests that can be shown here: saved on this device, or listed while online (v3 prAvailable).
        val items =
            gitHub
                .known()
                .filter { pull -> gitHubReference(pull.ref)?.id !in linked && gitHub.canShow(pull.ref.id) }
                .map { PickItem(it.ref.id, Icon.Pr, "${it.title} #${it.ref.number}", "${it.ref.owner}/${it.ref.repo}") }
        ItemPickerDialog(
            title = "Link a pull request",
            items = items,
            empty = "Nothing else is available on this device. Search Jira or open Pull requests to find more.",
            onPick = { picked ->
                linking = false
                val reference = PullRequestRef.parse(picked)?.let(gitHub::reference) ?: return@ItemPickerDialog
                val edit = workspace.linkToTopic(reference, topic.id) ?: return@ItemPickerDialog
                toasts.show("Linked", undo = { workspace.undo(edit) })
            },
            onCancel = { linking = false },
        )
    }
}

/** The linked rows. Unlinking also keeps the pull request from being suggested in the Topic again (D49, Q79). */
@Composable
private fun LinkedPulls(
    workspace: WorkspaceModel,
    gitHub: GitHubModel,
    topic: Topic,
    references: List<ExternalReference>,
    openId: String?,
    onOpen: (PullRequestRef) -> Unit,
) {
    val toasts = LocalToasts.current
    // The rows show CI and reviews: GitHub is asked for the latest while they show (D67). The open one asks on its own.
    RefreshPullsOnShow(gitHub, references.mapNotNull(::pullRequestRefOf).filter { it.id != openId })
    Ul({ classes("tlist") }) {
        references.forEach { reference ->
            val ref = pullRequestRefOf(reference)
            val summary = ref?.let { gitHub.summary(it.id) }
            val detail = ref?.let { gitHub.detail(it.id) }
            val gone = ref != null && gitHub.isGone(ref.id)
            val isOpen = ref != null && openId == ref.id
            val label = "#${reference.key}"
            key(reference.id) {
                Li({
                    itemCard(CardKind.PullRequest)
                    if (isOpen) classes("is-current")
                }) {
                    KindMark(CardKind.PullRequest)
                    if (ref != null) {
                        A(prPlace(ref, review = ref in gitHub.reviewRequested).hash, {
                            classes("xrow__main")
                            attr("data-id", ref.id)
                            if (isOpen) attr("aria-current", "true")
                            onPlainClick { onOpen(ref) }
                        }) {
                            Span({ classes("xrow__key") }) {
                                B { Text(label) }
                                if (gone) {
                                    GoneBadge()
                                } else if (detail != null) {
                                    CiIndicator(detail.ci)
                                    ReviewIndicator(detail.review)
                                }
                            }
                            if (summary != null) {
                                Span({ classes("xrow__title") }) { Text(summary.title) }
                            } else if (gone) {
                                Span({ classes("xrow__snip") }) { Text(reference.instance) }
                            } else {
                                Span({ classes("xrow__snip") }) {
                                    Text("${reference.instance} ")
                                    Span({ classes("dl-badge") }) {
                                        IconView(Icon.Cloud, 13)
                                        Text("Not on this device yet")
                                    }
                                }
                            }
                        }
                    } else {
                        Div({ classes("xrow__main") }) {
                            Span({ classes("xrow__key") }) { B { Text(label) } }
                            Span({ classes("xrow__snip") }) { Text(reference.instance) }
                        }
                    }
                    IconButton(Icon.Unlink, "Unlink $label from this Topic", small = true) {
                        val edit = workspace.unlinkFromTopic(reference, topic.id) ?: return@IconButton
                        toasts.show(
                            "Unlinked from ${topic.name} and not suggested there. The pull request is unchanged.",
                            undo = { workspace.undo(edit) },
                        )
                    }
                }
            }
        }
    }
}
