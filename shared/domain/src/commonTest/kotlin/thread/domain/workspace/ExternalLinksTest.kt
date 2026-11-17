package thread.domain.workspace

import thread.domain.external.JiraCloudId
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraSite
import thread.domain.external.PullRequestRef
import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import thread.domain.trash.TrashEntry
import thread.domain.trash.trashEntries
import thread.domain.trash.visibleWorkspace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Every Topic, site and ticket here is SYNTHETIC test data.
class ExternalLinksTest {
    private val status = SEED_STATUSES[0].id
    private val alpha = Topic(TopicId("t-alpha"), "Synthetic Alpha", TopicColor.Green, status)
    private val beta = Topic(TopicId("t-beta"), "Synthetic Beta", TopicColor.Blue, status)
    private val cloudId = assertNotNull(JiraCloudId.parse("00000000-0000-4000-8000-000000000001"))
    private val site = assertNotNull(JiraSite.parse("synthetic.atlassian.net"))
    private val ticket = assertNotNull(jiraReference(cloudId, site, assertNotNull(JiraIssueKey.parse("SYN-101"))))

    private fun changed(result: EditResult): WorkspaceEdit = assertIs<EditResult.Changed>(result).edit

    /**
     * Applies [writes] the way the store would: one document per id, the last write wins. Link writes merge, so a write
     * without `dismissed` keeps the stored value.
     */
    private fun WorkspaceData.apply(writes: List<WorkspaceWrite>): WorkspaceData =
        writes.fold(this) { data, write ->
            when (write) {
                is WorkspaceWrite.PutExternalRef ->
                    data.copy(externalRefs = data.externalRefs.filterNot { it.id == write.reference.id } + write.reference)
                is WorkspaceWrite.PutExternalLink -> {
                    val stored = data.externalLinks.firstOrNull { it.id == write.link.id }
                    val merged = write.link.copy(dismissed = write.link.dismissed ?: stored?.dismissed)
                    data.copy(externalLinks = data.externalLinks.filterNot { it.id == write.link.id } + merged)
                }
                is WorkspaceWrite.PutTopic -> data.copy(topics = data.topics.filterNot { it.id == write.topic.id } + write.topic)
                else -> error("Unexpected write $write")
            }
        }

    private val empty = WorkspaceData(statuses = SEED_STATUSES, topics = listOf(alpha, beta))

    @Test
    fun aReferenceHoldsOnlyIdsAndTheCanonicalUrl() {
        assertEquals("jira~00000000-0000-4000-8000-000000000001~SYN-101", ticket.id)
        assertEquals("https://synthetic.atlassian.net/browse/SYN-101", ticket.url.value)
        assertEquals("github~synthetic~app~42", externalReferenceId(ExternalProvider.GitHub, "synthetic/app", "42"))
        assertEquals(TopicExternalLink(alpha.id, ticket.id).id, "t-alpha_${ticket.id}")
    }

    @Test
    fun oneTicketLinkedToTwoTopicsIsStoredOnce() {
        val edit = changed(setExternalLinks(ticket, listOf(alpha.id, beta.id), empty))
        assertEquals(1, edit.writes.count { it is WorkspaceWrite.PutExternalRef }, "the reference is written once")
        val after = empty.apply(edit.writes)
        assertEquals(listOf(ticket), after.externalRefs)
        assertEquals(listOf(alpha.id, beta.id), linkedTopicIdsOf(ticket.id, after.externalLinks))
        assertEquals(listOf(ticket), referencesOf(alpha.id, ExternalProvider.Jira, after))
        assertEquals(emptyList(), referencesOf(alpha.id, ExternalProvider.GitHub, after))

        // Linking it from a third place again writes no second reference.
        val again = changed(linkExternal(ticket, alpha.id, after.copy(externalLinks = emptyList())))
        assertTrue(again.writes.none { it is WorkspaceWrite.PutExternalRef })
        assertEquals(EditResult.Unchanged, linkExternal(ticket, alpha.id, after))
    }

