package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import thread.domain.external.CiSummary
import thread.domain.external.ExternalDocument
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalView
import thread.domain.external.PullRequestChanges
import thread.domain.external.PullRequestDetail
import thread.domain.external.PullRequestRef
import thread.domain.topic.Topic
import thread.domain.workspace.ExternalReference
import thread.web.presentation.CardKind
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalCards
import thread.web.presentation.LocalToasts
import thread.web.presentation.Place
import thread.web.presentation.SectionCard
import thread.web.presentation.TopicCreation
import thread.web.presentation.View
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.notes.topicsPhrase
import thread.web.presentation.topics.TopicsPickerDialog
import thread.web.presentation.topics.topicPlace
import thread.web.presentation.topics.topicProblemText
import kotlin.math.roundToInt

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

/**
 * One pull request (v3 `X.prDetail`, owner change #13), read-only: the copy on screen and when it is from, then the
 * pull request as cards. [heading1] on the pull request's own page. No diff or file content is read or stored; the
 * counts are GitHub's own totals.
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

/**
 * The pull request as cards, laid out like GitHub (owner change #13, D69): the head card (state, repository, title,
 * author, branches, CI and review, commits and changed files and lines, actions), Description on the left, Reviewers,
 * Assignees and Topics on the right. The cache bar above keeps when the copy was downloaded; the pull request's own
 * "Updated" time and the CI sources field are gone (the owner's ask). A CI part the token cannot read still says so.
 */
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
    val level = partLevel(heading1)
    DetailCards(
        kind = CardKind.PullRequest,
        heading1 = heading1,
        provider = "GitHub",
        kicker = {
            StatePill(pull.isDraft)
            Span({ classes("xd__repo") }) { Text("${pull.ref.owner}/${pull.ref.repo}") }
            if (onDevice) {
                Span({ classes("dl-badge") }) {
                    IconView(Icon.Device, 13)
                    Text("Saved on this device")
                }
            }
        },
        title = {
            Text("${pull.title} ")
            Span({ classes("xi__num") }) { Text("#${pull.ref.number}") }
        },
        head = {
            Div({ classes("xd__by") }) {
                pull.author?.let { Author(it, gitHub.viewer, large = true) }
                Branches(pull.headBranch, pull.baseBranch)
            }
            Div({ classes("xd__status") }) {
                CiIndicator(pull.ci)
                ReviewIndicator(pull.review)
            }
            ciSourcesNote(pull.ci)?.let { note -> P({ classes("xd__note", "muted") }) { Text(note) } }
            pull.changes?.let { Stats(it) }
            if (reference != null) PullRequestActions(pull, reference, gitHub, workspace, onGo)
        },
        main = {
            SectionCard(
                kind = CardKind.Part,
                headingId = "xd-desc-h",
                bodyId = "xd-desc-b",
                title = "Description",
                level = level,
                attrs = { classes("scard--desc") },
            ) { Description(pull.description) }
        },
        side = {
            SectionCard(
                kind = CardKind.Part,
                headingId = "xd-rev-h",
                bodyId = "xd-rev-b",
                title = "Reviewers",
                count = pull.reviewers.size.toString(),
                level = level,
                mark = Icon.Eye,
                attrs = { classes("scard--reviewers") },
            ) {
                if (pull.reviewers.isEmpty()) {
                    P({ classes("scard__empty") }) { Text("No reviewers yet.") }
                } else {
                    ReviewerList(pull.reviewers, gitHub.viewer)
                }
            }
            val assignees = pull.assignees
            SectionCard(
                kind = CardKind.Part,
                headingId = "xd-asg-h",
                bodyId = "xd-asg-b",
                title = "Assignees",
                count = assignees?.size?.toString(),
                level = level,
                mark = Icon.User,
                attrs = { classes("scard--assignees") },
            ) {
                when {
                    assignees == null -> P({ classes("scard__empty") }) { Text("Not in this saved copy. Refresh to load them.") }
                    assignees.isEmpty() -> P({ classes("scard__empty") }) { Text("No one assigned.") }
                    else -> PeopleList(assignees, gitHub.viewer)
                }
            }
            TopicsCard(reference, workspace, "pull request", heading1)
        },
    )
}

/** The head branch merging into the base branch, as two chips. */
@Composable
private fun Branches(
    head: String,
    base: String,
) {
    if (head.isBlank() && base.isBlank()) return
    Span({ classes("bchips") }) {
        if (head.isNotBlank()) {
            Span({ classes("bchip", "bchip--head") }) {
                IconView(Icon.Branch, 13)
                Text(head)
            }
        }
        if (head.isNotBlank() && base.isNotBlank()) {
            Span({
                classes("bchips__into")
                attr("aria-hidden", "true")
            }) { Text("→") }
            Span({ classes("vh") }) { Text(" into ") }
        }
        if (base.isNotBlank()) Span({ classes("bchip") }) { Text(base) }
    }
}

