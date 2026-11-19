package thread.web.presentation.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.HTMLElement
import thread.domain.calendar.CalendarDay
import thread.domain.calendar.Meeting
import thread.domain.calendar.MeetingsState
import thread.domain.calendar.SeriesKey
import thread.domain.calendar.calendarDays
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.Topic
import thread.web.platform.WallClock
import thread.web.platform.readDeviceFlag
import thread.web.platform.writeDeviceFlag
import thread.web.presentation.CardKind
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalCards
import thread.web.presentation.LocalNow
import thread.web.presentation.PageHeader
import thread.web.presentation.Place
import thread.web.presentation.Router
import thread.web.presentation.SectionCard
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.dayMonth
import thread.web.presentation.isPlainClick
import thread.web.presentation.shortDate
import thread.web.presentation.weekdayName
import thread.web.presentation.weekdayShort
import kotlin.math.min

/** Yesterday's meetings are unfolded on this device (D102). Folded is the default, like Completed. */
private const val YESTERDAY_OPEN_FLAG = "thread.calendar.yesterdayOpen"

private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/** A request to scroll to [day] and focus it; [serial] makes a second request for the same day a new one. */
private data class CalendarJump(
    val day: LocalDay,
    val serial: Int,
)

/**
 * The Calendar page (D102): every meeting Thread has, from yesterday to the last day the Mac uploaded, in week cards,
 * read-only. A two-week overview jumps to a day: a rail from 1100 px, a strip of day chips under it. `?day=` opens the
 * page at a day ([calendarJumpDay]). Rows are Today's ([MeetingRow]) and open the Meeting card; Join shows on today's
 * rows only. The line under the title is Today's: the Mac's last upload, or what is wrong ([meetingsNotice]).
 */
@Composable
internal fun CalendarScreen(
    meetings: MeetingsModel,
    workspace: WorkspaceModel,
    zone: String,
    router: Router,
    place: Place,
) {
    val now = LocalNow.current
    val state = meetings.state
    val ready = state as? MeetingsState.Ready
    val lastSuccess = ready?.source?.lastSuccessAtMillis?.let { WallClock.wallTimeAt(it.toDouble(), zone) }
    val days = remember(ready, now) { ready?.let { calendarDays(it.meetings, it.source, now) }.orEmpty() }
    val notice =
        meetingsNotice(state, days.any { it.rows.isNotEmpty() }, now.day, WallClock.nowMillis().toLong(), lastSuccess, page = true)
    val sub = lastSuccess?.takeIf { notice?.saysTime != true }?.let { updatedText(it, now.day) }
    PageHeader("Calendar", sub)
    when {
        state == MeetingsState.NotSetUp ->
            EmptyState(
                Icon.Calendar,
                "Your meetings from the Mac show here",
                "Thread Calendar on your Mac uploads one calendar, from yesterday to two weeks ahead. Thread only reads it.",
            ) {
                A(Place.of("settings", "calendar").hash, { classes("btn", "btn--line") }) { Text("Set up in Settings") }
            }
        state == MeetingsState.Loading -> {
            CalendarNote(null, loading = true)
            LoadingCalendar()
        }
        else -> {
            CalendarNote(notice, loading = false)
            if (days.isNotEmpty()) {
                val cards = LocalCards.current
                Agenda(days, now, router, place, workspace::topicsOfSeries) { cards?.openMeeting(it.id) }
            }
        }
    }
}

/** The status line above the page: always present, so a change is announced; empty while the days say it all. */
@Composable
private fun CalendarNote(
    notice: MeetingsNotice?,
    loading: Boolean,
) {
    P({
        classes("mtg__note", "cal__note")
        if (notice?.warning == true) classes("is-warn")
        attr("role", "status")
    }) {
        when {
            loading -> Span({ classes("vh") }) { Text("Loading meetings") }
            notice != null -> {
                if (notice.warning) IconView(Icon.Alert, 15)
                Span { Text(notice.text) }
            }
        }
    }
}

