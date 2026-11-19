package thread.web.presentation.calendar

import thread.domain.calendar.Meeting
import thread.domain.calendar.MeetingPhase
import thread.domain.calendar.MeetingResponse
import thread.domain.calendar.MeetingSourceState
import thread.domain.calendar.MeetingTime
import thread.domain.calendar.MeetingsState
import thread.domain.calendar.NextMeeting
import thread.domain.calendar.TodayMeeting
import thread.domain.calendar.isMeetingSourceStale
import thread.domain.calendar.todayKnown
import thread.domain.time.LocalDay
import thread.domain.time.MINUTES_PER_DAY
import thread.domain.time.WallTime
import thread.domain.time.clockText
import thread.web.presentation.dayText
import thread.web.presentation.shortDate

/*
 * The words meetings show (calendar-plan.md section 6). Plain functions of the domain types, so tests can read them.
 * Visible text is short ("12 min"); what screen readers hear is a full sentence ("12 minutes").
 */

/** Whole minutes from [from] to [to]. */
internal fun minutesBetween(
    from: WallTime,
    to: WallTime,
): Int = ((to.day.epochDay - from.day.epochDay) * MINUTES_PER_DAY + to.minuteOfDay - from.minuteOfDay).toInt()

/** "30 min", "1 h", "1 h 30 min". */
internal fun durationText(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$rest min"
        rest == 0 -> "$hours h"
        else -> "$hours h $rest min"
    }
}

/** "1 minute", "30 minutes", "1 hour", "1 hour 30 minutes", for screen readers. */
internal fun durationWords(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    val hourWords = if (hours == 1) "1 hour" else "$hours hours"
    val minuteWords = if (rest == 1) "1 minute" else "$rest minutes"
    return when {
        hours == 0 -> minuteWords
        rest == 0 -> hourWords
        else -> "$hourWords $minuteWords"
    }
}

/**
 * When a meeting happens, for its card: "Tue 17 Nov, 10:00 to 11:00 (1 h)", "Tue 17 Nov, 10:00" with no length,
 * "Tue 17 Nov 23:00 to Wed 18 Nov 01:00 (2 h)" across midnight, "Tue 17 Nov, all day", and
 * "Tue 17 Nov to Thu 19 Nov, all day" for several days.
 */
internal fun meetingWhenText(time: MeetingTime): String =
    when (time) {
        is MeetingTime.AllDay -> {
            val last = time.lastDayExclusive.plusDays(-1)
            val days = if (last == time.firstDay) shortDate(last) else "${shortDate(time.firstDay)} to ${shortDate(last)}"
            "$days, all day"
        }
        is MeetingTime.Timed -> {
            val start = time.start
            val end = time.end
            val length = minutesBetween(start, end)
            when {
                length == 0 -> "${shortDate(start.day)}, ${clockText(start.minuteOfDay)}"
                start.day == end.day ->
                    "${shortDate(start.day)}, ${clockText(start.minuteOfDay)} to ${clockText(end.minuteOfDay)} (${durationText(length)})"
                else ->
                    "${shortDate(start.day)} ${clockText(start.minuteOfDay)} to ${shortDate(end.day)} ${clockText(end.minuteOfDay)} " +
                        "(${durationText(length)})"
            }
        }
    }

/** "Tentative" or "Not answered", or null when there is nothing to say about the answer. */
internal fun responseText(response: MeetingResponse): String? =
    when (response) {
        MeetingResponse.Tentative -> "Tentative"
        MeetingResponse.Pending -> "Not answered"
        MeetingResponse.Accepted, MeetingResponse.None -> null
    }

/** What a row says after its title, compared with [now]: "Ends in 12 min", "Starts in 1 h 5 min", or null. */
internal fun rowRelativeText(
    row: TodayMeeting,
    now: WallTime,
): String? =
    when (row.phase) {
        MeetingPhase.Now -> "Ends in ${durationText(minutesBetween(now, row.meeting.time.endsAt))}"
        MeetingPhase.Next -> "Starts in ${durationText(minutesBetween(now, row.meeting.time.startsAt))}"
        else -> null
    }

/** The same as [rowRelativeText], in words for screen readers. */
private fun rowRelativeWords(
    row: TodayMeeting,
    now: WallTime,
): String? =
    when (row.phase) {
        MeetingPhase.Now -> "ends in ${durationWords(minutesBetween(now, row.meeting.time.endsAt))}"
        MeetingPhase.Next -> "starts in ${durationWords(minutesBetween(now, row.meeting.time.startsAt))}"
        else -> null
    }

