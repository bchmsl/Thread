package thread.data.firebase

import thread.domain.calendar.Meeting
import thread.domain.calendar.MeetingId
import thread.domain.calendar.MeetingResponse
import thread.domain.calendar.MeetingSource
import thread.domain.calendar.MeetingSourceState
import thread.domain.calendar.MeetingTime
import thread.domain.calendar.MeetingsState
import thread.domain.calendar.SeriesKey
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * How [FirestoreMeetingStore] reads stored meetings and the Mac app's source, without the emulators. Stored instants
 * are fake Timestamps, read in UTC here. All titles, places, links and ids are SYNTHETIC.
 */
class FirestoreMeetingMappingTest {
    private val day = LocalDay(2026, 11, 17)

    /** 2026-11-17 at [hour]:[minute] UTC, as a stored Timestamp. */
    private fun at(
        hour: Int,
        minute: Int = 0,
    ): dynamic {
        val millis = utcMillis(day, hour, minute)
        return jsObject("toMillis" to { millis })
    }

    private fun utcMillis(
        day: LocalDay,
        hour: Int,
        minute: Int,
    ): Double = (day.epochDay * DAY_MILLIS + (hour * 60 + minute) * MINUTE_MILLIS).toDouble()

    private fun utcWallTime(
        millis: Double,
        zone: String,
    ): WallTime {
        assertEquals(ZONE, zone, "The store passes the workspace zone")
        val whole = millis.toLong()
        return WallTime(LocalDay.fromEpochDay(whole.floorDiv(DAY_MILLIS)), (whole.mod(DAY_MILLIS) / MINUTE_MILLIS).toInt())
    }

    private fun stored(
        vararg extra: Pair<String, Any?>,
        without: Set<String> = emptySet(),
    ): dynamic {
        val fields =
            linkedMapOf<String, Any?>(
                "title" to "Synthetic planning",
                "allDay" to false,
                "start" to at(10),
                "end" to at(11),
                "response" to "accepted",
                "location" to "Synthetic room 1",
                "joinUrl" to JOIN_URL,
                "seriesKey" to SERIES_KEY,
                "updatedAt" to at(9),
                "schemaVersion" to 1,
            )
        extra.forEach { (key, value) -> fields[key] = value }
        without.forEach { fields.remove(it) }
        return jsObject(*fields.toList().toTypedArray())
    }

    private fun storedSource(vararg extra: Pair<String, Any?>): dynamic {
        val fields =
            linkedMapOf<String, Any?>(
                "state" to "ok",
                "calendarTitle" to "Synthetic work calendar",
                "fromDay" to "2026-11-16",
                "toDay" to "2026-12-01",
                "zone" to ZONE,
                "uploaderId" to "synthetic-uploader-1",
                "appVersion" to "1.0.0",
                "lastSuccessAt" to at(10, 42),
                "updatedAt" to at(10, 42),
                "schemaVersion" to 1,
            )
        extra.forEach { (key, value) -> fields[key] = value }
        return jsObject(*fields.toList().toTypedArray())
    }

    private fun read(
        data: dynamic,
        id: String = MEETING_ID,
    ): Meeting? = meetingFrom(id, data, ZONE, ::utcWallTime)

    @Test
    fun aTimedMeetingReadsAsWallClockTimesInTheZone() {
        assertEquals(
            Meeting(
                id = MeetingId(MEETING_ID),
                seriesKey = SeriesKey(SERIES_KEY),
                title = "Synthetic planning",
                time = MeetingTime.Timed(WallTime.of(day, 10, 0), WallTime.of(day, 11, 0)),
                response = MeetingResponse.Accepted,
                location = "Synthetic room 1",
                joinUrl = JOIN_URL,
            ),
            read(stored()),
        )
    }

    @Test
    fun anAllDayMeetingKeepsItsDays() {
        val meeting =
            read(stored("allDay" to true, "firstDay" to "2026-11-17", "lastDayExclusive" to "2026-11-19", without = setOf("start", "end")))
        assertEquals(MeetingTime.AllDay(day, LocalDay(2026, 11, 19)), meeting?.time)
    }

    @Test
    fun everyResponseReads() {
        MeetingResponse.entries.forEach { response ->
            assertEquals(response, read(stored("response" to response.id))?.response)
        }
    }

    @Test
    fun anEmptyTitleIsKeptAndShownAsNoTitle() {
        val meeting = read(stored("title" to ""))
        assertEquals("", meeting?.title)
        assertEquals("(No title)", meeting?.displayTitle)
    }

