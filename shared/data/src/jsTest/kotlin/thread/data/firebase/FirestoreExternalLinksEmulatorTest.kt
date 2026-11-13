package thread.data.firebase

import kotlinx.coroutines.await
import kotlinx.coroutines.flow.first
import thread.domain.external.JiraCloudId
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraSite
import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.TopicId
import thread.domain.topic.newTopic
import thread.domain.trash.trashTopic
import thread.domain.trash.visibleWorkspace
import thread.domain.workspace.EditResult
import thread.domain.workspace.ExternalProvider
import thread.domain.workspace.WorkspaceWrite
import thread.domain.workspace.jiraReference
import thread.domain.workspace.linkedTopicIdsOf
import thread.domain.workspace.referencesOf
import thread.domain.workspace.seedWrites
import thread.domain.workspace.setExternalLinks
import thread.domain.workspace.unlinkExternal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Topic links to Jira tickets through [FirestoreWorkspaceStore] against the emulators
 * (adr/ADR-013-jira-in-the-workspace.md). All accounts, sites and tickets are synthetic.
 */
class FirestoreExternalLinksEmulatorTest {
    private val alpha = newTopic(TopicId("t-synthetic-a"), "Synthetic Topic A", SEED_STATUSES, 0)!!
    private val beta = newTopic(TopicId("t-synthetic-b"), "Synthetic Topic B", SEED_STATUSES, 1)!!
    private val ticket =
        jiraReference(
            JiraCloudId.parse("00000000-0000-4000-8000-000000000001")!!,
            JiraSite.parse("synthetic.atlassian.net")!!,
            JiraIssueKey.parse("SYN-101")!!,
        )!!

    @Test
    fun oneTicketInTwoTopicsIsOneRecordAndUnlinkingOfflineKeepsIt() =
        emulatorTest {
            val subject = syntheticSubject()
            val deviceA = EmulatedDevice.signIn(subject)
            val deviceB = EmulatedDevice.signIn(subject)
            val storeA = FirestoreWorkspaceStore(deviceA.backend, deviceA.accountId)
            val storeB = FirestoreWorkspaceStore(deviceB.backend, deviceB.accountId)
            try {
                storeA.write(seedWrites() + WorkspaceWrite.PutTopic(alpha, isNew = true) + WorkspaceWrite.PutTopic(beta, isNew = true))
                val seeded = storeA.observe().first { !it.pendingWrites && it.topics.size == 2 }
                storeA.write(assertIs<EditResult.Changed>(setExternalLinks(ticket, listOf(alpha.id, beta.id), seeded)).edit.writes)
                storeA.observe().first { !it.pendingWrites && it.externalLinks.size == 2 }

                val onB = storeB.observe().first { !it.pendingWrites && it.externalLinks.size == 2 }
                assertEquals(listOf(ticket), onB.externalRefs, "one record for two Topics")
                assertEquals(listOf(ticket), referencesOf(beta.id, ExternalProvider.Jira, onB))

                // The stored record holds ids and the URL only.
                val stored =
                    getDocsFromServer(
                        collection(deviceB.backend.firestore, "users", deviceB.accountId.value, "externalRefs"),
                    ).await()
                val keys = js("Object").keys(stored.docs.single().data(jsObject())).unsafeCast<Array<String>>().sorted()
                assertEquals(listOf("instance", "key", "provider", "schemaVersion", "updatedAt", "url"), keys)

                // B unlinks Topic A while offline; the record and the link to Topic B stay.
                deviceB.backend.goOfflineForTesting()
                storeB.write(assertIs<EditResult.Changed>(unlinkExternal(ticket, alpha.id, onB)).edit.writes)
                deviceB.backend.goOnlineForTesting()
                storeB.observe().first { !it.pendingWrites && linkedTopicIdsOf(ticket.id, it.externalLinks).size == 1 }
                val onA = storeA.observe().first { !it.pendingWrites && linkedTopicIdsOf(ticket.id, it.externalLinks) == listOf(beta.id) }
                assertEquals(listOf(ticket), onA.externalRefs)
                assertEquals(2, onA.externalLinks.size, "unlinking writes linked: false and deletes nothing")

                // Topic B in Trash hides its link; the link document is not touched.
                storeA.write(assertIs<EditResult.Changed>(trashTopic(onA.topics.single { it.id == beta.id })).edit.writes)
                val trashed = storeA.observe().first { !it.pendingWrites && it.topics.single { t -> t.id == beta.id }.deleted }
                assertTrue(linkedTopicIdsOf(ticket.id, visibleWorkspace(trashed).externalLinks).isEmpty())
                assertEquals(listOf(beta.id), linkedTopicIdsOf(ticket.id, trashed.externalLinks))
                assertNotNull(trashed.externalRefs.singleOrNull())
                assertTrue(storeA.refused.value.isEmpty() && storeB.refused.value.isEmpty())
            } finally {
                deviceA.backend.close()
                deviceB.backend.close()
            }
        }
}
