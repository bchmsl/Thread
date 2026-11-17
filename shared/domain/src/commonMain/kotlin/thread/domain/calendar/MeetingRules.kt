package thread.domain.calendar

import thread.domain.time.LocalDay
import thread.domain.time.MINUTES_PER_DAY
import thread.domain.time.WallTime
import thread.domain.topic.TopicId

/** Where a meeting on Today's Meetings card is, compared with now. */
enum class MeetingPhase {
    /** Over. The row is greyed and says "Ended" to screen readers. */
    Ended,

    /** Started and not over yet. */
    Now,

    /** The next timed meeting to start. Several share it when they start at the same minute. */
    Next,

    /** Starts after the next one. */
    Later,

    /** An all-day meeting that includes today. */
    AllDay,
}

/**
 * One row of Today's Meetings card. [startsBeforeToday] and [endsAfterToday] mark a timed meeting that crosses
 * midnight, so the row can say so instead of showing a time from another day as today's.
 */
data class TodayMeeting(
    val meeting: Meeting,
    val phase: MeetingPhase,
    val startsBeforeToday: Boolean = false,
    val endsAfterToday: Boolean = false,
)

/**
 * The meetings that take place on [now]'s day, all-day ones first, then by start, end, title and id. A timed meeting
 * counts when any part of it is on that day; one with no length counts when it starts on that day (as the Mac app's
 * window does).
 */
fun todayMeetings(
    meetings: List<Meeting>,
    now: WallTime,
): List<TodayMeeting> {
    val today = now.day
    val dayStart = WallTime(today, 0)
    val dayEnd = WallTime(today.plusDays(1), 0)
    val allDay =
        meetings
            .filter { meeting ->
                val time = meeting.time as? MeetingTime.AllDay ?: return@filter false
                today >= time.firstDay && today < time.lastDayExclusive
            }.sortedWith(compareBy<Meeting>({ it.time.startsAt }, { it.displayTitle }, { it.id.value }))
            .map { TodayMeeting(it, MeetingPhase.AllDay) }
    val timed =
        meetings
            .filter { meeting ->
                val time = meeting.time as? MeetingTime.Timed ?: return@filter false
                time.start < dayEnd && (time.end > dayStart || (time.start == time.end && time.start >= dayStart))
            }.sortedWith(compareBy<Meeting>({ it.time.startsAt }, { it.time.endsAt }, { it.displayTitle }, { it.id.value }))
    val nextStart = timed.map { it.time.startsAt }.filter { it > now }.minOrNull()
    return allDay +
        timed.map { meeting ->
            val phase =
                when {
                    hasEnded(meeting.time, now) -> MeetingPhase.Ended
                    meeting.time.startsAt <= now -> MeetingPhase.Now
                    meeting.time.startsAt == nextStart -> MeetingPhase.Next
                    else -> MeetingPhase.Later
                }
            TodayMeeting(
                meeting = meeting,
                phase = phase,
                startsBeforeToday = meeting.time.startsAt < dayStart,
                endsAfterToday = meeting.time.endsAt > dayEnd,
            )
        }
}

/** Over at [now]. A meeting with no length is over the minute after it starts. */
fun hasEnded(
    time: MeetingTime,
    now: WallTime,
): Boolean = if (time.startsAt == time.endsAt) now > time.startsAt else now >= time.endsAt

/** The status line's meeting chip (Q6). */
sealed interface NextMeeting {
    val meeting: Meeting

    /** Starts in [minutes], 1 to [NEXT_MEETING_WINDOW_MINUTES]: "Standup in 12 min". */
    data class Soon(
        override val meeting: Meeting,
        val minutes: Int,
    ) : NextMeeting

    /** Started less than [STARTED_MEETING_MINUTES] ago: "Standup now". */
    data class Started(
        override val meeting: Meeting,
    ) : NextMeeting
}

/** How far ahead the status line looks for the next meeting. A routine choice (plan section 6), adjustable later. */
const val NEXT_MEETING_WINDOW_MINUTES: Int = 60

/** How long the status line keeps saying a meeting is on now after it starts. */
const val STARTED_MEETING_MINUTES: Int = 5

/**
 * What the status line shows at [now], or null for nothing. Only timed meetings that start on [now]'s day count;
 * all-day meetings never do. A meeting that started in the last [STARTED_MEETING_MINUTES] wins over one that starts
 * soon, so the chip does not jump ahead while the owner is joining.
 */
