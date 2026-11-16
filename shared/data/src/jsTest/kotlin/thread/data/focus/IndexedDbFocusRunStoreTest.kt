package thread.data.focus

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import thread.data.local.IndexedDbKeyValueDatabase
import thread.domain.focus.FocusDevice
import thread.domain.focus.FocusPhase
import thread.domain.focus.FocusSession
import thread.domain.focus.FocusSettings
import thread.domain.focus.FocusTarget
import thread.domain.focus.FocusTargetKind
import thread.domain.focus.FocusTimer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Runs against the real IndexedDB and BroadcastChannel of ChromeHeadless, in a database of its own. Two store
 * instances on one database play two tabs. SYNTHETIC runs only.
 */
class IndexedDbFocusRunStoreTest {
    private val suffix = Random.nextLong().toString(16)
    private val name = "thread-test-focus-$suffix"
    private val channel = "thread-test-focus-$suffix"
    private var now = T0
    private var nextRun = 0
    private val recorded = mutableListOf<FocusSession>()

    private fun tab() = IndexedDbFocusRunStore(IndexedDbKeyValueDatabase(name, listOf(IndexedDbKeyValueDatabase.FOCUS_STORE)), channel)

    private fun timer(store: IndexedDbFocusRunStore) = FocusTimer(store, { recorded += it }, { now }, { "run-${++nextRun}" })

    @Test
    fun aStartIsStoredAndReadBackByANewPage() =
        runTest {
            val target = FocusTarget(FocusTargetKind.Topic, "t-1")
            val timer = timer(tab())
            timer.choose(target)
            timer.start(FocusSettings(40, 10))

            val stored = tab().observe().first()

            val run = assertNotNull(stored.run)
            assertEquals(target, stored.lastTarget)
            assertEquals(target, run.target)
            assertEquals(T0 + 40 * MIN, run.endsAtMillis)
        }

    @Test
    fun aChangeInOneTabReachesTheOther() =
        runTest {
            val one = tab()
            val two = tab()
            assertEquals(FocusDevice(), two.observe().first())

            timer(one).start(FocusSettings())

            val seen = two.observe().first { it.run != null }.run!!
            now += MIN
            timer(one).pause(seen)
            val paused = two.observe().first { it.run?.running == false }
            assertEquals(seen.runId, paused.run?.runId)
        }

    @Test
    fun twoTabsStartingAtOnceMakeOneRunAndSettlingTwiceRecordsOnce() =
        runTest {
            val one = timer(tab())
            val two = timer(tab())

            listOf(async { one.start(FocusSettings()) }, async { two.start(FocusSettings()) }).awaitAll()
            val run = assertNotNull(tab().observe().first().run)
            now += 26 * MIN
            listOf(async { one.settle() }, async { two.settle() }).awaitAll()

            assertEquals(1, nextRun, "The second start found the run and made none")
            assertEquals(listOf(FocusSession.idFor(run.runId)), recorded.map { it.id })
            val stored = tab().observe().first()
            assertEquals(FocusPhase.Break, stored.run?.phase)
        }

    @Test
    fun aStopFromAnOlderViewOfTheRunChangesNothing() =
        runTest {
            val store = tab()
            timer(store).start(FocusSettings())
            val seen = store.observe().first().run!!
            now += 2 * MIN
            timer(tab()).stop(seen)

            timer(store).pause(seen)
            timer(store).stop(seen)

            assertNull(tab().observe().first().run)
            assertEquals(1, recorded.size)
        }

    @Test
    fun anUnreadableRecordCountsAsNoTimerAndIsReplacedByTheNextChange() =
        runTest {
            val database = IndexedDbKeyValueDatabase(name, listOf(IndexedDbKeyValueDatabase.FOCUS_STORE))
            database.store(IndexedDbKeyValueDatabase.FOCUS_STORE).put("device", "{\"v\":99}")

            assertEquals(FocusDevice(), tab().observe().first())
            timer(tab()).start(FocusSettings())
            assertNotNull(tab().observe().first().run)
        }

    private companion object {
        const val MIN = 60_000L

        // A SYNTHETIC start time.
        const val T0 = 1_794_820_800_000L
    }
}
