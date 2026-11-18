package thread.domain.calendar

import thread.domain.time.LocalDay
import thread.domain.time.MINUTES_PER_DAY
import thread.domain.time.WallTime
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

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
 * whole title when it is shorter, flat or along the block's diagonal (D88). Eight keep the start of a second word, so
 * "Weekly s…" and "Weekly p…" differ.
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
 * How the Horizontal timeline shows a block too narrow for its title's run flat (D97, Settings "Narrow meeting titles"):
 * [Diagonal] along the block's diagonal (D88), [Vertical] up the block (D85), [Flags] as a flag above the lanes (D85),
 * or [Wrap] flat on more lines, the block taller. A title that fits flat, and a meeting too narrow for one turned line,
 * look the same in each ([titleFit]).
 */
enum class MeetingTitleFit(
    val id: String,
) {
    Diagonal("diagonal"),
    Vertical("vertical"),
    Flags("flags"),
    Wrap("wrap"),
    ;

    companion object {
        fun fromId(id: String?): MeetingTitleFit? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Today's meetings on a timeline (D79, Settings "Meetings layout: Horizontal"). It shows the hours from [startMinute]
 * to [endMinute], minutes of the day on whole hours: [TIMELINE_FIRST_HOUR] to [TIMELINE_LAST_HOUR], widened to hold
 * every timed meeting of the day, each [hourPx] wide. [allDay] sit above the hours. [items] are the timed meetings in
 * time order: one too narrow for a turned title is a [TimelineFlag] in one of [flagRows] rows above the lanes (D85), as
 * is one too narrow for its title flat with Dots with flags (D97), any other a [TimelineBlock] in a lane.
 * [laneHeightsPx] holds each lane's height, its tallest block. [nowMinute] is now, or null while now is outside the
 * hours shown.
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
 * start and end, [heightPx] tall, its title running [turn] (D85, D88). A meeting that overlaps an earlier block goes to
 * the first [lane] free at its start, counted from 0.
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
 * A meeting too narrow for a turned title as a flag (D85), or for its title flat when Settings picks Dots with flags
 * (D97): a pole up from its start to its title in [row], counted from
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

/**
 * How a block's title runs (D85, D88, D97): [Flat], on one or two lines, one line along the block's [Diagonal], or flat
 * on as many lines as its start needs, [Wrapped].
 */
sealed interface TitleTurn {
    /** Flat, on one or two lines. */
    data object Flat : TitleTurn

    /**
     * One line from the block's bottom-left corner to its top-right (D88), read from the bottom: [angleDeg] up from flat
     * and [lengthPx] long, as [titleDiagonal] finds it. It is 90, up the block, in a block one line wide, and in every
     * turned block when Settings picks Vertical (D97).
     */
    data class Diagonal(
        val angleDeg: Double,
        val lengthPx: Double,
    ) : TitleTurn

    /**
     * Flat on [lines] lines, two or more (D97, Settings "Increase height"): as many as [titleLines] counts for the title's
     * start. Words break where they end, a word wider than the block where the line does, and the rest of the title is
     * cut with an ellipsis.
     */
    data class Wrapped(
        val lines: Int,
    ) : TitleTurn
}

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
 * - Else along the block's diagonal ([titleDiagonal], D88), when the slot holds one [TIMELINE_LINE_PX] line and
 *   [TIMELINE_TURNED_SIDES_PX]. The block is [TIMELINE_BLOCK_PX] tall, or the fewest whole px taller whose diagonal
 *   holds the run. A wider block's diagonal is longer, so it needs less.
 * - Else a flag. Its pole and cloth need no width.
 */
fun titleFit(
    slotPx: Double,
    titleChars: Int,
): TitleFit {
    val run = titleRun(titleChars)
    return when {
        slotPx < TURNED_SLOT_PX -> TitleFit.Flag
        slotPx >= TIMELINE_FLAT_SIDES_PX + run -> TitleFit.Block(TitleTurn.Flat, TIMELINE_BLOCK_PX)
        else -> {
            val heightPx = generateSequence(TIMELINE_BLOCK_PX) { it + 1 }.first { titleDiagonal(slotPx, it).lengthPx >= run }
            TitleFit.Block(titleDiagonal(slotPx, heightPx), heightPx)
        }
    }
}

/**
 * Where [title] fits with [titles], the choice in Settings "Narrow meeting titles" (D97). A flag, and a block whose title
 * lies flat, are what [titleFit] gives for the title's length in every choice. Only a block too narrow for the run flat
 * changes:
 * - [MeetingTitleFit.Diagonal]: along its diagonal, as [titleFit] gives it (D88).
 * - [MeetingTitleFit.Vertical]: one line up the block, read from the bottom, as before D88 (D85). The block is
 *   [TIMELINE_BLOCK_PX] tall, or the fewest whole px taller that hold the run and [TIMELINE_TURNED_ENDS_PX].
 * - [MeetingTitleFit.Flags]: a flag.
 * - [MeetingTitleFit.Wrap]: flat, on the lines [titleLines] counts in the slot less [TIMELINE_FLAT_SIDES_PX]. A block
 *   [TIMELINE_BLOCK_PX] tall holds two; each more makes it [TIMELINE_LINE_PX] taller.
 */
fun titleFit(
    slotPx: Double,
    title: String,
    titles: MeetingTitleFit,
): TitleFit {
    val fit = titleFit(slotPx, title.length)
    if (fit !is TitleFit.Block || fit.turn == TitleTurn.Flat) return fit
    return when (titles) {
        MeetingTitleFit.Diagonal -> fit
        MeetingTitleFit.Vertical -> {
            val heightPx = maxOf(TIMELINE_BLOCK_PX, ceil(TIMELINE_TURNED_ENDS_PX + titleRun(title.length)).toInt())
            TitleFit.Block(TitleTurn.Diagonal(UPRIGHT_DEG, heightPx - TIMELINE_TURNED_ENDS_PX), heightPx)
        }
        MeetingTitleFit.Flags -> TitleFit.Flag
        MeetingTitleFit.Wrap -> {
            val lines = maxOf(2, titleLines(title, slotPx - TIMELINE_FLAT_SIDES_PX))
            TitleFit.Block(TitleTurn.Wrapped(lines), TIMELINE_BLOCK_PX + (lines - 2) * TIMELINE_LINE_PX.toInt())
        }
    }
}

/**
 * The lines a flat title needs on lines [widthPx] wide to show its run (D97, Settings "Increase height"): its first
 * [MIN_TITLE_CHARS] characters, or all of a shorter title, and an ellipsis when more of it follows. It lays the title out
 * as the block draws it, at [TIMELINE_CHAR_PX] a character: a word goes on the line while it fits, else on the next; a
 * word longer than a whole line breaks where each line ends; a space where a line breaks takes no room, and spaces
 * together count as one. The run ends on the line of its last character, or the next one when the title goes on past
 * that line and an ellipsis ([TIMELINE_ELLIPSIS_PX]) does not fit after that character, since the block cuts the title
 * at the end of its last line. Every line holds at least one character.
 */
fun titleLines(
    title: String,
    widthPx: Double,
): Int {
    val perLine = maxOf(1, floor(widthPx / TIMELINE_CHAR_PX).toInt())
    val words = title.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return 1
    // Each character's line, and how many characters its line holds up to and with it.
    val lineOf = mutableListOf<Int>()
    val columnOf = mutableListOf<Int>()
    var line = 1
    var column = 0
    words.forEachIndexed { index, word ->
        if (index > 0) {
            // The space between two words stays on the line when the next word fits after it. Else it ends the line,
            // taking no room, and the word starts the next one.
            val fits = column + 1 + word.length <= perLine
            if (fits) column++
            lineOf += line
            columnOf += column
            if (!fits) {
                line++
                column = 0
            }
        }
        word.forEach { _ ->
            if (column == perLine) {
                line++
                column = 0
            }
            column++
            lineOf += line
            columnOf += column
        }
    }
    val last = minOf(MIN_TITLE_CHARS, lineOf.size) - 1
    val runLine = lineOf[last]
    val cutAfter = lineOf.last() > runLine && columnOf[last] * TIMELINE_CHAR_PX + TIMELINE_ELLIPSIS_PX > widthPx
    return if (cutAfter) runLine + 1 else runLine
}

/** The px a title's run takes flat (D85): [MIN_TITLE_CHARS] characters and an ellipsis, or a shorter title whole. */
private fun titleRun(titleChars: Int): Double =
    minOf(titleChars, MIN_TITLE_CHARS) * TIMELINE_CHAR_PX + if (titleChars > MIN_TITLE_CHARS) TIMELINE_ELLIPSIS_PX else 0.0

/** A title turned up the block (D85, D97). */
private const val UPRIGHT_DEG = 90.0

/**
 * The title line along the diagonal of a block in a slot [slotPx] wide and [heightPx] tall (D88, owner: "the text
 * always starts from bottom left corner and ends to top right corner"). The line is [TIMELINE_LINE_PX] thick and keeps
 * to the block's room for it: [TIMELINE_TURNED_SIDES_PX] less than the slot across, [TIMELINE_TURNED_ENDS_PX] less than
 * the block up. Its angle is the one where it touches all four sides of that room, its start the left and the bottom,
 * its end the top and the right, which makes it the longest line from corner to corner. For a thin line that is the
 * room's own diagonal; a line's thickness makes it steeper, and it is upright only in a slot one line wide.
 */
fun titleDiagonal(
    slotPx: Double,
    heightPx: Int,
): TitleTurn.Diagonal {
    val across = slotPx - TIMELINE_TURNED_SIDES_PX
    val up = heightPx - TIMELINE_TURNED_ENDS_PX
    val line = TIMELINE_LINE_PX
    require(across >= line && up >= line) { "A block's title needs room for one line" }
    if (across == line) return TitleTurn.Diagonal(90.0, up)
    // At angle a the line of length L takes L cos a + line sin a across and L sin a + line cos a up. Both are the
    // room's where across sin a - up cos a + line cos 2a is 0: below 0 flat, above 0 upright, so halving finds it.
    var low = 0.0
    var high = PI / 2
    repeat(DIAGONAL_HALVINGS) {
        val mid = (low + high) / 2
        if (across * sin(mid) - up * cos(mid) + line * cos(2 * mid) < 0) low = mid else high = mid
    }
    val angle = (low + high) / 2
    // Both sides give the length there; each is the steadier one on its own side of 45 degrees.
    val length = if (angle < PI / 4) (across - line * sin(angle)) / cos(angle) else (up - line * cos(angle)) / sin(angle)
    return TitleTurn.Diagonal(angle * 180 / PI, length)
}

/** Enough halvings of a right angle for a Double. */
private const val DIAGONAL_HALVINGS = 60

/**
 * Lays out [today], the rows [todayMeetings] gives for [now]'s day, on a timeline [widthPx] wide: the hours share it,
 * each at least [TIMELINE_MIN_HOUR_PX]. Before the width is known, pass 0 for the narrowest hours. [titles] is how a
 * block too narrow for its title's run flat shows it (D97).
 */
fun meetingTimeline(
    today: List<TodayMeeting>,
    now: WallTime,
    widthPx: Double,
    titles: MeetingTitleFit = MeetingTitleFit.Diagonal,
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
        val placed = timed.map { it.place(hourPx, titles) }
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
     * Its fit with hours [hourPx] wide and narrow titles shown as [titles]. A meeting that started yesterday is a block
     * even when shorter: it keeps one turned line's width, so it can be clicked, and the next block in its lane starts
     * after that. With [MeetingTitleFit.Flags] its title runs along the diagonal, the default (D97).
     */
    fun place(
        hourPx: Double,
        titles: MeetingTitleFit,
    ): Placed {
        val slotPx = (to - from) * hourPx / 60
        val title = row.meeting.displayTitle
        val fit =
            if (row.startsBeforeToday) {
                titleFit(maxOf(slotPx, TURNED_SLOT_PX), title, if (titles == MeetingTitleFit.Flags) MeetingTitleFit.Diagonal else titles)
            } else {
                titleFit(slotPx, title, titles)
            }
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
