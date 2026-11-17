package thread.domain.calendar

import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// D79, D85 and D88: the meetings timeline's hours, lanes, flags, title fit and now. Every meeting here is SYNTHETIC
// test data.
class MeetingTimelineTest {
    private val today = LocalDay(2026, 11, 17)
    private val yesterday = today.plusDays(-1)
    private val tomorrow = today.plusDays(1)

    /** A card 1125 px wide: 125 px for each of the nine working hours. */
    private val wide = 1125.0

    /** A phone: narrower than the hours need, so each is [TIMELINE_MIN_HOUR_PX]. */
    private val phone = 0.0

    /** A title longer than [MIN_TITLE_CHARS]: the test meetings' own. */
    private val long = "SYNTHETIC meeting".length

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
    ) = Meeting(
        MeetingId(hex(index)),
        SeriesKey(hex(1000 + index)),
        title,
        MeetingTime.Timed(start, end),
        MeetingResponse.Accepted,
    )

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

    /** A 10-minute meeting from [hour]:[minute]: a flag at every width. */
    private fun brief(
        index: Int,
        hour: Int,
        minute: Int = 0,
    ) = lasting(10, index, hour, minute)

    private fun allDay(index: Int) =
        Meeting(
            MeetingId(hex(index)),
            SeriesKey(hex(1000 + index)),
            "SYNTHETIC all-day $index",
            MeetingTime.AllDay(today, tomorrow),
            MeetingResponse.Accepted,
        )

    private fun timeline(
        now: WallTime,
        vararg meetings: Meeting,
        widthPx: Double = phone,
    ) = meetingTimeline(todayMeetings(meetings.toList(), now), now, widthPx)

    /** The index a test meeting was made with. */
    private val TodayMeeting.number: Int get() = meeting.id.value.toInt(16)

    private fun MeetingTimeline.lanesById(): Map<Int, Int> = blocks.associate { it.meeting.number to it.lane }

    /** The blocks whose title runs along their diagonal, in time order; the others lie flat. */
    private fun MeetingTimeline.diagonalIds(): List<Int> = blocks.filter { it.turn is TitleTurn.Diagonal }.map { it.meeting.number }

    private fun MeetingTimeline.rowsById(): Map<Int, Int> = flags.associate { it.meeting.number to it.row }

    private fun MeetingTimeline.roomsById(): Map<Int, Int> = flags.associate { it.meeting.number to it.roomMinutes }

    private fun flat() = TitleFit.Block(TitleTurn.Flat, TIMELINE_BLOCK_PX)

    private fun diagonal(
        slotPx: Double,
        heightPx: Int,
    ) = TitleFit.Block(titleDiagonal(slotPx, heightPx), heightPx)

    private fun Double.tenths(): Double = round(this * 10) / 10

    /** The px a long title needs: [MIN_TITLE_CHARS] characters and an ellipsis. */
    private val longRun = MIN_TITLE_CHARS * TIMELINE_CHAR_PX + TIMELINE_ELLIPSIS_PX

    /** The line [titleDiagonal] finds touches all four sides of the block's room for it: across, then up. */
    private fun assertCornerToCorner(
        slotPx: Double,
        heightPx: Int,
    ) {
        val line = titleDiagonal(slotPx, heightPx)
        val angle = line.angleDeg * PI / 180
        val message = "A $slotPx px slot, $heightPx px tall: $line"
        assertEquals(slotPx - TIMELINE_TURNED_SIDES_PX, line.lengthPx * cos(angle) + TIMELINE_LINE_PX * sin(angle), 1e-9, message)
        assertEquals(heightPx - TIMELINE_TURNED_ENDS_PX, line.lengthPx * sin(angle) + TIMELINE_LINE_PX * cos(angle), 1e-9, message)
    }

    @Test
    fun anEmptyDayShowsTheWorkingHoursAndNow() {
        val line = timeline(at(12, 15), widthPx = wide)

        assertEquals(10 * 60, line.startMinute)
        assertEquals(19 * 60, line.endMinute)
        assertEquals(9, line.hours)
        assertEquals(125.0, line.hourPx, "The hours share the width")
        assertEquals(0, line.lanes)
        assertEquals(0, line.flagRows)
        assertEquals(emptyList(), line.items)
        assertEquals(12 * 60 + 15, line.nowMinute)
        assertEquals(0.25, line.offsetOf(12 * 60 + 15))
    }

    @Test
    fun aNarrowCardKeepsTheNarrowestHours() {
        assertEquals(TIMELINE_MIN_HOUR_PX, timeline(at(12)).hourPx)
        assertEquals(TIMELINE_MIN_HOUR_PX, timeline(at(12), widthPx = 9 * 88.0 - 1).hourPx)
        assertEquals(90.0, timeline(at(12), widthPx = 9 * 90.0).hourPx)
    }

    @Test
    fun meetingsInsideTheWorkingHoursKeepThem() {
        val line = timeline(at(10), timed(1, at(10), at(11)), timed(2, at(18), at(19)))

        assertEquals(10 * 60, line.startMinute)
        assertEquals(19 * 60, line.endMinute)
        assertEquals(listOf(10 * 60 to 11 * 60, 18 * 60 to 19 * 60), line.blocks.map { it.fromMinute to it.toMinute })
        assertEquals(1, line.lanes)
        assertEquals(0, line.flagRows, "An hour long is a block")
    }

    @Test
    fun earlierAndLaterMeetingsWidenTheHoursToWholeHours() {
        val line = timeline(at(12), lasting(30, 1, 8, 30), lasting(30, 2, 19, 30), widthPx = wide)

        assertEquals(8 * 60, line.startMinute)
        assertEquals(20 * 60, line.endMinute)
        assertEquals(12, line.hours)
        assertEquals(wide / 12, line.hourPx, "More hours share the same width")
    }

    @Test
    fun meetingsAcrossMidnightShowTheirPartOfToday() {
        val line =
            timeline(
                at(12),
                timed(1, at(23, day = yesterday), at(1)),
                timed(2, at(23), at(1, day = tomorrow)),
            )

        assertEquals(0, line.startMinute)
        assertEquals(24 * 60, line.endMinute)
        assertEquals(listOf(0 to 60, 23 * 60 to 24 * 60), line.blocks.map { it.fromMinute to it.toMinute })
    }

    @Test
    fun aMeetingEndingAtMidnightEndsAtTheEndOfToday() {
        val line = timeline(at(12), timed(1, at(22), at(0, day = tomorrow)))

        assertEquals(22 * 60 to 24 * 60, line.blocks.single().let { it.fromMinute to it.toMinute })
        assertEquals(24 * 60, line.endMinute)
    }

    @Test
    fun overlappingMeetingsTakeTheFirstFreeLaneInTimeOrder() {
        val line =
            timeline(
                at(8),
                timed(4, at(11, 15), at(12, 15)),
                timed(1, at(10), at(11)),
                timed(3, at(11), at(12)),
                timed(2, at(10, 30), at(11, 30)),
                timed(5, at(12), at(13)),
            )

        // A meeting that starts as another ends shares its lane.
        assertEquals(mapOf(1 to 0, 2 to 1, 3 to 0, 4 to 2, 5 to 0), line.lanesById())
        assertEquals(3, line.lanes)
        assertEquals(listOf(1, 2, 3, 4, 5), line.blocks.map { it.meeting.number }, "Time order")
    }

    @Test
    fun aShortBlockFromYesterdayKeepsRoomToBeClicked() {
        val line =
            timeline(
                at(8),
                timed(1, at(23, 50, day = yesterday), at(0, 5)),
                timed(2, at(0, 10), at(1, 10)),
                timed(3, at(0, 15), at(1, 15)),
            )

        // 1 is 5 minutes of today, 7 px, so it keeps one turned line's 21 px: 15 minutes at 88 px an hour. So 2 starts
        // a lane and 3 fits after 1.
        assertEquals(mapOf(1 to 0, 2 to 1, 3 to 0), line.lanesById())
        assertEquals(5, line.blocks.first().toMinute, "The block keeps the real end")
        assertEquals(TitleTurn.Diagonal(90.0, 67.0), line.blocks.first().turn, "One line wide: upright")
        assertEquals(0, line.flagRows)
    }

    @Test
    fun aMeetingTooNarrowForATurnedLineIsAFlag() {
        val line =
            timeline(
                at(8),
                timed(1, at(10), at(10, 14)),
                timed(2, at(11), at(11, 15)),
                timed(3, at(12), at(12)),
            )

        // At 88 px an hour, 14 minutes are 20.5 px and 15 are 22 px; a turned line needs 21.
        assertEquals(listOf(1, 3), line.flags.map { it.meeting.number })
        assertEquals(listOf(2), line.blocks.map { it.meeting.number })
        assertEquals(10 * 60 to 10 * 60 + 14, line.flags.first().let { it.fromMinute to it.toMinute }, "The flag keeps the real times")
        assertEquals(1, line.lanes, "Lanes count blocks only")
        assertEquals(1, line.flagRows)
    }

    @Test
    fun widerHoursMakeAShorterMeetingABlock() {
        val meetings = arrayOf(lasting(11, 1, 12), lasting(14, 2, 14))

        // 11 minutes are 22.9 px at 125 px an hour and 16.1 px at 88.
        assertEquals(listOf(1, 2), timeline(at(10), *meetings, widthPx = wide).blocks.map { it.meeting.number })
        assertEquals(listOf(1, 2), timeline(at(10), *meetings).flags.map { it.meeting.number })
    }

    @Test
    fun aShortPartOfTodayIsAFlagUnlessItStartedYesterday() {
        val line =
            timeline(
                at(12),
                timed(1, at(23, 30, day = yesterday), at(0, 10)),
                timed(2, at(23, 50), at(0, 30, day = tomorrow)),
            )

        // 1 keeps its square edge at midnight as a block; 2 starts today, so its pole marks when it starts.
        assertEquals(listOf(1), line.blocks.map { it.meeting.number })
        assertEquals(listOf(2), line.flags.map { it.meeting.number })
        assertEquals(23 * 60 + 50 to 24 * 60, line.flags.single().let { it.fromMinute to it.toMinute })
    }

    @Test
    fun aBusyMorningTurnsTitlesByTheirWidth() {
        val meetings =
            arrayOf(
                lasting(15, 1, 11),
                lasting(20, 2, 11, 15),
                lasting(30, 3, 11, 35),
                lasting(45, 4, 12, 5),
                lasting(60, 5, 12, 50),
                brief(6, 12, 20),
                lasting(30, 7, 13, 50, title = "QA sync"),
            )

        // 125 px an hour: a long title lies flat from 78 px, 37.4 minutes; "QA sync" whole from 60 px.
        val wideLine = timeline(at(10), *meetings, widthPx = wide)
        assertEquals(listOf(1, 2, 3), wideLine.diagonalIds(), "4, 5 and 7 lie flat")
        assertEquals(listOf(6), wideLine.flags.map { it.meeting.number })
        // The wider the block, the less steep its diagonal and the less it grows: 15, 20 and 30 minutes.
        val turned = wideLine.blocks.take(3)
        assertEquals(listOf(81.1, 71.5, 47.7), turned.map { (it.turn as TitleTurn.Diagonal).angleDeg.tenths() })
        assertEquals(listOf(79, 79, 70), turned.map { it.heightPx })
        // 88 px an hour: a long title lies flat from 53.2 minutes; "QA sync" from 40.9.
        val phoneLine = timeline(at(10), *meetings)
        assertEquals(listOf(1, 2, 3, 4, 7), phoneLine.diagonalIds(), "5 lies flat")
        assertEquals(listOf(6), phoneLine.flags.map { it.meeting.number })
        // Back to back: every block in the first lane, and Tab follows the day.
        assertEquals(setOf(0), phoneLine.lanesById().values.toSet())
        assertEquals(listOf(1, 2, 3, 4, 6, 5, 7), phoneLine.items.map { it.meeting.number })
    }

    @Test
    fun aLongTitleLiesFlatFromMinTitleCharsAndAnEllipsis() {
        val edge = TIMELINE_FLAT_SIDES_PX + MIN_TITLE_CHARS * TIMELINE_CHAR_PX + TIMELINE_ELLIPSIS_PX

        assertEquals(78.0, edge)
        assertEquals(flat(), titleFit(edge, long))
        assertTrue((titleFit(edge - 0.01, long) as TitleFit.Block).turn is TitleTurn.Diagonal)
        // One character under the minimum turns it.
        val oneUnder = TIMELINE_FLAT_SIDES_PX + (MIN_TITLE_CHARS - 1) * TIMELINE_CHAR_PX + TIMELINE_ELLIPSIS_PX
        assertTrue((titleFit(oneUnder, long) as TitleFit.Block).turn is TitleTurn.Diagonal)
    }

    @Test
    fun aTitleOfMinTitleCharsOrFewerLiesFlatWhole() {
        val whole = TIMELINE_FLAT_SIDES_PX + MIN_TITLE_CHARS * TIMELINE_CHAR_PX

        // No ellipsis: the whole title fits.
        assertEquals(flat(), titleFit(whole, MIN_TITLE_CHARS))
        assertTrue((titleFit(whole - 0.01, MIN_TITLE_CHARS) as TitleFit.Block).turn is TitleTurn.Diagonal)
        assertEquals(flat(), titleFit(TIMELINE_FLAT_SIDES_PX + 7 * TIMELINE_CHAR_PX, "QA sync".length))
    }

    @Test
    fun aSlotNarrowerThanOneTurnedLineIsAFlag() {
        val line = TIMELINE_TURNED_SIDES_PX + TIMELINE_LINE_PX

        assertEquals(21.0, line)
        // One line wide, the line can only stand up, as long as the room is tall: 77 px, as D85's upright line.
        assertEquals(TitleFit.Block(TitleTurn.Diagonal(90.0, 67.0), TIMELINE_BLOCK_PX + 17), titleFit(line, long))
        assertEquals(TitleFit.Flag, titleFit(line - 0.01, long))
        // A short title would fit flat, but a block is never narrower than a turned line.
        assertEquals(flat(), titleFit(line, 1))
        assertEquals(TitleFit.Flag, titleFit(line - 0.01, 1))
    }

    @Test
    fun aTurnedTitleRunsFromTheBottomLeftCornerToTheTopRight() {
        // D88: the line touches all four sides of the block's room, its start the left and the bottom, its end the
        // top and the right. From one line wide to just under flat, and in a short and a tall block.
        listOf(21.0 to 77, 22.0 to 78, 31.25 to 79, 40.0 to 79, 56.0 to 60, 62.5 to 70, 77.9 to 60, 77.9 to 200)
            .forEach { (slotPx, heightPx) -> assertCornerToCorner(slotPx, heightPx) }
    }

    @Test
    fun aSquareRoomRunsItsTitleAtFortyFiveDegrees() {
        // A 56 px slot and a 60 px block leave a 50 px square: the line is its diagonal less the line's own height.
        val line = titleDiagonal(56.0, TIMELINE_BLOCK_PX)

        assertEquals(45.0, line.angleDeg, 1e-9)
        assertEquals(50 * sqrt(2.0) - TIMELINE_LINE_PX, line.lengthPx, 1e-9)
    }

    @Test
    fun aTallNarrowBlockRunsItsTitleNearlyUpright() {
        // 15 minutes on a phone, 22 px: a room 16 px wide, 1 px more than the line. It leans a little, so it is
        // shorter than the room is tall, and steeper than the room's own diagonal.
        val line = titleDiagonal(22.0, 78)

        assertEquals(89.2, line.angleDeg.tenths())
        assertTrue(line.lengthPx < 78 - TIMELINE_TURNED_ENDS_PX)
        assertTrue(line.angleDeg > atan2(78 - TIMELINE_TURNED_ENDS_PX, 22 - TIMELINE_TURNED_SIDES_PX) * 180 / PI)
    }

    @Test
    fun aWiderOrShorterBlockRunsItsTitleLessSteeply() {
        val acrossSlots = listOf(22.0, 31.25, 41.7, 62.5, 77.9).map { titleDiagonal(it, 79).angleDeg }
        val acrossHeights = listOf(200, 120, 79, 60).map { titleDiagonal(40.0, it).angleDeg }

        assertEquals(acrossSlots.sortedDescending(), acrossSlots)
        assertEquals(acrossHeights.sortedDescending(), acrossHeights)
    }

    @Test
    fun aLongTitleKeepsTheBlockHeightFromTheEdgeOfItsDiagonal() {
        // The narrowest slot whose 60 px block holds a long title, 67 px, along its diagonal: where the line, 15 px
        // high, is 50 px tall, as the room. Its width there is the room's.
        val up = TIMELINE_BLOCK_PX - TIMELINE_TURNED_ENDS_PX
        val angle = asin(up / hypot(longRun, TIMELINE_LINE_PX)) - atan2(TIMELINE_LINE_PX, longRun)
        val edge = TIMELINE_TURNED_SIDES_PX + longRun * cos(angle) + TIMELINE_LINE_PX * sin(angle)

        assertEquals(69.88, edge, 0.005)
        assertEquals(diagonal(edge + 0.001, TIMELINE_BLOCK_PX), titleFit(edge + 0.001, long))
        assertEquals(diagonal(edge - 0.001, TIMELINE_BLOCK_PX + 1), titleFit(edge - 0.001, long))
        // An upright line (D85) needed 77 px at every width; 30 minutes in a 1440 px window (D87) are 73.1 px.
        assertEquals(TIMELINE_BLOCK_PX, (titleFit(73.1, long) as TitleFit.Block).heightPx)
    }

    @Test
    fun oneCharacterMoreThanTheDiagonalHoldsGrowsTheBlock() {
        // A 50 px slot's 60 px block holds 51.9 px along its diagonal: 7 characters, 49 px, fit. 8 need 56 px: the
        // block is the fewest px taller whose diagonal holds them.
        assertEquals(diagonal(50.0, TIMELINE_BLOCK_PX), titleFit(50.0, 7))
        assertEquals(diagonal(50.0, 65), titleFit(50.0, MIN_TITLE_CHARS))
        assertTrue(titleDiagonal(50.0, 64).lengthPx < MIN_TITLE_CHARS * TIMELINE_CHAR_PX)
    }

    @Test
    fun aTurnedLongTitleGrowsTheBlockExactly() {
        // A 40 px slot: at 78 px the diagonal holds 66.5 px, at 79 px 67.6, enough for 8 characters and an ellipsis.
        // 2 px more than an upright line needed (D85): a narrow block's thick line takes height as it leans.
        assertTrue(titleDiagonal(40.0, 78).lengthPx < longRun)
        assertTrue(titleDiagonal(40.0, 79).lengthPx >= longRun)
        assertEquals(diagonal(40.0, 79), titleFit(40.0, long))
        assertEquals(diagonal(40.0, 79), titleFit(40.0, 300), "Longer titles need no more: the rest is cut")
    }

    @Test
    fun aTallBlockMakesItsLaneTallerAndMovesTheLanesBelow() {
        val line =
            timeline(
                at(10),
                lasting(30, 1, 11),
                lasting(60, 2, 11, 15),
                lasting(60, 3, 12),
                widthPx = wide,
            )

        // 1 is 62.5 px wide, its title along its diagonal, and 70 px tall; 2 overlaps it in the second lane; 3
        // follows 1 in the first lane.
        assertEquals(mapOf(1 to 0, 2 to 1, 3 to 0), line.lanesById())
        assertEquals(listOf(70, 60, 60), line.blocks.map { it.heightPx }, "Only the block that needs it grows")
        assertEquals(listOf(70, 60), line.laneHeightsPx)
        assertEquals(6, line.laneTopPx(0))
        assertEquals(6 + 70 + 6, line.laneTopPx(1))
        assertEquals(6 + 70 + 6 + 60 + 6, line.laneTopPx(line.lanes), "The lanes' whole height")
    }

    @Test
    fun flagsAndBlocksComeInTimeOrder() {
        val line =
            timeline(
                at(8),
                timed(3, at(11), at(12)),
                brief(1, 10),
                brief(4, 11, 30),
                timed(2, at(10, 30), at(11, 30)),
            )

        assertEquals(listOf(1, 2, 3, 4), line.items.map { it.meeting.number }, "Tab follows the day")
    }

    @Test
    fun aBusyHourIsAStaircaseEarliestHighest() {
        val line = timeline(at(8), brief(1, 10), brief(2, 10, 15), brief(3, 10, 30))

        assertEquals(mapOf(1 to 2, 2 to 1, 3 to 0), line.rowsById())
        assertEquals(mapOf(1 to 90, 2 to 90, 3 to 90), line.roomsById())
        assertEquals(3, line.flagRows)
    }

    @Test
    fun aFlagTakesTheLowestRowWithAnHourFree() {
        val line = timeline(at(8), brief(1, 10), brief(2, 10, 45), brief(3, 12))

        // 2 has 75 minutes before 3 in the first row, so it stays there with a shorter title. 1 has 45 there, too few.
        assertEquals(mapOf(1 to 1, 2 to 0, 3 to 0), line.rowsById())
        assertEquals(mapOf(1 to 90, 2 to 75, 3 to 90), line.roomsById())
        assertEquals(2, line.flagRows)
    }

    @Test
    fun aCutTitleMovesUpToARowWhereItFits() {
        val line = timeline(at(8), brief(1, 10), brief(2, 10, 15), brief(3, 17), brief(4, 18))

        // 3 had an hour before 4 in the first row. The second row has its whole title, and no row is added for it.
        assertEquals(mapOf(1 to 1, 2 to 0, 3 to 1, 4 to 0), line.rowsById())
        assertEquals(mapOf(1 to 90, 2 to 90, 3 to 90, 4 to 60), line.roomsById(), "4 has the hour left before 19:00")
        assertEquals(2, line.flagRows)
    }

    @Test
    fun aCutTitleStaysWhenItsPoleWouldCrossATitle() {
        val line =
            timeline(
                at(8),
                brief(1, 10),
                brief(2, 10, 15),
                brief(3, 10, 30),
                brief(4, 11, 30),
                brief(5, 12, 40),
            )

        // The third row has room for 4's whole title, but its pole would cross 2's title in the second row.
        assertEquals(mapOf(1 to 2, 2 to 1, 3 to 0, 4 to 0, 5 to 0), line.rowsById())
        assertEquals(mapOf(1 to 90, 2 to 90, 3 to 60, 4 to 70, 5 to 90), line.roomsById())
        assertEquals(3, line.flagRows)
    }

    @Test
    fun flagsAtOneStartShareThePoleOneAboveTheOther() {
        val line = timeline(at(8), timed(1, at(11), at(11, 5)), timed(2, at(11), at(11, 10)))

        assertEquals(mapOf(1 to 1, 2 to 0), line.rowsById())
        assertEquals(mapOf(1 to 90, 2 to 90), line.roomsById())
    }

    @Test
    fun flagsWidenTheHoursForTheirTitle() {
        val line = timeline(at(10), brief(1, 7, 30), timed(2, at(18, 50), at(18, 50)))

        assertEquals(7 * 60, line.startMinute)
        assertEquals(20 * 60, line.endMinute, "An hour after 18:50 is 19:50")
        assertEquals(mapOf(1 to 90, 2 to 70), line.roomsById())
    }

    @Test
    fun theHoursWidenUntilTheyHoldStill() {
        val line = timeline(at(10), lasting(11, 1, 12), brief(2, 18, 45), widthPx = wide)

        // At 125 px an hour 1 is a block, 22.9 px, and 2 a flag whose title widens the hours to 20:00. Ten hours are
        // 112.5 px each, so 1 is 20.6 px: a flag too.
        assertEquals(20 * 60, line.endMinute)
        assertEquals(112.5, line.hourPx)
        assertEquals(listOf(1, 2), line.flags.map { it.meeting.number })
        assertEquals(0, line.lanes)
    }

    @Test
    fun aFlagLateAtNightStaysInsideToday() {
        val line = timeline(at(12), timed(1, at(23, 50), at(23, 50)))

        assertEquals(24 * 60, line.endMinute)
        assertEquals(23 * 60 + 50, line.flags.single().toMinute)
        assertEquals(10, line.flags.single().roomMinutes, "The title has the ten minutes left")
    }

    @Test
    fun aDayOfOnlyFlagsHasNoLanes() {
        val line = timeline(at(10), brief(1, 10), brief(2, 10, 15), timed(3, at(16), at(16, 14)))

        assertEquals(0, line.lanes)
        assertEquals(2, line.flagRows)
        assertEquals(emptyList(), line.blocks)
        assertEquals(TIMELINE_LANE_GAP_PX, line.laneTopPx(line.lanes))
    }

    @Test
    fun nowShowsOnlyInsideTheHoursShown() {
        assertNull(timeline(at(9, 59)).nowMinute)
        assertEquals(10 * 60, timeline(at(10)).nowMinute)
        assertEquals(19 * 60, timeline(at(19)).nowMinute)
        assertNull(timeline(at(19, 30)).nowMinute)
        assertEquals(20 * 60 + 30, timeline(at(20, 30), timed(1, at(20), at(21))).nowMinute)
    }

    @Test
    fun allDayMeetingsSitApartFromTheHours() {
        val line = timeline(at(10), allDay(2), timed(1, at(11), at(12)), allDay(3))

        assertEquals(listOf(2, 3), line.allDay.map { it.number })
        assertEquals(listOf(1), line.blocks.map { it.meeting.number })
        assertEquals(10 * 60, line.startMinute)
        assertEquals(19 * 60, line.endMinute)
    }

    @Test
    fun blocksKeepTheirPhase() {
        val line = timeline(at(11, 30), timed(1, at(10), at(11)), timed(2, at(11), at(12)), timed(3, at(13), at(14)))

        assertEquals(listOf(MeetingPhase.Ended, MeetingPhase.Now, MeetingPhase.Next), line.blocks.map { it.meeting.phase })
    }
}
