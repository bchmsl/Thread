package thread.domain.calendar

import thread.domain.time.LocalDay
import thread.domain.time.MINUTES_PER_DAY
import thread.domain.time.WallTime

/** The first hour the meetings timeline always shows (D79): 08:00 in the workspace zone. */
const val TIMELINE_FIRST_HOUR: Int = 8

/** The hour the meetings timeline always shows up to (D79): 18:00 in the workspace zone. */
const val TIMELINE_LAST_HOUR: Int = 18

/**
 * The room a meeting shorter than this takes on the timeline, in minutes. Its block keeps a size that can be clicked,
 * so the next meeting in the same row starts no earlier than this after it, and the hours shown hold it.
 */
const val TIMELINE_MIN_ROOM_MINUTES: Int = 25

/**
 * Today's meetings on a timeline (D79, Settings "Meetings layout: Horizontal"). It shows the hours from [startMinute]
 * to [endMinute], minutes of the day on whole hours: [TIMELINE_FIRST_HOUR] to [TIMELINE_LAST_HOUR], widened to hold
 * every timed meeting of the day. [allDay] sit above the hours. [blocks] are the timed meetings in time order, each in a
 * lane: a meeting that overlaps an earlier one goes to the first lane free at its start. [nowMinute] is now, or null
 * while now is outside the hours shown.
 */
data class MeetingTimeline(
    val startMinute: Int,
    val endMinute: Int,
    val allDay: List<TodayMeeting>,
    val blocks: List<TimelineBlock>,
    val lanes: Int,
    val nowMinute: Int?,
) {
    /** How many hours it shows. */
    val hours: Int get() = (endMinute - startMinute) / 60

    /** Where [minute] of the day sits: 0.0 at [startMinute], 1.0 at [endMinute]. */
    fun offsetOf(minute: Int): Double = (minute - startMinute).toDouble() / (endMinute - startMinute)
}

/**
 * A timed meeting on the timeline. [fromMinute] and [toMinute] are its part of today, minutes of the day: a meeting that
 * started yesterday starts at 0, one that ends tomorrow ends at 1440. [lane] counts from 0.
 */
data class TimelineBlock(
    val meeting: TodayMeeting,
    val fromMinute: Int,
    val toMinute: Int,
    val lane: Int,
)

/** Lays out [today], the rows [todayMeetings] gives for [now]'s day, on a timeline. */
fun meetingTimeline(
    today: List<TodayMeeting>,
    now: WallTime,
): MeetingTimeline {
    val day = now.day
    val timed =
        today
            .filter { it.meeting.time is MeetingTime.Timed }
            .map { row ->
                val from = minuteOn(day, row.meeting.time.startsAt)
                val to = minuteOn(day, row.meeting.time.endsAt)
                Placed(row, from, to, room = minOf(maxOf(to, from + TIMELINE_MIN_ROOM_MINUTES), MINUTES_PER_DAY))
            }
    val laneEnds = mutableListOf<Int>()
    val blocks =
        timed.map { placed ->
            val free = laneEnds.indexOfFirst { it <= placed.from }
            val lane = if (free >= 0) free else laneEnds.size.also { laneEnds += 0 }
            laneEnds[lane] = placed.room
            TimelineBlock(placed.row, placed.from, placed.to, lane)
        }
    val start = minOf(TIMELINE_FIRST_HOUR * 60, timed.minOfOrNull { it.from / 60 * 60 } ?: MINUTES_PER_DAY)
    val end = maxOf(TIMELINE_LAST_HOUR * 60, timed.maxOfOrNull { (it.room + 59) / 60 * 60 } ?: 0)
    return MeetingTimeline(
        startMinute = start,
        endMinute = end,
        allDay = today.filter { it.meeting.time is MeetingTime.AllDay },
        blocks = blocks,
        lanes = laneEnds.size,
        nowMinute = now.minuteOfDay.takeIf { it in start..end },
    )
}

private class Placed(
    val row: TodayMeeting,
    val from: Int,
    val to: Int,
    val room: Int,
)

/** [at] as a minute of [day], kept within it: 0 before the day, 1440 after. */
private fun minuteOn(
    day: LocalDay,
    at: WallTime,
): Int = ((at.day.epochDay - day.epochDay) * MINUTES_PER_DAY + at.minuteOfDay).coerceIn(0L, MINUTES_PER_DAY.toLong()).toInt()
