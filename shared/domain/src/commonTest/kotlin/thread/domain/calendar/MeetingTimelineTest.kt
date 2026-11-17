package thread.domain.calendar

import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// D79 and D85: the meetings timeline's hours, lanes, flags, title fit and now. Every meeting here is SYNTHETIC test data.
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

    private fun MeetingTimeline.turnsById(): Map<Int, TitleTurn> = blocks.associate { it.meeting.number to it.turn }

    private fun MeetingTimeline.rowsById(): Map<Int, Int> = flags.associate { it.meeting.number to it.row }

    private fun MeetingTimeline.roomsById(): Map<Int, Int> = flags.associate { it.meeting.number to it.roomMinutes }

    private fun flat() = TitleFit.Block(TitleTurn.Flat, TIMELINE_BLOCK_PX)

    private fun up(heightPx: Int = TIMELINE_BLOCK_PX) = TitleFit.Block(TitleTurn.Up, heightPx)

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
        assertEquals(TitleTurn.Up, line.blocks.first().turn)
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
        assertEquals(
            mapOf(1 to TitleTurn.Up, 2 to TitleTurn.Up, 3 to TitleTurn.Up, 4 to TitleTurn.Flat, 5 to TitleTurn.Flat, 7 to TitleTurn.Flat),
            wideLine.turnsById(),
        )
        assertEquals(listOf(6), wideLine.flags.map { it.meeting.number })
        // 88 px an hour: a long title lies flat from 53.2 minutes; "QA sync" from 40.9.
        val phoneLine = timeline(at(10), *meetings)
        assertEquals(
            mapOf(1 to TitleTurn.Up, 2 to TitleTurn.Up, 3 to TitleTurn.Up, 4 to TitleTurn.Up, 5 to TitleTurn.Flat, 7 to TitleTurn.Up),
            phoneLine.turnsById(),
        )
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
        assertEquals(TitleTurn.Up, (titleFit(edge - 0.01, long) as TitleFit.Block).turn)
        // One character under the minimum turns it.
        val oneUnder = TIMELINE_FLAT_SIDES_PX + (MIN_TITLE_CHARS - 1) * TIMELINE_CHAR_PX + TIMELINE_ELLIPSIS_PX
        assertEquals(TitleTurn.Up, (titleFit(oneUnder, long) as TitleFit.Block).turn)
    }

    @Test
    fun aTitleOfMinTitleCharsOrFewerLiesFlatWhole() {
        val whole = TIMELINE_FLAT_SIDES_PX + MIN_TITLE_CHARS * TIMELINE_CHAR_PX

        // No ellipsis: the whole title fits.
        assertEquals(flat(), titleFit(whole, MIN_TITLE_CHARS))
        assertEquals(TitleTurn.Up, (titleFit(whole - 0.01, MIN_TITLE_CHARS) as TitleFit.Block).turn)
        assertEquals(flat(), titleFit(TIMELINE_FLAT_SIDES_PX + 7 * TIMELINE_CHAR_PX, "QA sync".length))
    }

    @Test
    fun aSlotNarrowerThanOneTurnedLineIsAFlag() {
        val line = TIMELINE_TURNED_SIDES_PX + TIMELINE_LINE_PX

        assertEquals(21.0, line)
        assertEquals(up(TIMELINE_BLOCK_PX + 17), titleFit(line, long))
        assertEquals(TitleFit.Flag, titleFit(line - 0.01, long))
        // A short title would fit flat, but a block is never narrower than a turned line.
        assertEquals(flat(), titleFit(line, 1))
        assertEquals(TitleFit.Flag, titleFit(line - 0.01, 1))
    }

    @Test
    fun aTurnedTitleKeepsTheBlockHeightWhileItFits() {
        // 7 characters need 10 + 49 px: the 60 px block holds them.
        assertEquals(up(), titleFit(40.0, 7))
    }

    @Test
    fun aTurnedTitleOneCharacterShortGrowsTheBlock() {
        // 8 characters need 10 + 56 px, 6 more than the block's 60: it is 66 px tall.
        assertEquals(up(66), titleFit(40.0, MIN_TITLE_CHARS))
    }

    @Test
    fun aTurnedLongTitleGrowsTheBlockExactly() {
        // 8 characters, an ellipsis and the ends: 10 + 56 + 11 px.
        assertEquals(up(77), titleFit(40.0, long))
        assertEquals(up(77), titleFit(40.0, 300), "Longer titles need no more: the rest is cut")
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

        // 1 is turned and 77 px tall; 2 overlaps it in the second lane; 3 follows 1 in the first lane.
        assertEquals(mapOf(1 to 0, 2 to 1, 3 to 0), line.lanesById())
        assertEquals(listOf(77, 60, 60), line.blocks.map { it.heightPx }, "Only the block that needs it grows")
        assertEquals(listOf(77, 60), line.laneHeightsPx)
        assertEquals(6, line.laneTopPx(0))
        assertEquals(6 + 77 + 6, line.laneTopPx(1))
        assertEquals(6 + 77 + 6 + 60 + 6, line.laneTopPx(line.lanes), "The lanes' whole height")
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
