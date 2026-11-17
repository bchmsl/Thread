package thread.domain.workspace

import thread.domain.calendar.Meeting
import thread.domain.calendar.MeetingId
import thread.domain.calendar.MeetingLink
import thread.domain.calendar.MeetingResponse
import thread.domain.calendar.MeetingTime
import thread.domain.calendar.SeriesKey
import thread.domain.calendar.meetingsForTopic
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import thread.domain.trash.visibleWorkspace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Every Topic, meeting and series here is SYNTHETIC test data.
class MeetingLinksTest {
    private val status = SEED_STATUSES[0].id
    private val alpha = Topic(TopicId("t-alpha"), "Synthetic Alpha", TopicColor.Green, status)
    private val beta = Topic(TopicId("t-beta"), "Synthetic Beta", TopicColor.Blue, status)
    private val standup = SeriesKey("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val review = SeriesKey("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val empty = WorkspaceData(statuses = SEED_STATUSES, topics = listOf(alpha, beta))

    private fun changed(result: EditResult): WorkspaceEdit = assertIs<EditResult.Changed>(result).edit

    /** Applies [writes] as the store does: one document per id, and a write without a title keeps the stored one. */
    private fun WorkspaceData.apply(writes: List<WorkspaceWrite>): WorkspaceData =
        writes.fold(this) { data, write ->
            when (write) {
                is WorkspaceWrite.PutMeetingLink -> {
                    val stored = data.meetingLinks.firstOrNull { it.id == write.link.id }
                    val merged = write.link.copy(title = write.link.title ?: stored?.title)
                    data.copy(meetingLinks = data.meetingLinks.filterNot { it.id == write.link.id } + merged)
                }
                is WorkspaceWrite.PutTopic -> data.copy(topics = data.topics.filterNot { it.id == write.topic.id } + write.topic)
                else -> error("Unexpected write $write")
            }
        }

    @Test
    fun aLinkIsOneDocumentPerTopicAndSeries() {
        assertEquals("t-alpha_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", MeetingLink(alpha.id, standup).id)
    }

    @Test
    fun linkingASeriesToTwoTopicsStoresTheTitleSnapshotInEach() {
        val edit = changed(setMeetingLinks(standup, "Synthetic standup", listOf(alpha.id, beta.id), empty))

        assertEquals(
            listOf(
                WorkspaceWrite.PutMeetingLink(MeetingLink(alpha.id, standup, linked = true, title = "Synthetic standup")),
                WorkspaceWrite.PutMeetingLink(MeetingLink(beta.id, standup, linked = true, title = "Synthetic standup")),
            ),
            edit.writes,
        )
        val after = empty.apply(edit.writes)
        assertEquals(listOf(alpha.id, beta.id), linkedTopicIdsOfSeries(standup, after.meetingLinks))
        assertEquals(EditResult.Unchanged, linkMeeting(standup, "Synthetic standup", alpha.id, after), "linking again changes nothing")
    }

    @Test
    fun aBlankTitleStoresNoSnapshot() {
        val edit = changed(linkMeeting(standup, "  ", alpha.id, empty))

        assertNull(assertIs<WorkspaceWrite.PutMeetingLink>(edit.writes.single()).link.title)
    }

    @Test
    fun unlinkingWritesLinkedFalseKeepsTheSnapshotAndTheOtherLinkAndUndoPutsItBack() {
        val linked = empty.apply(changed(setMeetingLinks(standup, "Synthetic standup", listOf(alpha.id, beta.id), empty)).writes)

        val unlink = changed(unlinkMeeting(standup, alpha.id, linked))
        assertEquals(listOf(WorkspaceWrite.PutMeetingLink(MeetingLink(alpha.id, standup, linked = false))), unlink.writes)
        val after = linked.apply(unlink.writes)
        assertEquals(listOf(beta.id), linkedTopicIdsOfSeries(standup, after.meetingLinks))
        assertEquals(2, after.meetingLinks.size, "nothing is deleted")
        assertEquals("Synthetic standup", after.meetingLinks.first { it.topicId == alpha.id }.title, "the snapshot stays")

        val undone = after.apply(unlink.undo)
        assertEquals(listOf(alpha.id, beta.id).toSet(), linkedTopicIdsOfSeries(standup, undone.meetingLinks).toSet())
        assertEquals(EditResult.Unchanged, unlinkMeeting(review, alpha.id, linked), "a series that is not linked")
    }

    @Test
    fun undoOfALinkUnlinksIt() {
        val edit = changed(linkMeeting(standup, "Synthetic standup", alpha.id, empty))
        val undone = empty.apply(edit.writes).apply(edit.undo)

        assertEquals(emptyList(), linkedTopicIdsOfSeries(standup, undone.meetingLinks))
    }

    @Test
    fun aTopicInTrashHidesItsMeetingLinksAndRestoringBringsThemBack() {
        val linked = empty.apply(changed(setMeetingLinks(standup, "Synthetic standup", listOf(alpha.id, beta.id), empty)).writes)
        val trashed = linked.apply(listOf(WorkspaceWrite.PutTopic(alpha.copy(deleted = true), before = alpha)))

        assertEquals(listOf(beta.id), linkedTopicIdsOfSeries(standup, visibleWorkspace(trashed).meetingLinks))
        assertEquals(2, trashed.meetingLinks.size, "the stored links are not touched")
        val restored = trashed.apply(listOf(WorkspaceWrite.PutTopic(alpha, before = alpha.copy(deleted = true))))
        assertEquals(listOf(alpha.id, beta.id), linkedTopicIdsOfSeries(standup, visibleWorkspace(restored).meetingLinks))
    }

    @Test
    fun theTopicStillNamesASeriesThatLeftTheWindow() {
        val linked = empty.apply(changed(linkMeeting(standup, "Synthetic standup", alpha.id, empty)).writes)
        val now = WallTime(LocalDay(2026, 11, 17), 9 * 60)
        val renamed =
            Meeting(
                MeetingId("cccccccccccccccccccccccccccccccc"),
                standup,
                "Synthetic standup (moved)",
                MeetingTime.Timed(WallTime(now.day, 10 * 60), WallTime(now.day, 10 * 60 + 15)),
                MeetingResponse.Accepted,
            )

        val inWindow = meetingsForTopic(alpha.id, linked.meetingLinks, listOf(renamed), now).single()
        assertEquals("Synthetic standup (moved)", inWindow.title, "a meeting in the window names the series")
        val gone = meetingsForTopic(alpha.id, linked.meetingLinks, emptyList(), now).single()
        assertEquals("Synthetic standup", gone.title, "the snapshot names it after it leaves")
        assertNull(gone.next)
    }

    @Test
    fun aRefusedMeetingLinkIsUnconfirmedUntilTheStoredLinkShowsIt() {
        val write = WorkspaceWrite.PutMeetingLink(MeetingLink(alpha.id, standup, linked = true, title = "Synthetic standup"))
        val leftovers = listOf(RefusedChange("c1", listOf(write)))

        assertTrue(unconfirmedChanges(leftovers, emptyList(), empty).single().unconfirmed, "no stored link")
        assertEquals(emptyList(), unconfirmedChanges(leftovers, emptyList(), empty.apply(listOf(write))))
        val unlinked = empty.apply(listOf(write.copy(link = write.link.copy(linked = false))))
        assertTrue(unconfirmedChanges(leftovers, emptyList(), unlinked).single().unconfirmed, "stored as unlinked")
    }
}
