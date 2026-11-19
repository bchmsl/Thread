package thread.domain.calendar

import thread.domain.search.searchMeetings
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.TopicId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Every meeting, title and link here is SYNTHETIC test data (D100).
class MeetingAlternateTest {
    private val day = LocalDay(2026, 11, 19)
    private val now = WallTime(day, 9 * 60)
    private val standup = SeriesKey("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    private val review = SeriesKey("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    private val teams = "https://teams.microsoft.com/l/meetup-join/synthetic"
    private val own = "https://meet.synthetic.example/room-1"

    private fun meeting(
        series: SeriesKey = standup,
        title: String = "Synthetic standup",
        joinUrl: String? = teams,
        alternate: MeetingAlternate? = null,
        start: Int = 10 * 60,
    ) = Meeting(
        id = MeetingId(series.value.take(30) + start.toString(16).padStart(2, '0').takeLast(2)),
        seriesKey = series,
        title = title,
        time = MeetingTime.Timed(WallTime(day, start), WallTime(day, start + 30)),
        response = MeetingResponse.Accepted,
        joinUrl = joinUrl,
        alternate = alternate,
    )

    @Test
    fun withoutAnAlternateTheCalendarsTitleAndLinkShow() {
        val plain = meeting()
        assertEquals("Synthetic standup", plain.displayTitle)
        assertEquals(MeetingJoin(teams, JoinService.Teams), plain.join)
        assertEquals("(No title)", meeting(title = " ").displayTitle)
        assertNull(meeting(joinUrl = null).join)
        // A calendar link off the allowlist offers no Join, as before D100.
        assertNull(meeting(joinUrl = own).join)
    }

    @Test
    fun theAlternateTitleAndLinkWinAndTheCalendarsStayKnown() {
        val renamed = meeting(alternate = MeetingAlternate(standup, title = "SYNTHETIC Team sync", joinUrl = own))
        assertEquals("SYNTHETIC Team sync", renamed.displayTitle)
        assertEquals("Synthetic standup", renamed.calendarTitle)
        // Any https link the owner typed is offered, as a plain Join when the service is not known.
        assertEquals(MeetingJoin(own, null), renamed.join)
        assertEquals(MeetingJoin(teams, JoinService.Teams), renamed.calendarJoin)

        val zoom = meeting(joinUrl = null, alternate = MeetingAlternate(standup, joinUrl = "https://us02web.zoom.us/j/1"))
        assertEquals(JoinService.Zoom, zoom.join?.service)
        assertEquals("Synthetic standup", zoom.displayTitle, "a link alone keeps the calendar's title")
    }

    @Test
    fun anAlternateHoldsOnlyCleanValues() {
        assertFailsWith<IllegalArgumentException> { MeetingAlternate(standup, title = " padded ") }
        assertFailsWith<IllegalArgumentException> { MeetingAlternate(standup, title = "") }
        assertFailsWith<IllegalArgumentException> { MeetingAlternate(standup, joinUrl = "http://synthetic.example") }
        assertTrue(MeetingAlternate(standup).isEmpty)
        assertFalse(MeetingAlternate(standup, title = "SYNTHETIC").isEmpty)
    }

    @Test
    fun anAlternateLinkIsAFullHttpsLinkWithNothingBeforeTheHost() {
        listOf(
            own,
            teams,
            "https://synthetic.example",
            "https://synthetic.example:8443/join?id=1&pwd=SYNTHETIC#top",
            "https://a-b.synthetic.example/x@y",
            "https://synthetic.example/" + "x".repeat(JOIN_URL_MAX_LENGTH - 26),
        ).forEach { assertTrue(isAlternateJoinUrl(it), it) }
        listOf(
            "http://synthetic.example/room",
            "javascript:alert(1)",
            "https://synthetic.example@evil.example/room",
            "https://localhost/room",
            "https://synthetic.example/room 1",
            "https://synthetic.example/rööm",
            "synthetic.example/room",
            "",
            "https://synthetic.example/" + "x".repeat(JOIN_URL_MAX_LENGTH - 25),
        ).forEach { assertFalse(isAlternateJoinUrl(it), it) }
    }

    @Test
    fun alternateTitlesAreTrimmedAndNeverBlank() {
        assertEquals("SYNTHETIC", alternateTitleOf("  SYNTHETIC \n"))
        assertNull(alternateTitleOf("   "))
        assertNull(alternateTitleOf("x".repeat(MEETING_TEXT_MAX_LENGTH + 1)))
        assertEquals(MEETING_TEXT_MAX_LENGTH, alternateTitleOf("x".repeat(MEETING_TEXT_MAX_LENGTH))?.length)
    }

    @Test
    fun eachOccurrenceOfTheSeriesGetsTheAlternateAndOtherSeriesDoNot() {
        val alternate = MeetingAlternate(standup, title = "SYNTHETIC Team sync")
        val meetings = listOf(meeting(start = 9 * 60), meeting(start = 15 * 60), meeting(series = review, title = "Synthetic review"))
        val shown = withAlternates(meetings, mapOf(standup to alternate))
        assertEquals(listOf("SYNTHETIC Team sync", "SYNTHETIC Team sync", "Synthetic review"), shown.map { it.displayTitle })
        // An empty alternate counts as none, and no alternates leave the list as it is.
        assertNull(withAlternates(meetings, mapOf(standup to MeetingAlternate(standup))).first().alternate)
        assertSame(meetings, withAlternates(meetings, emptyMap()))
        // Cleared on the next pass: the alternate goes when it is no longer stored.
        assertNull(withAlternates(shown, emptyMap()).first().alternate)
    }

    @Test
    fun aReadyStateCarriesTheAlternatesAndOtherStatesStayAsTheyAre() {
        val ready = MeetingsState.Ready(MeetingSource(MeetingSourceState.Ok), listOf(meeting()))
        val alternates = mapOf(standup to MeetingAlternate(standup, title = "SYNTHETIC Team sync"))
        val shown = withAlternates(ready, alternates) as MeetingsState.Ready
        assertEquals("SYNTHETIC Team sync", shown.meetings.single().displayTitle)
        assertSame(MeetingsState.Loading, withAlternates(MeetingsState.Loading, alternates))
        assertSame(ready, withAlternates(ready, emptyMap()))
    }

    @Test
    fun theStatusLineAndTodayUseTheAlternateTitle() {
        val alternate = MeetingAlternate(standup, title = "SYNTHETIC Team sync")
        val renamed = withAlternates(listOf(meeting(start = 9 * 60 + 10)), mapOf(standup to alternate))
        assertEquals("SYNTHETIC Team sync", nextMeeting(renamed, now)?.meeting?.displayTitle)
        assertEquals("SYNTHETIC Team sync", todayMeetings(renamed, now).single().meeting.displayTitle)
    }

    @Test
    fun searchFindsAMeetingByEitherTitleButNeverByALink() {
        val alternate = MeetingAlternate(standup, title = "SYNTHETIC Team sync", joinUrl = own)
        val renamed = withAlternates(listOf(meeting()), mapOf(standup to alternate))
        assertEquals(1, searchMeetings("team sync", renamed, now)?.size)
        assertEquals(1, searchMeetings("standup", renamed, now)?.size)
        assertEquals(0, searchMeetings("room-1", renamed, now)?.size)
        assertEquals(0, searchMeetings("synthetic.example", renamed, now)?.size)
    }

    @Test
    fun topicDetailsNameASeriesByItsAlternateAlsoAfterItLeftTheWindow() {
        val topic = TopicId("t-synthetic")
        val links = listOf(MeetingLink(topic, standup, title = "Synthetic standup"), MeetingLink(topic, review, title = "Synthetic review"))
        val alternates = mapOf(standup to MeetingAlternate(standup, title = "SYNTHETIC Team sync"))
        val inWindow = withAlternates(listOf(meeting()), alternates)
        val rows = meetingsForTopic(topic, links, inWindow, now, alternates)
        assertEquals(listOf("SYNTHETIC Team sync", "Synthetic review"), rows.map { it.title })
        // The series left the window: the alternate still wins over the link's snapshot.
        val gone = meetingsForTopic(topic, links, emptyList(), now, alternates)
        assertEquals(setOf("SYNTHETIC Team sync", "Synthetic review"), gone.map { it.title }.toSet())
    }
}
