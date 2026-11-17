package thread.domain.calendar

import thread.domain.time.LocalDay
import thread.domain.time.MINUTES_PER_DAY
import thread.domain.time.WallTime

/** The first hour the meetings timeline always shows (D79): 08:00 in the workspace zone. */
const val TIMELINE_FIRST_HOUR: Int = 8

/** The hour the meetings timeline always shows up to (D79): 18:00 in the workspace zone. */
const val TIMELINE_LAST_HOUR: Int = 18

/**
 * A meeting whose part of today is shorter than this, in minutes, draws as a one-line chip this long (D80): a block
 * at its real length would be too narrow for its title. The chip starts at the meeting's start, or ends at 24:00 when
 * that start is less than this before midnight, and shows the real length inside it.
 */
const val TIMELINE_CHIP_MINUTES: Int = 60

/**
 * Today's meetings on a timeline (D79, Settings "Meetings layout: Horizontal"). It shows the hours from [startMinute]
 * to [endMinute], minutes of the day on whole hours: [TIMELINE_FIRST_HOUR] to [TIMELINE_LAST_HOUR], widened to hold
 * every timed meeting as drawn. [allDay] sit above the hours. [blocks] are the timed meetings in time order, each in a
 * row (D80): a chip takes one row and a block two, and a meeting goes to the first rows free at the start of its drawn
 * part, so nothing drawn overlaps. [rows] counts the rows in use. [nowMinute] is now, or null while now is outside the
 * hours shown.
 */
data class MeetingTimeline(
    val startMinute: Int,
    val endMinute: Int,
    val allDay: List<TodayMeeting>,
    val blocks: List<TimelineBlock>,
    val rows: Int,
    val nowMinute: Int?,
) {
    /** How many hours it shows. */
    val hours: Int get() = (endMinute - startMinute) / 60

    /** Where [minute] of the day sits: 0.0 at [startMinute], 1.0 at [endMinute]. */
    fun offsetOf(minute: Int): Double = (minute - startMinute).toDouble() / (endMinute - startMinute)
}

/**
 * A timed meeting on the timeline. [fromMinute] and [toMinute] are its part of today, minutes of the day: a meeting that
 * started yesterday starts at 0, one that ends tomorrow ends at 1440. [drawnFromMinute] and [drawnToMinute] are where it
 * is drawn: the same for a block, [TIMELINE_CHIP_MINUTES] for a [chip]. [row] counts from 0; a block also takes the
 * row under it.
 */
data class TimelineBlock(
    val meeting: TodayMeeting,
    val fromMinute: Int,
    val toMinute: Int,
    val drawnFromMinute: Int,
    val drawnToMinute: Int,
    val row: Int,
    val chip: Boolean,
) {
    /** How many rows it takes: one for a chip, two for a block. */
    val height: Int get() = if (chip) 1 else 2
}

/** Lays out [today], the rows [todayMeetings] gives for [now]'s day, on a timeline. */
fun meetingTimeline(
    today: List<TodayMeeting>,
    now: WallTime,
): MeetingTimeline {
    val day = now.day
    // The drawn end of the last meeting in each row: a row is free from then on.
    val rowEnds = mutableListOf<Int>()
    val blocks =
        today
            .filter { it.meeting.time is MeetingTime.Timed }
            .map { row ->
                val from = minuteOn(day, row.meeting.time.startsAt)
                val to = minuteOn(day, row.meeting.time.endsAt)
                val chip = to - from < TIMELINE_CHIP_MINUTES
                val drawnFrom = if (chip) minOf(from, MINUTES_PER_DAY - TIMELINE_CHIP_MINUTES) else from
                val drawnTo = if (chip) drawnFrom + TIMELINE_CHIP_MINUTES else to
                val height = if (chip) 1 else 2
                val first = generateSequence(0) { it + 1 }.first { top -> (top until top + height).all { rowEnds.freeAt(it, drawnFrom) } }
                for (taken in first until first + height) {
                    if (taken < rowEnds.size) rowEnds[taken] = drawnTo else rowEnds += drawnTo
                }
                TimelineBlock(row, from, to, drawnFrom, drawnTo, first, chip)
            }
    val start = minOf(TIMELINE_FIRST_HOUR * 60, blocks.minOfOrNull { it.drawnFromMinute / 60 * 60 } ?: MINUTES_PER_DAY)
    val end = maxOf(TIMELINE_LAST_HOUR * 60, blocks.maxOfOrNull { (it.drawnToMinute + 59) / 60 * 60 } ?: 0)
    return MeetingTimeline(
        startMinute = start,
        endMinute = end,
        allDay = today.filter { it.meeting.time is MeetingTime.AllDay },
        blocks = blocks,
        rows = rowEnds.size,
        nowMinute = now.minuteOfDay.takeIf { it in start..end },
    )
}

/** Whether row [index] is free at [minute]: nothing drawn in it yet, or its last meeting is drawn up to then. */
private fun List<Int>.freeAt(
    index: Int,
    minute: Int,
): Boolean = index >= size || this[index] <= minute

/** [at] as a minute of [day], kept within it: 0 before the day, 1440 after. */
private fun minuteOn(
    day: LocalDay,
    at: WallTime,
): Int = ((at.day.epochDay - day.epochDay) * MINUTES_PER_DAY + at.minuteOfDay).coerceIn(0L, MINUTES_PER_DAY.toLong()).toInt()