    @Test
    fun anEndBeforeTheStartBecomesTheStart() {
        // A clock change inside the meeting can put the wall-clock end before the start.
        val meeting = read(stored("start" to at(11), "end" to at(10, 30)))
        assertEquals(MeetingTime.Timed(WallTime.of(day, 11, 0), WallTime.of(day, 11, 0)), meeting?.time)
    }

    @Test
    fun aBlankLocationAndAJoinLinkOffTheAllowlistAreDroppedButTheMeetingStays() {
        listOf(
            "http://teams.microsoft.com/l/meetup-join/synthetic",
            "https://teams.microsoft.com.example.test/l/synthetic",
            "https://user@zoom.us/j/1",
            "https://zoom.us:8443/j/1",
            "javascript:alert(1)",
        ).forEach { url ->
            val meeting = read(stored("location" to "  ", "joinUrl" to url))
            assertEquals("Synthetic planning", meeting?.title, url)
            assertNull(meeting?.location, url)
            assertNull(meeting?.joinUrl, url)
        }
        assertNull(read(stored(without = setOf("location", "joinUrl")))?.joinUrl)
    }

    @Test
    fun aMeetingThisVersionCannotReadIsLeftOut() {
        val unreadable =
            mapOf(
                "a newer schema" to stored("schemaVersion" to 2),
                "no schema" to stored(without = setOf("schemaVersion")),
                "a bad series key" to stored("seriesKey" to "SYNTHETIC"),
                "no title" to stored(without = setOf("title")),
                "a title that is not text" to stored("title" to 7),
                "an unknown response" to stored("response" to "declined"),
                "no allDay" to stored(without = setOf("allDay")),
                "no end" to stored(without = setOf("end")),
                "a start that is not a Timestamp" to stored("start" to "2026-11-17T10:00"),
                "an all-day meeting with no days" to stored("allDay" to true, without = setOf("start", "end")),
                "an all-day meeting that ends on its first day" to
                    stored("allDay" to true, "firstDay" to "2026-11-17", "lastDayExclusive" to "2026-11-17"),
            )
        unreadable.forEach { (case, data) -> assertNull(read(data), case) }
        assertNull(read(stored(), id = "not-a-hash"), "a bad id")
        assertNull(read(null), "no data")
    }

    @Test
    fun theSourceReadsWithItsLastSuccessAndDays() {
        val state = meetingsStateFrom(storedSource(), emptyList(), ZONE, ::utcWallTime)
        assertEquals(
            MeetingsState.Ready(
                MeetingSource(
                    state = MeetingSourceState.Ok,
                    calendarTitle = "Synthetic work calendar",
                    lastSuccessAtMillis = utcMillis(day, 10, 42).toLong(),
                    fromDay = LocalDay(2026, 11, 16),
                    toDay = LocalDay(2026, 12, 1),
                    appVersion = "1.0.0",
                ),
                emptyList(),
            ),
            state,
        )
    }

    @Test
    fun aSourceWithNoSuccessYetAndABlankTitleReads() {
        val state =
            meetingsStateFrom(
                storedSource("state" to "noAccess", "lastSuccessAt" to null, "calendarTitle" to " "),
                emptyList(),
                ZONE,
                ::utcWallTime,
            )
        val source = assertIs<MeetingsState.Ready>(state).source
        assertEquals(MeetingSourceState.NoAccess, source.state)
        assertNull(source.lastSuccessAtMillis)
        assertNull(source.calendarTitle)
    }

    @Test
    fun aSourceThisVersionCannotReadAsksForAnUpdate() {
        assertEquals(MeetingsState.NeedsUpdate, meetingsStateFrom(storedSource("schemaVersion" to 2), emptyList(), ZONE, ::utcWallTime))
        assertEquals(MeetingsState.NeedsUpdate, meetingsStateFrom(storedSource("state" to "sleeping"), emptyList(), ZONE, ::utcWallTime))
    }

    @Test
    fun oneUnreadableMeetingDoesNotHideTheOthers() {
        val other = "0".repeat(31) + "2"
        val state =
            meetingsStateFrom(
                storedSource(),
                listOf(MEETING_ID to stored(), other to stored("response" to "declined")),
                ZONE,
                ::utcWallTime,
            )
        assertEquals(listOf(MeetingId(MEETING_ID)), assertIs<MeetingsState.Ready>(state).meetings.map { it.id })
    }

    private companion object {
        const val ZONE = "Asia/Tbilisi"
        const val MEETING_ID = "00000000000000000000000000000001"
        const val SERIES_KEY = "0000000000000000000000000000000a"
        const val JOIN_URL = "https://teams.microsoft.com/l/meetup-join/synthetic"
        const val DAY_MILLIS = 86_400_000L
        const val MINUTE_MILLIS = 60_000L
    }
}
