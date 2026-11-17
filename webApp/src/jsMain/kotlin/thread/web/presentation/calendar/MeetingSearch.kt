package thread.web.presentation.calendar

import thread.domain.calendar.Meeting
import thread.domain.calendar.MeetingId
import thread.domain.calendar.SeriesKey
import thread.domain.time.WallTime
import thread.domain.topic.Topic
import thread.web.presentation.CardKind
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.shell.SearchOption

/**
 * The "Meetings" results of global search (D78 P5b, calendar-plan.md section 6): the title, then when the occurrence
 * is and the Topics its series is linked to. No Join here: a pick opens the Meeting card ([onOpen]), which has it. The
 * join link is never shown or matched.
 */
internal fun meetingSearchOptions(
    found: List<Meeting>,
    topicsOf: (SeriesKey) -> List<Topic>,
    now: WallTime,
    onOpen: (MeetingId) -> Unit,
): List<SearchOption> =
    found.map { meeting ->
        SearchOption(
            meeting.displayTitle,
            meetingSearchSub(meeting, topicsOf(meeting.seriesKey).map { it.name }, now),
            { IconView(Icon.Calendar, 18) },
            kind = CardKind.Meeting,
        ) { onOpen(meeting.id) }
    }

/** "Thu 20 Nov, 10:00", then the linked Topics: "Tomorrow, all day · QR payments, Cards". */
internal fun meetingSearchSub(
    meeting: Meeting,
    topics: List<String>,
    now: WallTime,
): String {
    val time = occurrenceText(meeting.time, now.day)
    return if (topics.isEmpty()) time else "$time · ${topics.joinToString(", ")}"
}
