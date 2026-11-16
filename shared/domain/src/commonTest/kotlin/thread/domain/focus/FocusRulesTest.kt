package thread.domain.focus

import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Every run, Task and Topic here is SYNTHETIC test data. Times are milliseconds from a fake clock.
class FocusRulesTest {
    private val settings = FocusSettings()
    private val target = FocusTarget(FocusTargetKind.Task, "k-1")

    private fun started(at: Long = T0) = startFocus(settings, target, at, "run-1")

    @Test
    fun startRunsAFocusPeriodOfTheSetLength() {
        val run = started()

        assertEquals(FocusPhase.Work, run.phase)
        assertTrue(run.running)
        assertEquals(T0 + 25 * MIN, run.endsAtMillis)
        assertEquals(25 * MIN, run.plannedWorkMillis)
        assertEquals(5 * MIN, run.breakMillis)
        assertEquals(T0, run.startedAtMillis)
        assertEquals(target, run.target)
        assertEquals(25 * MIN, run.remainingAt(T0))
        assertEquals(15 * MIN, run.remainingAt(T0 + 10 * MIN))
    }

    @Test
    fun pausesFreezeTheTimeLeftAndNeverCountAsFocus() {
        var run = pauseFocus(started(), T0 + 5 * MIN)
        assertEquals(20 * MIN, run.remainingAt(T0 + 50 * MIN))

        run = resumeFocus(run, T0 + 15 * MIN)
        assertEquals(T0 + 35 * MIN, run.endsAtMillis)
        run = pauseFocus(run, T0 + 20 * MIN)
        assertEquals(15 * MIN, run.remainingAt(T0 + 21 * MIN))
        run = resumeFocus(run, T0 + 30 * MIN)

        assertEquals(5 * MIN, run.remainingAt(T0 + 40 * MIN))
        assertEquals(20 * MIN, stopFocus(run, T0 + 40 * MIN)?.focusedMillis)
    }

    @Test
    fun pauseAndResumeTwiceChangeNothing() {
        val paused = pauseFocus(started(), T0 + MIN)

        assertEquals(paused, pauseFocus(paused, T0 + 2 * MIN))
        val resumed = resumeFocus(paused, T0 + 3 * MIN)
        assertEquals(resumed, resumeFocus(resumed, T0 + 4 * MIN))
    }

    @Test
    fun aStopDuringFocusSavesAStoppedSessionWithTheTimeFocused() {
        val session = stopFocus(started(), T0 + 12 * MIN + 30_000)

        assertEquals(
            FocusSession(
                id = "f-run-1",
                startedAtMillis = T0,
                endedAtMillis = T0 + 12 * MIN + 30_000,
                focusedMillis = 12 * MIN + 30_000,
                plannedMinutes = 25,
                outcome = FocusOutcome.Stopped,
                target = target,
            ),
            session,
        )
    }

    @Test
    fun aStopUnderOneMinuteSavesNothing() {
        assertNull(stopFocus(started(), T0 + MIN - 1))
        assertEquals(MIN, stopFocus(started(), T0 + MIN)?.focusedMillis)
    }

    @Test
    fun aStopDuringTheBreakSavesNothing() {
        val inBreak = settleFocus(started(), T0 + 26 * MIN).run!!

        assertEquals(FocusPhase.Break, inBreak.phase)
        assertNull(stopFocus(inBreak, T0 + 27 * MIN))
    }

    @Test
    fun theFocusEndIsSavedAtItsRealTimeAndTheBreakStartsThen() {
        val result = settleFocus(started(), T0 + 25 * MIN + 3_000)

        val session = result.sessions.single()
        assertEquals(FocusOutcome.Completed, session.outcome)
        assertEquals(T0 + 25 * MIN, session.endedAtMillis)
        assertEquals(25 * MIN, session.focusedMillis)
        assertEquals(listOf<FocusEvent>(FocusEvent.WorkEnded(T0 + 25 * MIN, 5)), result.events)
        val run = result.run!!
        assertEquals(FocusPhase.Break, run.phase)
        assertTrue(run.running)
        assertEquals(T0 + 30 * MIN, run.endsAtMillis)
        assertEquals(5 * MIN - 3_000, run.remainingAt(T0 + 25 * MIN + 3_000))
    }

    @Test
    fun aLongGapSettlesTheFocusEndAndTheBreakEndWithTheirRealTimes() {
        val result = settleFocus(started(), T0 + 3 * HOUR)

        assertNull(result.run)
        assertEquals(T0 + 25 * MIN, result.sessions.single().endedAtMillis)
        assertEquals(
            listOf(FocusEvent.WorkEnded(T0 + 25 * MIN, 5), FocusEvent.BreakEnded(T0 + 30 * MIN)),
            result.events,
        )
    }

    @Test
    fun theBreakEndAloneReturnsToReady() {
        val inBreak = settleFocus(started(), T0 + 25 * MIN).run

        val result = settleFocus(inBreak, T0 + 30 * MIN)

        assertNull(result.run)
        assertEquals(emptyList(), result.sessions)
        assertEquals(listOf<FocusEvent>(FocusEvent.BreakEnded(T0 + 30 * MIN)), result.events)
    }

    @Test
    fun settlingTwiceGivesTheSameResultAndRecordsNothingNew() {
        val run = started()
        val first = settleFocus(run, T0 + 26 * MIN)

        assertEquals(first, settleFocus(run, T0 + 26 * MIN))
        val again = settleFocus(first.run, T0 + 26 * MIN)
        assertEquals(first.run, again.run)
        assertEquals(emptyList(), again.sessions)
        assertEquals(emptyList(), again.events)
    }

