package thread.domain.calendar

import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// D79 and D80: the meetings timeline's hours, chips, rows and now. Every meeting here is SYNTHETIC test data.
class MeetingTimelineTest {
    private val today = LocalDay(2026, 11, 17)
    private val yesterday = today.plusDays(-1)
    private val tomorrow = today.plusDays(1)

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
    ) = Meeting(
        MeetingId(hex(index)),
        SeriesKey(hex(1000 + index)),
        "SYNTHETIC meeting $index",
        MeetingTime.Timed(start, end),
        MeetingResponse.Accepted,
    )

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
    ) = meetingTimeline(todayMeetings(meetings.toList(), now), now)

    /** The index a test meeting was made with. */
    private val TodayMeeting.number: Int get() = meeting.id.value.toInt(16)

    private fun MeetingTimeline.rowsById(): Map<Int, Int> = blocks.associate { it.meeting.number to it.row }

    private fun MeetingTimeline.block(number: Int): TimelineBlock = blocks.single { it.meeting.number == number }

    private fun TimelineBlock.drawn(): Pair<Int, Int> = drawnFromMinute to drawnToMinute

    @Test
    fun anEmptyDayShowsTheWorkingHoursAndNow() {
        val line = timeline(at(10, 30))

        assertEquals(8 * 60, line.startMinute)
        assertEquals(18 * 60, line.endMinute)
        assertEquals(10, line.hours)
        assertEquals(0, line.rows)
        assertEquals(emptyList(), line.blocks)
        assertEquals(10 * 60 + 30, line.nowMinute)
        assertEquals(0.25, line.offsetOf(10 * 60 + 30))
    }

    @Test
    fun meetingsInsideTheWorkingHoursKeepThem() {
        val line = timeline(at(10), timed(1, at(8), at(9)), timed(2, at(17), at(18)))

        assertEquals(8 * 60, line.startMinute)
        assertEquals(18 * 60, line.endMinute)
        assertEquals(listOf(8 * 60 to 9 * 60, 17 * 60 to 18 * 60), line.blocks.map { it.fromMinute to it.toMinute })
        assertEquals(2, line.rows, "A block takes two rows")
    }

    @Test
    fun anHourOrMoreIsABlockDrawnAtItsRealLength() {
        val line = timeline(at(8), timed(1, at(9), at(10)), timed(2, at(11), at(12, 45)))

        assertEquals(listOf(false, false), line.blocks.map { it.chip })
        assertEquals(9 * 60 to 10 * 60, line.block(1).drawn())
        assertEquals(11 * 60 to 12 * 60 + 45, line.block(2).drawn())
        assertEquals(listOf(2, 2), line.blocks.map { it.height })
    }

    @Test
    fun aShorterMeetingIsAChipDrawnAnHourFromItsStart() {
        val line = timeline(at(8), timed(1, at(11, 5), at(11, 10)), timed(2, at(14), at(14, 59)), timed(3, at(15), at(15)))

        assertEquals(listOf(true, true, true), line.blocks.map { it.chip })
        val ping = line.block(1)
        assertEquals(11 * 60 + 5 to 11 * 60 + 10, ping.fromMinute to ping.toMinute, "The chip keeps the real start and end")
        assertEquals(11 * 60 + 5 to 12 * 60 + 5, ping.drawn())
        assertEquals(14 * 60 to 15 * 60, line.block(2).drawn())
        assertEquals(15 * 60 to 16 * 60, line.block(3).drawn(), "A meeting with no length is a chip too")
        assertEquals(1, ping.height)
    }

    @Test
    fun earlierAndLaterMeetingsWidenTheHoursToWholeHoursAsDrawn() {
        val line = timeline(at(10), timed(1, at(7, 30), at(8, 15)), timed(2, at(18), at(19, 10)))

        assertEquals(7 * 60, line.startMinute)
        assertEquals(20 * 60, line.endMinute)
        assertEquals(13, line.hours)
    }

    @Test
    fun aChipLateInTheDayWidensTheHoursToHoldIt() {
        val line = timeline(at(10), timed(1, at(17, 50), at(17, 55)))

        assertEquals(17 * 60 + 50 to 18 * 60 + 50, line.block(1).drawn())
        assertEquals(19 * 60, line.endMinute)
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
        assertEquals(listOf(false, false), line.blocks.map { it.chip }, "An hour of today is a block")
    }

    @Test
    fun aMeetingEndingAtMidnightEndsAtTheEndOfToday() {
        val line = timeline(at(12), timed(1, at(22), at(0, day = tomorrow)))

        assertEquals(22 * 60 to 24 * 60, line.blocks.single().let { it.fromMinute to it.toMinute })
        assertEquals(24 * 60, line.endMinute)
    }

    @Test
    fun aChipCloseToMidnightEndsAtMidnight() {
        val line =
            timeline(
                at(12),
                timed(1, at(23, 50), at(23, 50)),
                timed(2, at(23, 45), at(0, 15, day = tomorrow)),
            )

        assertEquals(23 * 60 + 50, line.block(1).fromMinute, "The chip keeps its real start")
        assertEquals(23 * 60 to 24 * 60, line.block(1).drawn())
        assertEquals(23 * 60 + 45 to 24 * 60, line.block(2).let { it.fromMinute to it.toMinute })
        assertEquals(23 * 60 to 24 * 60, line.block(2).drawn())
        assertEquals(24 * 60, line.endMinute)
        assertEquals(mapOf(2 to 0, 1 to 1), line.rowsById(), "Drawn over the same hour, they take two rows")
    }

    @Test
    fun aChipFromYesterdayStartsAtMidnight() {
        val line = timeline(at(12), timed(1, at(23, 30, day = yesterday), at(0, 10)))

        assertEquals(0 to 10, line.block(1).let { it.fromMinute to it.toMinute })
        assertEquals(0 to 60, line.block(1).drawn())
        assertEquals(0, line.startMinute)
    }

    @Test
    fun aWidenedChipPushesALaterMeetingToTheNextFreeRows() {
        // 11:05 to 11:10 ends before 11:30 starts, but its chip is drawn until 12:05.
        val line = timeline(at(8), timed(1, at(11, 5), at(11, 10)), timed(2, at(11, 30), at(12, 30)), timed(3, at(12, 5), at(12, 15)))

        assertEquals(mapOf(1 to 0, 2 to 1, 3 to 0), line.rowsById(), "The block takes rows 1 and 2; the next chip fits after the first")
        assertEquals(3, line.rows)
    }

    @Test
    fun aBusyHourOfShortMeetingsStacksItsChips() {
        val line =
            timeline(
                at(8),
                timed(1, at(13), at(13, 5)),
                timed(2, at(13, 10), at(13, 20)),
                timed(3, at(13, 15), at(13, 40)),
                timed(4, at(13, 45), at(14)),
                timed(5, at(14), at(14, 30)),
            )

        assertTrue(line.blocks.all { it.chip })
        assertEquals(mapOf(1 to 0, 2 to 1, 3 to 2, 4 to 3, 5 to 0), line.rowsById())
        assertEquals(4, line.rows)
    }

    @Test
    fun aBlockTakesTheFirstTwoRowsFreeAtItsStart() {
        val line =
            timeline(
                at(8),
                timed(1, at(9), at(10)),
                timed(2, at(9, 15), at(9, 30)),
                timed(3, at(10), at(10, 10)),
                timed(4, at(10, 15), at(11, 15)),
            )

        // 3 fits in row 0 after 1; 4 needs two rows free at 10:15: row 0 holds 3's chip until 11:00, rows 1 and 2 are free.
        assertEquals(mapOf(1 to 0, 2 to 2, 3 to 0, 4 to 1), line.rowsById())
        assertEquals(3, line.rows)
    }

    @Test
    fun overlappingBlocksTakeTheFirstFreeRowsInTimeOrder() {
        val line =
            timeline(
                at(8),
                timed(4, at(10, 15), at(11, 15)),
                timed(1, at(9), at(10)),
                timed(3, at(10), at(11)),
                timed(2, at(9, 30), at(10, 30)),
                timed(5, at(11), at(12)),
            )

        // A meeting that starts as another ends shares its rows.
        assertEquals(mapOf(1 to 0, 2 to 2, 3 to 0, 4 to 4, 5 to 0), line.rowsById())
        assertEquals(6, line.rows)
        assertEquals(listOf(1, 2, 3, 4, 5), line.blocks.map { it.meeting.number }, "Time order")
    }

    @Test
    fun nothingDrawnOverlapsInARow() {
        val line =
            timeline(
                at(8),
                timed(1, at(9), at(9, 5)),
                timed(2, at(9, 10), at(10, 30)),
                timed(3, at(9, 20), at(9, 50)),
                timed(4, at(10), at(10, 15)),
                timed(5, at(10, 5), at(11)),
                timed(6, at(10, 20), at(10, 25)),
            )

        for (a in line.blocks) {
            for (b in line.blocks) {
                if (a === b) continue
                val sharedRow = a.row < b.row + b.height && b.row < a.row + a.height
                val overlap = a.drawnFromMinute < b.drawnToMinute && b.drawnFromMinute < a.drawnToMinute
                assertFalse(sharedRow && overlap, "${a.meeting.number} and ${b.meeting.number} overlap")
            }
        }
    }

    @Test
    fun nowShowsOnlyInsideTheHoursShown() {
        assertNull(timeline(at(7, 59)).nowMinute)
        assertEquals(8 * 60, timeline(at(8)).nowMinute)
        assertEquals(18 * 60, timeline(at(18)).nowMinute)
        assertNull(timeline(at(19, 30)).nowMinute)
        assertEquals(19 * 60 + 30, timeline(at(19, 30), timed(1, at(19), at(20))).nowMinute)
    }

    @Test
    fun allDayMeetingsSitApartFromTheHours() {
        val line = timeline(at(10), allDay(2), timed(1, at(9), at(10)), allDay(3))

        assertEquals(listOf(2, 3), line.allDay.map { it.number })
        assertEquals(listOf(1), line.blocks.map { it.meeting.number })
        assertEquals(8 * 60, line.startMinute)
        assertEquals(18 * 60, line.endMinute)
    }

    @Test
    fun blocksKeepTheirPhase() {
        val line = timeline(at(10, 30), timed(1, at(9), at(10)), timed(2, at(10), at(11)), timed(3, at(12), at(12, 30)))

        assertEquals(listOf(MeetingPhase.Ended, MeetingPhase.Now, MeetingPhase.Next), line.blocks.map { it.meeting.phase })
    }
}
