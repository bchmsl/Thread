package thread.web.presentation.calendar

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import thread.domain.calendar.JoinService
import thread.domain.calendar.Meeting
import thread.domain.calendar.MeetingAlternate
import thread.domain.calendar.MeetingId
import thread.domain.calendar.MeetingJoin
import thread.domain.calendar.MeetingResponse
import thread.domain.calendar.MeetingSource
import thread.domain.calendar.MeetingSourceState
import thread.domain.calendar.MeetingStore
import thread.domain.calendar.MeetingTime
import thread.domain.calendar.MeetingsState
import thread.domain.calendar.SeriesKey
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** D100: the meetings every screen reads carry the title and join link set in Thread. Every value here is SYNTHETIC. */
class MeetingsModelTest {
    private val today = LocalDay(2026, 11, 19)
    private val series = SeriesKey("a".repeat(32))
    private val calendarLink = "https://zoom.us/j/1234567890?pwd=SyntheticPasscode42"
    private val meeting =
        Meeting(
            MeetingId("1".repeat(32)),
            series,
            "SYNTHETIC calendar title",
            MeetingTime.Timed(WallTime.of(today, 10, 0), WallTime.of(today, 10, 30)),
            MeetingResponse.Accepted,
            joinUrl = calendarLink,
        )
    private val ready = MeetingsState.Ready(MeetingSource(MeetingSourceState.Ok), listOf(meeting))

    private class OneAnswer(
        val state: MeetingsState,
    ) : MeetingStore {
        override fun observe(zone: String) = flowOf(state)
    }

    @Test
    fun everyMeetingShowsTheAlternatesOfItsSeriesAndFollowsTheirChanges(): TestResult =
        runTest {
            var alternates = emptyMap<SeriesKey, MeetingAlternate>()
            val model = MeetingsModel(OneAnswer(ready)) { alternates }
            model.follow("Asia/Tbilisi")
            assertSame(ready, model.state, "no alternates: the store's answer as it is")

            val set = MeetingAlternate(series, title = "SYNTHETIC own title", joinUrl = "https://meet.synthetic.example/room-1")
            alternates = mapOf(series to set)
            assertEquals("SYNTHETIC own title", model.meeting(meeting.id.value)?.displayTitle)
            assertEquals(MeetingJoin("https://meet.synthetic.example/room-1", null), model.meetings.single().join)
            assertEquals(mapOf(series to set), model.alternates)

            val shown = model.state
            alternates = mapOf(series to set.copy())
            assertSame(shown, model.state, "the same alternates again: worked out once")

            alternates = emptyMap()
            assertEquals("SYNTHETIC calendar title", model.meetings.single().displayTitle)
            assertEquals(MeetingJoin(calendarLink, JoinService.Zoom), model.meetings.single().join)
        }
}
