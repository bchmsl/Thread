package thread.web.presentation

import thread.domain.task.Deadline
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeTextTest {
    private val today = LocalDay(2026, 11, 13)

    @Test
    fun datesReadLikeThePrototype() {
        assertEquals("Friday, 13 November", longDate(today))
        assertEquals("Fri 13 Nov", shortDate(today))
    }

    @Test
    fun nearDaysUseWords() {
        assertEquals("Today", dayText(today, today))
        assertEquals("today", dayText(today, today, mid = true))
        assertEquals("Tomorrow", dayText(today.plusDays(1), today))
        assertEquals("Yesterday", dayText(today.plusDays(-1), today))
        assertEquals("Sun 15 Nov", dayText(today.plusDays(2), today))
    }

    @Test
    fun timesAndDeadlines() {
        assertEquals("Tomorrow 15:00", whenText(WallTime.of(today.plusDays(1), 15, 0), today))
        assertEquals("Today", deadlineText(Deadline(today), today))
        assertEquals("tomorrow 09:05", deadlineText(Deadline(today.plusDays(1), 9 * 60 + 5), today, mid = true))
    }

    @Test
    fun captureAgeReadsLikeThePrototype() {
        val now = WallTime.of(today, 10, 0)
        assertEquals("just now", agoText(now, now))
        assertEquals("5 min ago", agoText(WallTime.of(today, 9, 55), now))
        assertEquals("today 08:40", agoText(WallTime.of(today, 8, 40), now))
        assertEquals("30 min ago", agoText(WallTime.of(today.plusDays(-1), 23, 50), WallTime.of(today, 0, 20)))
        assertEquals("yesterday 22:00", agoText(WallTime.of(today.plusDays(-1), 22, 0), WallTime.of(today, 1, 0)))
    }
}
