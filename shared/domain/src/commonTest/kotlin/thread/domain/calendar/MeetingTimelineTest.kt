package thread.domain.calendar

import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// D79: the meetings timeline's hours, lanes and now. Every meeting here is SYNTHETIC test data.
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

    private fun MeetingTimeline.lanesById(): Map<Int, Int> = blocks.associate { it.meeting.number to it.lane }

    @Test
    fun anEmptyDayShowsTheWorkingHoursAndNow() {
        val line = timeline(at(10, 30))

        assertEquals(8 * 60, line.startMinute)
        assertEquals(18 * 60, line.endMinute)
        assertEquals(10, line.hours)
        assertEquals(0, line.lanes)
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
        assertEquals(1, line.lanes)
    }

    @Test
    fun earlierAndLaterMeetingsWidenTheHoursToWholeHours() {
        val line = timeline(at(10), timed(1, at(7, 30), at(8, 15)), timed(2, at(18), at(19, 10)))

        assertEquals(7 * 60, line.startMinute)
        assertEquals(20 * 60, line.endMinute)
        assertEquals(13, line.hours)
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
                timed(4, at(10, 15), at(10, 45)),
                timed(1, at(9), at(10)),
                timed(3, at(10), at(11)),
                timed(2, at(9, 30), at(10, 30)),
                timed(5, at(11), at(12)),
            )

        // A meeting that starts as another ends shares its lane.
        assertEquals(mapOf(1 to 0, 2 to 1, 3 to 0, 4 to 2, 5 to 0), line.lanesById())
        assertEquals(3, line.lanes)
        assertEquals(listOf(1, 2, 3, 4, 5), line.blocks.map { it.meeting.number }, "Time order")
    }

    @Test
    fun aShortMeetingKeepsRoomForItsBlock() {
        val line =
            timeline(
                at(8),
                timed(1, at(9), at(9, 10)),
                timed(2, at(9, 15), at(9, 45)),
                timed(3, at(9, 30), at(10)),
                timed(4, at(17, 50), at(17, 50)),
            )

        // 1 takes until 09:25, so 2 starts a lane and 3 fits after 1.
        assertEquals(mapOf(1 to 0, 2 to 1, 3 to 0, 4 to 0), line.lanesById())
        assertEquals(9 * 60 + 10, line.blocks.first().toMinute, "The block keeps the real end")
        assertEquals(19 * 60, line.endMinute, "A meeting with no length at 17:50 takes room until 18:15")
    }

    @Test
    fun aShortMeetingLateAtNightStaysInsideToday() {
        val line = timeline(at(12), timed(1, at(23, 50), at(23, 50)))

        assertEquals(24 * 60, line.endMinute)
        assertEquals(23 * 60 + 50, line.blocks.single().toMinute)
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
        val line = timeline(at(10, 30), timed(1, at(9), at(10)), timed(2, at(10), at(11)), timed(3, at(12), at(13)))

        assertEquals(listOf(MeetingPhase.Ended, MeetingPhase.Now, MeetingPhase.Next), line.blocks.map { it.meeting.phase })
    }
}
