package thread.web.presentation.calendar

import thread.domain.calendar.Meeting
import thread.domain.calendar.MeetingId
import thread.domain.calendar.MeetingResponse
import thread.domain.calendar.MeetingSource
import thread.domain.calendar.MeetingSourceState
import thread.domain.calendar.MeetingTime
import thread.domain.calendar.MeetingsState
import thread.domain.calendar.NextMeeting
import thread.domain.calendar.SeriesKey
import thread.domain.calendar.TodayMeeting
import thread.domain.calendar.todayMeetings
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.web.platform.WallClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D78: what Today's Meetings card, the Meeting card and the status line chip say. Every meeting here is SYNTHETIC. */
class MeetingTextTest {
    private val today = LocalDay(2026, 11, 17)
    private val now = WallTime.of(today, 10, 48)

    private fun meeting(
        n: Int,
        title: String,
        time: MeetingTime,
        response: MeetingResponse = MeetingResponse.Accepted,
        location: String? = null,
    ) = Meeting(
        id = MeetingId(n.toString(16).padStart(32, '0')),
        seriesKey = SeriesKey("0".repeat(31) + "a"),
        title = title,
        time = time,
        response = response,
        location = location,
    )

    private fun timed(
        startDay: LocalDay,
        startHour: Int,
        startMinute: Int,
        endDay: LocalDay,
        endHour: Int,
        endMinute: Int,
    ) = MeetingTime.Timed(WallTime.of(startDay, startHour, startMinute), WallTime.of(endDay, endHour, endMinute))

    private fun timed(
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
    ) = timed(today, startHour, startMinute, today, endHour, endMinute)

    private val planning = meeting(1, "Synthetic planning", timed(10, 0, 11, 0), MeetingResponse.Tentative, "Synthetic room 1")
    private val review = meeting(2, "Synthetic review", timed(11, 30, 12, 0))
    private val standup = meeting(3, "Synthetic standup", timed(9, 0, 9, 30))
    private val lateCall = meeting(4, "Synthetic late call", timed(today.plusDays(-1), 23, 0, today, 0, 30))
    private val nightCall = meeting(5, "Synthetic night call", timed(today, 23, 30, today.plusDays(1), 0, 30), MeetingResponse.Pending)
    private val offsite = meeting(6, "Synthetic offsite", MeetingTime.AllDay(today, today.plusDays(1)))

    private val rows = todayMeetings(listOf(planning, review, standup, lateCall, nightCall, offsite), now)

    private fun row(meeting: Meeting) = rows.first { it.meeting == meeting }

    @Test
    fun durationsReadShortAndInWords() {
        assertEquals(listOf("0 min", "30 min", "1 h", "1 h 30 min"), listOf(0, 30, 60, 90).map(::durationText))
        assertEquals(listOf("1 minute", "30 minutes", "1 hour", "2 hours 30 minutes"), listOf(1, 30, 60, 150).map(::durationWords))
    }

    @Test
    fun theMeetingCardSaysWhenItHappens() {
        assertEquals("Tue 17 Nov, 10:00 to 11:00 (1 h)", meetingWhenText(planning.time))
        assertEquals("Tue 17 Nov, 10:00", meetingWhenText(timed(10, 0, 10, 0)))
        assertEquals("Tue 17 Nov 23:30 to Wed 18 Nov 00:30 (1 h)", meetingWhenText(nightCall.time))
        assertEquals("Tue 17 Nov, all day", meetingWhenText(offsite.time))
        assertEquals("Tue 17 Nov to Thu 19 Nov, all day", meetingWhenText(MeetingTime.AllDay(today, today.plusDays(3))))
    }

    @Test
    fun onlyAnAnswerWorthNoticingIsShown() {
        assertEquals("Tentative", responseText(MeetingResponse.Tentative))
        assertEquals("Not answered", responseText(MeetingResponse.Pending))
        assertNull(responseText(MeetingResponse.Accepted))
        assertNull(responseText(MeetingResponse.None))
    }