    @Test
    fun unlinkingKeepsTheReferenceAndTheOtherLinkAndUndoPutsItBack() {
        val linked = empty.apply(changed(setExternalLinks(ticket, listOf(alpha.id, beta.id), empty)).writes)
        val unlink = changed(unlinkExternal(ticket, alpha.id, linked))
        assertEquals(
            listOf<WorkspaceWrite>(
                WorkspaceWrite.PutExternalLink(TopicExternalLink(alpha.id, ticket.id, linked = false, dismissed = true)),
            ),
            unlink.writes,
            "unlinking writes linked: false and dismissed: true, and nothing else",
        )
        val after = linked.apply(unlink.writes)
        assertEquals(listOf(ticket), after.externalRefs, "the reference stays")
        assertEquals(listOf(beta.id), linkedTopicIdsOf(ticket.id, after.externalLinks))
        assertEquals(listOf(alpha.id, beta.id), linkedTopicIdsOf(ticket.id, after.apply(unlink.undo).externalLinks).sortedBy { it.value })
        assertEquals(EditResult.Unchanged, unlinkExternal(ticket, alpha.id, after))
    }

    @Test
    fun pickingTopicsLinksTheNewOnesAndUnlinksTheOthers() {
        val linked = empty.apply(changed(linkExternal(ticket, alpha.id, empty)).writes)
        val edit = changed(setExternalLinks(ticket, listOf(beta.id), linked))
        val after = linked.apply(edit.writes)
        assertEquals(listOf(beta.id), linkedTopicIdsOf(ticket.id, after.externalLinks))
        assertEquals(listOf(alpha.id), linkedTopicIdsOf(ticket.id, after.apply(edit.undo).externalLinks))
        assertEquals(EditResult.Unchanged, setExternalLinks(ticket, listOf(beta.id), after))
    }

    @Test
    fun createTopicFromATicketWritesTheTopicTheReferenceAndTheLink() {
        val topic = Topic(TopicId("t-new"), "Synthetic ticket title", TopicColor.Orange, status, info = "Created from SYN-101.")
        val writes = createTopicLinkedTo(topic, ticket, empty)
        assertEquals(
            listOf(
                WorkspaceWrite.PutTopic(topic, isNew = true),
                WorkspaceWrite.PutExternalRef(ticket),
                WorkspaceWrite.PutExternalLink(TopicExternalLink(topic.id, ticket.id)),
            ),
            writes,
        )
        val second = createTopicLinkedTo(topic.copy(id = TopicId("t-two")), ticket, empty.apply(writes))
        assertTrue(second.none { it is WorkspaceWrite.PutExternalRef }, "a ticket already stored is not written again")
    }

    @Test
    fun aTopicInTrashHidesItsTicketLinksAndTrashListsThem() {
        val linked = empty.apply(changed(setExternalLinks(ticket, listOf(alpha.id, beta.id), empty)).writes)
        val trashed = linked.copy(topics = listOf(alpha.copy(deleted = true, deletedAtMillis = 10), beta))
        val visible = visibleWorkspace(trashed)
        assertEquals(listOf(beta.id), linkedTopicIdsOf(ticket.id, visible.externalLinks))
        assertEquals(listOf(ticket), visible.externalRefs, "the reference stays for the other Topic")
        val entry = trashEntries(trashed).single()
        assertEquals(listOf(ticket), assertIs<TrashEntry.TopicEntry>(entry).externals)
        assertEquals(listOf(alpha.id, beta.id), linkedTopicIdsOf(ticket.id, trashed.externalLinks), "restore is the Topic field only")
    }

    @Test
    fun aPullRequestReferenceIsOwnerRepoAndNumberAndReadsBack() {
        val ref = assertNotNull(PullRequestRef.parse("synthetic-org/synthetic-app#12"))
        val pull = assertNotNull(gitHubReference(ref))

        assertEquals("github~synthetic-org~synthetic-app~12", pull.id)
        assertEquals("synthetic-org/synthetic-app", pull.instance)
        assertEquals("12", pull.key)
        assertEquals("https://github.com/synthetic-org/synthetic-app/pull/12", pull.url.value)
        assertEquals(ref, pullRequestRefOf(pull))
        assertNull(pullRequestRefOf(ticket), "a Jira reference is not a pull request")
    }

