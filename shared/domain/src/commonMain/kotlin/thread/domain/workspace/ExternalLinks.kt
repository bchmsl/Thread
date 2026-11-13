package thread.domain.workspace

import thread.domain.external.JiraCloudId
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraSite
import thread.domain.external.PullRequestRef
import thread.domain.note.LinkHref
import thread.domain.topic.Topic
import thread.domain.topic.TopicId

/*
 * Topic links to Jira tickets and pull requests (adr/ADR-013-jira-in-the-workspace.md, 04-data-and-sync.md
 * "ExternalReference" and "TopicExternalLink"). The workspace stores which record a Topic links to, never what the
 * record says (D08, owner-review-queue Q6). Linking never changes the record, and unlinking never deletes anything.
 */

/** The providers whose records a Topic can link to. */
enum class ExternalProvider(
    val id: String,
) {
    Jira("jira"),
    GitHub("github"),
    ;

    companion object {
        fun fromId(id: String?): ExternalProvider? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One external record that Topics link to. It holds only ids and the canonical URL, no title or other content. Its
 * [id] is made from the record's identity, so two devices that link the same record write the same document, and one
 * record linked to several Topics is stored once.
 */
data class ExternalReference(
    val provider: ExternalProvider,
    /** The Jira Cloud ID, or the GitHub `owner/repo`. */
    val instance: String,
    /** The Jira issue key, or the pull request number. */
    val key: String,
    val url: LinkHref,
) {
    val id: String get() = externalReferenceId(provider, instance, key)
}

/** `jira~{cloudId}~{KEY}` or `github~{owner}~{repo}~{number}`. Never holds `/`, which document ids cannot. */
fun externalReferenceId(
    provider: ExternalProvider,
    instance: String,
    key: String,
): String = "${provider.id}~${instance.replace('/', '~')}~$key"

/** The reference of the Jira issue [key] on the site with [cloudId]. Null only when the URL cannot be made. */
fun jiraReference(
    cloudId: JiraCloudId,
    site: JiraSite,
    key: JiraIssueKey,
): ExternalReference? =
    LinkHref.parse("https://${site.host}/browse/${key.value}")?.let { url ->
        ExternalReference(ExternalProvider.Jira, cloudId.value, key.value, url)
    }

/** The reference of pull request [ref]. Its URL is the pull request page on github.com. */
fun gitHubReference(ref: PullRequestRef): ExternalReference? =
    LinkHref.parse("https://github.com/${ref.owner}/${ref.repo}/pull/${ref.number}")?.let { url ->
        ExternalReference(ExternalProvider.GitHub, "${ref.owner}/${ref.repo}", ref.number.toString(), url)
    }

/** The pull request a GitHub reference points to, or null for a Jira reference or one that does not parse. */
fun pullRequestRefOf(reference: ExternalReference): PullRequestRef? =
    if (reference.provider == ExternalProvider.GitHub) PullRequestRef.parse("${reference.instance}#${reference.key}") else null

/** One Topic's link to one external record. Unlinking writes [linked] false; the document stays. */
data class TopicExternalLink(
    val topicId: TopicId,
    val referenceId: String,
    val linked: Boolean = true,
) {
    /** Topic ids never contain `_`, so the pair is unique. */
    val id: String get() = "${topicId.value}_$referenceId"
}

/** The Topics [referenceId] is linked to, in link order. Links to Topics that are gone are left out by the caller. */
fun linkedTopicIdsOf(
    referenceId: String,
    links: List<TopicExternalLink>,
): List<TopicId> = links.filter { it.linked && it.referenceId == referenceId }.map { it.topicId }.distinct()

/** The records of [provider] linked to [topicId], in link order. */
fun referencesOf(
    topicId: TopicId,
    provider: ExternalProvider,
    data: WorkspaceData,
): List<ExternalReference> {
    val byId = data.externalRefs.associateBy { it.id }
    return data.externalLinks
        .filter { it.linked && it.topicId == topicId }
        .mapNotNull { byId[it.referenceId] }
        .filter { it.provider == provider }
        .distinctBy { it.id }
}

/**
 * Makes [topicIds] the Topics of [reference] (v3 "Link to Topic", many to many). The reference is written once, the
 * first time any Topic links it. Topics no longer picked are unlinked. Undo puts every link back as it was.
 */
fun setExternalLinks(
    reference: ExternalReference,
    topicIds: List<TopicId>,
    data: WorkspaceData,
): EditResult {
    val current = linkedTopicIdsOf(reference.id, data.externalLinks)
    val wanted = topicIds.distinct()
    val added = wanted.filter { it !in current }
    val removed = current.filter { it !in wanted }
    if (added.isEmpty() && removed.isEmpty()) return EditResult.Unchanged
    val writes = mutableListOf<WorkspaceWrite>()
    val undo = mutableListOf<WorkspaceWrite>()
    if (added.isNotEmpty() && data.externalRefs.none { it == reference }) writes += WorkspaceWrite.PutExternalRef(reference)
    added.forEach {
        writes += WorkspaceWrite.PutExternalLink(TopicExternalLink(it, reference.id, true))
        undo += WorkspaceWrite.PutExternalLink(TopicExternalLink(it, reference.id, false))
    }
    removed.forEach {
        writes += WorkspaceWrite.PutExternalLink(TopicExternalLink(it, reference.id, false))
        undo += WorkspaceWrite.PutExternalLink(TopicExternalLink(it, reference.id, true))
    }
    return EditResult.Changed(WorkspaceEdit(writes, undo))
}

/** Links [reference] to one more Topic (Topic Details, "Link"). */
fun linkExternal(
    reference: ExternalReference,
    topicId: TopicId,
    data: WorkspaceData,
): EditResult = setExternalLinks(reference, linkedTopicIdsOf(reference.id, data.externalLinks) + topicId, data)

/** Unlinks [reference] from [topicId]. The record and its other links are not touched. */
fun unlinkExternal(
    reference: ExternalReference,
    topicId: TopicId,
    data: WorkspaceData,
): EditResult = setExternalLinks(reference, linkedTopicIdsOf(reference.id, data.externalLinks) - topicId, data)

/**
 * The writes of v3 "Create Topic from ticket": [topic] as a new Topic, linked to [reference]. The caller makes the
 * Topic (named after the ticket, "Created from KEY." where it left off).
 */
fun createTopicLinkedTo(
    topic: Topic,
    reference: ExternalReference,
    data: WorkspaceData,
): List<WorkspaceWrite> =
    buildList {
        add(WorkspaceWrite.PutTopic(topic, isNew = true))
        if (data.externalRefs.none { it == reference }) add(WorkspaceWrite.PutExternalRef(reference))
        add(WorkspaceWrite.PutExternalLink(TopicExternalLink(topic.id, reference.id, true)))
    }
