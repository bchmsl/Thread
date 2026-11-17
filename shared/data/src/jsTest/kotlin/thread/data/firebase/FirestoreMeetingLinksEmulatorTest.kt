package thread.data.firebase

import kotlinx.coroutines.await
import kotlinx.coroutines.flow.first
import thread.domain.calendar.SeriesKey
import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.TopicId
import thread.domain.topic.newTopic
import thread.domain.trash.trashTopic
import thread.domain.trash.visibleWorkspace
import thread.domain.workspace.EditResult
import thread.domain.workspace.WorkspaceWrite
import thread.domain.workspace.linkedTopicIdsOfSeries
import thread.domain.workspace.seedWrites
import thread.domain.workspace.setMeetingLinks
import thread.domain.workspace.unlinkMeeting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Topic links to meeting series through [FirestoreWorkspaceStore] against the emulators (D78 P5,
 * adr/ADR-020-calendar-meetings-sync.md). All accounts, Topics and series are synthetic.
 */
class FirestoreMeetingLinksEmulatorTest {
    private val alpha = newTopic(TopicId("t-synthetic-a"), "Synthetic Topic A", SEED_STATUSES, 0)!!
    private val beta = newTopic(TopicId("t-synthetic-b"), "Synthetic Topic B", SEED_STATUSES, 1)!!
    private val series = SeriesKey("0123456789abcdef0123456789abcdef")

    @Test
    fun aSeriesLinkedToTwoTopicsSyncsUnlinksOfflineKeepsItsTitleAndFollowsTheTopicIntoTrash() =
        emulatorTest {
            val subject = syntheticSubject()
            val deviceA = EmulatedDevice.signIn(subject)
            val deviceB = EmulatedDevice.signIn(subject)
            val storeA = FirestoreWorkspaceStore(deviceA.backend, deviceA.accountId)
            val storeB = FirestoreWorkspaceStore(deviceB.backend, deviceB.accountId)

            fun changed(result: EditResult) = assertIs<EditResult.Changed>(result).edit
            try {
                storeA.write(seedWrites() + WorkspaceWrite.PutTopic(alpha, isNew = true) + WorkspaceWrite.PutTopic(beta, isNew = true))
                val seeded = storeA.observe().first { !it.pendingWrites && it.topics.size == 2 }
                storeA.write(changed(setMeetingLinks(series, "Synthetic standup", listOf(alpha.id, beta.id), seeded)).writes)
                storeA.observe().first { !it.pendingWrites && it.meetingLinks.size == 2 }

                val onB = storeB.observe().first { !it.pendingWrites && it.meetingLinks.size == 2 }
                assertEquals(listOf(alpha.id, beta.id).toSet(), linkedTopicIdsOfSeries(series, onB.meetingLinks).toSet())
                assertTrue(onB.meetingLinks.all { it.title == "Synthetic standup" }, "the title snapshot syncs")
                assertEquals(0, onB.unreadable)

                // The stored link holds the pair, the flag and the title snapshot only: no time, place or join link.
                val stored =
                    getDocsFromServer(
                        collection(deviceB.backend.firestore, "users", deviceB.accountId.value, "topicMeetingLinks"),
                    ).await()
                val keys = js("Object").keys(stored.docs.first().data(jsObject())).unsafeCast<Array<String>>().sorted()
                assertEquals(listOf("linked", "schemaVersion", "seriesKey", "title", "topicId", "updatedAt"), keys)

                // B unlinks Topic A while offline; the document stays, with its title, and Topic B keeps its link.
                deviceB.backend.goOfflineForTesting()
                val unlink = changed(unlinkMeeting(series, alpha.id, onB))
                storeB.write(unlink.writes)
                deviceB.backend.goOnlineForTesting()
                storeB.observe().first { !it.pendingWrites && linkedTopicIdsOfSeries(series, it.meetingLinks).size == 1 }
                val onA = storeA.observe().first { !it.pendingWrites && linkedTopicIdsOfSeries(series, it.meetingLinks) == listOf(beta.id) }
                assertEquals(2, onA.meetingLinks.size, "unlinking writes linked: false and deletes nothing")
                assertEquals("Synthetic standup", onA.meetingLinks.single { it.topicId == alpha.id }.title)

                // Undo on B links it again.
                storeB.write(unlink.undo)
                val relinked = storeA.observe().first { !it.pendingWrites && linkedTopicIdsOfSeries(series, it.meetingLinks).size == 2 }

                // Topic B in Trash hides its link; the link document is not touched.
                storeA.write(changed(trashTopic(relinked.topics.single { it.id == beta.id })).writes)
                val trashed = storeA.observe().first { !it.pendingWrites && it.topics.single { t -> t.id == beta.id }.deleted }
                assertEquals(listOf(alpha.id), linkedTopicIdsOfSeries(series, visibleWorkspace(trashed).meetingLinks))
                assertEquals(2, linkedTopicIdsOfSeries(series, trashed.meetingLinks).size)
                assertTrue(storeA.refused.value.isEmpty() && storeB.refused.value.isEmpty(), "the rules accepted every write")
            } finally {
                deviceA.backend.close()
                deviceB.backend.close()
            }
        }
}
