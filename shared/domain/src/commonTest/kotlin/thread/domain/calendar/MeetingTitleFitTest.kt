package thread.domain.calendar

import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.workspace.WorkspacePrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// D97: Settings "Narrow meeting titles", how the timeline shows a block too narrow for its title's run flat. Every
// meeting and title here is SYNTHETIC test data.
class MeetingTitleFitTest {
    private val today = LocalDay(2026, 11, 18)
    private val yesterday = today.plusDays(-1)

    /** A title longer than [MIN_TITLE_CHARS]. */
    private val long = "SYNTHETIC meeting"

    private fun at(
        hour: Int,
        minute: Int = 0,
        day: LocalDay = today,
    ) = WallTime.of(day, hour, minute)

    private fun hex(index: Int): String = index.toString(16).padStart(32, '0')

    private fun timed(
        index: Int,
        start: WallTime,
        end: WallTime,
        title: String = "SYNTHETIC meeting $index",
    ) = Meeting(MeetingId(hex(index)), SeriesKey(hex(1000 + index)), title, MeetingTime.Timed(start, end), MeetingResponse.Accepted)

    /** A meeting of [minutes] from [hour]:[minute]. */
    private fun lasting(
        minutes: Int,
        index: Int,
        hour: Int,
        minute: Int = 0,
        title: String = "SYNTHETIC meeting $index",
    ): Meeting {
        val end = hour * 60 + minute + minutes
        return timed(index, at(hour, minute), at(end / 60, end % 60), title)
    }

    /** A phone's timeline: 88 px an hour. */
    private fun phoneLine(
        titles: MeetingTitleFit,
        vararg meetings: Meeting,
    ) = meetingTimeline(todayMeetings(meetings.toList(), at(10)), at(10), 0.0, titles)

    private val TodayMeeting.number: Int get() = meeting.id.value.toInt(16)

    private fun flat() = TitleFit.Block(TitleTurn.Flat, TIMELINE_BLOCK_PX)

    /** On a phone: 15, 20, 30 and 45 minutes with long titles, an hour, 10 minutes, and a short title in 30. */
    private val busyMorning =
        arrayOf(
            lasting(15, 1, 11),
            lasting(20, 2, 11, 15),
            lasting(30, 3, 11, 35),
            lasting(45, 4, 12, 5),
            lasting(60, 5, 12, 50),
            lasting(10, 6, 12, 20),
            lasting(30, 7, 13, 50, title = "QA sync"),
        )

    @Test
    fun theIdsAreStoredAndUnknownOnesReadAsNotSet() {
        assertEquals(listOf("diagonal", "vertical", "flags", "wrap"), MeetingTitleFit.entries.map { it.id })
        MeetingTitleFit.entries.forEach { assertEquals(it, MeetingTitleFit.fromId(it.id)) }
        assertNull(MeetingTitleFit.fromId("Diagonal"))
        assertNull(MeetingTitleFit.fromId(""))
        assertNull(MeetingTitleFit.fromId(null))
        assertEquals(MeetingTitleFit.Diagonal, WorkspacePrefs().meetingTitleFit, "As before D97")
    }

    @Test
    fun everyChoiceKeepsFlagsAndFlatTitles() {
        MeetingTitleFit.entries.forEach { titles ->
            // Narrower than one turned line, 21 px: a flag, as 14 minutes on a phone.
            assertEquals(TitleFit.Flag, titleFit(20.99, long, titles), "$titles")
            assertEquals(TitleFit.Flag, titleFit(20.99, "QA", titles), "$titles")
            // The run flat: 8 characters and an ellipsis, or a short title whole.
            assertEquals(flat(), titleFit(78.0, long, titles), "$titles")
            assertEquals(flat(), titleFit(25.0, "QA", titles), "$titles")
            assertEquals(flat(), titleFit(67.0, "Planning", titles), "$titles")
        }
    }

    @Test
    fun aMeetingUnderFifteenMinutesOnAPhoneIsAFlagInEveryChoice() {
        MeetingTitleFit.entries.forEach { titles ->
            // A one-character title would lie flat in 18 px, but 14 minutes, 20.5 px, are too narrow for a block.
            val line = phoneLine(titles, lasting(14, 1, 11, title = "Q"), lasting(15, 2, 12, title = "Q"))

            assertEquals(listOf(1), line.flags.map { it.meeting.number }, "$titles")
            assertEquals(listOf(2), line.blocks.map { it.meeting.number }, "$titles: 15 minutes are 22 px")
            assertEquals(TitleTurn.Flat, line.blocks.single().turn, "$titles")
        }
        assertEquals(TitleFit.Flag, titleFit(14 * TIMELINE_MIN_HOUR_PX / 60, long, MeetingTitleFit.Wrap))
    }

