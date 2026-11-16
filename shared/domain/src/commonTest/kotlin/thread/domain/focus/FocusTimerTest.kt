package thread.domain.focus

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Every run here is SYNTHETIC test data. Two FocusTimers on one store play two tabs of one device.
class FocusTimerTest {
    private class FakeRunStore : FocusRunStore {
        private val mutex = Mutex()
        val state = MutableStateFlow(FocusDevice())

        override fun observe(): Flow<FocusDevice> = state

        override suspend fun <T> update(change: (FocusDevice) -> FocusChange<T>): T =
            mutex.withLock {
                val result = change(state.value)
                state.value = result.device
                result.result
            }
    }

    private var now = T0
    private var nextRun = 0
    private val store = FakeRunStore()
    private val recorded = mutableListOf<FocusSession>()

    private fun tab() = FocusTimer(store, { recorded += it }, { now }, { "run-${++nextRun}" })

    private val run: FocusRun get() = store.state.value.run!!

    @Test
    fun startUsesThePickedTargetAndASecondStartChangesNothing() =
        runTest {
            val timer = tab()
            val target = FocusTarget(FocusTargetKind.Topic, "t-1")
            timer.choose(target)

            timer.start(FocusSettings(30, 10))
            val first = run
            now += MIN
            tab().start(FocusSettings())

            assertEquals(first, run)
            assertEquals("run-1", first.runId)
            assertEquals(target, first.target)
            assertEquals(T0 + 30 * MIN, first.endsAtMillis)
        }

    @Test
    fun anActionFromAnOlderViewOfTheRunChangesNothing() =
        runTest {
            val one = tab()
            val two = tab()
            one.start(FocusSettings())
            val seen = run
            now += 2 * MIN
            one.stop(seen)

            two.pause(seen)
            two.resume(seen)

            assertNull(store.state.value.run)
            assertEquals(1, recorded.size)
        }

    @Test
    fun aPauseAfterTheEndSettlesFirstAndDoesNotPauseTheBreak() =
        runTest {
            val timer = tab()
            timer.start(FocusSettings())
            val seen = run
            now += 25 * MIN + 1_000

            val events = timer.pause(seen)

            assertEquals(listOf<FocusEvent>(FocusEvent.WorkEnded(T0 + 25 * MIN, 5)), events)
            assertEquals(FocusPhase.Break, run.phase)
            assertEquals(true, run.running)
            assertEquals(listOf(FocusOutcome.Completed), recorded.map { it.outcome })
        }

    @Test
    fun twoTabsSettlingOneEndRecordItOnce() =
        runTest {
            val one = tab()
            val two = tab()
            one.start(FocusSettings())
            now += 26 * MIN

            val first = one.settle()
            val second = two.settle()

            assertEquals(1, first.size)
            assertEquals(emptyList(), second)
            assertEquals(listOf("f-run-1"), recorded.map { it.id })
        }

    @Test
    fun settlingAgainRecordsNothingNew() =
        runTest {
            val timer = tab()
            timer.start(FocusSettings())
            now += 3 * HOUR

            assertEquals(2, timer.settle().size)
            assertEquals(emptyList(), timer.settle())
            assertEquals(1, recorded.size)
            assertNull(store.state.value.run)
        }

    @Test
    fun pauseResumeAndStopRecordTheTimeFocused() =
        runTest {
            val timer = tab()
            timer.start(FocusSettings())
            now += 4 * MIN
            timer.pause(run)
            now += 10 * MIN
            timer.resume(run)
            now += 3 * MIN

            timer.stop(run)

            val session = recorded.single()
            assertEquals(FocusOutcome.Stopped, session.outcome)
            assertEquals(7 * MIN, session.focusedMillis)
            assertEquals(T0 + 17 * MIN, session.endedAtMillis)
            assertNull(store.state.value.run)
        }

    @Test
    fun aStopDuringTheBreakRecordsNoSecondSession() =
        runTest {
            val timer = tab()
            timer.start(FocusSettings())
            now += 26 * MIN
            timer.settle()

            timer.stop(run)

            assertEquals(listOf(FocusOutcome.Completed), recorded.map { it.outcome })
            assertNull(store.state.value.run)
        }

    @Test
    fun choosingATargetKeepsItForTheNextStartAndMovesARunningPeriod() =
        runTest {
            val timer = tab()
            timer.start(FocusSettings())
            val target = FocusTarget(FocusTargetKind.Task, "k-1")

            timer.choose(target)
            now += 25 * MIN
            timer.settle()
            now += 5 * MIN
            timer.settle()

            assertEquals(target, recorded.single().target)
            assertEquals(FocusDevice(run = null, lastTarget = target), store.observe().first())
        }

    @Test
    fun aCallerCancelledWhileTheStoreSavesStillRecordsTheSession() =
        runTest {
            tab().start(FocusSettings())
            now += 26 * MIN
            val saved = CompletableDeferred<Unit>()
            // Stores the change at once but answers later, like a browser transaction that commits before it reports.
            val slowStore =
                object : FocusRunStore {
                    override fun observe(): Flow<FocusDevice> = store.observe()

                    override suspend fun <T> update(change: (FocusDevice) -> FocusChange<T>): T {
                        val result = store.update(change)
                        saved.await()
                        return result
                    }
                }

            val settling = launch { FocusTimer(slowStore, { recorded += it }, { now }, { "run-x" }).settle() }
            runCurrent()
            settling.cancel()
            saved.complete(Unit)
            settling.join()

            assertEquals(listOf(FocusSession.idFor("run-1")), recorded.map { it.id })
            assertEquals(FocusPhase.Break, run.phase)
        }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN

        // A SYNTHETIC start time.
        const val T0 = 1_794_820_800_000L
    }
}
