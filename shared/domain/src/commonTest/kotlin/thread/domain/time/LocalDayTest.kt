package thread.domain.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalDayTest {
    @Test
    fun parsesAndPrintsIsoDays() {
        val day = LocalDay.parse("2026-11-13")
        assertEquals(LocalDay(2026, 11, 13), day)
        assertEquals("2026-11-13", day.toString())
        assertEquals("0987-01-02", LocalDay(987, 1, 2).toString())
    }

    @Test
    fun refusesDaysThatDoNotExist() {
        assertNull(LocalDay.parse("2026-02-29"))
        assertNull(LocalDay.parse("2026-13-01"))
        assertNull(LocalDay.parse("2026-11-31"))
        assertNull(LocalDay.parse("2026-1-13"))
        assertNull(LocalDay.parse("2026-11-13T10:00"))
        assertNull(LocalDay.parse(""))
        assertEquals(LocalDay(2028, 2, 29), LocalDay.parse("2028-02-29"))
        assertNull(LocalDay.parse("2100-02-29"))
        assertEquals(LocalDay(2000, 2, 29), LocalDay.parse("2000-02-29"))
        assertFailsWith<IllegalArgumentException> { LocalDay(2026, 4, 31) }
    }

    @Test
    fun epochDaysRoundTrip() {
        assertEquals(0L, LocalDay(1970, 1, 1).epochDay)
        assertEquals(-1L, LocalDay(1969, 12, 31).epochDay)
        assertEquals(20_405L, LocalDay(2025, 11, 13).epochDay)
        var day = LocalDay(1999, 12, 25)
        repeat(2_000) {
            assertEquals(day, LocalDay.fromEpochDay(day.epochDay))
            day = day.plusDays(1)
        }
    }

    @Test
    fun addsDaysAcrossMonthsYearsAndLeapDays() {
        assertEquals(LocalDay(2026, 12, 1), LocalDay(2026, 11, 30).plusDays(1))
        assertEquals(LocalDay(2027, 1, 1), LocalDay(2026, 12, 31).plusDays(1))
        assertEquals(LocalDay(2028, 2, 29), LocalDay(2028, 2, 28).plusDays(1))
        assertEquals(LocalDay(2028, 3, 1), LocalDay(2028, 2, 29).plusDays(1))
        assertEquals(LocalDay(2026, 11, 12), LocalDay(2026, 11, 13).plusDays(-1))
    }

    @Test
    fun knowsTheDayOfTheWeek() {
        assertEquals(5, LocalDay(2026, 11, 13).dayOfWeek, "2026-11-13 is a Friday")
        assertEquals(4, LocalDay(1970, 1, 1).dayOfWeek, "1970-01-01 was a Thursday")
        assertEquals(7, LocalDay(2026, 11, 15).dayOfWeek)
        assertEquals(1, LocalDay(2026, 11, 16).dayOfWeek)
    }

    @Test
    fun wallTimesParseCompareAndPrint() {
        val time = WallTime.parse("2026-11-13T09:05")
        assertEquals(WallTime.of(LocalDay(2026, 11, 13), 9, 5), time)
        assertEquals("2026-11-13T09:05", time.toString())
        assertNull(WallTime.parse("2026-11-13T24:00"))
        assertNull(WallTime.parse("2026-11-13T9:05"))
        assertNull(WallTime.parse("2026-11-13"))
        assertTrue(WallTime.parse("2026-11-13T23:59")!! < WallTime.parse("2026-11-14T00:00")!!)
        assertEquals(0, parseClock("00:00"))
        assertEquals(1439, parseClock("23:59"))
        assertEquals("18:00", clockText(1080))
    }
}
