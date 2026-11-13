package thread.web.platform

import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WallClockTest {
    private fun at(iso: String): Double = Date.parse(iso)

    @Test
    fun anEveningInUtcIsTheNextMorningInTbilisi() {
        // 21:30 UTC is 01:30 the next day at UTC+4: the case where a UTC day would be wrong.
        assertEquals(WallTime.of(LocalDay(2026, 11, 14), 1, 30), WallClock.wallTimeAt(at("2026-11-13T21:30:00Z"), "Asia/Tbilisi"))
        assertEquals(WallTime.of(LocalDay(2026, 11, 13), 21, 30), WallClock.wallTimeAt(at("2026-11-13T21:30:00Z"), "UTC"))
    }

    @Test
    fun aZoneBehindUtcKeepsTheEarlierDay() {
        assertEquals(WallTime.of(LocalDay(2026, 11, 13), 16, 30), WallClock.wallTimeAt(at("2026-11-13T21:30:00Z"), "America/New_York"))
    }

    @Test
    fun midnightIsHourZero() {
        assertEquals(WallTime.of(LocalDay(2026, 11, 14), 0, 0), WallClock.wallTimeAt(at("2026-11-13T20:00:00Z"), "Asia/Tbilisi"))
    }

    @Test
    fun anUnknownZoneFallsBackToTheWorkspaceDefault() {
        assertFalse(WallClock.isValidZone("Mars/Base"))
        assertTrue(WallClock.isValidZone("Asia/Tbilisi"))
        assertEquals(WallTime.of(LocalDay(2026, 11, 14), 1, 30), WallClock.wallTimeAt(at("2026-11-13T21:30:00Z"), "Mars/Base"))
    }
}
