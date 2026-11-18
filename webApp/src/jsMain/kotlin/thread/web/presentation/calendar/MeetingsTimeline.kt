package thread.web.presentation.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.AttrsScope
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
import thread.domain.calendar.MeetingTitleFit
import thread.domain.calendar.MeetingsState
import thread.domain.calendar.SeriesKey
import thread.domain.calendar.TIMELINE_BLOCK_PX
import thread.domain.calendar.TIMELINE_LANE_GAP_PX
import thread.domain.calendar.TimelineBlock
import thread.domain.calendar.TimelineFlag
import thread.domain.calendar.TitleTurn
import thread.domain.calendar.TodayMeeting
import thread.domain.calendar.joinServiceOf
import thread.domain.calendar.meetingTimeline
import thread.domain.time.WallTime
import thread.domain.time.clockText
import thread.domain.topic.Topic
import thread.web.platform.observeSize
import thread.web.presentation.CardKind
import thread.web.presentation.LocalNow
import thread.web.presentation.SectionCard
import thread.web.presentation.cardKind
import thread.web.presentation.itemCard
import kotlin.math.floor
import kotlin.math.round

/**
 * Today's meetings as a timeline across the page (D79, Settings "Meetings layout: Horizontal"). It is the Meetings card
 * of [MeetingsRail], with the same note and footer, and it keeps every meeting of the day: "Show past meetings" is for
 * the card only (D86). Its body runs the day's hours from left to right
 * ([meetingTimeline]): all-day meetings in a strip above the hours, timed ones as blocks placed by start and end, in
 * lanes while they overlap, and a line at now. The hours share the box's width, so the layout waits for it. A block too
 * narrow for the start of its title shows it as Settings "Narrow meeting titles" picks ([titles], D97): along its
 * diagonal (D88) or up its height, taller when it needs to; as a flag; or flat on more lines in a taller block. A meeting
 * too narrow even for one line is a flag in a band above the lanes in every choice ([thread.domain.calendar.titleFit],
 * D85). A block or a flag's
 * title is one button named by the row's sentence ([meetingRowLabel]); it opens the Meeting card.
 * Flags and blocks come in one list in time order, so Tab follows the day. Join for the meeting now and the next one
 * sits in the card's head, since a block is too narrow for it. A narrow screen scrolls the hours sideways inside the
 * card, starting at now; the page never scrolls sideways.
 */
@Composable
internal fun MeetingsTimeline(
    view: TodayMeetingsView,
    titles: MeetingTitleFit,
    topicsOf: (SeriesKey) -> List<Topic>,
    onOpen: (Meeting) -> Unit,
) {
    val now = LocalNow.current
    // The box's width in px, once measured: the hours share it (D85).
    var widthPx by remember { mutableStateOf<Double?>(null) }
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
                val line = meetingTimeline(view.rows, now, widthPx ?: 0.0, titles)
                if (line.allDay.isNotEmpty()) AllDayStrip(line.allDay, now, topicsOf, onOpen)
                Track(line, now, sized = widthPx != null, onWidth = { widthPx = it }, topicsOf, onOpen)
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
 * ([thread.domain.calendar.TIMELINE_MIN_HOUR_PX] each, css/cards.css). The box starts with now about a third from its
 * left edge, again when the hours change. It tells [onWidth] its width whenever that changes, and hides the hours until
 * it is [sized], so they never show laid out for another width first.
 */
@Composable
private fun Track(
    line: MeetingTimeline,
    now: WallTime,
    sized: Boolean,
    onWidth: (Double) -> Unit,
    topicsOf: (SeriesKey) -> List<Topic>,
    onOpen: (Meeting) -> Unit,
) {
    val nowOffset = line.nowMinute?.let(line::offsetOf)
    // A new box for new hours (a meeting across midnight came or went), so it starts at now again.
    key(line.startMinute, line.endMinute) { TrackBox(line, nowOffset, now, sized, onWidth, topicsOf, onOpen) }
}

@Composable
private fun TrackBox(
    line: MeetingTimeline,
    nowOffset: Double?,
    now: WallTime,
    sized: Boolean,
    onWidth: (Double) -> Unit,
    topicsOf: (SeriesKey) -> List<Topic>,
    onOpen: (Meeting) -> Unit,
) {
    Div({
        classes("mtl")
        if (!sized) classes("is-unsized")
        style { property("--hours", line.hours.toString()) }
        ref { element ->
            // The hours fill the box's width, so the box's width decides which titles turn and which meetings are flags.
            val stop = observeSize(element) { onWidth(element.getBoundingClientRect().width) }
            // Once, when the box appears: later minutes leave the place the person scrolled to.
            if (nowOffset != null) {
                window.requestAnimationFrame {
                    val room = element.scrollWidth - element.clientWidth
                    if (room > 0) {
                        element.scrollLeft = (element.scrollWidth * nowOffset - element.clientWidth / 3.0).coerceIn(0.0, room.toDouble())
                    }
                }
            }
            onDispose { stop() }
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
                // Each lane is as tall as its tallest block. A day of only flags has no empty lane under them; a day with
                // no meeting keeps one.
                val lanesPx =
                    if (line.lanes == 0 && line.flagRows == 0) {
                        TIMELINE_LANE_GAP_PX + TIMELINE_BLOCK_PX + TIMELINE_LANE_GAP_PX
                    } else {
                        line.laneTopPx(line.lanes)
                    }
                if (line.flagRows > 0) classes("has-flags")
                style {
                    property("--lanes-h", "${lanesPx}px")
                    if (line.flagRows > 0) property("--flag-rows", line.flagRows.toString())
                }
            }) {
                line.items.forEach { item ->
                    val topics = topicsOf(item.meeting.meeting.seriesKey)
                    key(item.meeting.meeting.id.value) {
                        when (item) {
                            is TimelineBlock -> Block(item, line, now, topics, onOpen)
                            is TimelineFlag -> Flag(item, line, now, topics, onOpen)
                        }
                    }
                }
            }
            if (nowOffset != null) NowLine(nowOffset, now)
        }
    }
}