    @Test
    fun beforeItsEndARunIsLeftAsItIs() {
        val run = started()

        assertEquals(FocusSettlement(run, emptyList(), emptyList()), settleFocus(run, T0 + 25 * MIN - 1))
        assertEquals(FocusSettlement(null, emptyList(), emptyList()), settleFocus(null, T0))
    }

    @Test
    fun aPausedRunHasNoEnd() {
        val paused = pauseFocus(started(), T0 + MIN)

        assertEquals(FocusSettlement(paused, emptyList(), emptyList()), settleFocus(paused, T0 + 5 * HOUR))
    }

    @Test
    fun aClockMovedBackwardsNeverGivesNegativeFocusOrAnEndBeforeTheStart() {
        val run = started()

        assertEquals(25 * MIN, run.remainingAt(T0 - HOUR))
        assertNull(stopFocus(run, T0 - HOUR))
        val paused = pauseFocus(run, T0 + 10 * MIN)
        val session = stopFocus(resumeFocus(paused, T0 - HOUR), T0 - HOUR + MIN)!!
        assertEquals(11 * MIN, session.focusedMillis)
        assertEquals(T0, session.endedAtMillis)
    }

    @Test
    fun durationsDefaultTo25And5AndStayInTheirLimits() {
        assertEquals(FocusSettings(25, 5), FocusSettings())
        FocusSettings(1, 1)
        FocusSettings(90, 30)
        assertFailsWith<IllegalArgumentException> { FocusSettings(0, 5) }
        assertFailsWith<IllegalArgumentException> { FocusSettings(91, 5) }
        assertFailsWith<IllegalArgumentException> { FocusSettings(25, 0) }
        assertFailsWith<IllegalArgumentException> { FocusSettings(25, 31) }
    }

    @Test
    fun typedDurationsReadAsV3ReadsThem() {
        assertEquals(40, FocusSettings.workMinutesFrom(" 40 "))
        assertEquals(25, FocusSettings.workMinutesFrom(""))
        assertEquals(25, FocusSettings.workMinutesFrom("abc"))
        assertEquals(1, FocusSettings.workMinutesFrom("0"))
        assertEquals(90, FocusSettings.workMinutesFrom("100"))
        assertEquals(5, FocusSettings.breakMinutesFrom(""))
        assertEquals(1, FocusSettings.breakMinutesFrom("-3"))
        assertEquals(30, FocusSettings.breakMinutesFrom("45"))
    }

    @Test
    fun aRunStartsWithTheLengthsOfItsSettings() {
        val run = startFocus(FocusSettings(50, 10), null, T0, "run-2")

        assertEquals(T0 + 50 * MIN, run.endsAtMillis)
        assertEquals(listOf<FocusEvent>(FocusEvent.WorkEnded(T0 + 50 * MIN, 10)), settleFocus(run, T0 + 50 * MIN).events)
        assertEquals(50, settleFocus(run, T0 + 50 * MIN).sessions.single().plannedMinutes)
    }

    @Test
    fun targetLabelsAreReadNowFromTheWorkspace() {
        val topic = Topic(TopicId("t-1"), "Synthetic topic", TopicColor.entries.first(), SEED_STATUSES[0].id)
        val open = Task(TaskId("k-1"), "Synthetic task", topic.id)
        val inTrash = Task(TaskId("k-2"), "Synthetic task in Trash", null, deleted = true)
        val tasks = listOf(open, inTrash)
        val topics = listOf(topic)

        assertEquals("No Task or Topic", focusTargetLabel(null, tasks, topics))
        assertEquals("Synthetic task", focusTargetLabel(FocusTarget(FocusTargetKind.Task, "k-1"), tasks, topics))
        assertEquals("Synthetic task in Trash", focusTargetLabel(FocusTarget(FocusTargetKind.Task, "k-2"), tasks, topics))
        assertEquals("Deleted task", focusTargetLabel(FocusTarget(FocusTargetKind.Task, "k-gone"), tasks, topics))
        assertEquals("Synthetic topic", focusTargetLabel(FocusTarget(FocusTargetKind.Topic, "t-1"), tasks, topics))
        assertEquals("Deleted Topic", focusTargetLabel(FocusTarget(FocusTargetKind.Topic, "t-gone"), tasks, topics))
    }

    @Test
    fun theSessionIdIsMadeFromTheRunId() {
        assertEquals("f-run-1", FocusSession.idFor("run-1"))
        assertEquals("f-run-1", settleFocus(started(), T0 + 25 * MIN).sessions.single().id)
        assertFailsWith<IllegalArgumentException> { startFocus(settings, null, T0, "bad id") }
        assertFailsWith<IllegalArgumentException> { startFocus(settings, null, T0, "x".repeat(65)) }
    }

    @Test
    fun theHistoryShowsWholeMinutes() {
        val completed = settleFocus(started(), T0 + 25 * MIN).sessions.single()
        assertEquals(25, completed.shownMinutes)
        assertEquals(1, stopFocus(started(), T0 + MIN + 29_999)!!.shownMinutes)
        assertEquals(2, stopFocus(started(), T0 + MIN + 30_000)!!.shownMinutes)
    }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN

        // 2026-11-16 09:00 UTC, a SYNTHETIC start time.
        const val T0 = 1_794_820_800_000L
    }
}
