package thread.web.presentation.calendar

import thread.domain.calendar.CalendarDay
import thread.domain.calendar.Meeting
import thread.domain.calendar.MeetingId
import thread.domain.calendar.MeetingPhase
import thread.domain.calendar.MeetingResponse
import thread.domain.calendar.MeetingTime
import thread.domain.calendar.SeriesKey
import thread.domain.calendar.TodayMeeting
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.web.presentation.View
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D102: how the Calendar page groups its days and names them. Every meeting here is SYNTHETIC. */
class CalendarAgendaTest {
    // Thursday. The Mac's window: yesterday, Wednesday 18 Nov, to Thursday 3 Dec.
    private val today = LocalDay(2026, 11, 19)

    private fun day(
        month: Int,
        dayOfMonth: Int,
    ) = LocalDay(2026, month, dayOfMonth)

    private var next = 0

    private fun rows(
        on: LocalDay,
        count: Int,
    ): List<TodayMeeting> =
        List(count) {
            next += 1
            val start = WallTime.of(on, 9 + it, 0)
            val meeting =
                Meeting(
                    id = MeetingId(next.toString(16).padStart(32, '0')),
                    seriesKey = SeriesKey(next.toString(16).padStart(32, 'a')),
                    title = "Synthetic meeting $next",
                    time = MeetingTime.Timed(start, WallTime.of(on, 9 + it, 30)),
                    response = MeetingResponse.Accepted,
                    location = null,
                )
            TodayMeeting(meeting, MeetingPhase.Later)
        }

    /** Wednesday 18 Nov to Thursday 3 Dec, with [counts] meetings on the days it names and none on the rest. */
    private fun window(counts: Map<LocalDay, Int>): List<CalendarDay> =
        (day(11, 18).epochDay..day(12, 3).epochDay).map { epochDay ->
            val day = LocalDay.fromEpochDay(epochDay)
            CalendarDay(day, rows(day, counts[day] ?: 0))
        }

    private val days =
        window(
            mapOf(
                day(11, 18) to 1,
                day(11, 23) to 3,
                day(11, 25) to 1,
                day(11, 27) to 2,
                day(12, 1) to 1,
            ),
        )

    private fun CalendarPart.text(): String =
        when (this) {
            is CalendarPart.Day -> "day ${day.day} (${day.rows.size})"
            is CalendarPart.Gap -> "gap ${gapText(days)}"
        }

    @Test
    fun weeksStartOnMondayAndAreNamedFromThisWeek() {
        val weeks = calendarWeeks(days, today)

        assertEquals(listOf(day(11, 16), day(11, 23), day(11, 30)), weeks.map { it.monday })
        assertEquals(listOf("This week", "Next week", "Week of 30 Nov"), weeks.map { it.title })
        assertEquals(listOf("Wed 18 to Sun 22 Nov", "Mon 23 to Sun 29 Nov", "Mon 30 Nov to Thu 3 Dec"), weeks.map { it.hint })
        assertEquals(listOf(1, 6, 1), weeks.map { it.count })
    }

    @Test
    fun daysWithNoMeetingsInARowAreOneLineButTodayAlwaysShows() {
        val weeks = calendarWeeks(days, today)

        assertEquals(
            listOf("day 2026-11-18 (1)", "day 2026-11-19 (0)", "gap Fri 20 to Sun 22 Nov"),
            weeks[0].parts.map { it.text() },
        )
        assertEquals(
            listOf(
                "day 2026-11-23 (3)",
                "gap Tue 24 Nov",
                "day 2026-11-25 (1)",
                "gap Thu 26 Nov",
                "day 2026-11-27 (2)",
                "gap Sat 28 and Sun 29 Nov",
            ),
            weeks[1].parts.map { it.text() },
        )
        assertEquals(
            listOf("gap Mon 30 Nov", "day 2026-12-01 (1)", "gap Wed 2 and Thu 3 Dec"),
            weeks[2].parts.map { it.text() },
        )
        // A link to any day of a run lands on the run's first day.
        val gap = weeks[0].parts[2]
        assertEquals("cal-2026-11-20", gap.anchorId)
        assertEquals(listOf(day(11, 20), day(11, 21), day(11, 22)), gap.days)
    }

