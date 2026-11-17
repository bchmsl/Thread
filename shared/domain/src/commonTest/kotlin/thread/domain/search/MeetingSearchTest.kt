package thread.domain.search

import thread.domain.calendar.Meeting
import thread.domain.calendar.MeetingId
import thread.domain.calendar.MeetingResponse
import thread.domain.calendar.MeetingTime
import thread.domain.calendar.SeriesKey
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Every meeting, place and link here is SYNTHETIC test data.
class MeetingSearchTest {
    private val today = LocalDay(2026, 11, 17)
    private val now = WallTime(today, 10 * 60 + 5)

    private fun meeting(
        id: Char,
        series: Char,
        title: String,
        day: LocalDay = today,
        startMinute: Int = 9 * 60,
        location: String? = null,
        joinUrl: String? = null,
    ) = Meeting(
        MeetingId(id.toString().repeat(32)),
        SeriesKey(series.toString().repeat(32)),
        title,
        MeetingTime.Timed(WallTime(day, startMinute), WallTime(day, startMinute + 30)),
        MeetingResponse.Accepted,
        location,
        joinUrl,
    )

    @Test
    fun aMeetingIsFoundByTitleOrLocationIgnoringCase() {
        val review = meeting('1', 'a', "Synthetic design review")
        val sync = meeting('2', 'b', "Synthetic sync", location = "Room Orion, floor 3")
        val other = meeting('3', 'c', "Synthetic lunch")

        assertEquals(listOf(review), searchMeetings("DESIGN", listOf(review, sync, other), now))
        assertEquals(listOf(sync), searchMeetings("orion", listOf(review, sync, other), now))
        assertEquals(emptyList(), searchMeetings("budget", listOf(review, sync, other), now))
        assertNull(searchMeetings("  ", listOf(review, sync, other), now))
    }

    @Test
    fun theJoinLinkIsNeverMatched() {
        val call =
            meeting(
                '1',
                'a',
                "Synthetic call",
                joinUrl = "https://zoom.us/j/1234567890?pwd=SyntheticPasscode42",
            )

        assertEquals(emptyList(), searchMeetings("zoom.us", listOf(call), now))
        assertEquals(emptyList(), searchMeetings("pwd", listOf(call), now))
        assertEquals(emptyList(), searchMeetings("SyntheticPasscode42", listOf(call), now))
        assertEquals(emptyList(), searchMeetings("1234567890", listOf(call), now))
    }

    @Test
    fun aRepeatingMeetingShowsItsThreeNearestOccurrencesUpcomingFirst() {
        val yesterday = meeting('1', 'a', "Synthetic standup", day = today.plusDays(-1))
        val earlierToday = meeting('2', 'a', "Synthetic standup", startMinute = 8 * 60)
        val onNow = meeting('3', 'a', "Synthetic standup", startMinute = 10 * 60)
        val tomorrow = meeting('4', 'a', "Synthetic standup", day = today.plusDays(1))
        val inTwoDays = meeting('5', 'a', "Synthetic standup", day = today.plusDays(2))
        val inThreeDays = meeting('6', 'a', "Synthetic standup", day = today.plusDays(3))
        val all = listOf(inThreeDays, yesterday, tomorrow, earlierToday, inTwoDays, onNow)

        assertEquals(listOf(onNow, tomorrow, inTwoDays), searchMeetings("standup", all, now), "one under way is nearest")
        assertEquals(
            listOf(onNow, earlierToday, yesterday),
            searchMeetings("standup", listOf(yesterday, earlierToday, onNow), now),
            "then the ones that are over, the latest first",
        )
    }

    @Test
    fun theCapIsPerSeriesAndTheListHoldsSixAtMost() {
        val standups = (1..5).map { meeting(('0' + it), 'a', "Synthetic team standup", day = today.plusDays(it)) }
        val retros = (1..5).map { meeting(('a' + it), 'b', "Synthetic team retro", day = today.plusDays(it), startMinute = 15 * 60) }

        val found = searchMeetings("team", standups + retros, now).orEmpty()

        assertEquals(SEARCH_GROUP_LIMIT, found.size)
        assertEquals(listOf(3, 3), found.groupBy { it.seriesKey }.values.map { it.size })
        assertEquals(found.sortedBy { it.time.startsAt }, found, "nearest first across series")
    }
}
