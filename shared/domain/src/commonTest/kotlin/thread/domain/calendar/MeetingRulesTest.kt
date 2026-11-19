package thread.domain.calendar

import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.TopicId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Every meeting, source and link here is SYNTHETIC test data.
class MeetingRulesTest {
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
        title: String = "SYNTHETIC meeting $index",
        series: Int = index,
    ) = Meeting(MeetingId(hex(index)), SeriesKey(hex(1000 + series)), title, MeetingTime.Timed(start, end), MeetingResponse.Accepted)

    private fun allDay(
        index: Int,
        firstDay: LocalDay,
        lastDayExclusive: LocalDay,
        title: String = "SYNTHETIC all-day $index",
    ) = Meeting(
        MeetingId(hex(index)),
        SeriesKey(hex(1000 + index)),
        title,
        MeetingTime.AllDay(firstDay, lastDayExclusive),
        MeetingResponse.Tentative,
    )

    private fun List<TodayMeeting>.ids(): List<Int> = map { index(it.meeting.id) }

    private fun index(id: MeetingId): Int = id.value.toInt(16)

    @Test
    fun todaysMeetingsPutAllDayFirstAndMarkEndedNowNextAndLater() {
        val now = at(10, 30)
        val list =
            todayMeetings(
                listOf(
                    timed(5, at(14), at(15)),
                    timed(1, at(9), at(10)),
                    timed(3, at(11), at(11, 30)),
                    timed(2, at(10), at(11)),
                    allDay(9, today, tomorrow),
                    timed(4, at(11), at(12)),
                ),
                now,
            )
        assertEquals(listOf(9, 1, 2, 3, 4, 5), list.ids())
        assertEquals(
            listOf(
                MeetingPhase.AllDay,
                MeetingPhase.Ended,
                MeetingPhase.Now,
                MeetingPhase.Next,
                MeetingPhase.Next,
                MeetingPhase.Later,
            ),
            list.map { it.phase },
        )
    }

    @Test
    fun aMeetingEndsAtItsEndMinuteAndOneWithNoLengthTheMinuteAfter() {
        val meeting = timed(1, at(10), at(11))
        assertEquals(MeetingPhase.Next, todayMeetings(listOf(meeting), at(9, 59)).single().phase)
        assertEquals(MeetingPhase.Now, todayMeetings(listOf(meeting), at(10)).single().phase)
        assertEquals(MeetingPhase.Now, todayMeetings(listOf(meeting), at(10, 59)).single().phase)
        assertEquals(MeetingPhase.Ended, todayMeetings(listOf(meeting), at(11)).single().phase)
        val point = timed(2, at(12), at(12))
        assertEquals(MeetingPhase.Now, todayMeetings(listOf(point), at(12)).single().phase)
        assertEquals(MeetingPhase.Ended, todayMeetings(listOf(point), at(12, 1)).single().phase)
    }

    @Test
    fun meetingsThatCrossMidnightShowOnBothDaysAndSaySo() {
        val fromYesterday = timed(1, at(23, day = yesterday), at(1))
        val intoTomorrow = timed(2, at(23), at(0, 30, tomorrow))
        val endsAtMidnight = timed(3, at(22, day = yesterday), at(0))
        val startsAtMidnight = timed(4, at(0, day = tomorrow), at(1, day = tomorrow))
        val pointAtMidnight = timed(5, at(0), at(0))
        val list = todayMeetings(listOf(fromYesterday, intoTomorrow, endsAtMidnight, startsAtMidnight, pointAtMidnight), at(0, 10))
        assertEquals(listOf(1, 5, 2), list.ids())
        assertEquals(listOf(true, false, false), list.map { it.startsBeforeToday })
        assertEquals(listOf(false, false, true), list.map { it.endsAfterToday })
        assertEquals(listOf(MeetingPhase.Now, MeetingPhase.Ended, MeetingPhase.Next), list.map { it.phase })
    }

    @Test
    fun allDayMeetingsShowOnEachOfTheirDaysOnly() {
        val twoDays = allDay(1, yesterday, tomorrow)
        val endedYesterday = allDay(2, yesterday.plusDays(-1), today)
        val startsTomorrow = allDay(3, tomorrow, tomorrow.plusDays(1))
        assertEquals(listOf(1), todayMeetings(listOf(twoDays, endedYesterday, startsTomorrow), at(12)).ids())
        assertEquals(listOf(3), todayMeetings(listOf(twoDays, endedYesterday, startsTomorrow), at(0, day = tomorrow)).ids())
    }

    // D102: the Calendar page's days.
    @Test
    fun onlyTodayHasNowAndNextAndOtherDaysAreEndedOrLater() {
        val now = at(10, 30)
        val meetings =
            listOf(
                timed(1, at(9, day = yesterday), at(10, day = yesterday)),
                timed(2, at(15, day = yesterday), at(16, day = yesterday)),
                timed(3, at(10), at(11)),
                timed(4, at(12), at(13)),
                timed(5, at(9, day = tomorrow), at(10, day = tomorrow)),
                timed(6, at(11, day = tomorrow), at(12, day = tomorrow)),
            )
        assertEquals(listOf(MeetingPhase.Ended, MeetingPhase.Ended), meetingsOn(meetings, yesterday, now).map { it.phase })
        assertEquals(listOf(MeetingPhase.Now, MeetingPhase.Next), meetingsOn(meetings, today, now).map { it.phase })
        assertEquals(listOf(MeetingPhase.Later, MeetingPhase.Later), meetingsOn(meetings, tomorrow, now).map { it.phase })
        assertEquals(listOf(5, 6), meetingsOn(meetings, tomorrow, now).ids())
        assertEquals(todayMeetings(meetings, now), meetingsOn(meetings, today, now))
    }

    @Test
    fun aMeetingThatCrossesMidnightShowsOnBothOfItsDaysWithFlagsForThatDay() {
        val late = timed(1, at(23), at(0, 30, tomorrow))
        val now = at(23, 10)
        val tonight = meetingsOn(listOf(late), today, now).single()
        assertEquals(MeetingPhase.Now, tonight.phase)
        assertFalse(tonight.startsBeforeToday)
        assertTrue(tonight.endsAfterToday)
        val afterMidnight = meetingsOn(listOf(late), tomorrow, now).single()
        assertEquals(MeetingPhase.Later, afterMidnight.phase)
        assertTrue(afterMidnight.startsBeforeToday)
        assertFalse(afterMidnight.endsAfterToday)
        // The next day, once it is over: the first day's row is ended and the second day's row is today's.
        val nextMorning = at(1, day = tomorrow)
        assertEquals(MeetingPhase.Ended, meetingsOn(listOf(late), today, nextMorning).single().phase)
        assertEquals(MeetingPhase.Ended, meetingsOn(listOf(late), tomorrow, nextMorning).single().phase)
    }

    @Test
    fun aMeetingWithNoLengthAtMidnightShowsOnItsOwnDayOnly() {
        val point = timed(1, at(0, day = tomorrow), at(0, day = tomorrow))
        val now = at(12)
        assertTrue(meetingsOn(listOf(point), today, now).isEmpty())
        assertEquals(listOf(1), meetingsOn(listOf(point), tomorrow, now).ids())
    }

    @Test
    fun anAllDayMeetingOverThreeDaysShowsOnEachOfThem() {
        val threeDays = allDay(1, yesterday, tomorrow.plusDays(1))
        val now = at(12)
        listOf(yesterday, today, tomorrow).forEach { day ->
            assertEquals(listOf(MeetingPhase.AllDay), meetingsOn(listOf(threeDays), day, now).map { it.phase }, "$day")
        }
        assertTrue(meetingsOn(listOf(threeDays), yesterday.plusDays(-1), now).isEmpty())
        assertTrue(meetingsOn(listOf(threeDays), tomorrow.plusDays(1), now).isEmpty())
    }

    private fun source(
        from: LocalDay?,
        to: LocalDay?,
        lastSuccess: Long? = 1L,
    ) = MeetingSource(MeetingSourceState.Ok, lastSuccessAtMillis = lastSuccess, fromDay = from, toDay = to)

    @Test
    fun theCalendarRunsFromYesterdayOrTheFirstCoveredDayToTheLastCoveredDay() {
        val meetings = listOf(timed(1, at(9), at(10)), timed(2, at(9, day = tomorrow), at(10, day = tomorrow)))
        val now = at(12)
        val wide = calendarDays(meetings, source(yesterday.plusDays(-3), today.plusDays(14)), now)
        assertEquals(yesterday, wide.first().day)
        assertEquals(today.plusDays(14), wide.last().day)
        assertEquals(16, wide.size)
        assertEquals(listOf(1), wide[1].rows.ids())
        assertEquals(listOf(2), wide[2].rows.ids())
        assertTrue(wide[0].rows.isEmpty())
        val late = calendarDays(meetings, source(tomorrow, tomorrow.plusDays(2)), now)
        assertEquals(listOf(tomorrow, tomorrow.plusDays(1), tomorrow.plusDays(2)), late.map { it.day })
    }

    @Test
    fun theCalendarIsEmptyUntilAPassSucceeded() {
        val now = at(12)
        assertTrue(calendarDays(emptyList(), source(yesterday, tomorrow, lastSuccess = null), now).isEmpty())
        assertTrue(calendarDays(emptyList(), source(null, tomorrow), now).isEmpty())
        assertTrue(calendarDays(emptyList(), source(yesterday, null), now).isEmpty())
    }

    @Test
    fun anOldPassGivesOnlyTheDaysItCovered() {
        val now = at(12)
        assertEquals(listOf(yesterday), calendarDays(emptyList(), source(yesterday.plusDays(-5), yesterday), now).map { it.day })
        assertTrue(calendarDays(emptyList(), source(yesterday.plusDays(-5), yesterday.plusDays(-1)), now).isEmpty())
    }

    // D86: Settings "Show past meetings" for the Vertical card.
    private val day =
        listOf(
            allDay(9, today, tomorrow),
            timed(1, at(8), at(9)),
            timed(2, at(9), at(10)),
            timed(3, at(10), at(11)),
            timed(4, at(12), at(13)),
            timed(5, at(15), at(16)),
        )

    @Test
    fun pastMeetingsKeepTheLatestEndedOnesAndEveryOtherRow() {
        val rows = todayMeetings(day, at(12, 30))
        assertEquals(listOf(9, 1, 2, 3, 4, 5), limitPastMeetings(rows, null).ids(), "All: every row")
        assertEquals(listOf(9, 4, 5), limitPastMeetings(rows, 0).ids(), "None: all-day, now and later stay")
        assertEquals(listOf(9, 3, 4, 5), limitPastMeetings(rows, 1).ids(), "The one that ended last")
        assertEquals(listOf(9, 2, 3, 4, 5), limitPastMeetings(rows, 2).ids())
        assertEquals(listOf(9, 1, 2, 3, 4, 5), limitPastMeetings(rows, 3).ids(), "As many as there are")
        assertEquals(listOf(9, 1, 2, 3, 4, 5), limitPastMeetings(rows, 20).ids(), "More than there are")
    }

    @Test
    fun pastMeetingsNeverHideAllDayMeetingsOrADayWithoutEndedOnes() {
        val allDayOnly = todayMeetings(listOf(allDay(9, yesterday, tomorrow), allDay(8, today, tomorrow)), at(23, 59))
        assertEquals(listOf(9, 8), limitPastMeetings(allDayOnly, 0).ids())
        val morning = todayMeetings(day, at(7))
        assertEquals(listOf(9, 1, 2, 3, 4, 5), limitPastMeetings(morning, 0).ids())
        assertEquals(emptyList<TodayMeeting>(), limitPastMeetings(emptyList(), 0))
        val evening = todayMeetings(day, at(17))
        assertEquals(listOf(9), limitPastMeetings(evening, 0).ids(), "Everything ended: only the all-day row is left")
    }

    @Test
    fun theMeetingThatEndedLastStaysEvenWhenItStartedFirst() {
        val long = timed(1, at(9), at(12))
        val short = timed(2, at(10), at(10, 30))
        val sameEndLaterStart = timed(3, at(11), at(12))
        val rows = todayMeetings(listOf(long, short, sameEndLaterStart), at(13))
        assertEquals(listOf(3), limitPastMeetings(rows, 1).ids(), "Same end: the later start")
        assertEquals(listOf(1, 3), limitPastMeetings(rows, 2).ids(), "Then the long one, which ended after the short one")
    }

    @Test
    fun pastMeetingsFollowTheDayChange() {
        val crossing = timed(6, at(23, 30), at(0, 30, tomorrow))
        val tomorrows = timed(7, at(8, day = tomorrow), at(9, day = tomorrow))
        val meetings = day + crossing + tomorrows
        // Before midnight: today's five timed meetings have ended; the one across midnight is on now.
        val lateEvening = todayMeetings(meetings, at(23, 45))
        assertEquals(listOf(9, 5, 6), limitPastMeetings(lateEvening, 1).ids())
        // After midnight: yesterday's meetings are gone, so none is past until the one across midnight ends.
        val justAfter = todayMeetings(meetings, at(0, 10, tomorrow))
        assertEquals(listOf(6, 7), limitPastMeetings(justAfter, 0).ids())
        val later = todayMeetings(meetings, at(0, 40, tomorrow))
        assertEquals(listOf(7), limitPastMeetings(later, 0).ids())
        assertEquals(listOf(6, 7), limitPastMeetings(later, 1).ids())
    }

    @Test
    fun sameStartMeetingsAreOrderedByEndThenTitleThenId() {
        val list =
            todayMeetings(
                listOf(
                    timed(4, at(9), at(11), "B"),
                    timed(3, at(9), at(10), "B"),
                    timed(2, at(9), at(10), "A"),
                    timed(1, at(9), at(10), "B"),
                ),
                at(8),
            )
        assertEquals(listOf(2, 1, 3, 4), list.ids())
    }

    @Test
    fun theStatusLineCountsDownTheHourBeforeTheNextMeeting() {
        val standup = timed(1, at(10), at(10, 15), "SYNTHETIC Standup")
        val review = timed(2, at(10, 30), at(11), "SYNTHETIC Review")
        val meetings = listOf(review, standup)
        assertNull(nextMeeting(meetings, at(8, 59)))
        assertEquals(NextMeeting.Soon(standup, 60), nextMeeting(meetings, at(9)))
        assertEquals(NextMeeting.Soon(standup, 12), nextMeeting(meetings, at(9, 48)))
        assertEquals(NextMeeting.Soon(standup, 1), nextMeeting(meetings, at(9, 59)))
        assertEquals(NextMeeting.Started(standup), nextMeeting(meetings, at(10)))
        assertEquals(NextMeeting.Started(standup), nextMeeting(meetings, at(10, 4)))
        assertEquals(NextMeeting.Soon(review, 25), nextMeeting(meetings, at(10, 5)))
        assertEquals(NextMeeting.Started(review), nextMeeting(meetings, at(10, 34)))
        assertNull(nextMeeting(meetings, at(10, 35)))
    }

    @Test
    fun aJustStartedMeetingWinsAndTheLatestStartIsNamed() {
        val first = timed(1, at(10), at(11), "SYNTHETIC First")
        val second = timed(2, at(10, 2), at(11), "SYNTHETIC Second")
        val soon = timed(3, at(10, 3), at(11), "SYNTHETIC Soon")
        assertEquals(NextMeeting.Started(second), nextMeeting(listOf(soon, first, second), at(10, 2)))
        val sameStart = timed(4, at(10, 2), at(10, 30), "SYNTHETIC A")
        assertEquals(NextMeeting.Started(sameStart), nextMeeting(listOf(second, sameStart), at(10, 3)))
        assertEquals(NextMeeting.Soon(sameStart, 2), nextMeeting(listOf(second, sameStart), at(10)))
    }

    @Test
    fun allDayAndOtherDaysMeetingsNeverReachTheStatusLine() {
        val allDayToday = allDay(1, today, tomorrow)
        val afterMidnight = timed(2, at(0, 10, tomorrow), at(1, day = tomorrow))
        val fromYesterday = timed(3, at(23, 58, yesterday), at(1))
        assertNull(nextMeeting(listOf(allDayToday, afterMidnight), at(23, 30)))
        assertNull(nextMeeting(listOf(allDayToday, fromYesterday), at(0, 1)))
    }

    @Test
    fun joinStandsOutFromTenMinutesBeforeTheStartUntilTheEnd() {
        val meeting = timed(1, at(10), at(11))
        assertFalse(isJoinTime(meeting, at(9, 49)))
        assertTrue(isJoinTime(meeting, at(9, 50)))
        assertTrue(isJoinTime(meeting, at(10, 59)))
        assertFalse(isJoinTime(meeting, at(11)))
        assertFalse(isJoinTime(meeting, at(9, day = yesterday)))
    }

    @Test
    fun noMeetingsTodayNeedsASuccessfulPassThatCoveredToday() {
        val covered = MeetingSource(MeetingSourceState.Ok, lastSuccessAtMillis = 1L, fromDay = yesterday, toDay = today.plusDays(14))
        assertTrue(todayKnown(covered, today))
        assertTrue(todayKnown(covered, yesterday))
        assertTrue(todayKnown(covered, today.plusDays(14)))
        assertFalse(todayKnown(covered, today.plusDays(15)))
        assertFalse(todayKnown(covered, yesterday.plusDays(-1)))
        assertFalse(todayKnown(covered.copy(lastSuccessAtMillis = null), today))
        assertFalse(todayKnown(covered.copy(fromDay = null), today))
        assertFalse(todayKnown(covered.copy(toDay = null), today))
        assertTrue(todayKnown(covered.copy(state = MeetingSourceState.Paused), today))
    }

    @Test
    fun theSourceIsStaleAnHourAfterItsLastSuccess() {
        val hour = MEETING_SOURCE_STALE_MILLIS
        val source = MeetingSource(MeetingSourceState.Ok, lastSuccessAtMillis = 10 * hour)
        assertFalse(isMeetingSourceStale(source, 11 * hour))
        assertTrue(isMeetingSourceStale(source, 11 * hour + 1))
        assertFalse(isMeetingSourceStale(source.copy(lastSuccessAtMillis = null), 99 * hour))
    }

    @Test
    fun aTopicListsEachLinkedSeriesWithItsNextOccurrence() {
        val topic = TopicId("t-synthetic-1")
        val other = TopicId("t-synthetic-2")
        val standupToday = timed(1, at(9), at(9, 15), "SYNTHETIC Standup", series = 1)
        val standupTomorrow = timed(2, at(9, day = tomorrow), at(9, 15, tomorrow), "SYNTHETIC Standup", series = 1)
        val review = timed(3, at(15), at(16), "SYNTHETIC Review", series = 3)
        val retroYesterday = timed(4, at(15, day = yesterday), at(16, day = yesterday), "SYNTHETIC Retro", series = 4)
        val meetings = listOf(review, standupTomorrow, retroYesterday, standupToday)
        val links =
            listOf(
                MeetingLink(topic, SeriesKey(hex(1001)), title = "Old standup"),
                MeetingLink(topic, SeriesKey(hex(1003))),
                MeetingLink(topic, SeriesKey(hex(1004))),
                MeetingLink(topic, SeriesKey(hex(1005)), title = "SYNTHETIC Planning"),
                MeetingLink(topic, SeriesKey(hex(1006)), title = " "),
                MeetingLink(topic, SeriesKey(hex(1007)), linked = false, title = "Unlinked"),
                MeetingLink(other, SeriesKey(hex(1003))),
            )
        val rows = meetingsForTopic(topic, links, meetings, at(10))
        assertEquals(
            listOf("SYNTHETIC Review", "SYNTHETIC Standup", UNTITLED_MEETING, "SYNTHETIC Planning", "SYNTHETIC Retro"),
            rows.map { it.title },
        )
        assertEquals(listOf(review, standupTomorrow, null, null, null), rows.map { it.next })
        assertEquals(standupToday, meetingsForTopic(topic, links, meetings, at(9, 5)).first().next)
    }
}
