package thread.domain.calendar

import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.TopicId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Every meeting, link and URL here is SYNTHETIC test data.
class MeetingTest {
    private val today = LocalDay(2026, 11, 17)
    private val hash = "0123456789abcdef0123456789abcdef"

    @Test
    fun meetingIdsAndSeriesKeysAre32LowercaseHex() {
        assertTrue(isMeetingHash(hash))
        assertEquals(hash, MeetingId(hash).value)
        assertEquals(hash, SeriesKey(hash).toString())
        for (bad in listOf("", hash.uppercase(), hash.drop(1), hash + "0", "g" + hash.drop(1), "$hash ")) {
            assertFalse(isMeetingHash(bad), bad)
            assertFailsWith<IllegalArgumentException> { MeetingId(bad) }
            assertFailsWith<IllegalArgumentException> { SeriesKey(bad) }
        }
    }

    @Test
    fun responsesAndSourceStatesReadTheirStoredIds() {
        assertEquals(
            listOf(MeetingResponse.Accepted, MeetingResponse.Tentative, MeetingResponse.Pending, MeetingResponse.None),
            listOf("accepted", "tentative", "pending", "none").map { MeetingResponse.fromId(it) },
        )
        assertNull(MeetingResponse.fromId("declined"))
        assertNull(MeetingResponse.fromId(null))
        assertEquals(
            MeetingSourceState.entries,
            listOf("ok", "noAccess", "noCalendar", "paused").map { MeetingSourceState.fromId(it) },
        )
        assertNull(MeetingSourceState.fromId("NOACCESS"))
    }

    @Test
    fun aTimedMeetingEndsAtOrAfterItsStartAndAnAllDayOneHasADay() {
        val start = WallTime.of(today, 10, 0)
        assertEquals(start, MeetingTime.Timed(start, start).endsAt)
        assertFailsWith<IllegalArgumentException> { MeetingTime.Timed(start, WallTime.of(today, 9, 59)) }
        val allDay = MeetingTime.AllDay(today, today.plusDays(2))
        assertEquals(WallTime(today, 0), allDay.startsAt)
        assertEquals(WallTime(today.plusDays(2), 0), allDay.endsAt)
        assertFailsWith<IllegalArgumentException> { MeetingTime.AllDay(today, today) }
    }

    @Test
    fun aMeetingWithoutATitleIsShownAsUntitled() {
        val time = MeetingTime.Timed(WallTime.of(today, 10, 0), WallTime.of(today, 11, 0))
        val blank = Meeting(MeetingId(hash), SeriesKey(hash), "  ", time, MeetingResponse.None)
        assertEquals(UNTITLED_MEETING, blank.displayTitle)
        assertEquals("SYNTHETIC Standup", blank.copy(title = "SYNTHETIC Standup").displayTitle)
    }

    @Test
    fun joinIsOfferedOnlyForHttpsLinksOnTheAllowlist() {
        val accepted =
            mapOf(
                "https://teams.microsoft.com/l/meetup-join/synthetic" to JoinService.Teams,
                "https://teams.live.com/meet/000" to JoinService.Teams,
                "https://zoom.us/j/000" to JoinService.Zoom,
                "https://synthetic.zoom.us/j/000?pwd=SYNTHETIC" to JoinService.Zoom,
                "https://meet.google.com/aaa-bbbb-ccc" to JoinService.GoogleMeet,
                "https://synthetic.webex.com/meet/x" to JoinService.Webex,
                "https://teams.microsoft.com" to JoinService.Teams,
                "https://zoom.us#x" to JoinService.Zoom,
                "https://teams.microsoft.com/" + "x".repeat(JOIN_URL_MAX_LENGTH - 28) to JoinService.Teams,
            )
        for ((url, service) in accepted) assertEquals(service, joinServiceOf(url), url)
        val refused =
            listOf(
                "http://teams.microsoft.com/l/x",
                "https://teams.microsoft.com.example.test/x",
                "https://example.test/teams.microsoft.com",
                "https://user@teams.microsoft.com/x",
                "https://teams.microsoft.com:8443/x",
                "https://webex.com/x",
                "https://zoom.us.example.test/j/1",
                "https://evilzoom.us/j/1",
                "https://TEAMS.MICROSOFT.COM/x",
                "javascript:alert(1)",
                " https://zoom.us/j/1",
                "https://teams.microsoft.com/" + "x".repeat(JOIN_URL_MAX_LENGTH - 27),
                "",
            )
        for (url in refused) assertNull(joinServiceOf(url), url)
        assertEquals("Google Meet", JoinService.GoogleMeet.label)
    }

    @Test
    fun aTopicLinkIdIsTheTopicAndTheSeries() {
        val link = MeetingLink(TopicId("t-synthetic-1"), SeriesKey(hash))
        assertEquals("t-synthetic-1_$hash", link.id)
        assertTrue(link.linked)
        assertNull(link.title)
    }
}
