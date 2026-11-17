package thread.domain.calendar

import thread.domain.time.LocalDay
import thread.domain.time.MINUTES_PER_DAY
import thread.domain.time.WallTime
import kotlin.math.ceil

/** The first hour the meetings timeline always shows (D79, D85): 10:00 in the workspace zone. */
const val TIMELINE_FIRST_HOUR: Int = 10

/** The hour the meetings timeline always shows up to (D79, D85): 19:00 in the workspace zone. */
const val TIMELINE_LAST_HOUR: Int = 19

/**
 * The narrowest an hour is drawn, in px (D79). The hours share the card's width; a card narrower than this many px an
 * hour scrolls them sideways.
 */
const val TIMELINE_MIN_HOUR_PX: Double = 88.0

/**
 * The characters of a title that are enough to tell a call apart (D85, owner): a block shows at least this many, or the
 * whole title when it is shorter, flat or turned up the block. Eight keep the start of a second word, so "Weekly s…"
 * and "Weekly p…" differ.
 */
const val MIN_TITLE_CHARS: Int = 8

/**
 * A block title's character, in px (D85): 12 px at weight 600 in the system font, as css/cards.css draws it. Measured
 * in Chrome on macOS over SYNTHETIC titles: 6.4 px on average, but eight and the ellipsis took up to 66.2 px, as in
 * "Weekly p…" and "Roadmap…". 7 px keeps those whole in a turned block, where 6.5 px showed only "Weekly…". Wide
 * capitals, as in a one-word title in upper case, can still lose a character or two.
 */
const val TIMELINE_CHAR_PX: Double = 7.0

/** The ellipsis after a cut title, in px (D85): 10.9 px measured, as [TIMELINE_CHAR_PX]. */
const val TIMELINE_ELLIPSIS_PX: Double = 11.0

/** One title line, in px (D85): its line height, flat or turned. */
const val TIMELINE_LINE_PX: Double = 15.0

/**
 * The px of a block's slot beside a flat title (D85): the 3 px colour edge and 3 px after it, 2 px before the 1 px right
 * border, and the 2 px gap to the next block.
 */
const val TIMELINE_FLAT_SIDES_PX: Double = 11.0

/**
 * The px of a block's slot beside a turned title (D85): the 3 px colour edge, the 1 px right border and the 2 px gap.
 * The line's own height spaces its letters from them.
 */
const val TIMELINE_TURNED_SIDES_PX: Double = 6.0

/** The px of a block above and below a turned title (D85): a 1 px border and 4 px inside it at each end. */
const val TIMELINE_TURNED_ENDS_PX: Double = 10.0

/** A block's height, in px (D79), unless a turned title needs more (D85). */
const val TIMELINE_BLOCK_PX: Int = 60

/** The px above each lane and below the last one (D79). */
const val TIMELINE_LANE_GAP_PX: Int = 6

/** The most of its row a flag's title takes, in minutes (D85). */
const val TIMELINE_FLAG_ROOM_MINUTES: Int = 90

/**
 * The room a flag's title is sure of, in minutes (D85): a flag goes to the lowest row with this much free from its
 * start, and the hours shown hold this much after every flag's start, up to the end of the day.
 */
const val TIMELINE_FLAG_MIN_ROOM_MINUTES: Int = 60

/** The narrowest slot a block has, in px (D85): one turned line. A narrower meeting is a flag. */
private const val TURNED_SLOT_PX: Double = TIMELINE_TURNED_SIDES_PX + TIMELINE_LINE_PX

/**
 * Today's meetings on a timeline (D79, Settings "Meetings layout: Horizontal"). It shows the hours from [startMinute]
 * to [endMinute], minutes of the day on whole hours: [TIMELINE_FIRST_HOUR] to [TIMELINE_LAST_HOUR], widened to hold
 * every timed meeting of the day, each [hourPx] wide. [allDay] sit above the hours. [items] are the timed meetings in
 * time order: one too narrow for a turned title is a [TimelineFlag] in one of [flagRows] rows above the lanes (D85),
 * any other a [TimelineBlock] in a lane. [laneHeightsPx] holds each lane's height, its tallest block. [nowMinute] is
 * now, or null while now is outside the hours shown.
 */
