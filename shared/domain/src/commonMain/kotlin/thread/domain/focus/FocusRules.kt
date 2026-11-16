package thread.domain.focus

import thread.domain.task.Task
import thread.domain.topic.Topic

/*
 * The Focus timer's rules as pure functions over a run and the time now (focus-timer-plan section 5). Time left is
 * always computed from stored instants, so a slow or sleeping tab only delays what the screen shows, never what is
 * saved (02, 04 "Pomodoro persistence", 07 Prompt 6).
 */

/** A new run: a focus period of [settings] that starts at [nowMillis] and runs at once. */
fun startFocus(
    settings: FocusSettings,
    target: FocusTarget?,
    nowMillis: Long,
    runId: String,
): FocusRun {
    val work = settings.workMinutes * MINUTE_MILLIS
    return FocusRun(
        runId = runId,
        phase = FocusPhase.Work,
        running = true,
        endsAtMillis = nowMillis + work,
        remainingMillis = work,
        plannedWorkMillis = work,
        breakMillis = settings.breakMinutes * MINUTE_MILLIS,
        startedAtMillis = nowMillis,
        target = target,
    )
}

/** Freezes the time left. A paused run never counts as focus. */
fun pauseFocus(
    run: FocusRun,
    nowMillis: Long,
): FocusRun = if (!run.running) run else run.copy(running = false, remainingMillis = run.remainingAt(nowMillis))

/** Runs again from the frozen time left. */
fun resumeFocus(
    run: FocusRun,
    nowMillis: Long,
): FocusRun = if (run.running) run else run.copy(running = true, endsAtMillis = nowMillis + run.remainingMillis)

/**
 * The session a Stop at [nowMillis] saves: during focus, a Stopped session with the time focused, when that is at
 * least [FocusSession.MIN_SAVED_MILLIS] (FD2). During a break nothing is saved. Either way the run ends.
 */
fun stopFocus(
    run: FocusRun,
    nowMillis: Long,
): FocusSession? {
    if (run.phase != FocusPhase.Work) return null
    val focused = run.plannedWorkMillis - run.remainingAt(nowMillis)
    if (focused < FocusSession.MIN_SAVED_MILLIS) return null
    return sessionOf(run, nowMillis, focused, FocusOutcome.Stopped)
}

/** What [settleFocus] found: the run after every end that passed (null is Ready), the sessions and the events. */
data class FocusSettlement(
    val run: FocusRun?,
    val sessions: List<FocusSession>,
    val events: List<FocusEvent>,
)

/**
 * Applies every end that passed by [nowMillis], in order, each at its real time:
 *
 * - The focus period ends at its end time: a Completed session ends then, and the break starts at that same instant
 *   (FD3).
 * - The break ends at its end time: the timer is Ready again.
 *
 * Both can pass in one call, after a long reload or a closed tab (F1 A). A paused run has no end. With the same input
 * the output is the same, and settling the result again finds nothing new.
 */
fun settleFocus(
    run: FocusRun?,
    nowMillis: Long,
): FocusSettlement {
    var current = run
    val sessions = mutableListOf<FocusSession>()
    val events = mutableListOf<FocusEvent>()
    if (current != null && current.running && current.phase == FocusPhase.Work && nowMillis >= current.endsAtMillis) {
        val endedAt = current.endsAtMillis
        sessions += sessionOf(current, endedAt, current.plannedWorkMillis, FocusOutcome.Completed)
        events += FocusEvent.WorkEnded(endedAt, (current.breakMillis / MINUTE_MILLIS).toInt())
        current =
            current.copy(
                phase = FocusPhase.Break,
                endsAtMillis = endedAt + current.breakMillis,
                remainingMillis = current.breakMillis,
            )
    }
    if (current != null && current.running && current.phase == FocusPhase.Break && nowMillis >= current.endsAtMillis) {
        events += FocusEvent.BreakEnded(current.endsAtMillis)
        current = null
    }
    return FocusSettlement(current, sessions, events)
}

/**
 * The session of [run] ending at [endedAtMillis]. A clock moved backwards could put the end before the start, which the
 * account would refuse, so the end is never earlier than the start.
 */
private fun sessionOf(
    run: FocusRun,
    endedAtMillis: Long,
    focusedMillis: Long,
    outcome: FocusOutcome,
): FocusSession =
    FocusSession(
        id = FocusSession.idFor(run.runId),
        startedAtMillis = run.startedAtMillis,
        endedAtMillis = endedAtMillis.coerceAtLeast(run.startedAtMillis),
        focusedMillis = focusedMillis,
        plannedMinutes = (run.plannedWorkMillis / MINUTE_MILLIS).toInt(),
        outcome = outcome,
        target = run.target,
    )

/**
 * How the panel names [target], read now from the stored workspace (with Trash). A Task or Topic in Trash keeps its
 * name (FD5); one deleted forever, or not known here, reads as deleted.
 */
fun focusTargetLabel(
    target: FocusTarget?,
    tasks: List<Task>,
    topics: List<Topic>,
): String =
    when {
        target == null -> NO_FOCUS_TARGET
        target.kind == FocusTargetKind.Task -> tasks.firstOrNull { it.id.value == target.id }?.title ?: DELETED_FOCUS_TASK
        else -> topics.firstOrNull { it.id.value == target.id }?.name ?: DELETED_FOCUS_TOPIC
    }

const val NO_FOCUS_TARGET = "No Task or Topic"
const val DELETED_FOCUS_TASK = "Deleted task"
const val DELETED_FOCUS_TOPIC = "Deleted Topic"