/**
 * One timed meeting: its title, then Now or Next and when it starts. Placed by start and end; a part that runs into
 * yesterday or tomorrow has a square edge on that side. Now is tinted and Next outlined, so a block too narrow for its
 * badge still shows it. Tentative and not answered have a dashed edge. A block too narrow for the start of its title
 * runs it along its diagonal, from the bottom-left corner to the top-right, at the angle and length the domain gives
 * ([TitleTurn.Diagonal], D88; straight up with Vertical, D97), or lies flat on as many lines as the domain gives
 * ([TitleTurn.Wrapped], Increase height, D97). It is as tall as the domain says ([TimelineBlock.heightPx]); its lane
 * grows with it.
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
    val diagonal = block.turn as? TitleTurn.Diagonal
    val wrapped = block.turn as? TitleTurn.Wrapped
    Li({
        classes("mtl__slot")
        style {
            property("--x", percent(from))
            property("--w", percent(line.offsetOf(block.toMinute) - from))
            property("--top", "${line.laneTopPx(block.lane)}px")
            property("--h", "${block.heightPx}px")
            if (diagonal != null) {
                // Hundredths: the length rounded down, so the line never passes the corners.
                property("--a", "${round(diagonal.angleDeg * 100) / 100}deg")
                property("--l", "${floor(diagonal.lengthPx * 100) / 100}px")
            }
            if (wrapped != null) property("--lines", wrapped.lines.toString())
        }
    }) {
        Button({
            type(ButtonType.Button)
            itemCard(CardKind.Meeting)
            classes("mblock")
            phase(row.phase)
            if (meeting.response.isOpen) classes("is-open")
            if (row.startsBeforeToday) classes("is-from-yesterday")
            if (row.endsAfterToday) classes("is-to-tomorrow")
            if (diagonal != null) classes("is-turned")
            if (wrapped != null) classes("is-wrapped")
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

/**
 * One meeting too narrow for a turned title as a flag (D85): a pole up from its start to its title, in its row above the lanes,
 * and a bar on the time line for its real length. Only the title is a button, named by the row's sentence, and it opens
 * the Meeting card as a block does; its target is 24 px tall on every screen. Now has the strongest fill, Next
 * an outline in the meeting color, and an ended one is grey. Tentative and not answered have a dashed pole. A flag has
 * no badge or time: the title needs the room, and the sentence says them.
 */
@Composable
private fun Flag(
    flag: TimelineFlag,
    line: MeetingTimeline,
    now: WallTime,
    topics: List<Topic>,
    onOpen: (Meeting) -> Unit,
) {
    val row = flag.meeting
    val meeting = row.meeting
    val label = meetingRowLabel(row, now, topics.map { it.name })
    Li({
        classes("mtl__flag")
        cardKind(CardKind.Meeting)
        phase(row.phase)
        if (meeting.response.isOpen) classes("is-open")
        style {
            property("--x", percent(line.offsetOf(flag.fromMinute)))
            property("--room", percent(flag.roomMinutes.toDouble() / (line.endMinute - line.startMinute)))
            property("--len", ((flag.toMinute - flag.fromMinute).toDouble() / flag.roomMinutes.coerceAtLeast(1)).toString())
            property("--row", flag.row.toString())
        }
    }) {
        Span({ classes("mflag__len") })
        Span({ classes("mflag__pole") })
        Span({ classes("mflag__foot") })
        Button({
            type(ButtonType.Button)
            classes("mflag")
            attr("aria-label", label)
            onClick { onOpen(meeting) }
        }) {
            // As on a block: the tooltip sits on the hidden part, so a screen reader reads the sentence once.
            Span({
                classes("mflag__in")
                attr("aria-hidden", "true")
                attr("title", label)
            }) { Span({ classes("mflag__name") }) { Text(meeting.displayTitle) } }
        }
    }
}

/** Ended, now or next, for the timeline's styles. */
private fun AttrsScope<*>.phase(phase: MeetingPhase) {
    when (phase) {
        MeetingPhase.Ended -> classes("is-ended")
        MeetingPhase.Now -> classes("is-now")
        MeetingPhase.Next -> classes("is-next")
        else -> Unit
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
