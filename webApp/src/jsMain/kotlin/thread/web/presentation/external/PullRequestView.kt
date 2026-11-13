package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Article
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.DList
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
import thread.domain.external.CiSummary
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalView
import thread.domain.external.PullRequestDetail
import thread.domain.external.PullRequestRef
import thread.domain.external.ReviewerState
import thread.domain.topic.Topic
import thread.domain.workspace.ExternalReference
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalToasts
import thread.web.presentation.Place
import thread.web.presentation.TopicCreation
import thread.web.presentation.View
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.notes.topicsPhrase
import thread.web.presentation.topics.TopicsPickerDialog
import thread.web.presentation.topics.topicPlace
import thread.web.presentation.topics.topicProblemText

internal const val PR_TAB_PARAM = "tab"
internal const val PR_ID_PARAM = "id"
internal const val PR_REVIEW_TAB = "review"

/** `#/prs?id=owner/repo#n`, with `tab=review` for the Review requested list. */
internal fun prPlace(
    ref: PullRequestRef,
    review: Boolean,
): Place =
    View.Prs.place
        .let { if (review) it.with(PR_TAB_PARAM, PR_REVIEW_TAB) else it }
        .with(PR_ID_PARAM, ref.id)

/** `#/item/pr/owner/repo/n`, the pull request as its own page; [fromTopic] makes its back link return to that Topic. */
internal fun prItemPlace(
    ref: PullRequestRef,
    fromTopic: String?,
): Place =
    Place.of("item", "pr", ref.owner, ref.repo, ref.number.toString()).let {
        if (fromTopic != null) it.with("from", "topic:$fromTopic") else it
    }

/** The pull request of an item place, or null. */
internal fun prRefOf(place: Place): PullRequestRef? {
    val owner = place.segment(2) ?: return null
    val repo = place.segment(3) ?: return null
    val number = place.segment(4) ?: return null
    return PullRequestRef.parse("$owner/$repo#$number")
}

/**
 * One pull request (v3 `X.prDetail`), read-only: the copy on screen and when it is from, the Topics it is linked to,
 * Link to Topic, Create Topic from pull request, Open in GitHub, then description, reviewers and fields. [heading1]
 * on the pull request's own page. No diff or file content is read or stored.
 */
@Composable
internal fun PullRequestDetailView(
    gitHub: GitHubModel,
    workspace: WorkspaceModel,
    view: ExternalView<PullRequestDetail>?,
    heading1: Boolean,
    onRefresh: () -> Unit,
    onGo: (Place) -> Unit,
) {
    val copy = view?.savedOrNull
    when {
        !gitHub.connected -> GitHubNotConnected(gitHub.name)
        view == null || view == ExternalView.Loading -> P({ classes("muted") }) { Text("Loading the pull request…") }
        view is ExternalView.Missing -> MissingPullRequest(view.problem, gitHub.name, onRefresh)
        copy != null -> {
            CacheBar(view, "Refresh this pull request", onRefresh)
            PullRequestArticle(copy.value, view.isOnDevice, gitHub, workspace, heading1, onGo)
        }
    }
}

/** v3 `X.notConnected('github')`. */
@Composable
internal fun GitHubNotConnected(name: String) {
    EmptyState(
        Icon.Pr,
        "$name is not connected",
        "Connect it in Settings to see your pull requests here. Thread only reads; it never changes anything in GitHub.",
    ) { A(CONNECTIONS_PLACE.hash, { classes("btn", "btn--line") }) { Text("Open Settings") } }
}

