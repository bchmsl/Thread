package thread.web.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.coroutines.delay
import org.w3c.dom.events.Event
import thread.domain.task.Deadline
import thread.domain.time.LocalDay
import thread.domain.time.MINUTES_PER_DAY
import thread.domain.time.WallTime
import thread.domain.time.clockText
import thread.web.platform.WallClock

/** The current minute in the workspace zone. Screens read it here, so Today and the banner follow the same clock. */
internal val LocalNow = compositionLocalOf<WallTime> { error("No clock provided") }

/**
 * Now in [zone], updated at each new minute and when the page becomes visible again (timers are slowed in a hidden
 * tab, so Today must not wait for the next tick after the laptop wakes up).
 */
@Composable
internal fun rememberNow(zone: String): State<WallTime> {
    val now = remember(zone) { mutableStateOf(WallClock.now(zone)) }
    LaunchedEffect(zone) {
        while (true) {
            val millis = WallClock.nowMillis()
            delay((MINUTE_MILLIS - millis % MINUTE_MILLIS).toLong() + 20)
            now.value = WallClock.now(zone)
        }
    }
    DisposableEffect(zone) {
        val refresh = { now.value = WallClock.now(zone) }
        val visibility: (Event) -> Unit = { if (document.asDynamic().visibilityState == "visible") refresh() }
        document.addEventListener("visibilitychange", visibility)
        val stopTestClock = WallClock.onClockChange(refresh)
        onDispose {
            document.removeEventListener("visibilitychange", visibility)
            stopTestClock()
        }
    }
    return now
}

private const val MINUTE_MILLIS = 60_000.0

private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val MONTH_NAMES =
    listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

/** "Friday, 13 November" (v3 D.longDate). */
internal fun longDate(day: LocalDay): String = "${DAY_NAMES[day.dayOfWeek - 1]}, ${day.day} ${MONTH_NAMES[day.month - 1]}"

/** "Fri 13 Nov" (v3 D.shortDate). */
internal fun shortDate(day: LocalDay): String = "${DAY_NAMES[day.dayOfWeek - 1].take(3)} ${day.day} ${MONTH_NAMES[day.month - 1].take(3)}"

/** "Friday". */
internal fun weekdayName(day: LocalDay): String = DAY_NAMES[day.dayOfWeek - 1]

/** "Fri". */
internal fun weekdayShort(day: LocalDay): String = DAY_NAMES[day.dayOfWeek - 1].take(3)

/** "13 Nov". */
internal fun dayMonth(day: LocalDay): String = "${day.day} ${MONTH_NAMES[day.month - 1].take(3)}"

/** Today, Yesterday or Tomorrow, else the short date (v3 D.day). [mid] is for a word inside a sentence: "Planned today". */
internal fun dayText(
    day: LocalDay,
    today: LocalDay,
    mid: Boolean = false,
): String {
    val word =
        when (day.epochDay - today.epochDay) {
            0L -> "Today"
            -1L -> "Yesterday"
            1L -> "Tomorrow"
            else -> return shortDate(day)
        }
    return if (mid) word.lowercase() else word
}

/** "Tomorrow 15:00" (v3 D.when). */
internal fun whenText(
    at: WallTime,
    today: LocalDay,
    mid: Boolean = false,
): String = "${dayText(at.day, today, mid)} ${clockText(at.minuteOfDay)}"

/** A deadline without a time reads as its day only. */
internal fun deadlineText(
    deadline: Deadline,
    today: LocalDay,
    mid: Boolean = false,
): String = deadline.minuteOfDay?.let { whenText(WallTime(deadline.day, it), today, mid) } ?: dayText(deadline.day, today, mid)

/** "just now", "5 min ago", "today 09:40", else when (v3 D.ago). */
internal fun agoText(
    at: WallTime,
    now: WallTime,
): String {
    val minutes = minutesOf(now) - minutesOf(at)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        at.day == now.day -> "today ${clockText(at.minuteOfDay)}"
        else -> whenText(at, now.day, mid = true)
    }
}

private fun minutesOf(time: WallTime): Long = time.day.epochDay * MINUTES_PER_DAY + time.minuteOfDay