    @Test
    fun diagonalIsTheDefaultAsBefore() {
        listOf(21.0, 22.0, 40.0, 50.0, 69.0, 77.9).forEach { slotPx ->
            assertEquals(titleFit(slotPx, long.length), titleFit(slotPx, long, MeetingTitleFit.Diagonal))
            assertEquals(titleFit(slotPx, 7), titleFit(slotPx, "Standup", MeetingTitleFit.Diagonal))
        }
        val meetings = todayMeetings(busyMorning.toList(), at(10))
        assertEquals(meetingTimeline(meetings, at(10), 1125.0), meetingTimeline(meetings, at(10), 1125.0, MeetingTitleFit.Diagonal))
    }

    @Test
    fun verticalTurnsTheTitleUpTheBlockAsBeforeTheDiagonal() {
        // D85: the run and 10 px up the block, at least 60 px. A long title: 67 px, so 77 px at every width.
        val longUp = TitleFit.Block(TitleTurn.Diagonal(90.0, 67.0), 77)
        listOf(21.0, 22.0, 40.0, 69.0, 77.9).forEach { assertEquals(longUp, titleFit(it, long, MeetingTitleFit.Vertical)) }
        assertEquals(TitleFit.Block(TitleTurn.Diagonal(90.0, 56.0), 66), titleFit(40.0, "Planning", MeetingTitleFit.Vertical))
        assertEquals(TitleFit.Block(TitleTurn.Diagonal(90.0, 50.0), 60), titleFit(40.0, "Standup", MeetingTitleFit.Vertical))
        // One line wide, the diagonal stands up too: the same line.
        assertEquals(titleFit(21.0, long.length), titleFit(21.0, long, MeetingTitleFit.Vertical))
    }

    @Test
    fun flagsMakeEveryBlockTooNarrowForItsTitleFlatAFlag() {
        assertEquals(TitleFit.Flag, titleFit(77.99, long, MeetingTitleFit.Flags))
        assertEquals(TitleFit.Flag, titleFit(21.0, long, MeetingTitleFit.Flags))
        assertEquals(TitleFit.Flag, titleFit(59.99, "QA sync", MeetingTitleFit.Flags))
        assertEquals(flat(), titleFit(60.0, "QA sync", MeetingTitleFit.Flags))
    }

    @Test
    fun wrapAddsALineUntilTheRunShows() {
        // On a phone: 15, 20, 30 and 45 minutes are 22, 29.3, 44 and 66 px, 11, 18.3, 33 and 55 px for the text, so 1,
        // 2, 4 and 7 characters a line. "SYNTHETI" ends on line 8, 4, 2 and 2, and the ellipsis fits after it only in
        // the widest.
        assertEquals(TitleFit.Block(TitleTurn.Wrapped(9), 165), titleFit(22.0, long, MeetingTitleFit.Wrap))
        assertEquals(TitleFit.Block(TitleTurn.Wrapped(5), 105), titleFit(88.0 / 3, long, MeetingTitleFit.Wrap))
        assertEquals(TitleFit.Block(TitleTurn.Wrapped(3), 75), titleFit(44.0, long, MeetingTitleFit.Wrap))
        assertEquals(TitleFit.Block(TitleTurn.Wrapped(2), 60), titleFit(66.0, long, MeetingTitleFit.Wrap))
    }

    @Test
    fun aShortTitleWrapsWholeWithNoEllipsis() {
        // 8 characters or fewer: the lines hold the whole title.
        assertEquals(2, titleLines("Standup", 30.0), "Stan, dup")
        assertEquals(7, titleLines("Standup", 11.0))
        assertEquals(2, titleLines("Planning", 33.0), "Plan, ning: nothing follows, so no ellipsis")
        assertEquals(TitleFit.Block(TitleTurn.Wrapped(2), 60), titleFit(44.0, "QA sync", MeetingTitleFit.Wrap))
    }

