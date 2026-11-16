package thread.web.presentation.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.events.Event
import thread.domain.focus.FocusDevice
import thread.domain.focus.FocusEvent
import thread.domain.focus.FocusPhase
import thread.domain.focus.FocusRun
import thread.domain.focus.FocusRunStore
import thread.domain.focus.FocusTarget
import thread.domain.focus.FocusTimer
import thread.domain.time.clockText
import thread.web.platform.WallClock
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.dayText

/** The page parameter that opens the Focus panel (v3 `?focus=1`). */
internal const val FOCUS_PARAM = "focus"

/**
 * The Focus timer for the screens (adr/ADR-019-focus-timer.md): this device's timer from a [FocusRunStore] (F2 A), the
 * lengths from the account's preferences and the history through [WorkspaceModel]. Actions run in [scope], the
 * signed-in account's scope, so closing the panel never cuts one short. [show] gets the in-app messages (D27).
 */
internal class FocusModel(
    store: FocusRunStore,
    private val workspace: WorkspaceModel,
    private val scope: CoroutineScope,
    newRunId: () -> String,
    private val show: (String) -> Unit,
) {
    private val timer = FocusTimer(store, workspace::recordFocus, ::clockMillis, newRunId)

    /** The stored timer. Empty until the first read. */
    var device: FocusDevice by mutableStateOf(FocusDevice())
        private set

    /** Whether the stored timer was read once (or could not be read). */
    var loaded: Boolean by mutableStateOf(false)
        private set

    /** The clock the screen shows. [tick] moves it on, so the time left changes only when the screen asks. */
    var nowMillis: Long by mutableStateOf(clockMillis())
        private set

    val run: FocusRun? get() = device.run

    /** The Task or Topic the panel shows: the period's while one exists, else the one picked for the next Start (FD4). */
    val target: FocusTarget? get() = device.run.let { run -> if (run != null) run.target else device.lastTarget }

    /** Time left in the current period, or the focus length while Ready (v3). */
    fun remainingMillis(): Long = run?.remainingAt(nowMillis) ?: (workspace.prefs.focusSettings.workMinutes * MINUTE_MILLIS)

    /** Follows the stored timer until cancelled. A timer this device cannot read shows as Ready; actions then say so. */
    suspend fun follow() {
        try {
            timer.observe().collect { stored ->
                tick()
                device = stored
                loaded = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            loaded = true
        }
    }

    fun tick() {
        nowMillis = clockMillis()
    }

    /** Applies the ends that passed: records each finished focus period at its real end time (F1 A). */
    fun settle() = act { settle() }

    fun start() = act { start(workspace.prefs.focusSettings) }

    fun pause(seen: FocusRun) = act { pause(seen) }

    fun resume(seen: FocusRun) = act { resume(seen) }

    fun stop(seen: FocusRun) = act { stop(seen) }

    fun choose(target: FocusTarget?) = act { choose(target) }

    private fun act(action: suspend FocusTimer.() -> List<FocusEvent>) {
        scope.launch {
            val events =
                try {
                    timer.action()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    show(NOT_SAVED)
                    return@launch
                }
            tick()
            focusEndText(events, nowMillis, workspace.prefs.timeZone)?.let(show)
        }
    }
}

/**
 * Keeps [focus] current while this page shows (adr/ADR-019-focus-timer.md): the time left moves once a second, and the
 * ends that passed are settled once the workspace has loaded, at each end, and when the page shows again (a hidden tab
 * waits, so its message comes with the real times on return). Nothing ticks for a timer that is paused or Ready.
 */
@Composable
internal fun FocusClock(
    focus: FocusModel,
    workspaceLoaded: Boolean,
) {
    val ready = workspaceLoaded && focus.loaded
    val currentReady by rememberUpdatedState(ready)
    LaunchedEffect(focus) { focus.follow() }
    DisposableEffect(focus) {
        val wake = {
            focus.tick()
            if (currentReady && pageVisible()) focus.settle()
        }
        val listener: (Event) -> Unit = { wake() }
        document.addEventListener("visibilitychange", listener)
        window.addEventListener("pageshow", listener)
        window.addEventListener("focus", listener)
        val stopTestClock = WallClock.onClockChange(wake)
        onDispose {
            document.removeEventListener("visibilitychange", listener)
            window.removeEventListener("pageshow", listener)
            window.removeEventListener("focus", listener)
            stopTestClock()
        }
    }
    // After a reload or a closed tab: a period that ended meanwhile is recorded at its real end time (F1 A).
    LaunchedEffect(ready) { if (ready && pageVisible()) focus.settle() }
    val run = focus.run
    LaunchedEffect(run, ready) {
        if (!ready || run == null || !run.running) return@LaunchedEffect
        while (true) {
            focus.tick()
            val left = run.remainingAt(focus.nowMillis)
            when {
                // Wake just after the shown second changes.
                left > 0 -> delay((left - 1) % SECOND_MILLIS + 1 + TICK_MARGIN_MILLIS)
                // Read now, not kept as a key: a hidden page does not recompose, so a key would still say visible.
                pageVisible() -> {
                    // The stored timer changes, which starts this effect again for the next period.
                    focus.settle()
                    break
                }
                // A hidden page waits for `visibilitychange`, which settles with the real times.
                else -> delay(SECOND_MILLIS)
            }
        }
    }
}

/** "24:59": the time left, rounded up to the second (v3 mmss). */
internal fun minutesAndSeconds(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) + SECOND_MILLIS - 1) / SECOND_MILLIS
    return "${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** The tool's name for screen readers (FD6): "Focus timer", "Focus timer, 12:34 left", "Focus timer, break, 04:59 left". */