@Composable
private fun MissingPullRequest(
    problem: ExternalProblem,
    name: String,
    onRefresh: () -> Unit,
) {
    when (problem) {
        ExternalProblem.NotConnected -> GitHubNotConnected(name)
        ExternalProblem.NotFound -> EmptyState(Icon.Pr, "Pull request not found", "It may be closed or not shared with you.")
        ExternalProblem.Offline ->
            EmptyState(
                Icon.CloudOff,
                "Not saved on this device",
                "This one was never opened here, so there is no copy to show offline. Connect to the internet to load it.",
            )
        else -> {
            val message = describe(problem, name, "pull requests", null, Times.now())
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
private fun PullRequestArticle(
    pull: PullRequestDetail,
    onDevice: Boolean,
    gitHub: GitHubModel,
    workspace: WorkspaceModel,
    heading1: Boolean,
    onGo: (Place) -> Unit,
) {
    val reference = gitHub.reference(pull.ref)
    Article({
        classes("xd")
        attr("aria-labelledby", XD_TITLE_ID)
    }) {
        Header({ classes("xd__head") }) {
            P({ classes("xd__kicker") }) {
                StatePill(pull.isDraft)
                Span { Text("${pull.ref.owner}/${pull.ref.repo}") }
                Span {
                    IconView(Icon.Branch, 14)
                    Text(" ${pull.headBranch}")
                }
                if (onDevice) {
                    Span({ classes("dl-badge") }) {
                        IconView(Icon.Device, 13)
                        Text("Saved on this device")
                    }
                }
            }
            val titleAttrs: AttrsScope<*>.() -> Unit = {
                classes("xd__title")
                id(XD_TITLE_ID)
                tabIndex(-1)
            }
            val title: @Composable () -> Unit = {
                Text("${pull.title} ")
                Span({ classes("xi__num") }) { Text("#${pull.ref.number}") }
            }
            if (heading1) H1(titleAttrs) { title() } else H2(titleAttrs) { title() }
            P({ classes("xd__kicker") }) {
                CiIndicator(pull.ci)
                ReviewIndicator(pull.review)
            }
            if (reference != null) {
                TopicLinks(reference, workspace, "pull request")
                PullRequestActions(pull, reference, gitHub, workspace, onGo)
            }
        }
        Div({ classes("xd__grid") }) {
            Div({ classes("xd__main") }) {
                H3({ classes("xd__h") }) { Text("Description") }
                PlainDescription(pull.body)
                H3({ classes("xd__h") }) { Text("Reviewers") }
                if (pull.reviewers.isEmpty()) {
                    P({ classes("muted") }) { Text("No reviewers yet.") }
                } else {
                    Ul({ classes("revs") }) {
                        pull.reviewers.forEach { reviewer ->
                            Li {
                                Span { Text(reviewer.name) }
                                Span({ classes("muted") }) { Text(reviewerText(reviewer.state)) }
                            }
                        }
                    }
                }
            }
            DList({ classes("fields") }) {
                Field("Repository", "${pull.ref.owner}/${pull.ref.repo}")
                Field("Author", pull.author)
                Field("Branch", "${pull.headBranch} → ${pull.baseBranch}")
                Field("Updated", pull.updatedAtMillis?.let { Times.whenText(it) })
                Field("CI sources", ciSourcesText(pull.ci))
            }
        }
        ReadOnlyNote("GitHub")
    }
}

/** v3 `X.openPill`, or a Draft pill for a draft pull request. */
@Composable
internal fun StatePill(isDraft: Boolean) {
    if (!isDraft) {
        OpenPill()
        return
    }
    Span({ classes("pill-open", "pill-open--draft") }) {
        IconView(Icon.Pr, 13)
        Text("Draft")
    }
}

/** GitHub's description as plain text: one paragraph per blank-line block, line breaks kept. No Markdown or HTML. */
@Composable
private fun PlainDescription(body: String) {
    val paragraphs = body.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    if (paragraphs.isEmpty()) {
        P({ classes("muted") }) { Text("No description.") }
        return
    }
    Div({ classes("desc", "desc--plain") }) { paragraphs.forEach { P { Text(it) } } }
}

private fun reviewerText(state: ReviewerState): String =
    when (state) {
        ReviewerState.Requested -> "Review requested"
        ReviewerState.Approved -> "Approved"
        ReviewerState.ChangesRequested -> "Changes requested"
        ReviewerState.Commented -> "Commented"
        ReviewerState.Dismissed -> "Dismissed"
    }

/** Says which CI part the token could not read (ADR-007 "CI sources"), or "Statuses and check runs". */
private fun ciSourcesText(ci: CiSummary): String =
    when {
        ci.statusesReadable && ci.checksReadable -> "Statuses and check runs"
        ci.checksReadable -> "Check runs only. This token may not read commit statuses."
        ci.statusesReadable -> "Statuses only. This token may not read check runs."
        else -> "None readable with this token"
    }

@Composable
private fun PullRequestActions(
    pull: PullRequestDetail,
    reference: ExternalReference,
    gitHub: GitHubModel,
    workspace: WorkspaceModel,
    onGo: (Place) -> Unit,
) {
    val toasts = LocalToasts.current
    var picking by remember(reference.id) { mutableStateOf(false) }
    val label = "#${pull.ref.number}"
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
                when (val created = workspace.createTopicLinkedTo(pull.title, reference)) {
                    is TopicCreation.Refused -> toasts.show(topicProblemText(created.problem))
                    is TopicCreation.Created ->
                        toasts.show(
                            "Created Topic “${created.topic.name}” and linked $label",
                            open = { onGo(topicPlace(created.topic)) },
                            openLabel = "Open Topic",
                        )
                }
            }
        }) {
            IconView(Icon.Plus, 16)
            Text(" Create Topic from pull request")
        }
        OpenOutside(pull.webUrl, "Open in GitHub", hidden = gitHub.isSynthetic)
    }
    if (picking) {
        val current = workspace.topicsLinkedTo(reference.id).map { it.id }
        TopicsPickerDialog(
            workspace = workspace,
            title = "Link $label to Topics",
            hint = "One pull request can belong to several Topics. Nothing is copied.",
            confirmLabel = "Save links",
            selected = current,
            onPick = { picked ->
                picking = false
                val edit = workspace.setTopicsOf(reference, picked) ?: return@TopicsPickerDialog
                val names = picked.mapNotNull { workspace.topic(it.value)?.name }
                val single: Topic? = if (picked.size == 1) workspace.topic(picked[0].value) else null
                toasts.show(
                    if (picked.isEmpty()) "Removed all Topic links from $label" else "Linked $label to ${topicsPhrase(names)}",
                    undo = { workspace.undo(edit) },
                    open = single?.let { topic -> { onGo(topicPlace(topic)) } },
                    openLabel = "Open Topic",
                )
            },
            onCancel = { picking = false },
        )
    }
}
