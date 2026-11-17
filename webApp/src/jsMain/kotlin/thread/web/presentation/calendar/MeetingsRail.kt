package thread.web.presentation.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.TagElement
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.HTMLElement
import thread.domain.calendar.Meeting
import thread.domain.calendar.MeetingPhase
import thread.domain.calendar.MeetingTime
import thread.domain.calendar.MeetingsState
import thread.domain.calendar.SeriesKey
import thread.domain.calendar.TodayMeeting
import thread.domain.calendar.isJoinTime
import thread.domain.calendar.joinServiceOf
import thread.domain.calendar.todayMeetings
import thread.domain.time.WallTime
import thread.domain.time.clockText
import thread.domain.topic.Topic
import thread.web.platform.WallClock
import thread.web.presentation.CardKind
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalNow
import thread.web.presentation.SectionCard
import thread.web.presentation.itemCard
import thread.web.presentation.topics.PlainTopicTag

/**
 * Today's Meetings card (D78, calendar-plan.md section 6): today's meetings from the Mac app, all-day first, then by
 * time. Each row opens the Meeting card; Join opens the meeting link in a new tab. Ended rows are greyed and say
 * "Ended" to screen readers. One status region per card says what is wrong or why the list is empty; the time of the
 * Mac app's last upload closes the card.
 */
@Composable
internal fun MeetingsRail(
    model: MeetingsModel,
    zone: String,
    topicsOf: (SeriesKey) -> List<Topic>,
    onOpen: (Meeting) -> Unit,
) {
    val now = LocalNow.current
    val state = model.state
    val rows = if (state is MeetingsState.Ready) todayMeetings(state.meetings, now) else emptyList()
    val lastSuccess = (state as? MeetingsState.Ready)?.source?.lastSuccessAtMillis?.let { WallClock.wallTimeAt(it.toDouble(), zone) }
    val notice = meetingsNotice(state, rows.isNotEmpty(), now.day, WallClock.nowMillis().toLong(), lastSuccess)
    SectionCard(
        kind = CardKind.Meeting,
        headingId = "mtg-h",
        bodyId = "mtg-b",
        title = "Meetings",
        count = if (state is MeetingsState.Ready) rows.size.toString() else null,
        attrs = { classes("scard--meet") },
    ) {
        // Always present, so a change of state is announced; empty while the rows say it all.
        P({
            classes("mtg__note")
            if (notice?.warning == true) classes("is-warn")
            attr("role", "status")
        }) {
            when {
                state == MeetingsState.Loading -> Span({ classes("vh") }) { Text("Loading meetings") }
                notice != null -> {
                    if (notice.warning) IconView(Icon.Alert, 15)
                    Span { Text(notice.text) }
                }
            }
        }
        if (state == MeetingsState.Loading || state == MeetingsState.NotSetUp) {
            LoadingRows()
            return@SectionCard
        }
        if (rows.isNotEmpty()) {
            Ul({ classes("tlist", "mtg-list") }) {
                rows.forEach { row -> key(row.meeting.id.value) { MeetingRow(row, now, topicsOf(row.meeting.seriesKey), onOpen) } }
            }
        }
        if (lastSuccess != null && notice?.saysTime != true) {
            P({ classes("mtg__foot") }) { Text(updatedText(lastSuccess, now.day)) }
        }
    }
}

/** Two grey rows while the first answer loads (aria-busy), so the card keeps its height. */
@Composable
private fun LoadingRows() {
    Ul({
        classes("tlist", "mtg-list")
        attr("aria-busy", "true")
        attr("aria-hidden", "true")
    }) {
        repeat(2) {
            Li({ classes("icard", "mrow", "mrow--skel") }) {
                Span({ classes("skel", "skel--time") })
                Span({ classes("skel", "skel--text") })
            }
        }
    }
}

