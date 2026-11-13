package thread.domain.workspace

import thread.domain.external.JiraCloudId
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraSite
import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import thread.domain.trash.TrashEntry
import thread.domain.trash.trashEntries
import thread.domain.trash.visibleWorkspace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Every Topic, site and ticket here is SYNTHETIC test data.
class ExternalLinksTest {
    private val status = SEED_STATUSES[0].id
    private val alpha = Topic(TopicId("t-alpha"), "Synthetic Alpha", TopicColor.Mint, status)
    private val beta = Topic(TopicId("t-beta"), "Synthetic Beta", TopicColor.Sky, status)
    private val cloudId = assertNotNull(JiraCloudId.parse("00000000-0000-4000-8000-000000000001"))
    private val site = assertNotNull(JiraSite.parse("synthetic.atlassian.net"))
    private val ticket = assertNotNull(jiraReference(cloudId, site, assertNotNull(JiraIssueKey.parse("SYN-101"))))

    private fun changed(result: EditResult): WorkspaceEdit = assertIs<EditResult.Changed>(result).edit

    /** Applies [writes] the way the store would: one document per id, the last write wins. */
    private fun WorkspaceData.apply(writes: List<WorkspaceWrite>): WorkspaceData =
        writes.fold(this) { data, write ->
            when (write) {
                is WorkspaceWrite.PutExternalRef ->
                    data.copy(externalRefs = data.externalRefs.filterNot { it.id == write.reference.id } + write.reference)
                is WorkspaceWrite.PutExternalLink ->
                    data.copy(externalLinks = data.externalLinks.filterNot { it.id == write.link.id } + write.link)
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
            listOf<WorkspaceWrite>(WorkspaceWrite.PutExternalLink(TopicExternalLink(alpha.id, ticket.id, linked = false))),
            unlink.writes,
            "unlinking writes linked: false and nothing else",
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
        val topic = Topic(TopicId("t-new"), "Synthetic ticket title", TopicColor.Peach, status, leftOff = "Created from SYN-101.")
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
}
