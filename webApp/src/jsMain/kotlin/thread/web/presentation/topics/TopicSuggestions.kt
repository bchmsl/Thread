package thread.web.presentation.topics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.HTMLElement
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalResult
import thread.domain.external.ExternalView
import thread.domain.external.JiraFamily
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraStatus
import thread.domain.external.JiraStatusCategory
import thread.domain.external.PullRequestMentions
import thread.domain.external.PullRequestRef
import thread.domain.external.RateLimitGate
import thread.domain.suggest.FamilyRole
import thread.domain.suggest.RELATED_FRESH_MILLIS
import thread.domain.suggest.SUGGESTIONS_SHOWN
import thread.domain.suggest.SuggestionFacts
import thread.domain.suggest.SuggestionReason
import thread.domain.suggest.TopicTextField
import thread.domain.suggest.familyCandidates
import thread.domain.suggest.familySearchKeys
import thread.domain.suggest.isFresh
import thread.domain.suggest.issueLinkCandidates
import thread.domain.suggest.keysToCheck
import thread.domain.suggest.mentionCandidates
import thread.domain.suggest.mentionSearchKeys
import thread.domain.suggest.mentionSearchRepos
import thread.domain.suggest.projectOf
import thread.domain.suggest.pullRequestMentionCandidates
import thread.domain.suggest.rankSuggestions
import thread.domain.suggest.topicTextSources
import thread.domain.suggest.writtenJiraKeys
import thread.domain.suggest.writtenPullRequests
import thread.domain.topic.Topic
import thread.domain.workspace.ExternalProvider
import thread.domain.workspace.ExternalReference
import thread.domain.workspace.dismissSuggestion
import thread.domain.workspace.dismissedReferencesOf
import thread.domain.workspace.pullRequestRefOf
import thread.domain.workspace.referencesOf
import thread.domain.workspace.restoreSuggestion
import thread.web.platform.isOnline
import thread.web.platform.nowMillis
import thread.web.platform.readDeviceText
import thread.web.platform.writeDeviceText
import thread.web.presentation.CardKind
import thread.web.presentation.CardMark
import thread.web.presentation.Icon
import thread.web.presentation.IconButton
import thread.web.presentation.IconView
import thread.web.presentation.JiraTypeName
import thread.web.presentation.LocalToasts
import thread.web.presentation.ModalDialog
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.external.GitHubModel
import thread.web.presentation.external.JiraModel
import thread.web.presentation.external.JiraStatusLozenge
import thread.web.presentation.external.orFallback
import thread.web.presentation.itemCard
import thread.web.presentation.rememberOnline

/*
 * Topic suggestions (D49, adr/ADR-017-topic-suggestions.md): related Jira tickets and pull requests under the linked
 * ones, found by fixed rules on this device. Only ticket keys, pull request ids and repository names go to Jira and
 * GitHub, never the Topic's text. Link and "Don't suggest" each come with Undo; "Not suggested here" restores.
 */

/** One suggestion row: the record, what the row shows, and why it is there. */
internal class SuggestionRow(
    val reference: ExternalReference,
    /** `SYN-104`, or `#12` for a pull request. */
    val label: String,
    val title: String?,
    val status: JiraStatus?,
    /** The repository of a pull request. */
    val repo: String?,
    val reasons: List<SuggestionReason>,
    val alsoIn: List<Topic>,
    val jiraKey: JiraIssueKey?,
    val pull: PullRequestRef?,
    /** A ticket's Jira issue type, for its icon (D63). */
    val type: String? = null,
)

/** A record not suggested in the Topic, with the title saved on this device when there is one. */
internal class DismissedRow(
    val reference: ExternalReference,
    val label: String,
    val title: String?,
    /** A Jira ticket's type, for its icon (D63). */
    val type: String? = null,
)

/**
 * The suggestions of one Topic, and what is not suggested there, per section. [jiraParentOf] is a ticket's parent as
 * this device knows it, for nesting the linked tickets (D64): the Topic's family answer (D49 S4) when it has the ticket,
 * else the ticket's saved copy.
 */
