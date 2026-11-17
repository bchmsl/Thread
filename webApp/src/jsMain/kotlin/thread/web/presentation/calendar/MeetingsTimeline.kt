package thread.web.presentation.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import thread.domain.calendar.Meeting
import thread.domain.calendar.MeetingPhase
import thread.domain.calendar.MeetingResponse
import thread.domain.calendar.MeetingTimeline
import thread.domain.calendar.MeetingsState
import thread.domain.calendar.SeriesKey
import thread.domain.calendar.TimelineBlock
import thread.domain.calendar.TodayMeeting
import thread.domain.calendar.joinServiceOf
import thread.domain.calendar.meetingTimeline
import thread.domain.time.WallTime
import thread.domain.time.clockText
import thread.domain.topic.Topic
import thread.web.presentation.CardKind
import thread.web.presentation.LocalNow
import thread.web.presentation.SectionCard
import thread.web.presentation.itemCard
import kotlin.math.round

/**
 * Today's meetings as a timeline across the page (D79, Settings "Meetings layout: Horizontal"). It is the Meetings card
 * of [MeetingsRail], with the same note and footer, and its body runs the day's hours from left to right
 * ([meetingTimeline]): all-day meetings in a strip above the hours, timed ones as blocks placed by start and end, in
 * lanes while they overlap, and a line at now. A block is one button named by the row's sentence ([meetingRowLabel]);
 * it opens the Meeting card. Blocks come in time order, so Tab follows the day. Join for the meeting now and the next
 * one sits in the card's head, since a block is too narrow for it. A narrow screen scrolls the hours sideways inside the
 * card, starting at now; the page never scrolls sideways.
 */
@Composable
internal fun MeetingsTimeline(
    model: MeetingsModel,
    zone: String,
    topicsOf: (SeriesKey) -> List<Topic>,
    onOpen: (Meeting) -> Unit,
) {
    val now = LocalNow.current
    val view = todayMeetingsView(model.state, now, zone)
    val joinable =
        view.rows.filter { row ->
            (row.phase == MeetingPhase.Now || row.phase == MeetingPhase.Next) && row.meeting.joinUrl?.let(::joinServiceOf) != null
        }
    val joins: (@Composable () -> Unit)? =
        if (joinable.isEmpty()) {
            null
        } else {
            { joinable.forEach { row -> key(row.meeting.id.value) { JoinLink(row.meeting, now, small = false, named = true) } } }
        }
    SectionCard(
        kind = CardKind.Meeting,
        headingId = "mtg-h",
        bodyId = "mtg-b",
        title = "Meetings",
        count = view.count,
        attrs = { classes("scard--meet", "scard--tl") },
        actions = joins,
    ) {
        MeetingsNote(view)
        when {
            view.loading -> LoadingTrack()
            view.state is MeetingsState.Ready -> {
                val line = meetingTimeline(view.rows, now)
                if (line.allDay.isNotEmpty()) AllDayStrip(line.allDay, now, topicsOf, onOpen)
                Track(line, now, topicsOf, onOpen)
            }
            else -> Unit
        }
        MeetingsFoot(view, now.day)
    }
}

/** All-day meetings: one small card each, above the hours. They wrap; they never scroll. */
@Composable
private fun AllDayStrip(
    rows: List<TodayMeeting>,
    now: WallTime,
    topicsOf: (SeriesKey) -> List<Topic>,
    onOpen: (Meeting) -> Unit,
) {
    Ul({ classes("mtl__allday") }) {
        rows.forEach { row ->
            key(row.meeting.id.value) {
                Li {
                    Button({
                        type(ButtonType.Button)
                        itemCard(CardKind.Meeting)
                        classes("mday")
                        if (row.meeting.response.isOpen) classes("is-open")
                        attr("aria-label", meetingRowLabel(row, now, topicsOf(row.meeting.seriesKey).map { it.name }))
                        onClick { onOpen(row.meeting) }
                    }) {
                        Span({ classes("mday__k") }) { Text("All day") }
                        Span({ classes("mday__name") }) { Text(row.meeting.displayTitle) }
                    }
                }
            }
        }
    }
}

/**
 * The hours, their meetings and now, inside a box that scrolls sideways when the card is narrower than the hours need
 * (88 px each, css/cards.css). The box starts with now about a third from its left edge, again when the hours change.
 */
@Composable
private fun Track(
    line: MeetingTimeline,
    now: WallTime,
    topicsOf: (SeriesKey) -> List<Topic>,
    onOpen: (Meeting) -> Unit,
) {
    val nowOffset = line.nowMinute?.let(line::offsetOf)
    // A new box for new hours (a meeting across midnight came or went), so it starts at now again.
    key(line.startMinute, line.endMinute) { TrackBox(line, nowOffset, now, topicsOf, onOpen) }
}

