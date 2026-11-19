package thread.web.presentation.calendar

import thread.domain.calendar.CalendarDay
import thread.domain.time.LocalDay
import thread.web.presentation.Place
import thread.web.presentation.View
import thread.web.presentation.dayMonth
import thread.web.presentation.shortDate
import thread.web.presentation.weekdayShort

/** `#/calendar?day=2026-11-23` opens the Calendar page at that day (D102). */
internal const val DAY_PARAM: String = "day"

/** The Calendar page, at [day] when given. */
internal fun calendarPlace(day: LocalDay? = null): Place = View.Calendar.place.with(DAY_PARAM, day?.toString())

/** The element a link to [day] scrolls to and focuses: a day's heading or the first day of a run with no meetings. */
internal fun calendarAnchorId(day: LocalDay): String = "cal-$day"

/** A piece of a week on the Calendar page. */
internal sealed interface CalendarPart {
    /** The days this piece shows. */
    val days: List<LocalDay>

    /** [calendarAnchorId] of the piece: links to any of its [days] go here. */
    val anchorId: String get() = calendarAnchorId(days.first())

    /** A day with meetings, or today: a heading and its rows. */
    data class Day(
        val day: CalendarDay,
    ) : CalendarPart {
        override val days: List<LocalDay> get() = listOf(day.day)
    }

    /** Covered days in a row with no meetings: one quiet line, not one empty day each. */
    data class Gap(
        override val days: List<LocalDay>,
    ) : CalendarPart
}

/** One week card: its covered days, Monday first, as [parts]. */
internal data class CalendarWeek(
    val monday: LocalDay,
    val title: String,
    /** The covered days' range: "Wed 18 to Sun 22 Nov". */
    val hint: String,
    val count: Int,
    val parts: List<CalendarPart>,
)

/**
 * [days] (from [thread.domain.calendar.calendarDays]) as week cards, Monday first. Today always shows as a day, even
 * with no meetings; any other covered days in a row with no meetings merge into one [CalendarPart.Gap].
 */
internal fun calendarWeeks(
    days: List<CalendarDay>,
    today: LocalDay,
): List<CalendarWeek> =
    days
        .groupBy { mondayOf(it.day) }
        .map { (monday, week) ->
            val parts = mutableListOf<CalendarPart>()
            val run = mutableListOf<LocalDay>()

            fun flush() {
                if (run.isNotEmpty()) parts += CalendarPart.Gap(run.toList())
                run.clear()
            }
            week.forEach { day ->
                if (day.rows.isEmpty() && day.day != today) {
                    run += day.day
                } else {
                    flush()
                    parts += CalendarPart.Day(day)
                }
            }
            flush()
            CalendarWeek(
                monday = monday,
                title = weekTitle(monday, mondayOf(today)),
                hint = dayRangeText(week.first().day, week.last().day, "to"),
                count = week.sumOf { it.rows.size },
                parts = parts,
            )
        }

/** The Monday of [day]'s week. */
internal fun mondayOf(day: LocalDay): LocalDay = day.plusDays(1 - day.dayOfWeek)

/** "This week", "Next week", "Last week", else "Week of 30 Nov". */
internal fun weekTitle(
    monday: LocalDay,
    thisMonday: LocalDay,
): String =
    when (monday.epochDay - thisMonday.epochDay) {
        0L -> "This week"
        7L -> "Next week"
        -7L -> "Last week"
        else -> "Week of ${dayMonth(monday)}"
    }

/**
 * Days from [first] to [last]: "Sat 21 Nov" for one, "Sat 21 and Sun 22 Nov" or "Wed 18 to Sun 22 Nov" in one month
 * ([joiner] is "and" or "to"), "Mon 30 Nov to Tue 1 Dec" across months.
 */
internal fun dayRangeText(
    first: LocalDay,
    last: LocalDay,
    joiner: String,
): String =
    when {
        first == last -> shortDate(first)
        first.year == last.year && first.month == last.month -> "${weekdayShort(first)} ${first.day} $joiner ${shortDate(last)}"
        else -> "${shortDate(first)} $joiner ${shortDate(last)}"
    }

/** A run of days with no meetings: "Sat 21 and Sun 22 Nov" for two, "Fri 27 to Sun 29 Nov" for more. */
internal fun gapText(days: List<LocalDay>): String = dayRangeText(days.first(), days.last(), if (days.size == 2) "and" else "to")

/** "1 meeting", "3 meetings", "No meetings". */
internal fun meetingCountText(count: Int): String =
    when (count) {
        0 -> "No meetings"
        1 -> "1 meeting"
        else -> "$count meetings"
    }

/** A day link's accessible name in the overview: "Mon 23 Nov, 3 meetings", "Thu 19 Nov, today, no meetings". */
internal fun overviewDayLabel(
    day: LocalDay,
    count: Int,
    today: LocalDay,
): String = listOfNotNull(shortDate(day), "today".takeIf { day == today }, meetingCountText(count).lowercase()).joinToString(", ")

/** Closes the list, after [last], the last day the Mac uploaded. */
internal fun calendarEndText(last: LocalDay): String = "Nothing after ${shortDate(last)} yet. Your Mac uploads up to two weeks ahead."

/** A cell of the overview: a covered day ([count] its meetings) or a day of the same weeks the Mac did not upload. */
internal data class OverviewCell(
    val day: LocalDay,
    val covered: Boolean,
    val count: Int,
)

/** The overview's cells: whole weeks, Monday to Sunday, around [days]. Empty when there are no days. */
internal fun overviewCells(days: List<CalendarDay>): List<OverviewCell> {
    if (days.isEmpty()) return emptyList()
    val byDay = days.associateBy { it.day }
    val first = mondayOf(days.first().day)
    val last = mondayOf(days.last().day).plusDays(6)
    return (first.epochDay..last.epochDay).map { epochDay ->
        val day = LocalDay.fromEpochDay(epochDay)
        val covered = byDay[day]
        OverviewCell(day, covered != null, covered?.rows?.size ?: 0)
    }
}

/**
 * The day `?day=` [param] opens the page at: that day when the page shows it; else today, for a bad or old link, when
 * the page shows today; else none. Null [param]: the page opens at its top.
 */
internal fun calendarJumpDay(
    param: String?,
    shown: Collection<LocalDay>,
    today: LocalDay,
): LocalDay? {
    if (param == null) return null
    return LocalDay.parse(param)?.takeIf { it in shown } ?: today.takeIf { it in shown }
}
