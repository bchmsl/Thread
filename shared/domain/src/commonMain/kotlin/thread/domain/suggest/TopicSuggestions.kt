package thread.domain.suggest

import thread.domain.external.JiraIssue
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraSite
import thread.domain.external.PullRequestDetail
import thread.domain.external.PullRequestRef
import thread.domain.external.jiraKeysIn
import thread.domain.external.pullRequestRefsIn
import thread.domain.note.NoteSummary
import thread.domain.task.Task
import thread.domain.topic.Topic
import thread.domain.topic.topicInfo

/*
 * Topic suggestions (D49, adr/ADR-017-topic-suggestions.md): Jira tickets and pull requests related to a Topic, found
 * by fixed rules, never by AI, and never linked without a click. Each rule gives candidates with a reason. Ranking adds
 * the points of an item's reasons, leaves out what is linked or dismissed in the Topic and what Jira or GitHub did not
 * confirm, and keeps the best ones.
 */

/** Where in a Topic a text was written. */
enum class TopicTextField {
    Name,
    Goal,

    /** "Additional information" ([topicInfo]), which shows the old "Pick up here" text until it is edited (D52). */
    Info,
    Task,
    Note,
}

data class TopicText(
    val field: TopicTextField,
    val text: String,
)

/**
 * The texts of [topic] that suggestions read: its name, goal, Additional information, the titles of its Tasks and
 * the titles and previews of its Notes (owner-review-queue Q82: never full Note bodies). [tasks] and [notes] are
 * the Topic's; Tasks of other Topics and Tasks or Notes in Trash are left out. Later rules add their texts here.
 */
fun topicTextSources(
    topic: Topic,
    tasks: List<Task>,
    notes: List<NoteSummary>,
): List<TopicText> =
    buildList {
        add(TopicText(TopicTextField.Name, topic.name))
        add(TopicText(TopicTextField.Goal, topic.goal))
        add(TopicText(TopicTextField.Info, topicInfo(topic)))
        tasks
            .filter { it.topicId == topic.id && !it.deleted && !it.discarded }
            .forEach { add(TopicText(TopicTextField.Task, it.title)) }
        notes
            .filter { !it.inTrash && !it.discarded }
            .forEach { add(TopicText(TopicTextField.Note, "${it.title}\n${it.preview}")) }
    }.filter { it.text.isNotBlank() }

/** Why an item is suggested. Reasons of different kinds add their [points]. */
sealed interface SuggestionReason {
    val points: Int

    /** S1: written in the Topic's [field]. */
    data class Written(
        val field: TopicTextField,
    ) : SuggestionReason {
        override val points: Int get() = WRITTEN_POINTS
    }

    /** S3: the pull request [pull], linked to the Topic, mentions it in its title, description or branch. */
    data class MentionedByPullRequest(
        val pull: PullRequestRef,
    ) : SuggestionReason {
        override val points: Int get() = PULL_REQUEST_POINTS
    }

    /** S2: Jira links it to [linkedKey], which is linked to the Topic. [linkedKey] [relation] it, as Jira words it. */
    data class IssueLink(
        val linkedKey: JiraIssueKey,
        val relation: String,
    ) : SuggestionReason {
        override val points: Int get() = ISSUE_LINK_POINTS
    }
}

const val WRITTEN_POINTS = 100
const val PULL_REQUEST_POINTS = 80
const val ISSUE_LINK_POINTS = 60

/** Done tickets go below open work by this much (owner-review-queue Q80). */
const val DONE_PENALTY = 20

/** Suggestion rows a section shows before "Show N more" (Q78). */
const val SUGGESTIONS_SHOWN = 3

/** Suggestion rows a section shows at most (Q78). */
const val SUGGESTIONS_MAX = 10

/** An item one rule found, before ranking. [item] is a [JiraIssueKey] or a [PullRequestRef]. */
data class SuggestionCandidate<K>(
    val item: K,
    val reason: SuggestionReason,
)

/** What ranking needs from Jira or GitHub. An item without facts is not confirmed, so it is not suggested. */
data class SuggestionFacts(
    val done: Boolean,
    val updatedAtMillis: Long?,
)

/** One suggestion row: the item, its best two reasons, and its score. */
data class Suggestion<K>(
    val item: K,
    val reasons: List<SuggestionReason>,
    val score: Int,
)