@Composable
private fun TrackBox(
    line: MeetingTimeline,
    nowOffset: Double?,
    now: WallTime,
    topicsOf: (SeriesKey) -> List<Topic>,
    onOpen: (Meeting) -> Unit,
) {
    Div({
        classes("mtl")
        style { property("--hours", line.hours.toString()) }
        ref { element ->
            // Once, when the box appears: later minutes leave the place the person scrolled to.
            if (nowOffset != null) {
                window.requestAnimationFrame {
                    val room = element.scrollWidth - element.clientWidth
                    if (room > 0) {
                        element.scrollLeft = (element.scrollWidth * nowOffset - element.clientWidth / 3.0).coerceIn(0.0, room.toDouble())
                    }
                }
            }
            onDispose { }
        }
    }) {
        Div({ classes("mtl__track") }) {
            Div({
                classes("mtl__ruler")
                attr("aria-hidden", "true")
            }) {
                repeat(line.hours) { hour -> Span({ classes("mtl__hour") }) { Text(clockText(line.startMinute + hour * 60)) } }
            }
            Ul({
                classes("mtl__lanes")
                style { property("--lanes", maxOf(1, line.lanes).toString()) }
            }) {
                line.blocks.forEach { block ->
                    key(block.meeting.meeting.id.value) { Block(block, line, now, topicsOf(block.meeting.meeting.seriesKey), onOpen) }
                }
            }
            if (nowOffset != null) NowLine(nowOffset, now)
        }
    }
}

/**
 * One timed meeting: its title, then Now or Next and when it starts. Placed by start and end; a part that runs into
 * yesterday or tomorrow has a square edge on that side. Now is tinted and Next outlined, so a block too narrow for its
 * badge still shows it. Tentative and not answered have a dashed edge.
 */
@Composable
private fun Block(
    block: TimelineBlock,
    line: MeetingTimeline,
    now: WallTime,
    topics: List<Topic>,
    onOpen: (Meeting) -> Unit,
) {
    val row = block.meeting
    val meeting = row.meeting
    val from = line.offsetOf(block.fromMinute)
    val label = meetingRowLabel(row, now, topics.map { it.name })
    Li({
        classes("mtl__slot")
        style {
            property("--x", percent(from))
            property("--w", percent(line.offsetOf(block.toMinute) - from))
            property("--lane", block.lane.toString())
        }
    }) {
        Button({
            type(ButtonType.Button)
            itemCard(CardKind.Meeting)
            classes("mblock")
            when (row.phase) {
                MeetingPhase.Ended -> classes("is-ended")
                MeetingPhase.Now -> classes("is-now")
                MeetingPhase.Next -> classes("is-next")
                else -> Unit
            }
            if (meeting.response.isOpen) classes("is-open")
            if (row.startsBeforeToday) classes("is-from-yesterday")
            if (row.endsAfterToday) classes("is-to-tomorrow")
            attr("aria-label", label)
            onClick { onOpen(meeting) }
        }) {
            // The title is often cut short, so a pointer shows the whole sentence. The tooltip sits on this hidden
            // part, not on the button, so a screen reader does not read the sentence again as a description.
            Span({
                classes("mblock__in")
                attr("aria-hidden", "true")
                attr("title", label)
            }) { BlockText(row) }
        }
    }
}

/** The title, then Now or Next and when it starts. */
@Composable
private fun BlockText(row: TodayMeeting) {
    val meeting = row.meeting
    Span({ classes("mblock__name") }) { Text(meeting.displayTitle) }
    Span({ classes("mblock__meta") }) {
        val badge =
            when (row.phase) {
                MeetingPhase.Now -> "Now"
                MeetingPhase.Next -> "Next"
                else -> null
            }
        if (badge != null) {
            Span({
                classes("mbadge")
                if (row.phase == MeetingPhase.Now) classes("mbadge--now")
            }) { Text(badge) }
        }
        // A meeting that started yesterday says when it ends; any other says when it starts.
        val time =
            if (row.startsBeforeToday) {
                "Until ${clockText(meeting.time.endsAt.minuteOfDay)}"
            } else {
                clockText(meeting.time.startsAt.minuteOfDay)
            }
        Span({ classes("mblock__time") }) { Text(time) }
    }
}

/** The line at now, with the time on the ruler. Decorative: the rows' sentences and the status line say it in words. */
@Composable
private fun NowLine(
    offset: Double,
    now: WallTime,
) {
    Div({
        classes("mtl__now")
        // Near an end, the time sits inside the hours instead of across the edge.
        if (offset < 0.04) classes("is-start")
        if (offset > 0.96) classes("is-end")
        attr("aria-hidden", "true")
        style { property("--x", percent(offset)) }
    }) { Span({ classes("mtl__nowt") }) { Text(clockText(now.minuteOfDay)) } }
}

/** Grey hours while the first answer loads (aria-busy), so the card keeps its height. */
@Composable
private fun LoadingTrack() {
    Div({
        classes("mtl", "mtl--skel")
        attr("aria-busy", "true")
        attr("aria-hidden", "true")
    }) {
        Span({ classes("skel", "mtl__skelrule") })
        Div({ classes("mtl__skellane") }) {
            Span({ classes("skel", "mtl__skelblock") })
            Span({ classes("skel", "mtl__skelblock") })
        }
    }
}

/** Not answered or tentative: the meeting may not happen for you yet. */
private val MeetingResponse.isOpen: Boolean get() = this == MeetingResponse.Tentative || this == MeetingResponse.Pending

/** "12.5%": three decimals are finer than a pixel and never print as an exponent. */
private fun percent(fraction: Double): String = "${round(fraction * 100_000) / 1000}%"