internal fun focusToolLabel(
    run: FocusRun?,
    left: String,
): String =
    when {
        run == null -> "Focus timer"
        run.phase == FocusPhase.Break -> "Focus timer, break, $left left"
        else -> "Focus timer, $left left"
    }

/**
 * The message for the ends in [events] (D27, in-app only). Noticed at once: "Focus session done. Break: 5 min." or
 * "Break over." (v3). Noticed late, after a hidden tab, a sleeping laptop or a closed Thread: one message with the real
 * times, such as "Focus session done at 14:25. Break ended at 14:30."
 */
internal fun focusEndText(
    events: List<FocusEvent>,
    nowMillis: Long,
    zone: String,
): String? {
    if (events.isEmpty()) return null
    val workEnd = events.filterIsInstance<FocusEvent.WorkEnded>().lastOrNull()
    val breakEnd = events.filterIsInstance<FocusEvent.BreakEnded>().lastOrNull()
    val late = events.any { nowMillis - it.atMillis > LATE_MILLIS }
    val parts =
        if (!late) {
            listOfNotNull(workEnd?.let { "Focus session done. Break: ${it.breakMinutes} min." }, breakEnd?.let { "Break over." })
        } else {
            val today = WallClock.wallTimeAt(nowMillis.toDouble(), zone).day

            fun at(millis: Long): String {
                val time = WallClock.wallTimeAt(millis.toDouble(), zone)
                val clock = clockText(time.minuteOfDay)
                return if (time.day == today) "at $clock" else "${dayText(time.day, today, mid = true)} at $clock"
            }
            listOfNotNull(
                workEnd?.let { "Focus session done ${at(it.atMillis)}." },
                when {
                    breakEnd != null -> "Break ended ${at(breakEnd.atMillis)}."
                    workEnd != null -> "Break until ${at(workEnd.atMillis + workEnd.breakMinutes * MINUTE_MILLIS).removePrefix("at ")}."
                    else -> null
                },
            )
        }
    return parts.joinToString(" ").ifEmpty { null }
}

private fun clockMillis(): Long = WallClock.nowMillis().toLong()

private fun pageVisible(): Boolean = document.asDynamic().visibilityState != "hidden"

private const val NOT_SAVED = "The Focus timer could not be saved on this device."
private const val MINUTE_MILLIS = 60_000L
private const val SECOND_MILLIS = 1_000L
private const val TICK_MARGIN_MILLIS = 15L

/** An end noticed later than this gets the message with real times. */
private const val LATE_MILLIS = 5_000L