/** S1 for Jira: the keys written in [texts], with the field each one is in. */
fun writtenJiraKeys(
    texts: List<TopicText>,
    site: JiraSite?,
): List<SuggestionCandidate<JiraIssueKey>> =
    texts.flatMap { text -> jiraKeysIn(text.text, site).map { SuggestionCandidate(it, SuggestionReason.Written(text.field)) } }

/** S1 for GitHub: the pull requests written in [texts], with the field each one is in. */
fun writtenPullRequests(texts: List<TopicText>): List<SuggestionCandidate<PullRequestRef>> =
    texts.flatMap { text -> pullRequestRefsIn(text.text).map { SuggestionCandidate(it, SuggestionReason.Written(text.field)) } }

/** S2: the issue links of the Topic's [linked] tickets, as Jira lists them. */
fun issueLinkCandidates(linked: List<JiraIssue>): List<SuggestionCandidate<JiraIssueKey>> =
    linked.flatMap { issue ->
        issue.related.map { SuggestionCandidate(it.key, SuggestionReason.IssueLink(issue.key, it.relation)) }
    }

/** S3: the Jira keys in the title, description and branch of the Topic's [linked] pull requests. */
fun pullRequestMentionCandidates(
    linked: List<PullRequestDetail>,
    site: JiraSite?,
): List<SuggestionCandidate<JiraIssueKey>> =
    linked.flatMap { pull ->
        jiraKeysIn("${pull.title}\n${pull.headBranch}\n${pull.body}", site)
            .map { SuggestionCandidate(it, SuggestionReason.MentionedByPullRequest(pull.ref)) }
    }

/**
 * The best [limit] suggestions among [candidates]. Items in [linked] or [dismissed] are left out, and so are items
 * [facts] does not know (Jira or GitHub did not confirm them, or the token cannot see them). Reasons of different kinds
 * add their points; the same kind counts once. Done items lose [DONE_PENALTY]. Ties: the most recently updated first,
 * then the order the rules found them in. Each suggestion keeps its two best reasons.
 */
fun <K> rankSuggestions(
    candidates: List<SuggestionCandidate<K>>,
    facts: (K) -> SuggestionFacts?,
    linked: Set<K>,
    dismissed: Set<K>,
    limit: Int = SUGGESTIONS_MAX,
): List<Suggestion<K>> {
    class Ranked(
        val suggestion: Suggestion<K>,
        val updatedAtMillis: Long?,
        val order: Int,
    )
    return candidates
        .groupBy { it.item }
        .entries
        .mapIndexedNotNull { order, (item, found) ->
            if (item in linked || item in dismissed) return@mapIndexedNotNull null
            val fact = facts(item) ?: return@mapIndexedNotNull null
            val reasons = found.map { it.reason }.distinct()
            val points = reasons.groupBy { it::class }.values.sumOf { kind -> kind.maxOf { it.points } }
            val score = points - if (fact.done) DONE_PENALTY else 0
            Ranked(Suggestion(item, reasons.sortedByDescending { it.points }.take(2), score), fact.updatedAtMillis, order)
        }.sortedWith(
            compareByDescending<Ranked> { it.suggestion.score }
                .thenByDescending { it.updatedAtMillis ?: Long.MIN_VALUE }
                .thenBy { it.order },
        ).take(limit)
        .map { it.suggestion }
}

/** The project part of [key]: `SYN` for `SYN-101`. */
fun projectOf(key: JiraIssueKey): String = key.value.substringBeforeLast('-')

/**
 * The unknown keys to ask Jira about, at most [limit]. Keys of projects already known on this device go first. Keys of
 * new projects follow, from at most [newProjects] projects, because text like `UTF-8` or `SHA-256` looks like a key.
 */
fun keysToCheck(
    unknown: List<JiraIssueKey>,
    knownProjects: Set<String>,
    limit: Int = 5,
    newProjects: Int = 3,
): List<JiraIssueKey> {
    val distinct = unknown.distinct()
    val known = distinct.filter { projectOf(it) in knownProjects }
    val others = distinct.filter { projectOf(it) !in knownProjects }
    val allowed =
        others
            .map(::projectOf)
            .distinct()
            .take(newProjects)
            .toSet()
    return (known + others.filter { projectOf(it) in allowed }).take(limit)
}