data class MeetingTimeline(
    val startMinute: Int,
    val endMinute: Int,
    val hourPx: Double,
    val allDay: List<TodayMeeting>,
    val items: List<TimelineItem>,
    val laneHeightsPx: List<Int>,
    val flagRows: Int,
    val nowMinute: Int?,
) {
    /** How many hours it shows. */
    val hours: Int get() = (endMinute - startMinute) / 60

    /** How many lanes the blocks take. */
    val lanes: Int get() = laneHeightsPx.size

    /** The blocks, in time order. */
    val blocks: List<TimelineBlock> get() = items.filterIsInstance<TimelineBlock>()

    /** The flags, in time order. */
    val flags: List<TimelineFlag> get() = items.filterIsInstance<TimelineFlag>()

    /** The px from the top of the lanes to the top of [lane], counted from 0; [lanes] gives the lanes' whole height. */
    fun laneTopPx(lane: Int): Int = TIMELINE_LANE_GAP_PX + laneHeightsPx.take(lane).sumOf { it + TIMELINE_LANE_GAP_PX }

    /** Where [minute] of the day sits: 0.0 at [startMinute], 1.0 at [endMinute]. */
    fun offsetOf(minute: Int): Double = (minute - startMinute).toDouble() / (endMinute - startMinute)
}

/**
 * A timed meeting on the timeline. [fromMinute] and [toMinute] are its part of today, minutes of the day: a meeting that
 * started yesterday starts at 0, one that ends tomorrow ends at 1440.
 */
sealed interface TimelineItem {
    val meeting: TodayMeeting
    val fromMinute: Int
    val toMinute: Int
}

/**
 * A meeting wide enough for at least one turned title line, or one that started yesterday, as a block placed by its
 * start and end, [heightPx] tall, its title running [turn] (D85). A meeting that overlaps an earlier block goes to the
 * first [lane] free at its start, counted from 0.
 */
data class TimelineBlock(
    override val meeting: TodayMeeting,
    override val fromMinute: Int,
    override val toMinute: Int,
    val lane: Int,
    val turn: TitleTurn,
    val heightPx: Int,
) : TimelineItem

/**
 * A meeting too narrow for a turned title as a flag (D85): a pole up from its start to its title in [row], counted from
 * 0 just above the hours. The title has [roomMinutes] of the row from the start: [TIMELINE_FLAG_ROOM_MINUTES], or less
 * where the next title, the pole of a higher flag or the end of the hours comes sooner.
 */
data class TimelineFlag(
    override val meeting: TodayMeeting,
    override val fromMinute: Int,
    override val toMinute: Int,
    val row: Int,
    val roomMinutes: Int,
) : TimelineItem

/** How a block's title runs (D85): flat, on one or two lines, or one line up the block, read from the bottom. */
enum class TitleTurn { Flat, Up }

/** Where a timed meeting's title fits on the timeline (D85), as [titleFit] picks it. */
sealed interface TitleFit {
    /** Too narrow for one turned line: a flag. */
    data object Flag : TitleFit

    /** A block [heightPx] tall whose title runs [turn]. */
    data class Block(
        val turn: TitleTurn,
        val heightPx: Int,
    ) : TitleFit
}

/**
 * Where the title of a timed meeting fits (D85), from [slotPx], the width of its part of today in the lanes with the gap
 * after it, and [titleChars], its title's length. It counts characters at [TIMELINE_CHAR_PX], not the drawn text, so
 * the same day always looks the same. The run a title needs is [MIN_TITLE_CHARS] characters and an ellipsis, or a
 * shorter title whole.
 * - Flat, when the slot holds that run and [TIMELINE_FLAT_SIDES_PX]. The block shows as much of the title as fits.
 * - Else up the block, when the slot holds one [TIMELINE_LINE_PX] line and [TIMELINE_TURNED_SIDES_PX]. The block is
 *   [TIMELINE_BLOCK_PX] tall, or taller when that height is short of the run and [TIMELINE_TURNED_ENDS_PX].
 * - Else a flag. Its pole and cloth need no width.
 */