internal class TopicSuggestions(
    val jira: List<SuggestionRow>,
    val jiraDismissed: List<DismissedRow>,
    val pulls: List<SuggestionRow>,
    val pullsDismissed: List<DismissedRow>,
    val jiraParentOf: (JiraIssueKey) -> JiraIssueKey? = { null },
) {
    companion object {
        val None = TopicSuggestions(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/** Jira GETs one Topic open may send for suggestions: 10 linked tickets without a saved copy, 5 keys to check. */
private const val JIRA_CHECKS_PER_OPEN = 15
private const val LINKED_TICKETS_TO_LOAD = 10

/** GitHub GETs one Topic open may send for suggestions: linked and written pull requests without a saved copy. */
private const val PULL_CHECKS_PER_OPEN = 5

/**
 * The suggestions of [topic]. What is on this device shows at once. Linked tickets and pull requests that are not
 * saved here, and keys written in the Topic that Jira or GitHub has not confirmed yet, are asked about in the
 * background, a few per Topic open, and join when they answer. Nothing is asked offline, inside a rate limit, or after
 * the token was refused; the rows on this device stay.
 */
@Composable
internal fun rememberTopicSuggestions(
    workspace: WorkspaceModel,
    jira: JiraModel,
    gitHub: GitHubModel,
    topic: Topic,
): TopicSuggestions {
    val data = workspace.data ?: return TopicSuggestions.None
    val site = jira.site
    val now = nowMillis()
    val texts = topicTextSources(topic, workspace.storedTasks, workspace.notesFor(topic.id))
    val linkedPullRefs = referencesOf(topic.id, ExternalProvider.GitHub, data).mapNotNull(::pullRequestRefOf)
    val linkedPulls = linkedPullRefs.mapNotNull { gitHub.detail(it.id) }

    // Jira: written keys (S1), issue links of linked tickets (S2), keys in linked pull requests (S3), and the parents,
    // children and siblings of linked tickets (S4).
    val jiraLinked =
        referencesOf(topic.id, ExternalProvider.Jira, data)
            .filter(jira::isHere)
            .mapNotNull { JiraIssueKey.parse(it.key) }
    // Only tickets Jira showed here are searched for (S4, S5): a key Jira does not know would refuse the search.
    val jiraConfirmed = jiraLinked.filter { jira.summary(it.value) != null }
    val family = rememberJiraFamily(jira, topic, familySearchKeys(jiraConfirmed))
    val familyRows = family?.rows.orEmpty().associateBy { it.key.value }
    val jiraDismissedRefs = dismissedReferencesOf(topic.id, ExternalProvider.Jira, data).filter(jira::isHere)
    val linkedIssues = jiraLinked.mapNotNull { jira.saved[it.value]?.value }
    val related = linkedIssues.flatMap { it.related }.associateBy { it.key.value }
    val jiraCandidates =
        if (site == null) {
            emptyList()
        } else {
            writtenJiraKeys(texts, site.site) + issueLinkCandidates(linkedIssues) + pullRequestMentionCandidates(linkedPulls, site.site) +
                familyCandidates(jiraLinked, family?.rows.orEmpty())
        }
    // A key is confirmed when Jira showed it here: a saved ticket, a list or search row, an issue link or a family row.
    val jiraFacts: (JiraIssueKey) -> SuggestionFacts? = { key ->
        (jira.summary(key.value) ?: familyRows[key.value])
            ?.let { SuggestionFacts(it.status?.category == JiraStatusCategory.Done, it.updatedAtMillis) }
            ?: related[key.value]?.let { SuggestionFacts(it.status?.category == JiraStatusCategory.Done, null) }
    }
    val jiraDismissed = jiraDismissedRefs.mapNotNull { JiraIssueKey.parse(it.key) }.toSet()
    val jiraRanked = rankSuggestions(jiraCandidates, jiraFacts, jiraLinked.toSet(), jiraDismissed)

    // Pull requests: written in the Topic (S1), and naming a linked ticket in their title or description (S5).
    val pullDismissedRefs = dismissedReferencesOf(topic.id, ExternalProvider.GitHub, data)
    val mentionRepos = mentionSearchRepos(linkedPullRefs, gitHub.known().map { it.ref })
    val mentions = rememberPullMentions(gitHub, topic, mentionRepos, mentionSearchKeys(jiraConfirmed))
    val mentionHits = mentions?.pulls.orEmpty().associateBy { it.summary.ref.id }
    // GitHub names a repository in its own letter case; a pull request linked in another case is still linked.
    val pullIdsTaken = (linkedPullRefs + pullDismissedRefs.mapNotNull(::pullRequestRefOf)).map { it.id.lowercase() }.toSet()
    val pullCandidates =
        if (gitHub.connected) {
            val mentioned = mentionCandidates(mentions?.pulls.orEmpty(), jiraLinked)
            writtenPullRequests(texts) + mentioned.filter { it.item.id.lowercase() !in pullIdsTaken }
        } else {
            emptyList()
        }
    val pullFacts: (PullRequestRef) -> SuggestionFacts? = { ref ->
        val summary = gitHub.summary(ref.id)
        val hit = mentionHits[ref.id]
        if (summary == null && hit == null) null else SuggestionFacts(hit?.closed ?: false, (summary ?: hit?.summary)?.updatedAtMillis)
    }
    val pullRanked =
        rankSuggestions(pullCandidates, pullFacts, linkedPullRefs.toSet(), pullDismissedRefs.mapNotNull(::pullRequestRefOf).toSet())

    // What to ask about: linked records without a saved copy, then unconfirmed keys, known projects first.
    val unknownKeys =
        jiraCandidates
            .map { it.item }
            .distinct()
            .filter { it !in jiraLinked && it !in jiraDismissed && jiraFacts(it) == null && !jira.isNotATicket(it, now) }
    val knownProjects = jira.known().map { projectOf(it.key) }.toSet()
    val jiraToAsk =
        jiraLinked.filter { it.value !in jira.saved && !jira.isNotATicket(it, now) }.take(LINKED_TICKETS_TO_LOAD) +
            keysToCheck(unknownKeys, knownProjects)
    val pullsToAsk =
        (linkedPullRefs.filter { it.id !in gitHub.saved } + pullCandidates.map { it.item }.filter { pullFacts(it) == null })
            .distinct()
            .filter { it !in pullDismissedRefs.mapNotNull(::pullRequestRefOf) }
            .take(PULL_CHECKS_PER_OPEN)
    AskInBackground(jira, gitHub, topic, jiraToAsk, pullsToAsk)

    return TopicSuggestions(
        jira =
            jiraRanked.mapNotNull { suggestion ->
                val key = suggestion.item
                val reference = jira.reference(key) ?: return@mapNotNull null
                val summary = jira.summary(key.value) ?: familyRows[key.value]
                val relatedRow = related[key.value]
                SuggestionRow(
                    reference = reference,
                    label = key.value,
                    title = summary?.title ?: relatedRow?.title,
                    status = summary?.status ?: relatedRow?.status,
                    repo = null,
                    reasons = suggestion.reasons,
                    alsoIn = workspace.topicsLinkedTo(reference.id).filter { it.id != topic.id },
                    jiraKey = key,
                    pull = null,
                    type = summary?.type ?: relatedRow?.type,
                )
            },
        jiraDismissed =
            jiraDismissedRefs.map { reference ->
                val summary = jira.summary(reference.key)
                val relatedRow = related[reference.key]
                DismissedRow(reference, reference.key, summary?.title ?: relatedRow?.title, summary?.type ?: relatedRow?.type)
            },
        pulls =
            pullRanked.mapNotNull { suggestion ->
                val ref = suggestion.item
                val reference = gitHub.reference(ref) ?: return@mapNotNull null
                SuggestionRow(
                    reference = reference,
                    label = "#${ref.number}",
                    title = (gitHub.summary(ref.id) ?: mentionHits[ref.id]?.summary)?.title,
                    status = null,
                    repo = "${ref.owner}/${ref.repo}",
                    reasons = suggestion.reasons,
                    alsoIn = workspace.topicsLinkedTo(reference.id).filter { it.id != topic.id },
                    jiraKey = null,
                    pull = ref,
                )
            },
        pullsDismissed =
            pullDismissedRefs.map { reference ->
                val title = pullRequestRefOf(reference)?.let { gitHub.summary(it.id)?.title }
                DismissedRow(reference, "#${reference.key}", title ?: reference.instance)
            },
        // A family row without a parent means Jira gave none, so it wins over an older saved copy. Without one, the saved
        // ticket's parent: list rows and the rows a refresh brings (D67) never ask for the parent, so a newer row
        // without one says nothing about it.
        jiraParentOf = { key ->
            val row = familyRows[key.value]
            if (row != null) row.parentKey else jira.saved[key.value]?.value?.parentKey ?: jira.summary(key.value)?.parentKey
        },
    )
}

/**
 * Asks Jira and GitHub about [jiraKeys] and [pulls] while [topic] is open, each key at most once per open and within
 * [JIRA_CHECKS_PER_OPEN] and [PULL_CHECKS_PER_OPEN]. Answers are saved on this device like an opened ticket or pull
 * request, so the next open sends nothing. A key Jira does not show is remembered for a day.
 */
@Composable
private fun AskInBackground(
    jira: JiraModel,
    gitHub: GitHubModel,
    topic: Topic,
    jiraKeys: List<JiraIssueKey>,
    pulls: List<PullRequestRef>,
) {
    val online by rememberOnline()
    val latestKeys by rememberUpdatedState(jiraKeys)
    val latestPulls by rememberUpdatedState(pulls)
    LaunchedEffect(topic.id, jira.version, online) {
        if (!online) return@LaunchedEffect
        val asked = mutableSetOf<JiraIssueKey>()
        snapshotFlow { latestKeys }.collect { keys ->
            for (key in keys) {
                if (key in asked || asked.size >= JIRA_CHECKS_PER_OPEN) continue
                if (!jira.connected || jira.problem == ExternalProblem.Unauthorized || !isOnline()) break
                if (jira.services.rateLimit.blockedUntil(nowMillis()) != null) break
                asked += key
                val view = jira.issueLoader.load(key).last()
                val missing = (view as? ExternalView.Missing)?.problem
                if (missing == ExternalProblem.NotFound || missing == ExternalProblem.Forbidden) jira.markNotATicket(key, nowMillis())
                // A ticket of a project the token cannot see is not a problem with the connection.
                if (missing != ExternalProblem.Forbidden) jira.observeTicket(key, view)
            }
        }
    }
    LaunchedEffect(topic.id, gitHub.version, online) {
        if (!online) return@LaunchedEffect
        val asked = mutableSetOf<PullRequestRef>()
        snapshotFlow { latestPulls }.collect { refs ->
            for (ref in refs) {
                if (ref in asked || asked.size >= PULL_CHECKS_PER_OPEN) continue
                if (!gitHub.connected || gitHub.problem == ExternalProblem.Unauthorized || !isOnline()) break
                if (gitHub.services.coreRateLimit.blockedUntil(nowMillis()) != null) break
                asked += ref
                val view = gitHub.pullLoader.load(ref).last()
                if ((view as? ExternalView.Missing)?.problem != ExternalProblem.Forbidden) gitHub.observePull(ref, view)
            }
        }
    }
}

/**
 * The [JiraFamily] of [topic]'s confirmed linked [keys] (D49 S4): the answer saved on this device at once, then a
 * refresh when it is older than [RELATED_FRESH_MILLIS] or was for other keys. The refresh waits [RELATED_WAIT_MILLIS]
 * so quick link changes send one search, and is skipped offline, inside Jira's rate limit or after the token was
 * refused. Only an expired token or a rate limit is shown; anything else keeps the saved answer quietly.
 */
@Composable
private fun rememberJiraFamily(
    jira: JiraModel,
    topic: Topic,
    keys: List<JiraIssueKey>,
): JiraFamily? {
    var family by remember(topic.id, jira.version) { mutableStateOf<JiraFamily?>(null) }
    val online by rememberOnline()
    LaunchedEffect(topic.id, jira.version, online, keys) {
        val source = jira.services.family
        val saved = orFallback(null) { source.readSaved(topic.id) }
        if (saved != null) family = saved.value
        val fresh = saved != null && saved.value.keys == keys && isFresh(saved.savedAtMillis, nowMillis())
        if (keys.isEmpty() || fresh) return@LaunchedEffect
        delay(RELATED_WAIT_MILLIS)
        if (!online || !isOnline() || !jira.connected || jira.problem == ExternalProblem.Unauthorized) return@LaunchedEffect
        if (jira.services.rateLimit.blockedUntil(nowMillis()) != null) return@LaunchedEffect
        when (val result = orFallback(null) { source.refresh(topic.id, keys, nowMillis()) }) {
            is ExternalResult.Ok -> family = result.value.saved.value
            is ExternalResult.Problem -> noteRelatedProblem(result.problem, jira::note, jira.services.rateLimit)
            null -> Unit
        }
    }
    return family
}

/**
 * The pull requests in [repos] that name [topic]'s confirmed linked [keys] (D49 S5), saved and refreshed like
 * [rememberJiraFamily], inside GitHub's search rate limit.
 */
@Composable
private fun rememberPullMentions(
    gitHub: GitHubModel,
    topic: Topic,
    repos: List<String>,
    keys: List<JiraIssueKey>,
): PullRequestMentions? {
    var mentions by remember(topic.id, gitHub.version) { mutableStateOf<PullRequestMentions?>(null) }
    val online by rememberOnline()
    LaunchedEffect(topic.id, gitHub.version, online, repos, keys) {
        val source = gitHub.services.mentions
        val saved = orFallback(null) { source.readSaved(topic.id) }
        if (saved != null) mentions = saved.value
        val fresh = saved != null && saved.value.repos == repos && saved.value.keys == keys && isFresh(saved.savedAtMillis, nowMillis())
        if (repos.isEmpty() || keys.isEmpty() || fresh) return@LaunchedEffect
        delay(RELATED_WAIT_MILLIS)
        if (!online || !isOnline() || !gitHub.connected || gitHub.problem == ExternalProblem.Unauthorized) return@LaunchedEffect
        if (gitHub.services.searchRateLimit.blockedUntil(nowMillis()) != null) return@LaunchedEffect
        when (val result = orFallback(null) { source.refresh(topic.id, repos, keys, nowMillis()) }) {
            is ExternalResult.Ok -> mentions = result.value.saved.value
            is ExternalResult.Problem -> noteRelatedProblem(result.problem, gitHub::note, gitHub.services.searchRateLimit)
            null -> Unit
        }
    }
    return mentions
}

/** An expired token is noted for the whole app; a rate limit holds the next request; the rest stays quiet. */
private fun noteRelatedProblem(
    problem: ExternalProblem,
    note: (ExternalProblem) -> Unit,
    gate: RateLimitGate,
) {
    when (problem) {
        ExternalProblem.Unauthorized -> note(problem)
        is ExternalProblem.RateLimited -> gate.block(problem.retryAtMillis)
        else -> Unit
    }
}

/** Link changes within this wait send one S4 or S5 search, not one per change. */
private const val RELATED_WAIT_MILLIS = 800L

/** Why a row is suggested, in words. */
internal fun reasonText(reason: SuggestionReason): String =
    when (reason) {
        is SuggestionReason.Written ->
            "Mentioned in " +
                when (reason.field) {
                    TopicTextField.Name -> "the Topic name"
                    TopicTextField.Goal -> "the goal"
                    TopicTextField.Info -> "Additional information"
                    TopicTextField.Task -> "a Task"
                    TopicTextField.Note -> "a Note"
                }
        is SuggestionReason.MentionedByPullRequest -> "Mentioned in pull request #${reason.pull.number}"
        is SuggestionReason.IssueLink -> "${reason.linkedKey.value} ${reason.relation} this"
        is SuggestionReason.Family ->
            when (reason.role) {
                FamilyRole.Parent -> "Parent of ${reason.linkedKey.value}"
                FamilyRole.Child -> "Child of ${reason.linkedKey.value}"
            }
        is SuggestionReason.SameParent -> "Same parent as ${reason.linkedKey.value}"
        is SuggestionReason.MentionsLinkedTicket -> "Mentions ${reason.linkedKey.value}"
    }

/** Where this device keeps whether a Topic's suggestions are open, per Topic and section: `1` or `0`. Not synced (D65). */
internal const val SUGGESTIONS_OPEN_NAME = "thread.suggest.open"

/** Whether [section]'s suggestions in Topic [topicId] are open on this device. They start open, also when storage is blocked. */
internal fun areSuggestionsOpen(
    topicId: String,
    section: String,
): Boolean = readDeviceText("$SUGGESTIONS_OPEN_NAME.$topicId.$section") != "0"

internal fun keepSuggestionsOpen(
    topicId: String,
    section: String,
    open: Boolean,
) = writeDeviceText("$SUGGESTIONS_OPEN_NAME.$topicId.$section", if (open) "1" else "0")

/**
 * The "Suggested" group under a section's linked rows: [SUGGESTIONS_SHOWN] rows, "Show N more", and "Not suggested
 * here (N)". Its heading folds the group away and opens it again, remembered on this device per Topic (owner change
 * #11, D65). [sectionKey] is the section's key (`jira`, `prs`); focus moves to the next suggestion after Link or
 * "Don't suggest", else to the section heading.
 */
@Composable
internal fun SuggestedGroup(
    sectionKey: String,
    card: CardKind,
    kind: String,
    workspace: WorkspaceModel,
    topic: Topic,
    rows: List<SuggestionRow>,
    dismissed: List<DismissedRow>,
    href: (SuggestionRow) -> String,
    onOpen: (SuggestionRow) -> Boolean,
) {
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    var expanded by remember(topic.id) { mutableStateOf(false) }
    var reviewing by remember(topic.id) { mutableStateOf(false) }
    var open by remember(topic.id, sectionKey) { mutableStateOf(areSuggestionsOpen(topic.id.value, sectionKey)) }
    val groupId = "sg-$sectionKey"
    val heading = { document.querySelector("#sh-$sectionKey button") as? HTMLElement }
    // After Link or "Don't suggest" the row leaves: focus the suggestion now at its place, else the heading.
    val focusAfter: (SuggestionRow, Int) -> Unit = { row, index ->
        scope.launch {
            focusWhenGone("#$groupId [data-suggested=\"${row.reference.id}\"]") {
                document.querySelectorAll("#$groupId .sg__link").item(index) as? HTMLElement ?: heading()
            }
        }
    }
    val shown = if (expanded) rows else rows.take(SUGGESTIONS_SHOWN)
    if (rows.isEmpty() && dismissed.isEmpty()) return
    // With no suggestions there is nothing to fold: only "Not suggested here" shows, as before.
    val folded = !open && rows.isNotEmpty()
    Div({
        classes("sugg")
        if (folded) classes("is-folded")
        id(groupId)
    }) {
        if (rows.isNotEmpty()) {
            H3({ classes("sect__sub", "sugg__head") }) {
                Button({
                    type(ButtonType.Button)
                    classes("sugg__toggle")
                    attr("aria-expanded", open.toString())
                    attr("aria-controls", "$groupId-b")
                    onClick {
                        open = !open
                        keepSuggestionsOpen(topic.id.value, sectionKey, open)
                    }
                }) {
                    Span({ classes("chev") }) { IconView(Icon.ChevDown, 16) }
                    Text("Suggested ")
                    Span({ classes("n") }) { Text(rows.size.toString()) }
                }
            }
        }
        Div({
            id("$groupId-b")
            if (folded) attr("hidden", "")
        }) {
            if (!folded && rows.isNotEmpty()) {
                Ul({ classes("tlist") }) {
                    shown.forEachIndexed { index, row ->
                        key(row.reference.id) {
                            Li({
                                itemCard(card)
                                classes("icard--sugg")
                            }) {
                                CardMark(card, row.type)
                                A(href(row), {
                                    classes("xrow__main")
                                    attr("data-suggested", row.reference.id)
                                    onClick { if (onOpen(row)) it.preventDefault() }
                                }) {
                                    Span({ classes("xrow__key") }) {
                                        // The card's stub shows the type's icon (D63, D68); screen readers hear it here.
                                        if (row.jiraKey != null) JiraTypeName(row.type)
                                        B { Text(row.label) }
                                        if (row.status != null) JiraStatusLozenge(row.status)
                                        if (row.repo != null) Span { Text(row.repo) }
                                    }
                                    if (row.title != null) Span({ classes("xrow__title") }) { Text(row.title) }
                                    Span(
                                        { classes("xrow__snip", "sugg__why") },
                                    ) { Text(row.reasons.joinToString(" · ", transform = ::reasonText)) }
                                    if (row.alsoIn.isNotEmpty()) {
                                        Span({ classes("xrow__also") }) {
                                            Text("Also in ")
                                            row.alsoIn.forEach { PlainTopicTag(it) }
                                        }
                                    }
                                }
                                Div({ classes("sugg__acts") }) {
                                    Button({
                                        type(ButtonType.Button)
                                        classes("btn", "btn--text", "btn--sm", "sg__link")
                                        attr("aria-label", "Link ${row.label} to this Topic")
                                        onClick {
                                            val edit = workspace.linkToTopic(row.reference, topic.id) ?: return@onClick
                                            toasts.show("Linked", undo = { workspace.undo(edit) })
                                            focusAfter(row, index)
                                        }
                                    }) {
                                        IconView(Icon.Link, 15)
                                        Text(" Link")
                                    }
                                    IconButton(Icon.X, "Don't suggest ${row.label} in this Topic", small = true) {
                                        val current = workspace.data ?: return@IconButton
                                        val edit = workspace.apply(dismissSuggestion(row.reference, topic.id, current)) ?: return@IconButton
                                        toasts.show("${row.label} won't be suggested in this Topic.", undo = { workspace.undo(edit) })
                                        focusAfter(row, index)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!folded) {
                val more = rows.size - shown.size
                if (more > 0 || dismissed.isNotEmpty()) {
                    Div({ classes("sugg__foot") }) {
                        if (more > 0) {
                            Button({
                                type(ButtonType.Button)
                                classes("btn", "btn--text", "btn--sm")
                                onClick { expanded = true }
                            }) { Text("Show $more more") }
                        }
                        if (dismissed.isNotEmpty()) {
                            Button({
                                type(ButtonType.Button)
                                classes("btn", "btn--text", "btn--sm", "sg__review")
                                attr("aria-haspopup", "dialog")
                                onClick { reviewing = true }
                            }) { Text("Not suggested here (${dismissed.size})") }
                        }
                    }
                }
            }
        }
    }
    if (reviewing) {
        NotSuggestedDialog(card, kind, workspace, topic, dismissed) {
            reviewing = false
            // Back to "Not suggested here", or the heading when everything was restored.
            scope.launch {
                focusWhenGone("dialog.dlg--sugg") { document.querySelector("#$groupId .sg__review") as? HTMLElement ?: heading() }
            }
        }
    }
}

/** The records not suggested in [topic], each with Restore (D49). */
@Composable
private fun NotSuggestedDialog(
    card: CardKind,
    kind: String,
    workspace: WorkspaceModel,
    topic: Topic,
    dismissed: List<DismissedRow>,
    onClose: () -> Unit,
) {
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    ModalDialog(classes = listOf("dlg--sugg"), onClose = onClose) { titleId ->
        Div({ classes("dlg__in") }) {
            H2({
                classes("dlg__title")
                id(titleId)
            }) { Text("Not suggested here") }
            P({ classes("dlg__text") }) {
                Text("These $kind are not suggested in ${topic.name}. Restore one to see it as a suggestion again.")
            }
            if (dismissed.isEmpty()) {
                P({ classes("sect__empty") }) { Text("Nothing left to restore.") }
            } else {
                Ul({ classes("tlist", "sugg__dismissed") }) {
                    dismissed.forEachIndexed { index, row ->
                        val reference = row.reference
                        val label = row.label
                        val title = row.title
                        key(reference.id) {
                            Li({
                                itemCard(card)
                                attr("data-dismissed", reference.id)
                            }) {
                                CardMark(card, row.type)
                                Div({ classes("xrow__main") }) {
                                    Span({ classes("xrow__key") }) {
                                        if (reference.provider == ExternalProvider.Jira) JiraTypeName(row.type)
                                        B { Text(label) }
                                    }
                                    Span({ classes("xrow__title") }) { Text(title ?: "Not on this device") }
                                }
                                Button({
                                    type(ButtonType.Button)
                                    classes("btn", "btn--line", "btn--sm", "sg__restore")
                                    attr("aria-label", "Restore $label as a suggestion")
                                    onClick {
                                        val current = workspace.data ?: return@onClick
                                        val edit = workspace.apply(restoreSuggestion(reference.id, topic.id, current)) ?: return@onClick
                                        toasts.show("$label can be suggested here again.", undo = { workspace.undo(edit) })
                                        scope.launch {
                                            focusWhenGone(".dlg--sugg [data-dismissed=\"${reference.id}\"]") {
                                                val buttons = document.querySelectorAll(".dlg--sugg .sg__restore")
                                                buttons.item(index) as? HTMLElement
                                                    ?: buttons.item(index - 1) as? HTMLElement
                                                    ?: document.querySelector(".dlg--sugg .sg__done") as? HTMLElement
                                            }
                                        }
                                    }
                                }) { Text("Restore") }
                            }
                        }
                    }
                }
            }
            Div({ classes("dlg__acts") }) {
                Button({
                    type(ButtonType.Button)
                    classes("btn", "btn--primary", "sg__done")
                    onClick { onClose() }
                }) { Text("Done") }
            }
        }
    }
}

/**
 * Moves focus to [target] once [gone] has left the page. Compose removes a row on its next frame; focusing earlier
 * would land on the row that is about to go, and focus would fall back to the page.
 */
private suspend fun focusWhenGone(
    gone: String,
    target: () -> HTMLElement?,
) {
    var frames = 0
    while (document.querySelector(gone) != null && frames++ < FOCUS_WAIT_FRAMES) delay(16)
    target()?.focus()
}

private const val FOCUS_WAIT_FRAMES = 30
