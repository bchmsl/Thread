package thread.web.platform

import kotlinx.browser.window
import org.w3c.dom.events.Event
import thread.domain.time.DEFAULT_WORKSPACE_ZONE
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import kotlin.js.Date

/**
 * The browser clock read as a wall time in an IANA time zone, through `Intl.DateTimeFormat`
 * (adr/ADR-010-tasks-reminders-and-dates.md). The domain never sees an instant: Today, deadlines and reminders all
 * compare wall times in the workspace zone, so a date-only value never moves across a UTC boundary.
 */
object WallClock {
    /** Added to the real clock. Only the emulator test hooks change it, through [shiftForTesting]. */
    private var offsetMillis: Double = 0.0

    private val formatters = mutableMapOf<String, Intl.DateTimeFormat>()

    fun nowMillis(): Double = Date.now() + offsetMillis

    /** Now in [zone]. An unknown zone falls back to the workspace default, so a bad stored value never stops the app. */
    fun now(zone: String): WallTime = wallTimeAt(nowMillis(), zone)

    fun wallTimeAt(
        epochMillis: Double,
        zone: String,
    ): WallTime {
        val format = formatter(zone) ?: formatter(DEFAULT_WORKSPACE_ZONE) ?: return deviceWallTime(epochMillis)
        var year = 0
        var month = 0
        var day = 0
        var hour = 0
        var minute = 0
        format.formatToParts(Date(epochMillis)).forEach { part ->
            val value = part.value.toIntOrNull() ?: return@forEach
            when (part.type) {
                "year" -> year = value
                "month" -> month = value
                "day" -> day = value
                // Some engines print midnight as 24 even with h23.
                "hour" -> hour = value % 24
                "minute" -> minute = value
            }
        }
        return WallTime.of(LocalDay(year, month, day), hour, minute)
    }

    fun isValidZone(zone: String): Boolean = formatter(zone) != null

    /** Moves the clock by [millis] and tells [onClockChange] listeners. For emulator checks only. */
    fun shiftForTesting(millis: Double) {
        offsetMillis = millis
        window.dispatchEvent(Event(CLOCK_CHANGE_EVENT))
    }

    /** Calls [onChange] when the test clock moves. Returns a function that stops listening. */
    fun onClockChange(onChange: () -> Unit): () -> Unit {
        val listener: (Event) -> Unit = { onChange() }
        window.addEventListener(CLOCK_CHANGE_EVENT, listener)
        return { window.removeEventListener(CLOCK_CHANGE_EVENT, listener) }
    }

    private fun formatter(zone: String): Intl.DateTimeFormat? {
        formatters[zone]?.let { return it }
        val options: dynamic = js("({})")
        options.timeZone = zone
        options.year = "numeric"
        options.month = "2-digit"
        options.day = "2-digit"
        options.hour = "2-digit"
        options.minute = "2-digit"
        options.hourCycle = "h23"
        return try {
            Intl.DateTimeFormat("en-US", options).also { formatters[zone] = it }
        } catch (e: Throwable) {
            // RangeError: not a zone this browser knows.
            null
        }
    }

    private fun deviceWallTime(epochMillis: Double): WallTime {
        val date = Date(epochMillis)
        return WallTime.of(LocalDay(date.getFullYear(), date.getMonth() + 1, date.getDate()), date.getHours(), date.getMinutes())
    }

    private const val CLOCK_CHANGE_EVENT = "thread-clock-change"
}

/** The parts of `Intl` this app uses. */
external object Intl {
    class DateTimeFormat(
        locales: String,
        options: dynamic,
    ) {
        fun formatToParts(date: Date): Array<DateTimeFormatPart>
    }
}

external interface DateTimeFormatPart {
    val type: String
    val value: String
}