    @Test
    fun onePullRequestLinkedToTwoTopicsIsStoredOnceAndUnlinkKeepsIt() {
        val pull = assertNotNull(gitHubReference(assertNotNull(PullRequestRef.parse("synthetic-org/synthetic-app#12"))))
        val linked = empty.apply(changed(setExternalLinks(pull, listOf(alpha.id, beta.id), empty)).writes)
        assertEquals(listOf(pull), linked.externalRefs)
        assertEquals(listOf(pull), referencesOf(beta.id, ExternalProvider.GitHub, linked))
        assertEquals(emptyList(), referencesOf(beta.id, ExternalProvider.Jira, linked))

        val unlinked = linked.apply(changed(unlinkExternal(pull, alpha.id, linked)).writes)
        assertEquals(listOf(pull), unlinked.externalRefs, "unlinking never deletes the reference")
        assertEquals(listOf(beta.id), linkedTopicIdsOf(pull.id, unlinked.externalLinks))
        assertTrue(unlinked.externalLinks.any { it.topicId == alpha.id && !it.linked })
    }

    @Test
    fun dismissingWritesTheReferenceAndADismissedLinkAndUndoRestoresIt() {
        val edit = changed(dismissSuggestion(ticket, alpha.id, empty))
        assertEquals(
            listOf(
                WorkspaceWrite.PutExternalRef(ticket),
                WorkspaceWrite.PutExternalLink(TopicExternalLink(alpha.id, ticket.id, linked = false, dismissed = true)),
            ),
            edit.writes,
        )
        val after = empty.apply(edit.writes)
        assertEquals(listOf(ticket), dismissedReferencesOf(alpha.id, ExternalProvider.Jira, after))
        assertEquals(emptyList(), dismissedReferencesOf(beta.id, ExternalProvider.Jira, after), "only in that Topic")
        assertEquals(emptyList(), dismissedReferencesOf(alpha.id, ExternalProvider.GitHub, after))
        assertEquals(emptyList(), referencesOf(alpha.id, ExternalProvider.Jira, after), "a dismissal is not a link")
        assertEquals(EditResult.Unchanged, dismissSuggestion(ticket, alpha.id, after), "already dismissed")

        val undone = after.apply(edit.undo)
        assertEquals(emptyList(), dismissedReferencesOf(alpha.id, ExternalProvider.Jira, undone))
        assertEquals(listOf(ticket), undone.externalRefs, "the reference stays, as after Link and Undo")
    }

    @Test
    fun aLinkedRecordCannotBeDismissedAndLinkedWins() {
        val linked = empty.apply(changed(linkExternal(ticket, alpha.id, empty)).writes)
        assertEquals(EditResult.Unchanged, dismissSuggestion(ticket, alpha.id, linked))

        // Another device linked it while this one had it dismissed: both flags are true, and it counts as linked.
        val both = linked.copy(externalLinks = listOf(TopicExternalLink(alpha.id, ticket.id, linked = true, dismissed = true)))
        assertEquals(emptyList(), dismissedReferencesOf(alpha.id, ExternalProvider.Jira, both))
        assertEquals(listOf(ticket), referencesOf(alpha.id, ExternalProvider.Jira, both))
        assertFalse(both.externalLinks.single().isDismissed)
    }

    @Test
    fun restoringSuggestsItAgainAndUndoDismissesItAgain() {
        val dismissed = empty.apply(changed(dismissSuggestion(ticket, alpha.id, empty)).writes)
        val restore = changed(restoreSuggestion(ticket.id, alpha.id, dismissed))
        assertEquals(
            listOf<WorkspaceWrite>(
                WorkspaceWrite.PutExternalLink(TopicExternalLink(alpha.id, ticket.id, linked = false, dismissed = false)),
            ),
            restore.writes,
        )
        val after = dismissed.apply(restore.writes)
        assertEquals(emptyList(), dismissedReferencesOf(alpha.id, ExternalProvider.Jira, after))
        assertEquals(EditResult.Unchanged, restoreSuggestion(ticket.id, alpha.id, after), "nothing to restore")
        assertEquals(EditResult.Unchanged, restoreSuggestion(ticket.id, beta.id, after), "no document")
        assertEquals(listOf(ticket), dismissedReferencesOf(alpha.id, ExternalProvider.Jira, after.apply(restore.undo)))
    }

