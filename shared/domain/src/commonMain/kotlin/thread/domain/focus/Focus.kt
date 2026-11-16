package thread.domain.focus

/*
 * The Focus timer (D25, D45, adr/ADR-019-focus-timer.md). The running timer lives on this device only (F2 A) and is
 * kept as instants, never as a count of ticks, so it keeps running across pages, reloads and a closed tab (F1 A).
 * Each focus period that ends, or that is stopped after a minute or more, becomes one [FocusSession] in the account.
 */

/** Focus and break lengths in minutes (v3 limits). The account keeps them, so every device uses the same ones (FD1). */
data class FocusSettings(
    val workMinutes: Int = DEFAULT_WORK_MINUTES,
    val breakMinutes: Int = DEFAULT_BREAK_MINUTES,
) {
    init {
        require(workMinutes in WORK_MINUTES) { "Focus minutes are 1 to 90" }
        require(breakMinutes in BREAK_MINUTES) { "Break minutes are 1 to 30" }
    }

    companion object {
        const val DEFAULT_WORK_MINUTES = 25
        const val DEFAULT_BREAK_MINUTES = 5
        val WORK_MINUTES = 1..90
        val BREAK_MINUTES = 1..30

        /** A typed focus length, as v3 reads it: not a number reads as 25, and the value is kept in 1..90. */
        fun workMinutesFrom(input: String): Int = (input.trim().toIntOrNull() ?: DEFAULT_WORK_MINUTES).coerceIn(WORK_MINUTES)

        /** A typed break length: not a number reads as 5, and the value is kept in 1..30. */
        fun breakMinutesFrom(input: String): Int = (input.trim().toIntOrNull() ?: DEFAULT_BREAK_MINUTES).coerceIn(BREAK_MINUTES)
    }
}

/** What a focus period can be for (04: at most one direct Task or Topic). */
enum class FocusTargetKind(
    val id: String,
) {
    Task("task"),
    Topic("topic"),
    ;

    companion object {
        fun fromId(id: String?): FocusTargetKind? = entries.firstOrNull { it.id == id }
    }
}

/** The Task or Topic a focus period is for. None means a standalone period. */
data class FocusTarget(
    val kind: FocusTargetKind,
    val id: String,
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_ID_LENGTH) { "A focus target has an id of 1 to 128 characters" }
    }

    companion object {
        const val MAX_ID_LENGTH = 128
    }
}

enum class FocusPhase {
    Work,
    Break,
}

/**
 * The live timer of one run: a focus period, then its break. No run means Ready.
 *
 * While [running], [endsAtMillis] is when the current phase ends. While paused, [remainingMillis] is the time the phase
 * had left, frozen. The lengths are fixed at Start, so a change of durations elsewhere never moves a running period.
 */
data class FocusRun(
    /** Made on this device at Start: letters, digits, `-` and `_`, at most 64. The session id comes from it. */
    val runId: String,
    val phase: FocusPhase,
    val running: Boolean,
    val endsAtMillis: Long,
    val remainingMillis: Long,
    val plannedWorkMillis: Long,
    val breakMillis: Long,
    /** When Start was pressed. */
    val startedAtMillis: Long,
    val target: FocusTarget?,
) {
    init {
        require(RUN_ID.matches(runId)) { "A run id is 1 to 64 letters, digits, - or _" }
    }

    /** The length of the current phase. */
    val phaseMillis: Long get() = if (phase == FocusPhase.Work) plannedWorkMillis else breakMillis

    /** The time the current phase has left at [nowMillis], never below 0 or above the phase length. */
    fun remainingAt(nowMillis: Long): Long = (if (running) endsAtMillis - nowMillis else remainingMillis).coerceIn(0, phaseMillis)

    companion object {
        private val RUN_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

/** This device's timer for one account, and the Task or Topic picked for the next Start (FD4). */
data class FocusDevice(
    val run: FocusRun? = null,
    val lastTarget: FocusTarget? = null,
)

enum class FocusOutcome(
    val id: String,
) {
    Completed("completed"),
    Stopped("stopped"),
    ;

    companion object {
        fun fromId(id: String?): FocusOutcome? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One history entry: a focus period that ended, or that was stopped after [MIN_SAVED_MILLIS] or more (FD2). Its
 * values never change once made, and its id comes from the run, so sending it twice stores it once.
 */
data class FocusSession(
    val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val focusedMillis: Long,
    val plannedMinutes: Int,
    val outcome: FocusOutcome,
    val target: FocusTarget?,
) {
    /** The minutes the history shows: the planned length when completed, else the focused time, at least 1. */
    val shownMinutes: Int
        get() =
            if (outcome == FocusOutcome.Completed) {
                plannedMinutes
            } else {
                ((focusedMillis + MINUTE_MILLIS / 2) / MINUTE_MILLIS).toInt().coerceAtLeast(1)
            }

    companion object {
        /** A stopped focus period shorter than this is not saved (FD2). */
        const val MIN_SAVED_MILLIS: Long = 60_000

        /** The session id of [runId]: one run, at most one entry. */
        fun idFor(runId: String): String = "f-$runId"
    }
}

/** An end that passed, at its real time. The screen shows it as a toast (D27: in-app only). */
sealed interface FocusEvent {
    val atMillis: Long

    /** The focus period ended; the break of [breakMinutes] started at the same instant. */
    data class WorkEnded(
        override val atMillis: Long,
        val breakMinutes: Int,
    ) : FocusEvent

    data class BreakEnded(
        override val atMillis: Long,
    ) : FocusEvent
}

/** How many sessions the account's history listener keeps, newest first. The panel shows the first 4 (F3 A). */
const val FOCUS_HISTORY_LIMIT = 50

/** How many sessions the panel lists (v3). */
const val FOCUS_RECENT_SHOWN = 4

internal const val MINUTE_MILLIS: Long = 60_000