/** "Started yesterday" or "Ends tomorrow" for a timed meeting that crosses midnight, else null. */
internal fun rowSpanText(
    row: TodayMeeting,
    today: LocalDay,
): String? =
    when {
        row.startsBeforeToday -> "Started ${dayText(row.meeting.time.startsAt.day, today, mid = true)}"
        row.endsAfterToday -> "Ends ${dayText(row.meeting.time.endsAt.day, today, mid = true)}"
        else -> null
    }

/** The row's time in words: "10:00 to 11:00", "All day", "Yesterday 22:00 to 01:00", "23:00 to tomorrow 01:00". */
private fun rowTimeWords(
    row: TodayMeeting,
    today: LocalDay,
): String {
    val time = row.meeting.time
    if (time is MeetingTime.AllDay) return "All day"
    val start = clockText(time.startsAt.minuteOfDay)
    val end = clockText(time.endsAt.minuteOfDay)
    return when {
        time.startsAt == time.endsAt -> start
        row.startsBeforeToday -> "${dayText(time.startsAt.day, today)} $start to $end"
        row.endsAfterToday -> "$start to ${dayText(time.endsAt.day, today, mid = true)} $end"
        else -> "$start to $end"
    }
}

/**
 * The row as one sentence, its accessible name: "10:00 to 11:00, Sprint planning, now, ends in 12 minutes". Then the
 * answer, the place and the Topics its series is linked to ([topics], names), when there are any.
 */
internal fun meetingRowLabel(
    row: TodayMeeting,
    now: WallTime,
    topics: List<String> = emptyList(),
): String {
    val phase =
        when (row.phase) {
            MeetingPhase.Ended -> "ended"
            MeetingPhase.Now -> "now"
            MeetingPhase.Next -> "next"
            MeetingPhase.Later, MeetingPhase.AllDay -> null
        }
    return listOfNotNull(
        rowTimeWords(row, now.day),
        row.meeting.displayTitle,
        phase,
        rowRelativeWords(row, now),
        responseText(row.meeting.response)?.lowercase(),
        row.meeting.location,
        linkedToText(topics),
    ).joinToString(", ")
}

/** "linked to QR payments", "linked to A and B", "linked to A, B and C", or null for no Topic. */
internal fun linkedToText(topics: List<String>): String? =
    when (topics.size) {
        0 -> null
        1 -> "linked to ${topics[0]}"
        else -> "linked to ${topics.dropLast(1).joinToString(", ")} and ${topics.last()}"
    }

/**
 * When an occurrence starts, for Topic Details and search: "Today, 10:00", "Thu 20 Nov, 10:00", "Tomorrow, all day".
 * [today] is the workspace's day.
 */
internal fun occurrenceText(
    time: MeetingTime,
    today: LocalDay,
): String =
    when (time) {
        is MeetingTime.AllDay -> "${dayText(time.firstDay, today)}, all day"
        is MeetingTime.Timed -> "${dayText(time.start.day, today)}, ${clockText(time.start.minuteOfDay)}"
    }

/** Shown for a linked series with no occurrence ahead in the window. */
internal const val NO_NEXT_MEETING: String = "None in the next 14 days"

/**
 * A linked series' next occurrence on Topic Details: "Now, until 10:30" while it runs, "Today, all day" for an all-day
 * one that includes today, else [occurrenceText], or [NO_NEXT_MEETING].
 */
internal fun nextOccurrenceText(
    next: Meeting?,
    now: WallTime,
): String {
    if (next == null) return NO_NEXT_MEETING
    val time = next.time
    if (time is MeetingTime.AllDay && time.firstDay <= now.day) return "Today, all day"
    if (time is MeetingTime.Timed && time.start <= now) {
        val end = time.end
        val clock = clockText(end.minuteOfDay)
        return "Now, until ${if (end.day == now.day) clock else "${dayText(end.day, now.day, mid = true)} $clock"}"
    }
    return occurrenceText(time, now.day)
}

/** The status line chip's text: "Standup in 12 min" or "Standup now". */
internal fun chipText(next: NextMeeting): String = "${next.meeting.displayTitle} ${chipWhenText(next)}"

/** The chip's text after the title, which may be cut short: "in 12 min" or "now". */
internal fun chipWhenText(next: NextMeeting): String =
    when (next) {
        is NextMeeting.Soon -> "in ${durationText(next.minutes)}"
        is NextMeeting.Started -> "now"
    }