fun titleFit(
    slotPx: Double,
    titleChars: Int,
): TitleFit {
    val run = minOf(titleChars, MIN_TITLE_CHARS) * TIMELINE_CHAR_PX + if (titleChars > MIN_TITLE_CHARS) TIMELINE_ELLIPSIS_PX else 0.0
    return when {
        slotPx < TURNED_SLOT_PX -> TitleFit.Flag
        slotPx >= TIMELINE_FLAT_SIDES_PX + run -> TitleFit.Block(TitleTurn.Flat, TIMELINE_BLOCK_PX)
        else -> TitleFit.Block(TitleTurn.Up, maxOf(TIMELINE_BLOCK_PX, ceil(TIMELINE_TURNED_ENDS_PX + run).toInt()))
    }
}

/**
 * Lays out [today], the rows [todayMeetings] gives for [now]'s day, on a timeline [widthPx] wide: the hours share it,
 * each at least [TIMELINE_MIN_HOUR_PX]. Before the width is known, pass 0 for the narrowest hours.
 */
fun meetingTimeline(
    today: List<TodayMeeting>,
    now: WallTime,
    widthPx: Double,
): MeetingTimeline {
    val day = now.day
    val timed =
        today
            .filter { it.meeting.time is MeetingTime.Timed }
            .map { row -> Timed(row, minuteOn(day, row.meeting.time.startsAt), minuteOn(day, row.meeting.time.endsAt)) }
    val start = minOf(TIMELINE_FIRST_HOUR * 60, timed.minOfOrNull { it.from / 60 * 60 } ?: MINUTES_PER_DAY)
    // A flag needs narrower hours, and the hours hold an hour after each flag's start, which makes them narrower still.
    // So the layout runs again until the hours hold still: they only ever grow, so it ends.
    var end = endFor(timed.map { it.to })
    while (true) {
        val hourPx = maxOf(TIMELINE_MIN_HOUR_PX, widthPx / ((end - start) / 60))
        val placed = timed.map { it.place(hourPx) }
        val next = endFor(placed.map { it.reach })
        if (next == end) return lay(today, now, placed, start, end, hourPx)
        end = next
    }
}

private fun endFor(reaches: List<Int>): Int = maxOf(TIMELINE_LAST_HOUR * 60, reaches.maxOfOrNull { (it + 59) / 60 * 60 } ?: 0)

private fun lay(
    today: List<TodayMeeting>,
    now: WallTime,
    placed: List<Placed>,
    start: Int,
    end: Int,
    hourPx: Double,
): MeetingTimeline {
    val laneEnds = mutableListOf<Int>()
    val laneHeights = mutableListOf<Int>()
    val lanes =
        placed.filter { it.fit is TitleFit.Block }.associateWith { block ->
            val fit = block.fit as TitleFit.Block
            val free = laneEnds.indexOfFirst { it <= block.from }
            val lane =
                if (free >= 0) {
                    free
                } else {
                    laneEnds += 0
                    laneHeights += 0
                    laneEnds.lastIndex
                }
            laneEnds[lane] = block.reach
            laneHeights[lane] = maxOf(laneHeights[lane], fit.heightPx)
            lane
        }
    val flags = flagPlaces(placed.filter { it.fit == TitleFit.Flag }, end)
    return MeetingTimeline(
        startMinute = start,
        endMinute = end,
        hourPx = hourPx,
        allDay = today.filter { it.meeting.time is MeetingTime.AllDay },
        items =
            placed.map { item ->
                val fit = item.fit
                if (fit is TitleFit.Block) {
                    TimelineBlock(item.row, item.from, item.to, lanes.getValue(item), fit.turn, fit.heightPx)
                } else {
                    val flag = flags.getValue(item)
                    TimelineFlag(item.row, item.from, item.to, flag.row, flag.title.to - flag.title.from)
                }
            },
        laneHeightsPx = laneHeights,
        flagRows = flags.values.maxOfOrNull { it.row + 1 } ?: 0,
        nowMinute = now.minuteOfDay.takeIf { it in start..end },
    )
}

/**
 * Puts each flag in a row (D85), latest start first, so a busy hour reads as a staircase with the earliest highest. A
 * flag goes to the lowest row with [TIMELINE_FLAG_MIN_ROOM_MINUTES] free from its start, up to the next title or the
 * pole of a higher flag, and its title gets that run, at most [TIMELINE_FLAG_ROOM_MINUTES] and never past [end]. Its
 * pole then runs through every row below. Last, a flag whose title was cut moves up to the lowest row the day already
 * has where its whole title fits, if its pole crosses no title on the way, so no row is added for it.
 */