    @Test
    fun aRunOfDaysAcrossTwoMonthsNamesBothMonths() {
        assertEquals("Mon 30 Nov to Tue 1 Dec", dayRangeText(day(11, 30), day(12, 1), "to"))
        assertEquals("Mon 30 Nov and Tue 1 Dec", gapText(listOf(day(11, 30), day(12, 1))))
        assertEquals("Sun 29 Nov to Tue 1 Dec", gapText(listOf(day(11, 29), day(11, 30), day(12, 1))))
    }

    @Test
    fun weekTitlesAreRelativeToThisWeek() {
        val thisMonday = mondayOf(today)

        assertEquals(day(11, 16), thisMonday)
        assertEquals(thisMonday, mondayOf(day(11, 16)))
        assertEquals(thisMonday, mondayOf(day(11, 22)))
        assertEquals("Last week", weekTitle(day(11, 9), thisMonday))
        assertEquals("This week", weekTitle(day(11, 16), thisMonday))
        assertEquals("Next week", weekTitle(day(11, 23), thisMonday))
        assertEquals("Week of 30 Nov", weekTitle(day(11, 30), thisMonday))
        assertEquals("Week of 2 Nov", weekTitle(day(11, 2), thisMonday))
    }

    @Test
    fun theListEndsWithTheLastDayTheMacUploaded() {
        assertEquals("Nothing after Thu 3 Dec yet. Your Mac uploads up to two weeks ahead.", calendarEndText(day(12, 3)))
    }

    @Test
    fun eachOverviewDayNamesItselfAndItsCount() {
        assertEquals("Mon 23 Nov, 3 meetings", overviewDayLabel(day(11, 23), 3, today))
        assertEquals("Wed 18 Nov, 1 meeting", overviewDayLabel(day(11, 18), 1, today))
        assertEquals("Thu 19 Nov, today, no meetings", overviewDayLabel(today, 0, today))
        assertEquals(listOf("No meetings", "1 meeting", "2 meetings"), (0..2).map(::meetingCountText))
    }

    @Test
    fun theOverviewShowsWholeWeeksAndMarksDaysTheMacDidNotUpload() {
        val cells = overviewCells(days)

        assertEquals(21, cells.size)
        assertEquals(day(11, 16), cells.first().day)
        assertEquals(day(12, 6), cells.last().day)
        assertEquals(listOf(day(11, 16), day(11, 17), day(12, 4), day(12, 5), day(12, 6)), cells.filter { !it.covered }.map { it.day })
        assertEquals(3, cells.single { it.day == day(11, 23) }.count)
        assertTrue(cells.filter { !it.covered }.all { it.count == 0 })
        assertEquals(emptyList(), overviewCells(emptyList()))
    }

    @Test
    fun aDayLinkOpensThatDayElseTodayElseTheTop() {
        val shown = days.map { it.day }

        assertNull(calendarJumpDay(null, shown, today))
        assertEquals(day(11, 23), calendarJumpDay("2026-11-23", shown, today))
        assertEquals(day(11, 18), calendarJumpDay("2026-11-18", shown, today))
        // Out of range, not a day, or not a real date: today, with no error.
        assertEquals(today, calendarJumpDay("2026-11-17", shown, today))
        assertEquals(today, calendarJumpDay("2026-12-04", shown, today))
        assertEquals(today, calendarJumpDay("next-week", shown, today))
        assertEquals(today, calendarJumpDay("2026-11-31", shown, today))
        assertEquals(today, calendarJumpDay("", shown, today))
        // An old upload that ended before today: a bad link opens at the top.
        val old = listOf(day(11, 10), day(11, 11))
        assertEquals(day(11, 11), calendarJumpDay("2026-11-11", old, today))
        assertNull(calendarJumpDay("2026-11-23", old, today))
    }

    @Test
    fun theCalendarHasItsOwnAddressAndADayParameter() {
        assertEquals("#/calendar", calendarPlace().hash)
        assertEquals("#/calendar?day=2026-11-23", calendarPlace(day(11, 23)).hash)
        assertEquals(View.Calendar, View.of(calendarPlace(day(11, 23))))
        assertEquals("cal-2026-11-23", calendarAnchorId(day(11, 23)))
    }
}