/** The chip on a narrow screen: "12m" or "Now". */
internal fun chipShortText(next: NextMeeting): String =
    when (next) {
        is NextMeeting.Soon -> "${next.minutes}m"
        is NextMeeting.Started -> "Now"
    }

/** The chip's accessible name, a full sentence: "Next meeting: Standup, starts in 12 minutes. Opens the meeting." */
internal fun chipLabel(next: NextMeeting): String {
    val meeting = next.meeting
    val sentence =
        when (next) {
            is NextMeeting.Soon -> "Next meeting: ${meeting.displayTitle}, starts in ${durationWords(next.minutes)}."
            is NextMeeting.Started -> "Meeting now: ${meeting.displayTitle}, started at ${clockText(meeting.time.startsAt.minuteOfDay)}."
        }
    return "$sentence Opens the meeting."
}

/** "10:42", "yesterday 22:10" or "Sat 15 Nov 22:10": when the Mac app last uploaded, compared with [today]. */
internal fun lastUpdateText(
    at: WallTime,
    today: LocalDay,
): String = if (at.day == today) clockText(at.minuteOfDay) else "${dayText(at.day, today, mid = true)} ${clockText(at.minuteOfDay)}"

/** "Updated from your Mac at 10:42", or "… yesterday 22:10" when the last upload was on another day. */
internal fun updatedText(
    at: WallTime,
    today: LocalDay,
): String = "Updated from your Mac ${if (at.day == today) "at " else ""}${lastUpdateText(at, today)}"

/** The stale hint: "Not updated since 08:10. Is your Mac awake and Thread Calendar running?" */
internal fun staleText(
    at: WallTime,
    today: LocalDay,
): String = "Not updated since ${lastUpdateText(at, today)}. Is your Mac awake and Thread Calendar running?"

/** "Join (Teams)", or plain "Join" for a link set in Thread that is not on the allowlist (D100). */
internal fun joinLabel(service: String?): String = service?.let { "Join ($it)" } ?: "Join"

/** The line at the top of the Meetings card: a problem ([warning]) or why the list is empty. */
internal data class MeetingsNotice(
    val text: String,
    val warning: Boolean,
    /** The text already says when the Mac app last uploaded, so the card leaves out its "Updated" line. */
    val saysTime: Boolean = false,
)

/**
 * What the Meetings card says above its rows, or null when the rows speak for themselves. A problem on the Mac wins
 * over a stale upload, which wins over an empty list. [lastSuccess] is the source's last success in the workspace zone.
 * [pastHidden]: Settings left out ended meetings (D86), so an empty list means none is left, not none at all. [page]: the
 * line above the Calendar page (D102), which is about every day, and whose days say themselves when they have none.
 */
internal fun meetingsNotice(
    state: MeetingsState,
    hasRows: Boolean,
    today: LocalDay,
    nowMillis: Long,
    lastSuccess: WallTime?,
    pastHidden: Boolean = false,
    page: Boolean = false,
): MeetingsNotice? {
    val source =
        when (state) {
            is MeetingsState.Ready -> state.source
            MeetingsState.NeedsUpdate ->
                return MeetingsNotice("Update Thread to see meetings. Reload the page to get the latest version.", false)
            is MeetingsState.Unavailable -> return MeetingsNotice("Meetings are unavailable. Reload the page to try again.", true)
            MeetingsState.Loading, MeetingsState.NotSetUp -> return null
        }
    return when {
        source.state == MeetingSourceState.NoAccess ->
            MeetingsNotice("Thread Calendar on your Mac has no calendar access. Open its menu and choose Allow calendar access.", true)
        source.state == MeetingSourceState.NoCalendar ->
            MeetingsNotice("Thread Calendar on your Mac has no calendar picked. Pick one in its menu under Calendar.", true)
        source.state == MeetingSourceState.Paused -> MeetingsNotice("Syncing is paused in Thread Calendar on your Mac.", true)
        lastSuccess != null && isMeetingSourceStale(source, nowMillis) ->
            MeetingsNotice(staleText(lastSuccess, today), true, saysTime = true)
        !todayKnown(source, today) ->
            MeetingsNotice(if (page) "Meetings are not loaded yet." else "Meetings for today are not loaded yet.", false)
        page -> null
        !hasRows -> MeetingsNotice(if (pastHidden) "No more meetings today." else "No meetings today.", false)
        else -> null
    }
}