@Composable
private fun Agenda(
    days: List<CalendarDay>,
    now: WallTime,
    router: Router,
    place: Place,
    topicsOf: (SeriesKey) -> List<Topic>,
    onOpen: (Meeting) -> Unit,
) {
    val today = now.day
    val yesterday = today.plusDays(-1)
    val weeks = calendarWeeks(days, today)
    val anchors = weeks.flatMap { week -> week.parts.flatMap { part -> part.days.map { it to part.anchorId } } }.toMap()
    var yesterdayOpen by remember { mutableStateOf(readDeviceFlag(YESTERDAY_OPEN_FLAG)) }
    val closedWeeks = remember { mutableStateListOf<LocalDay>() }
    var jump by remember { mutableStateOf<CalendarJump?>(null) }
    // The `?day=` value already shown, so replacing it after a click does not jump twice.
    var handled by remember { mutableStateOf<String?>(null) }

    fun jumpTo(day: LocalDay) {
        closedWeeks.remove(mondayOf(day))
        // Jumping to yesterday unfolds it for now; only the fold button changes what this device remembers.
        if (day == yesterday) yesterdayOpen = true
        jump = CalendarJump(day, (jump?.serial ?: 0) + 1)
    }

    val onDay: (LocalDay) -> Unit = { day ->
        handled = day.toString()
        jumpTo(day)
        router.replace(router.place.with(DAY_PARAM, day.toString()))
    }
    val param = place.param(DAY_PARAM)
    LaunchedEffect(param) {
        if (param == null || param == handled) return@LaunchedEffect
        handled = param
        calendarJumpDay(param, anchors.keys, today)?.let(::jumpTo)
    }
    LaunchedEffect(jump) {
        val target = jump ?: return@LaunchedEffect
        val element = anchors[target.day]?.let { document.getElementById(it) } as? HTMLElement ?: return@LaunchedEffect
        element.asDynamic().scrollIntoView(js("({ block: 'start' })"))
        element.asDynamic().focus(js("({ preventScroll: true })"))
        showChip(target.day)
    }

    Nav({
        classes("strip")
        id(STRIP_ID)
        attr("aria-label", "Days")
    }) {
        days.forEach { day ->
            key(day.day.toString()) {
                DayLink(day.day, day.rows.size, today, "strip__day", onDay) {
                    Span({
                        classes("strip__wd")
                        attr("aria-hidden", "true")
                    }) { Text(weekdayShort(day.day)) }
                    Span({
                        classes("strip__n")
                        attr("aria-hidden", "true")
                    }) { Text(day.day.day.toString()) }
                    Dots(day.rows.size)
                }
            }
        }
    }
    Div({ classes("cal") }) {
        // The overview comes first, so Tab reaches it before the days; the grid puts it on the right.
        Div({ classes("cal__rail") }) { Overview(days, today, onDay) }
        Div({ classes("cal__main") }) {
            weeks.forEach { week ->
                key(week.monday.toString()) {
                    val open = week.monday !in closedWeeks
                    WeekCard(
                        week = week,
                        open = open,
                        onToggle = { if (open) closedWeeks.add(week.monday) else closedWeeks.remove(week.monday) },
                    ) {
                        week.parts.forEach { part ->
                            key(part.anchorId) {
                                when (part) {
                                    is CalendarPart.Gap -> GapLine(part)
                                    is CalendarPart.Day -> {
                                        val folded = if (part.day.day == yesterday) !yesterdayOpen else null
                                        DayBlock(part.day, now, folded, topicsOf, onOpen) {
                                            yesterdayOpen = !yesterdayOpen
                                            writeDeviceFlag(YESTERDAY_OPEN_FLAG, yesterdayOpen)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            P({ classes("calend") }) { Text(calendarEndText(days.last().day)) }
        }
    }
}

private const val STRIP_ID = "cal-strip"

/** Scrolls the day strip, when it shows, so [day]'s chip is in view. */
private fun showChip(day: LocalDay) {
    val strip = document.getElementById(STRIP_ID) as? HTMLElement ?: return
    if (strip.offsetParent == null) return
    val chip = strip.querySelector("[data-day=\"$day\"]") as? HTMLElement ?: return
    val left = chip.offsetLeft - strip.offsetLeft
    if (left < strip.scrollLeft || left + chip.offsetWidth > strip.scrollLeft + strip.clientWidth) {
        strip.scrollLeft = (left - (strip.clientWidth - chip.offsetWidth) / 2).toDouble()
    }
}

/** A day in the overview or the strip: a link to `?day=`, named by [overviewDayLabel]. A plain click stays on the page. */
@Composable
private fun DayLink(
    day: LocalDay,
    count: Int,
    today: LocalDay,
    className: String,
    onDay: (LocalDay) -> Unit,
    content: @Composable () -> Unit,
) {
    A(calendarPlace(day).hash, {
        classes(className)
        if (day == today) {
            classes("is-today")
            attr("aria-current", "date")
        }
        if (day < today) classes("is-past")
        attr("aria-label", overviewDayLabel(day, count, today))
        attr("data-day", day.toString())
        onClick { event ->
            if (!isPlainClick(event.nativeEvent)) return@onClick
            event.preventDefault()
            onDay(day)
        }
    }) { content() }
}

/** Up to four dots for a day's meetings. */
@Composable
private fun Dots(count: Int) {
    Span({
        classes("ov__dots")
        attr("aria-hidden", "true")
    }) { repeat(min(count, 4)) { Span() } }
}

/** The "Two weeks" card: the covered days in whole weeks; days the Mac did not upload are dashed and not links. */
@Composable
private fun Overview(
    days: List<CalendarDay>,
    today: LocalDay,
    onDay: (LocalDay) -> Unit,
) {
    val cells = overviewCells(days)
    SectionCard(
        kind = CardKind.Meeting,
        headingId = "calov-h",
        bodyId = "calov-b",
        title = "Two weeks",
        hint = "${shortDate(days.first().day)} to ${shortDate(days.last().day)}",
    ) {
        Div({ classes("ov") }) {
            WeekdayLetters()
            cells.forEach { cell ->
                key(cell.day.toString()) {
                    if (cell.covered) {
                        DayLink(cell.day, cell.count, today, "ov__day", onDay) {
                            Text(cell.day.day.toString())
                            Dots(cell.count)
                        }
                    } else {
                        Span({
                            classes("ov__day", "is-out")
                            attr("aria-hidden", "true")
                        }) { Text(cell.day.day.toString()) }
                    }
                }
            }
        }
        if (cells.any { !it.covered }) {
            P({ classes("ov__note") }) { Text("Dashed days are not in Thread. Your Mac uploads yesterday to two weeks ahead.") }
        }
    }
}

@Composable
private fun WeekdayLetters() {
    WEEKDAYS.forEach { name ->
        Span({
            classes("ov__wd")
            attr("aria-hidden", "true")
            attr("title", name)
        }) { Text(name.take(1)) }
    }
}

@Composable
private fun WeekCard(
    week: CalendarWeek,
    open: Boolean,
    onToggle: () -> Unit,
    body: @Composable () -> Unit,
) {
    val id = "calw-${week.monday}"
    SectionCard(
        kind = CardKind.Meeting,
        headingId = "$id-h",
        bodyId = "$id-b",
        title = week.title,
        count = week.count.toString(),
        open = open,
        onToggle = onToggle,
        hint = week.hint,
        attrs = { classes("calweek") },
    ) { body() }
}

/** Covered days in a row with no meetings: "Sat 21 and Sun 22 Nov  No meetings". A day link lands here. */
@Composable
private fun GapLine(part: CalendarPart.Gap) {
    P({
        classes("calgap")
        id(part.anchorId)
        tabIndex(-1)
    }) {
        B { Text(gapText(part.days)) }
        Span { Text("No meetings") }
    }
}

/**
 * A day: its heading (Today, Yesterday or Tomorrow, else the weekday; the date; the count), then its rows. [folded] is
 * null for a day that does not fold, else whether yesterday is folded; [onFold] flips it.
 */
@Composable
private fun DayBlock(
    day: CalendarDay,
    now: WallTime,
    folded: Boolean?,
    topicsOf: (SeriesKey) -> List<Topic>,
    onOpen: (Meeting) -> Unit,
    onFold: () -> Unit,
) {
    val today = now.day
    val anchor = calendarAnchorId(day.day)
    val listId = "$anchor-l"
    val relative =
        when (day.day.epochDay - today.epochDay) {
            0L -> "Today"
            -1L -> "Yesterday"
            1L -> "Tomorrow"
            else -> null
        }
    val count = day.rows.size
    Section({
        classes("calday")
        if (day.day == today) classes("is-today")
        if (day.day < today) classes("is-past")
        attr("aria-labelledby", anchor)
    }) {
        H3({
            classes("calday__h")
            if (folded != null) classes("calday__h--fold")
            id(anchor)
            tabIndex(-1)
        }) {
            val head: @Composable () -> Unit = {
                Span({ classes("calday__name") }) { Text(relative ?: weekdayName(day.day)) }
                Span({ classes("calday__date") }) { Text(if (relative != null) shortDate(day.day) else dayMonth(day.day)) }
                if (count > 0) Span({ classes("calday__n") }) { Text(meetingCountText(count)) }
            }
            if (folded != null) {
                Button({
                    type(ButtonType.Button)
                    classes("calfold")
                    attr("aria-expanded", (!folded).toString())
                    attr("aria-controls", listId)
                    onClick { onFold() }
                }) {
                    head()
                    Span({ classes("chev") }) { IconView(Icon.ChevDown, 16) }
                }
            } else {
                head()
            }
        }
        when {
            count == 0 -> P({ classes("calgap", "calgap--day") }) { Span { Text("No meetings") } }
            else ->
                Ul({
                    classes("tlist", "mtg-list")
                    id(listId)
                    if (folded == true) attr("hidden", "")
                }) {
                    if (folded != true) {
                        day.rows.forEach { row ->
                            key(row.meeting.id.value) {
                                MeetingRow(row, now, topicsOf(row.meeting.seriesKey), onOpen, join = day.day == today)
                            }
                        }
                    }
                }
        }
    }
}

/** While the first answer loads: a grey week and a grey overview keep the page's shape. */
@Composable
private fun LoadingCalendar() {
    Div({
        classes("cal")
        attr("aria-hidden", "true")
    }) {
        Div({ classes("cal__rail") }) {
            SectionCard(kind = CardKind.Meeting, headingId = "calov-h", bodyId = "calov-b", title = "Two weeks") {
                Div({ classes("ov", "ov--skel") }) {
                    WeekdayLetters()
                    repeat(21) { Span({ classes("ov__day") }) }
                }
            }
        }
        Div({ classes("cal__main") }) {
            SectionCard(
                kind = CardKind.Meeting,
                headingId = "calw-h",
                bodyId = "calw-b",
                title = "This week",
                attrs = {
                    classes("calweek")
                    attr("aria-busy", "true")
                },
            ) {
                Div({ classes("calday") }) {
                    P({ classes("calday__h") }) { Span({ classes("skel", "skel--time") }) }
                    Ul({ classes("tlist", "mtg-list") }) {
                        repeat(3) {
                            Li({ classes("icard", "mrow", "mrow--skel") }) {
                                Span({ classes("skel", "skel--time") })
                                Span({ classes("skel", "skel--text") })
                            }
                        }
                    }
                }
            }
        }
    }
}