    @Test
    fun unlinkingDismissesInThatTopicOnlyAndUndoLinksAgain() {
        val linked = empty.apply(changed(setExternalLinks(ticket, listOf(alpha.id, beta.id), empty)).writes)
        val unlink = changed(unlinkExternal(ticket, alpha.id, linked))
        val after = linked.apply(unlink.writes)
        assertEquals(listOf(ticket), dismissedReferencesOf(alpha.id, ExternalProvider.Jira, after))
        assertEquals(emptyList(), dismissedReferencesOf(beta.id, ExternalProvider.Jira, after), "the other Topic keeps its link")
        assertEquals(listOf(ticket), referencesOf(beta.id, ExternalProvider.Jira, after))

        val undone = after.apply(unlink.undo)
        assertEquals(listOf(ticket), referencesOf(alpha.id, ExternalProvider.Jira, undone))
        assertEquals(emptyList(), dismissedReferencesOf(alpha.id, ExternalProvider.Jira, undone))
        assertEquals(
            TopicExternalLink(alpha.id, ticket.id, linked = true, dismissed = false),
            undone.externalLinks.first {
                it.topicId ==
                    alpha.id
            },
        )
    }

    @Test
    fun linkingADismissedRecordLinksItAndUndoDismissesItAgain() {
        val dismissed = empty.apply(changed(dismissSuggestion(ticket, alpha.id, empty)).writes)
        val link = changed(linkExternal(ticket, alpha.id, dismissed))
        assertEquals(
            listOf<WorkspaceWrite>(WorkspaceWrite.PutExternalLink(TopicExternalLink(alpha.id, ticket.id, linked = true))),
            link.writes,
            "a link write leaves dismissed as stored, so it has the shape older rules accept",
        )
        val after = dismissed.apply(link.writes)
        assertEquals(listOf(ticket), referencesOf(alpha.id, ExternalProvider.Jira, after))
        assertEquals(emptyList(), dismissedReferencesOf(alpha.id, ExternalProvider.Jira, after), "linked wins")
        assertEquals(listOf(ticket), dismissedReferencesOf(alpha.id, ExternalProvider.Jira, after.apply(link.undo)))
    }

    @Test
    fun pickingTopicsKeepsTheDismissalsOfOtherTopics() {
        val dismissedInBeta = empty.apply(changed(dismissSuggestion(ticket, beta.id, empty)).writes)
        val edit = changed(setExternalLinks(ticket, listOf(alpha.id), dismissedInBeta))
        assertTrue(edit.writes.none { it is WorkspaceWrite.PutExternalLink && it.link.topicId == beta.id }, "beta is not written")
        val after = dismissedInBeta.apply(edit.writes)
        assertEquals(listOf(ticket), dismissedReferencesOf(beta.id, ExternalProvider.Jira, after))
        assertEquals(listOf(alpha.id), linkedTopicIdsOf(ticket.id, after.externalLinks))
    }

    @Test
    fun aTopicInTrashHidesItsDismissalsAndRestoreBringsThemBack() {
        val dismissed = empty.apply(changed(dismissSuggestion(ticket, alpha.id, empty)).writes)
        val trashed = dismissed.copy(topics = listOf(alpha.copy(deleted = true, deletedAtMillis = 10), beta))
        assertEquals(emptyList(), dismissedReferencesOf(alpha.id, ExternalProvider.Jira, visibleWorkspace(trashed)))
        val restored = trashed.copy(topics = listOf(alpha, beta))
        assertEquals(listOf(ticket), dismissedReferencesOf(alpha.id, ExternalProvider.Jira, visibleWorkspace(restored)))
    }
}