/** Commits, changed files, and lines added and removed, as GitHub counts them for the whole pull request (D69). */
@Composable
private fun Stats(changes: PullRequestChanges) {
    Ul({
        classes("stats")
        attr("aria-label", "Size")
    }) {
        Stat(Icon.Commit, grouped(changes.commits), if (changes.commits == 1) "Commit" else "Commits")
        Stat(Icon.Doc, grouped(changes.changedFiles), if (changes.changedFiles == 1) "File changed" else "Files changed")
        Li({ classes("stat", "stat--diff") }) {
            Span({
                classes("stat__n")
                attr("aria-hidden", "true")
            }) {
                Span({ classes("add") }) { Text("+${grouped(changes.additions)}") }
                Span({ classes("del") }) { Text("−${grouped(changes.deletions)}") }
                DiffBlocks(changes.additions, changes.deletions)
            }
            Span({
                classes("stat__l")
                attr("aria-hidden", "true")
            }) { Text("Lines added and removed") }
            Span({ classes("vh") }) { Text("${grouped(changes.additions)} lines added, ${grouped(changes.deletions)} removed") }
        }
    }
}

@Composable
private fun Stat(
    icon: Icon,
    value: String,
    label: String,
) {
    Li({ classes("stat") }) {
        Span({ classes("stat__n") }) {
            IconView(icon, 18)
            Text(value)
        }
        Span({ classes("stat__l") }) { Text(label) }
    }
}

/** GitHub's five blocks: how much of the change adds and how much removes. Decorative; the numbers say it. */
@Composable
private fun DiffBlocks(
    additions: Int,
    deletions: Int,
) {
    val (added, removed) = diffBlocks(additions, deletions)
    Span({ classes("dstat") }) {
        repeat(DIFF_BLOCKS) { index ->
            Span({
                classes("dstat__b")
                if (index < added) {
                    classes("dstat__b--add")
                } else if (index < added + removed) {
                    classes("dstat__b--del")
                }
            })
        }
    }
}

private const val DIFF_BLOCKS = 5

/**
 * How many of the five blocks show added and how many removed lines. The five split by share, and a side with any lines
 * keeps at least one block. No lines, no colored blocks.
 */
internal fun diffBlocks(
    additions: Int,
    deletions: Int,
): Pair<Int, Int> {
    val total = additions.toLong() + deletions
    if (total <= 0L) return 0 to 0
    val least = if (additions > 0) 1 else 0
    val most = if (deletions > 0) DIFF_BLOCKS - 1 else DIFF_BLOCKS
    val added = (additions * DIFF_BLOCKS.toDouble() / total).roundToInt().coerceIn(least, most)
    return added to DIFF_BLOCKS - added
}

/** `12345` → `12,345`. */
internal fun grouped(value: Int): String =
    value
        .toString()
        .reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()

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

/** GitHub's description, parsed from its Markdown into safe blocks (ADR-007, D57). No HTML from GitHub is inserted. */
@Composable
private fun Description(description: ExternalDocument) {
    if (description.blocks.isEmpty()) {
        P({ classes("muted") }) { Text("No description.") }
        return
    }
    Div({ classes("desc", "desc--md") }) { ExternalDocumentView(description, DocumentSource.GitHub) }
}

/**
 * Says which CI part the token could not read (ADR-007 "CI sources"), or null when it read both. The field is gone
 * (owner change #13); this line only shows when the CI state may be missing something.
 */
private fun ciSourcesNote(ci: CiSummary): String? =
    when {
        ci.statusesReadable && ci.checksReadable -> null
        ci.checksReadable -> "CI from check runs only. This token may not read commit statuses."
        ci.statusesReadable -> "CI from commit statuses only. This token may not read check runs."
        else -> "This token cannot read CI for this pull request."
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
    val cards = LocalCards.current
    // A Topic opens as a card on top of the pull request's screen (D70).
    val openTopic: (Topic) -> Unit = { topic -> if (cards != null) cards.openTopic(topic.id) else onGo(topicPlace(topic)) }
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
                            open = { openTopic(created.topic) },
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
                    open = single?.let { topic -> { openTopic(topic) } },
                    openLabel = "Open Topic",
                )
            },
            onCancel = { picking = false },
        )
    }
}