/**
 * One meeting: the time leads, like a calendar's block, then the title, then Now or Next with how long until it starts
 * or ends and the answer, then the place on one line, then the Topics its series is linked to ([topics], D78 P5). The
 * title keeps the row's full width: the rail is narrow. The row is one button whose name is the whole sentence
 * ([meetingRowLabel]), so the Topic tags inside it are not links.
 */
@Composable
private fun MeetingRow(
    row: TodayMeeting,
    now: WallTime,
    topics: List<Topic>,
    onOpen: (Meeting) -> Unit,
) {
    val meeting = row.meeting
    val time = meeting.time
    Li {
        Div({
            itemCard(CardKind.Meeting)
            classes("mrow")
            when (row.phase) {
                MeetingPhase.Ended -> classes("is-ended")
                MeetingPhase.Now -> classes("is-now")
                else -> Unit
            }
        }) {
            Button({
                type(ButtonType.Button)
                classes("mrow__open")
                attr("aria-label", meetingRowLabel(row, now, topics.map { it.name }))
                onClick { onOpen(meeting) }
            }) {
                Span({ classes("mrow__time") }) {
                    if (time is MeetingTime.AllDay) {
                        Span({ classes("mrow__allday") }) { Text("All day") }
                    } else {
                        TimeTag(time.startsAt, "mrow__start")
                        if (time.endsAt != time.startsAt) TimeTag(time.endsAt, "mrow__end")
                    }
                }
                Span({ classes("mrow__main") }) {
                    Span({ classes("mrow__name") }) { Text(meeting.displayTitle) }
                    val badge =
                        when (row.phase) {
                            MeetingPhase.Now -> "Now"
                            MeetingPhase.Next -> "Next"
                            else -> null
                        }
                    val meta = listOfNotNull(rowRelativeText(row, now), rowSpanText(row, now.day), responseText(meeting.response))
                    if (badge != null || meta.isNotEmpty()) {
                        Span({ classes("mrow__meta") }) {
                            if (badge != null) {
                                Span({
                                    classes("mbadge")
                                    if (row.phase == MeetingPhase.Now) classes("mbadge--now")
                                }) { Text(badge) }
                            }
                            meta.forEach { part -> Span { Text(part) } }
                        }
                    }
                    meeting.location?.let { location -> Span({ classes("mrow__where") }) { Text(location) } }
                    if (topics.isNotEmpty()) {
                        Span({ classes("mrow__topics") }) { topics.forEach { PlainTopicTag(it) } }
                    }
                }
            }
            if (row.phase != MeetingPhase.Ended) JoinLink(meeting, now, small = true)
        }
    }
}

/**
 * The meeting's Join link, when it has one on the allowlist: "Join (Teams)", in a new tab, without the opener or the
 * referrer. The link itself is never shown as text: it may hold a passcode. It uses the action style from
 * [thread.domain.calendar.JOIN_LEAD_MINUTES] before the start until the end.
 */
@Composable
internal fun JoinLink(
    meeting: Meeting,
    now: WallTime,
    small: Boolean,
) {
    val url = meeting.joinUrl ?: return
    val service = joinServiceOf(url) ?: return
    val label = joinLabel(service.label)
    A(url, {
        classes("btn", if (isJoinTime(meeting, now)) "btn--primary" else "btn--line", "mjoin")
        if (small) classes("mjoin--row")
        target(ATarget.Blank)
        attr("rel", "noopener noreferrer")
        // A row's Join names its meeting, so a list of Join links can be told apart.
        if (small) attr("aria-label", "$label, ${meeting.displayTitle}")
    }) {
        // A row is narrow: it shows "Join", and its name says the service.
        Text(if (small) "Join" else label)
    }
}

/** A `<time>` with its machine-readable value, `2026-11-17T10:00`. */
@Composable
private fun TimeTag(
    at: WallTime,
    className: String,
) {
    TagElement<HTMLElement>("time", {
        classes(className)
        attr("datetime", at.toString())
    }) { Text(clockText(at.minuteOfDay)) }
}
