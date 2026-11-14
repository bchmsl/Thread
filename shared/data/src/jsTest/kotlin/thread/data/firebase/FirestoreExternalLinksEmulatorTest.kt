package thread.data.firebase

import kotlinx.coroutines.await
import kotlinx.coroutines.flow.first
import thread.domain.external.JiraCloudId
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraSite
import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.TopicId
import thread.domain.topic.newTopic
import thread.domain.trash.restoreTopic
import thread.domain.trash.trashTopic
import thread.domain.trash.visibleWorkspace
import thread.domain.workspace.EditResult
import thread.domain.workspace.ExternalProvider
import thread.domain.workspace.WorkspaceWrite
import thread.domain.workspace.dismissSuggestion
import thread.domain.workspace.dismissedReferencesOf
import thread.domain.workspace.jiraReference
import thread.domain.workspace.linkExternal
import thread.domain.workspace.linkedTopicIdsOf
import thread.domain.workspace.referencesOf
import thread.domain.workspace.restoreSuggestion
import thread.domain.workspace.seedWrites
import thread.domain.workspace.setExternalLinks
import thread.domain.workspace.unlinkExternal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    @Test
    fun aDismissalSyncsFollowsTheTopicIntoTrashAndBackAndLinkWins() =
        emulatorTest {
            val subject = syntheticSubject()
            val deviceA = EmulatedDevice.signIn(subject)
            val deviceB = EmulatedDevice.signIn(subject)
            val storeA = FirestoreWorkspaceStore(deviceA.backend, deviceA.accountId)
            val storeB = FirestoreWorkspaceStore(deviceB.backend, deviceB.accountId)

            fun changed(result: EditResult) = assertIs<EditResult.Changed>(result).edit.writes
            try {
                storeA.write(seedWrites() + WorkspaceWrite.PutTopic(alpha, isNew = true) + WorkspaceWrite.PutTopic(beta, isNew = true))
                val seeded = storeA.observe().first { !it.pendingWrites && it.topics.size == 2 }
                // Linked to B in the shape older builds write: no dismissed field.
                storeA.write(changed(linkExternal(ticket, beta.id, seeded)))
                val linked = storeA.observe().first { !it.pendingWrites && it.externalLinks.size == 1 }
                assertNull(linked.externalLinks.single().dismissed, "a document without the field")
                assertEquals(emptyList(), dismissedReferencesOf(beta.id, ExternalProvider.Jira, linked))

                // A dismisses it in Topic A; B sees it after a round trip through the server.
                storeA.write(changed(dismissSuggestion(ticket, alpha.id, linked)))
                storeA.observe().first { !it.pendingWrites && it.externalLinks.size == 2 }
                val onB =
                    storeB.observe().first {
                        !it.pendingWrites &&
                            dismissedReferencesOf(alpha.id, ExternalProvider.Jira, it).isNotEmpty()
                    }
                assertEquals(listOf(ticket), dismissedReferencesOf(alpha.id, ExternalProvider.Jira, onB))
                assertEquals(listOf(beta.id), linkedTopicIdsOf(ticket.id, onB.externalLinks), "Topic B keeps its link")
                val stored =
                    getDocsFromServer(
                        collection(deviceB.backend.firestore, "users", deviceB.accountId.value, "topicExternalLinks"),
                    ).await()
                val dismissedDoc = stored.docs.single { it.id.startsWith("${alpha.id.value}_") }
                val keys = js("Object").keys(dismissedDoc.data(jsObject())).unsafeCast<Array<String>>().sorted()
                assertEquals(listOf("dismissed", "linked", "refId", "schemaVersion", "topicId", "updatedAt"), keys)

                // Topic A in Trash hides the dismissal; Restore brings it back.
                storeA.write(changed(trashTopic(onB.topics.single { it.id == alpha.id })))
                val trashed = storeA.observe().first { !it.pendingWrites && it.topics.single { t -> t.id == alpha.id }.deleted }
                assertEquals(emptyList(), dismissedReferencesOf(alpha.id, ExternalProvider.Jira, visibleWorkspace(trashed)))
                storeA.write(changed(restoreTopic(trashed.topics.single { it.id == alpha.id }, trashed.statuses)))
                val restored = storeA.observe().first { !it.pendingWrites && !it.topics.single { t -> t.id == alpha.id }.deleted }
                assertEquals(listOf(ticket), dismissedReferencesOf(alpha.id, ExternalProvider.Jira, visibleWorkspace(restored)))

                // B links it to Topic A while offline: the merge keeps dismissed, and linked wins.
                val beforeLink = storeB.observe().first { !it.pendingWrites && !it.topics.single { t -> t.id == alpha.id }.deleted }
                deviceB.backend.goOfflineForTesting()
                storeB.write(changed(linkExternal(ticket, alpha.id, beforeLink)))
                deviceB.backend.goOnlineForTesting()
                val bothFlags = storeA.observe().first { !it.pendingWrites && alpha.id in linkedTopicIdsOf(ticket.id, it.externalLinks) }
                val link = bothFlags.externalLinks.single { it.topicId == alpha.id }
                assertEquals(true, link.dismissed, "a link write leaves dismissed as stored")
                assertEquals(emptyList(), dismissedReferencesOf(alpha.id, ExternalProvider.Jira, bothFlags))
                assertEquals(listOf(ticket), referencesOf(alpha.id, ExternalProvider.Jira, bothFlags))

                // Unlink dismisses again, and Restore suggests it again.
                storeA.write(changed(unlinkExternal(ticket, alpha.id, bothFlags)))
                val unlinked = storeA.observe().first { !it.pendingWrites && alpha.id !in linkedTopicIdsOf(ticket.id, it.externalLinks) }
                assertEquals(listOf(ticket), dismissedReferencesOf(alpha.id, ExternalProvider.Jira, unlinked))
                storeA.write(changed(restoreSuggestion(ticket.id, alpha.id, unlinked)))
                val back =
                    storeB.observe().first {
                        !it.pendingWrites &&
                            it.externalLinks.single { l -> l.topicId == alpha.id }.dismissed == false
                    }
                assertEquals(emptyList(), dismissedReferencesOf(alpha.id, ExternalProvider.Jira, back))
                assertTrue(storeA.refused.value.isEmpty() && storeB.refused.value.isEmpty(), "the rules accepted every write")
            } finally {
                deviceA.backend.close()
                deviceB.backend.close()
            }
        }
}