    @Test
    fun eachRowSaysWhereItIsComparedWithNow() {
        assertEquals("Ends in 12 min", rowRelativeText(row(planning), now))
        assertEquals("Starts in 42 min", rowRelativeText(row(review), now))
        assertNull(rowRelativeText(row(standup), now))
        assertNull(rowRelativeText(row(nightCall), now), "Only the next meeting says when it starts")
        assertEquals("Started yesterday", rowSpanText(row(lateCall), today))
        assertEquals("Ends tomorrow", rowSpanText(row(nightCall), today))
        assertNull(rowSpanText(row(planning), today))
    }

    @Test
    fun eachRowIsOneSentenceForScreenReaders() {
        assertEquals(
            "10:00 to 11:00, Synthetic planning, now, ends in 12 minutes, tentative, Synthetic room 1",
            meetingRowLabel(row(planning), now),
        )
        assertEquals("11:30 to 12:00, Synthetic review, next, starts in 42 minutes", meetingRowLabel(row(review), now))
        assertEquals("09:00 to 09:30, Synthetic standup, ended", meetingRowLabel(row(standup), now))
        assertEquals("Yesterday 23:00 to 00:30, Synthetic late call, ended", meetingRowLabel(row(lateCall), now))
        assertEquals("23:30 to tomorrow 00:30, Synthetic night call, not answered", meetingRowLabel(row(nightCall), now))
        assertEquals("All day, Synthetic offsite", meetingRowLabel(row(offsite), now))
    }

    @Test
    fun aRowNamesTheTopicsItsSeriesIsLinkedTo() {
        assertEquals(
            "11:30 to 12:00, Synthetic review, next, starts in 42 minutes, linked to Synthetic QR",
            meetingRowLabel(row(review), now, listOf("Synthetic QR")),
        )
        assertEquals("linked to Synthetic A and Synthetic B", linkedToText(listOf("Synthetic A", "Synthetic B")))
        assertEquals("linked to A, B and C", linkedToText(listOf("A", "B", "C")))
        assertNull(linkedToText(emptyList()))
    }

    @Test
    fun topicDetailsSayWhenALinkedSeriesMeetsNext() {
        val thursday = today.plusDays(2)
        assertEquals("Today, 11:30", nextOccurrenceText(review, now))
        assertEquals("Now, until 11:00", nextOccurrenceText(planning, now))
        assertEquals("Now, until tomorrow 00:30", nextOccurrenceText(nightCall, WallTime.of(today, 23, 45)))
        assertEquals("Thu 19 Nov, 10:00", nextOccurrenceText(meeting(7, "Synthetic sync", timed(thursday, 10, 0, thursday, 11, 0)), now))
        val trip = meeting(8, "Synthetic trip", MeetingTime.AllDay(today.plusDays(1), thursday))
        assertEquals("Tomorrow, all day", nextOccurrenceText(trip, now))
        val conference = meeting(9, "Synthetic conference", MeetingTime.AllDay(today.plusDays(-1), thursday))
        assertEquals("Today, all day", nextOccurrenceText(conference, now), "an all-day one under way")
        assertEquals("Yesterday, all day", occurrenceText(conference.time, today), "search shows when it starts")
        assertEquals(NO_NEXT_MEETING, nextOccurrenceText(null, now))
    }

    @Test
    fun theChipNamesTheNextMeeting() {
        val soon = NextMeeting.Soon(review, 42)
        assertEquals("Synthetic review in 42 min", chipText(soon))
        assertEquals("in 42 min", chipWhenText(soon))
        assertEquals("42m", chipShortText(soon))
        assertEquals("Next meeting: Synthetic review, starts in 42 minutes. Opens the meeting.", chipLabel(soon))

        val started = NextMeeting.Started(planning)
        assertEquals("Synthetic planning now", chipText(started))
        assertEquals("Now", chipShortText(started))
        assertEquals("Meeting now: Synthetic planning, started at 10:00. Opens the meeting.", chipLabel(started))
    }