    @Test
    fun linesBreakWhereWordsEnd() {
        // 7 characters a line: "Weekly", then "sync" on the next; the 8th character is its "s".
        assertEquals(2, titleLines("Weekly sync with design", 50.0))
        // 8 a line: "Team", then "planning", whose 3rd character is the 8th of the title.
        assertEquals(2, titleLines("Team planning review", 60.0))
        // A title that ends on the run's line needs no ellipsis, though it is longer than 8: "Daily", "sync".
        assertEquals(2, titleLines("Daily sync", 42.0))
        // Spaces together count as one, and none at either end.
        assertEquals(titleLines("Daily sync", 42.0), titleLines("  Daily   sync  ", 42.0))
    }

    @Test
    fun aWordWiderThanTheBlockBreaksWhereTheLineEnds() {
        // 4 characters a line: "Supe", "rcal", and the ellipsis does not fit after the "l", so a third line.
        assertEquals(3, titleLines("Supercalifragilistic", 33.0))
        // A long word after a short one starts its own line first, as the block draws it: "QA", "Super", "calif".
        assertEquals(3, titleLines("QA Supercalifragilistic", 40.0))
        assertEquals(TitleFit.Block(TitleTurn.Wrapped(3), 75), titleFit(44.0, "Supercalifragilistic", MeetingTitleFit.Wrap))
    }

    @Test
    fun theEllipsisNeedsRoomAfterTheRunOrALineMore() {
        // 8 a line: "Abcdefgh" fills the first, so the ellipsis goes on the second.
        assertEquals(2, titleLines("Abcdefgh ijk", 56.0))
        // 9 a line and 67 px: 8 characters and the ellipsis just fit, as a flat title does at 78 px.
        assertEquals(1, titleLines("Abcdefgh ijk", 67.0))
        assertEquals(1, titleLines("", 40.0))
    }

    @Test
    fun aBusyMorningInEachChoice() {
        val vertical = phoneLine(MeetingTitleFit.Vertical, *busyMorning)
        assertEquals(listOf(77, 77, 77, 77, 60, 60), vertical.blocks.map { it.heightPx }, "5 lies flat; QA sync needs 59 px")
        assertEquals(listOf(77), vertical.laneHeightsPx)
        assertEquals(listOf(6), vertical.flags.map { it.meeting.number })

        val flags = phoneLine(MeetingTitleFit.Flags, *busyMorning)
        assertEquals(listOf(5), flags.blocks.map { it.meeting.number }, "Only the hour lies flat")
        assertEquals(listOf(1, 2, 3, 4, 6, 7), flags.flags.map { it.meeting.number })
        assertEquals(listOf(1, 2, 3, 4, 6, 5, 7), flags.items.map { it.meeting.number }, "Tab follows the day")

        val wrap = phoneLine(MeetingTitleFit.Wrap, *busyMorning)
        val lines = listOf(9, 5, 3, 2, null, 2)
        assertEquals(lines.map { it?.let(TitleTurn::Wrapped) ?: TitleTurn.Flat }, wrap.blocks.map { it.turn })
        assertEquals(listOf(165, 105, 75, 60, 60, 60), wrap.blocks.map { it.heightPx })
        assertEquals(listOf(165), wrap.laneHeightsPx, "Back to back in one lane, as tall as its tallest")
    }

    @Test
    fun aTallWrappedBlockMovesTheLanesBelow() {
        val line = phoneLine(MeetingTitleFit.Wrap, lasting(30, 1, 11), lasting(60, 2, 11, 15))

        assertEquals(listOf(75, 60), line.laneHeightsPx)
        assertEquals(6 + 75 + 6, line.laneTopPx(1))
    }

    @Test
    fun aShortBlockFromYesterdayStaysABlockWithFlags() {
        val meetings = arrayOf(timed(1, at(23, 50, day = yesterday), at(0, 5)), timed(2, at(0, 10), at(0, 20)))

        val line = phoneLine(MeetingTitleFit.Flags, *meetings)
        // 1 keeps its square edge at midnight, its title along the diagonal (the default); 2 starts today: a flag.
        assertEquals(listOf(1), line.blocks.map { it.meeting.number })
        assertEquals(TitleTurn.Diagonal(90.0, 67.0), line.blocks.single().turn)
        assertEquals(listOf(2), line.flags.map { it.meeting.number })
        // The other choices draw it their way.
        assertEquals(TitleTurn.Wrapped(9), phoneLine(MeetingTitleFit.Wrap, *meetings).blocks.single().turn)
        assertEquals(TitleTurn.Diagonal(90.0, 67.0), phoneLine(MeetingTitleFit.Vertical, *meetings).blocks.single().turn)
    }
}
