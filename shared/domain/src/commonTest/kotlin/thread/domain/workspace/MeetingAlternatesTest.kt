package thread.domain.workspace

import thread.domain.calendar.MeetingAlternate
import thread.domain.calendar.SeriesKey
import thread.domain.topic.SEED_STATUSES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// Every series, title and link here is SYNTHETIC test data (D100).
class MeetingAlternatesTest {
    private val standup = SeriesKey("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val review = SeriesKey("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val own = "https://meet.synthetic.example/room-1"
    private val empty = WorkspaceData(statuses = SEED_STATUSES)

    private fun changed(result: EditResult): WorkspaceEdit = assertIs<EditResult.Changed>(result).edit

    private fun WorkspaceData.with(vararg alternates: MeetingAlternate) = copy(meetingAlternates = alternates.toList())

    @Test
    fun aFirstTitleIsTrimmedAndUndoClearsIt() {
        val edit = changed(setAlternateTitle(standup, "  SYNTHETIC Team sync ", empty))
        val set = MeetingAlternate(standup, title = "SYNTHETIC Team sync")
        assertEquals(listOf(WorkspaceWrite.PutMeetingAlternate(set, before = null)), edit.writes)
        assertEquals(listOf(WorkspaceWrite.PutMeetingAlternate(MeetingAlternate(standup), before = set)), edit.undo)
    }

    @Test
    fun changingOneFieldKeepsTheOtherAndUndoPutsItBack() {
        val stored = MeetingAlternate(standup, title = "SYNTHETIC Team sync", joinUrl = own)
        val edit = changed(setAlternateJoinUrl(standup, " https://meet.synthetic.example/room-2 ", empty.with(stored)))
        val after = stored.copy(joinUrl = "https://meet.synthetic.example/room-2")
        assertEquals(listOf(WorkspaceWrite.PutMeetingAlternate(after, before = stored)), edit.writes)
        assertEquals(listOf(WorkspaceWrite.PutMeetingAlternate(stored, before = after)), edit.undo)
    }

    @Test
    fun blankTextClearsAField() {
        val stored = MeetingAlternate(standup, title = "SYNTHETIC Team sync", joinUrl = own)
        val title = changed(setAlternateTitle(standup, "   ", empty.with(stored)))
        assertEquals(listOf(WorkspaceWrite.PutMeetingAlternate(stored.copy(title = null), before = stored)), title.writes)
        val link = changed(setAlternateJoinUrl(standup, "", empty.with(stored)))
        assertEquals(listOf(WorkspaceWrite.PutMeetingAlternate(stored.copy(joinUrl = null), before = stored)), link.writes)
    }

    @Test
    fun theSameValueOrClearingNothingChangesNothing() {
        val stored = MeetingAlternate(standup, title = "SYNTHETIC Team sync")
        assertEquals(EditResult.Unchanged, setAlternateTitle(standup, " SYNTHETIC Team sync", empty.with(stored)))
        assertEquals(EditResult.Unchanged, setAlternateJoinUrl(standup, "", empty.with(stored)))
        assertEquals(EditResult.Unchanged, setAlternateTitle(standup, "", empty))
        assertEquals(EditResult.Unchanged, setAlternateJoinUrl(review, " ", empty.with(stored)))
    }

    @Test
    fun aBadLinkOrALongTitleIsRefused() {
        listOf("http://synthetic.example", "synthetic.example/room", "https://a@synthetic.example", "javascript:alert(1)").forEach {
            assertEquals(EditResult.Refused(EditProblem.BadLink), setAlternateJoinUrl(standup, it, empty), it)
        }
        assertEquals(EditResult.Refused(EditProblem.TextTooLong), setAlternateTitle(standup, "x".repeat(301), empty))
    }

    @Test
    fun onlyAlternatesThatSetSomethingCountPerSeries() {
        val set = MeetingAlternate(standup, title = "SYNTHETIC Team sync")
        val cleared = MeetingAlternate(review)
        assertEquals(mapOf(standup to set), alternatesBySeries(listOf(set, cleared)))
        assertEquals(cleared, alternateOfSeries(review, listOf(set, cleared)))
    }
}