private fun flagPlaces(
    flags: List<Placed>,
    end: Int,
): Map<Placed, FlagPlace> {
    val rows = mutableListOf<FlagRow>()
    val places = HashMap<Placed, FlagPlace>()
    flags.asReversed().forEach { flag ->
        val most = minOf(TIMELINE_FLAG_ROOM_MINUTES, end - flag.from)
        val found = rows.indexOfFirst { it.freeFrom(flag.from) >= minOf(TIMELINE_FLAG_MIN_ROOM_MINUTES, most) }
        val row = if (found >= 0) found else rows.size.also { rows += FlagRow() }
        val title = Title(flag.from, flag.from + minOf(most, rows[row].freeFrom(flag.from)))
        rows[row].titles += title
        for (below in 0 until row) rows[below].poles += flag.from
        places[flag] = FlagPlace(row, title)
    }
    flags.asReversed().forEach { flag ->
        val place = places.getValue(flag)
        val most = minOf(TIMELINE_FLAG_ROOM_MINUTES, end - flag.from)
        if (place.title.to - place.title.from >= most) return@forEach
        val crossed = { row: FlagRow -> row.titles.any { it !== place.title && flag.from > it.from && flag.from < it.to } }
        val target =
            (place.row + 1 until rows.size).firstOrNull { up ->
                rows[up].freeFrom(flag.from) >= most && (place.row until up).none { crossed(rows[it]) }
            } ?: return@forEach
        rows[place.row].titles -= place.title
        for (below in place.row until target) rows[below].poles += flag.from
        val title = Title(flag.from, flag.from + most)
        rows[target].titles += title
        places[flag] = FlagPlace(target, title)
    }
    return places
}

/** A timed meeting's part of today, before it is laid out. */
private class Timed(
    val row: TodayMeeting,
    val from: Int,
    val to: Int,
) {
    /**
     * Its fit with hours [hourPx] wide. A meeting that started yesterday is a block even when shorter: it keeps one turned
     * line's width, so it can be clicked, and the next block in its lane starts after that.
     */
    fun place(hourPx: Double): Placed {
        val slotPx = (to - from) * hourPx / 60
        val fit = titleFit(if (row.startsBeforeToday) maxOf(slotPx, TURNED_SLOT_PX) else slotPx, row.meeting.displayTitle.length)
        // A flag's title is sure of an hour; a block reaches its end, or one turned line's width.
        val reach =
            if (fit == TitleFit.Flag) {
                from + TIMELINE_FLAG_MIN_ROOM_MINUTES
            } else {
                maxOf(to, from + ceil(TURNED_SLOT_PX * 60 / hourPx).toInt())
            }
        return Placed(row, from, to, fit, minOf(reach, MINUTES_PER_DAY))
    }
}

/** A timed meeting being laid out: [reach] is the minute the hours shown must reach for it, and a block's lane end. */
private class Placed(
    val row: TodayMeeting,
    val from: Int,
    val to: Int,
    val fit: TitleFit,
    val reach: Int,
)

/** The minutes a flag's title takes in its row. */
private class Title(
    val from: Int,
    val to: Int,
)

private class FlagPlace(
    val row: Int,
    val title: Title,
)

/** One row of flags: their titles, and the poles of higher flags that pass through it. */
private class FlagRow {
    val titles = mutableListOf<Title>()
    val poles = mutableListOf<Int>()

    /** The minutes free from [minute]: none under a title, else up to the next title or pole, or the end of the day. */
    fun freeFrom(minute: Int): Int =
        if (titles.any { minute >= it.from && minute < it.to }) {
            0
        } else {
            ((titles.map { it.from } + poles).filter { it > minute }.minOrNull() ?: MINUTES_PER_DAY) - minute
        }
}

/** [at] as a minute of [day], kept within it: 0 before the day, 1440 after. */
private fun minuteOn(
    day: LocalDay,
    at: WallTime,
): Int = ((at.day.epochDay - day.epochDay) * MINUTES_PER_DAY + at.minuteOfDay).coerceIn(0L, MINUTES_PER_DAY.toLong()).toInt()
