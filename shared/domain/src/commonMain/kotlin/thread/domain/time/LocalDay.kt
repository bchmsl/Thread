package thread.domain.time

/**
 * The workspace time zone when the account has none stored (04-data-and-sync.md, "Dates and time"). Asia/Tbilisi has
 * no daylight saving time.
 */
const val DEFAULT_WORKSPACE_ZONE: String = "Asia/Tbilisi"

/**
 * A calendar day with no time and no time zone, for example a planned day or a date-only deadline. It is never
 * turned into an instant, so it never shifts (04-data-and-sync.md: "Deadline-only dates should not be accidentally
 * shifted by UTC conversion"). Stored as `YYYY-MM-DD`.
 */
data class LocalDay(
    val year: Int,
    val month: Int,
    val day: Int,
) : Comparable<LocalDay> {
    init {
        require(year in MIN_YEAR..MAX_YEAR) { "Year out of range: $year" }
        require(month in 1..12) { "Month out of range: $month" }
        require(day in 1..daysInMonth(year, month)) { "Day out of range: $year-$month-$day" }
    }

    /** Days since 1970-01-01. */
    val epochDay: Long get() = daysFromCivil(year, month, day)

    /** ISO day of the week: 1 is Monday, 7 is Sunday. */
    val dayOfWeek: Int get() = ((epochDay + 3).mod(7L) + 1).toInt()

    fun plusDays(days: Int): LocalDay = fromEpochDay(epochDay + days)

    override fun compareTo(other: LocalDay): Int = compareValuesBy(this, other, { it.year }, { it.month }, { it.day })

    override fun toString(): String = year.toString().padStart(4, '0') + "-" + two(month) + "-" + two(day)

    companion object {
        private const val MIN_YEAR = 1
        private const val MAX_YEAR = 9999
        private val PATTERN = Regex("""(\d{4})-(\d{2})-(\d{2})""")

        /** Reads `YYYY-MM-DD`. Returns null for anything else, including days that do not exist. */
        fun parse(text: String): LocalDay? {
            val match = PATTERN.matchEntire(text) ?: return null
            val (y, m, d) = match.destructured
            val year = y.toInt()
            val month = m.toInt()
            val day = d.toInt()
            if (year !in MIN_YEAR..MAX_YEAR || month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
            return LocalDay(year, month, day)
        }

        fun fromEpochDay(epochDay: Long): LocalDay {
            // Howard Hinnant's civil_from_days.
            val z = epochDay + 719_468
            val era = (if (z >= 0) z else z - 146_096) / 146_097
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
            return LocalDay(year, month, day)
        }

        fun daysInMonth(
            year: Int,
            month: Int,
        ): Int =
            when (month) {
                2 -> if (isLeapYear(year)) 29 else 28
                4, 6, 9, 11 -> 30
                else -> 31
            }

        private fun isLeapYear(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

        private fun daysFromCivil(
            year: Int,
            month: Int,
            day: Int,
        ): Long {
            // Howard Hinnant's days_from_civil.
            val y = (if (month <= 2) year - 1 else year).toLong()
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val mp = if (month > 2) month - 3 else month + 9
            val doy = (153L * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097 + doe - 719_468
        }
    }
}

/**
 * A wall-clock time in the workspace time zone: a day and a minute of that day, with no seconds. "Now" for Today,
 * overdue and reminders is always one of these, computed by the platform from the real clock and the zone. Stored
 * as `YYYY-MM-DDTHH:MM`.
 */
data class WallTime(
    val day: LocalDay,
    val minuteOfDay: Int,
) : Comparable<WallTime> {
    init {
        require(minuteOfDay in 0 until MINUTES_PER_DAY) { "Minute out of range: $minuteOfDay" }
    }

    val hour: Int get() = minuteOfDay / 60

    val minute: Int get() = minuteOfDay % 60

    override fun compareTo(other: WallTime): Int = compareValuesBy(this, other, { it.day }, { it.minuteOfDay })

    override fun toString(): String = "$day" + "T" + clockText(minuteOfDay)

    companion object {
        /** Reads `YYYY-MM-DDTHH:MM`. Returns null for anything else. */
        fun parse(text: String): WallTime? {
            val day = LocalDay.parse(text.substringBefore('T', "")) ?: return null
            val minute = parseClock(text.substringAfter('T', "")) ?: return null
            return WallTime(day, minute)
        }

        fun of(
            day: LocalDay,
            hour: Int,
            minute: Int,
        ): WallTime = WallTime(day, hour * 60 + minute)
    }
}

const val MINUTES_PER_DAY: Int = 24 * 60

/** `HH:MM` for a minute of the day. */
fun clockText(minuteOfDay: Int): String = two(minuteOfDay / 60) + ":" + two(minuteOfDay % 60)

/** Reads `HH:MM` (24-hour). Returns the minute of the day, or null. */
fun parseClock(text: String): Int? {
    val match = Regex("""(\d{2}):(\d{2})""").matchEntire(text) ?: return null
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

private fun two(value: Int): String = value.toString().padStart(2, '0')