    @Test
    fun theLastUploadReadsAsATimeOrADay() {
        assertEquals("Updated from your Mac at 10:42", updatedText(WallTime.of(today, 10, 42), today))
        assertEquals("Updated from your Mac yesterday 22:10", updatedText(WallTime.of(today.plusDays(-1), 22, 10), today))
        assertEquals("Updated from your Mac Sun 15 Nov 22:10", updatedText(WallTime.of(today.plusDays(-2), 22, 10), today))
        assertEquals(
            "Not updated since 08:10. Is your Mac awake and Thread Calendar running?",
            staleText(WallTime.of(today, 8, 10), today),
        )
        assertEquals("Join (Teams)", joinLabel("Teams"))
    }

    @Test
    fun theCardSaysWhatIsWrongBeforeWhyTheListIsEmpty() {
        val lastSuccess = WallTime.of(today, 8, 10)
        val fresh = LAST_SUCCESS + 10 * MINUTE
        val stale = LAST_SUCCESS + 2 * HOUR

        fun ready(
            state: MeetingSourceState = MeetingSourceState.Ok,
            lastSuccessAt: Long? = LAST_SUCCESS,
            fromDay: LocalDay = today.plusDays(-1),
        ) = MeetingsState.Ready(MeetingSource(state, "Synthetic work calendar", lastSuccessAt, fromDay, today.plusDays(14)), emptyList())

        fun notice(
            state: MeetingsState,
            hasRows: Boolean = false,
            nowMillis: Long = fresh,
            last: WallTime? = lastSuccess,
        ) = meetingsNotice(state, hasRows, today, nowMillis, last)

        assertNull(notice(MeetingsState.Loading))
        assertNull(notice(MeetingsState.NotSetUp))
        val update = notice(MeetingsState.NeedsUpdate)
        assertEquals("Update Thread to see meetings. Reload the page to get the latest version.", update?.text)
        assertFalse(update!!.warning)
        assertEquals(
            MeetingsNotice("Meetings are unavailable. Reload the page to try again.", warning = true),
            notice(MeetingsState.Unavailable("permission-denied")),
        )
        // A problem on the Mac wins over a stale upload.
        assertEquals(
            "Thread Calendar on your Mac has no calendar access. Open its menu and choose Allow calendar access.",
            notice(ready(MeetingSourceState.NoAccess), nowMillis = stale)?.text,
        )
        assertEquals(
            "Thread Calendar on your Mac has no calendar picked. Pick one in its menu under Calendar.",
            notice(ready(MeetingSourceState.NoCalendar))?.text,
        )
        assertEquals("Syncing is paused in Thread Calendar on your Mac.", notice(ready(MeetingSourceState.Paused))?.text)
        // A stale upload wins over an empty list, and says its time, so the footer leaves it out.
        assertEquals(MeetingsNotice(staleText(lastSuccess, today), warning = true, saysTime = true), notice(ready(), nowMillis = stale))
        assertEquals(
            MeetingsNotice(staleText(lastSuccess, today), warning = true, saysTime = true),
            notice(ready(), hasRows = true, nowMillis = stale),
        )
        // An empty list proves nothing until a pass that covered today succeeded.
        assertEquals("Meetings for today are not loaded yet.", notice(ready(lastSuccessAt = null), last = null)?.text)
        assertEquals("Meetings for today are not loaded yet.", notice(ready(fromDay = today.plusDays(1)))?.text)
        assertEquals(MeetingsNotice("No meetings today.", warning = false), notice(ready()))
        assertNull(notice(ready(), hasRows = true))
    }

