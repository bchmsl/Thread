package thread.web.presentation.settings

import thread.domain.calendar.MeetingSource
import thread.domain.calendar.MeetingSourceState
import thread.domain.calendar.MeetingsState
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** D78: the status line of Settings "Calendar from your Mac". SYNTHETIC times. */
class CalendarStatusTest {
    private val today = LocalDay(2026, 11, 17)

    /** Every instant reads as 08:10 today in the workspace zone. */
    private fun at08h10(
        millis: Double,
        zone: String,
    ): WallTime {
        assertEquals(ZONE, zone)
        assertEquals(LAST_SUCCESS.toDouble(), millis)
        return WallTime.of(today, 8, 10)
    }

    private fun status(
        state: MeetingsState,
        nowMillis: Long = LAST_SUCCESS + 10 * MINUTE,
    ): Pair<StatusTone, String> = calendarStatus(state, today, nowMillis, ZONE, ::at08h10).let { it.tone to it.text }

    private fun ready(
        state: MeetingSourceState = MeetingSourceState.Ok,
        lastSuccessAt: Long? = LAST_SUCCESS,
    ) = MeetingsState.Ready(MeetingSource(state, "Synthetic work calendar", lastSuccessAt, today, today.plusDays(14), "1.0.0"), emptyList())

    @Test
    fun theStatusSaysWhetherMeetingsAreUpToDateAndWhatToDoOnTheMac() {
        assertEquals(StatusTone.Plain to "Reading…", status(MeetingsState.Loading))
        assertEquals(StatusTone.Plain to "Not set up", status(MeetingsState.NotSetUp))
        assertEquals(StatusTone.Warn to "Update Thread to read meetings. Reload the page.", status(MeetingsState.NeedsUpdate))
        assertEquals(StatusTone.Warn to "Unavailable. Reload the page to try again.", status(MeetingsState.Unavailable("unavailable")))
        assertEquals(StatusTone.On to "Up to date", status(ready()))
        assertEquals(StatusTone.Warn to "Not updated since 08:10. Is your Mac awake?", status(ready(), nowMillis = LAST_SUCCESS + 2 * HOUR))
        assertEquals(StatusTone.Plain to "Waiting for the first upload", status(ready(lastSuccessAt = null)))
        assertEquals(StatusTone.Warn to "No calendar access on the Mac", status(ready(MeetingSourceState.NoAccess)))
        assertEquals(StatusTone.Warn to "No calendar picked on the Mac", status(ready(MeetingSourceState.NoCalendar)))
        assertEquals(StatusTone.Warn to "Paused on the Mac", status(ready(MeetingSourceState.Paused), nowMillis = LAST_SUCCESS + 2 * HOUR))
    }

    private companion object {
        const val ZONE = "Asia/Tbilisi"
        const val LAST_SUCCESS = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