fun nextMeeting(
    meetings: List<Meeting>,
    now: WallTime,
): NextMeeting? {
    val todays =
        meetings
            .filter { it.time is MeetingTime.Timed && it.time.startsAt.day == now.day }
            .sortedWith(compareBy<Meeting>({ it.time.startsAt }, { it.displayTitle }, { it.id.value }))
    // The chip names one meeting: the latest start among those just started, else the soonest one ahead.
    val started = todays.lastOrNull { minutesBetween(it.time.startsAt, now) in 0 until STARTED_MEETING_MINUTES }
    if (started != null) {
        val latestStart = started.time.startsAt
        return NextMeeting.Started(todays.first { it.time.startsAt == latestStart })
    }
    val soon = todays.firstOrNull { minutesBetween(now, it.time.startsAt) in 1..NEXT_MEETING_WINDOW_MINUTES } ?: return null
    return NextMeeting.Soon(soon, minutesBetween(now, soon.time.startsAt))
}

/** How long before the start Join uses the action style. */
const val JOIN_LEAD_MINUTES: Int = 10

/** True from [JOIN_LEAD_MINUTES] before the start until the meeting is over: Join then uses the action style. */
fun isJoinTime(
    meeting: Meeting,
    now: WallTime,
): Boolean = minutesBetween(now, meeting.time.startsAt) <= JOIN_LEAD_MINUTES && !hasEnded(meeting.time, now)

/**
 * True when the last pass that succeeded covered [today], so an empty list can say "No meetings today". Otherwise Today
 * says the meetings are not loaded yet: an empty list then proves nothing.
 */
fun todayKnown(
    source: MeetingSource,
    today: LocalDay,
): Boolean {
    if (source.lastSuccessAtMillis == null) return false
    val from = source.fromDay ?: return false
    val to = source.toDay ?: return false
    return today in from..to
}

/** How old the last successful pass may be before Today asks whether the Mac is awake. */
const val MEETING_SOURCE_STALE_MILLIS: Long = 60L * 60 * 1000

/** True when a pass once succeeded and the last success is more than an hour before [nowMillis]. */
fun isMeetingSourceStale(
    source: MeetingSource,
    nowMillis: Long,
): Boolean {
    val lastSuccess = source.lastSuccessAtMillis ?: return false
    return nowMillis - lastSuccess > MEETING_SOURCE_STALE_MILLIS
}

/**
 * A meeting series linked to a Topic, for Topic Details. [next] is the first occurrence that is not over at the time
 * asked, or null for "None in the next 14 days". [title] comes from the meetings when one is in the window, otherwise
 * from the link's snapshot (QA).
 */
data class TopicMeeting(
    val link: MeetingLink,
    val title: String,
    val next: Meeting?,
)

/**
 * The series linked to [topicId], one row each: those with a next occurrence first, soonest first, then the rest by
 * title. Unlinked links are left out.
 */
fun meetingsForTopic(
    topicId: TopicId,
    links: List<MeetingLink>,
    meetings: List<Meeting>,
    now: WallTime,
): List<TopicMeeting> {
    val bySeries = meetings.groupBy { it.seriesKey }
    val rows =
        links
            .filter { it.topicId == topicId && it.linked }
            .distinctBy { it.seriesKey }
            .map { link ->
                val occurrences = bySeries[link.seriesKey].orEmpty().sortedWith(compareBy<Meeting>({ it.time.startsAt }, { it.id.value }))
                val next = occurrences.firstOrNull { !hasEnded(it.time, now) }
                val latest = next ?: occurrences.lastOrNull()
                val title = latest?.displayTitle ?: link.title?.takeIf { it.isNotBlank() } ?: UNTITLED_MEETING
                TopicMeeting(link, title, next)
            }
    val (upcoming, none) = rows.partition { it.next != null }
    return upcoming.sortedWith(compareBy<TopicMeeting>({ it.next?.time?.startsAt }, { it.title }, { it.link.seriesKey.value })) +
        none.sortedWith(compareBy<TopicMeeting>({ it.title.lowercase() }, { it.link.seriesKey.value }))
}

/** Whole minutes from [from] to [to]; negative when [to] is earlier. */
private fun minutesBetween(
    from: WallTime,
    to: WallTime,
): Int = ((to.day.epochDay - from.day.epochDay) * MINUTES_PER_DAY + to.minuteOfDay - from.minuteOfDay).toInt()