    @Test
    fun theCardKeepsTheLatestPastMeetingsAndSaysWhenNoneIsLeft() {
        // D86. A fresh upload, so the card has no warning of its own.
        val uploaded = WallClock.nowMillis().toLong()
        val source = MeetingSource(MeetingSourceState.Ok, "Synthetic work calendar", uploaded, today.plusDays(-1), today)

        fun view(
            meetings: List<Meeting>,
            past: Int?,
        ) = todayMeetingsView(MeetingsState.Ready(source, meetings), now, "UTC", past)

        val all = listOf(planning, review, standup, lateCall, nightCall, offsite)
        assertEquals(rows, view(all, null).rows)
        assertEquals(rows, view(all, 2).rows, "Two ended meetings, two kept")
        assertEquals(rows - row(lateCall), view(all, 1).rows, "The standup ended last")
        assertEquals(rows - row(lateCall) - row(standup), view(all, 0).rows)
        assertEquals("4", view(all, 0).count, "The head counts what the card shows")
        assertNull(view(all, 0).notice)

        val morning = listOf(standup, lateCall)
        assertEquals(MeetingsNotice("No more meetings today.", warning = false), view(morning, 0).notice)
        assertFalse(view(morning, 0).hasContent, "Hidden when empty, it leaves")
        assertNull(view(morning, null).notice)
        assertTrue(view(morning, null).hasContent)
        assertEquals(MeetingsNotice("No meetings today.", warning = false), view(emptyList(), 0).notice, "Nothing was hidden")
    }

    @Test
    fun aCardHiddenWhenEmptyStaysForAMeetingOrAProblem() {
        // D86, plan A10: what keeps the card while Settings hides it when empty.
        val lastSuccess = WallTime.of(today, 8, 10)

        fun ready(
            state: MeetingSourceState = MeetingSourceState.Ok,
            fromDay: LocalDay = today.plusDays(-1),
        ) = MeetingsState.Ready(MeetingSource(state, "Synthetic work calendar", LAST_SUCCESS, fromDay, today.plusDays(14)), emptyList())

        fun view(
            state: MeetingsState,
            rows: List<TodayMeeting> = emptyList(),
            nowMillis: Long = LAST_SUCCESS + 10 * MINUTE,
        ) = TodayMeetingsView(state, rows, meetingsNotice(state, rows.isNotEmpty(), today, nowMillis, lastSuccess), lastSuccess)

        assertFalse(view(MeetingsState.Loading).hasContent, "The first answer is on its way")
        assertFalse(view(MeetingsState.NotSetUp).hasContent)
        assertFalse(view(ready()).hasContent, "No meetings today")
        assertFalse(view(ready(fromDay = today.plusDays(1))).hasContent, "Today not loaded yet")
        assertTrue(view(ready(), rows = listOf(row(planning))).hasContent)
        assertTrue(view(ready(), nowMillis = LAST_SUCCESS + 2 * HOUR).hasContent, "The Mac has not uploaded for over an hour")
        listOf(MeetingSourceState.NoAccess, MeetingSourceState.NoCalendar, MeetingSourceState.Paused).forEach { state ->
            assertTrue(view(ready(state)).hasContent, "$state")
        }
        assertTrue(view(MeetingsState.Unavailable("permission-denied")).hasContent)
        assertTrue(view(MeetingsState.NeedsUpdate).hasContent)
    }

    @Test
    fun todayKeepsTheMeetingsCardAsLastSeenWhileItLoads() {
        val ready = MeetingsState.Ready(MeetingSource(MeetingSourceState.Ok), emptyList())
        listOf(true, false).forEach { seen ->
            assertTrue(meetingsCardShown(ready, seen))
            assertTrue(meetingsCardShown(MeetingsState.NeedsUpdate, seen))
            assertFalse(meetingsCardShown(MeetingsState.NotSetUp, seen))
            assertEquals(seen, meetingsCardShown(MeetingsState.Loading, seen))
            assertEquals(seen, meetingsCardShown(MeetingsState.Unavailable("unavailable"), seen))
        }
    }

    private companion object {
        const val LAST_SUCCESS = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
