package thread.domain.external

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Refresh on show (D67). SYNTHETIC keys only; the clock is the test's virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShowRefreshTest {
    private var conditions =
        BackgroundRefresh.Conditions(
            signedIn = true,
            fixtures = false,
            connected = true,
            online = true,
            visible = true,
            accessExpired = false,
            blockedUntilMillis = null,
        )

    /** Every chunk [fetch] got, with the virtual time it started. */
    private val asked = mutableListOf<Pair<Long, List<String>>>()
    private var active = 0
    private var mostActive = 0

    private fun TestScope.refresh(
        chunkSize: Int = 50,
        budget: RequestBudget = RequestBudget(100, 60_000),
        latencyMillis: Long = 400,
        onFetch: (List<String>) -> Unit = {},
    ): ShowRefresh<String> =
        ShowRefresh(
            // The test scope, not backgroundScope: advanceUntilIdle does not run work that lives only in the background.
            scope = this,
            nowMillis = { currentTime },
            chunkSize = chunkSize,
            budget = budget,
            conditions = { conditions },
        ) { keys ->
            asked += currentTime to keys
            active++
            mostActive = maxOf(mostActive, active)
            try {
                onFetch(keys)
                delay(latencyMillis)
            } finally {
                active--
            }
        }

    private fun chunks(): List<List<String>> = asked.map { it.second }

    @Test
    fun rowsThatShowTogetherShareOneRequest() =
        runTest {
            val refresh = refresh()

            refresh.show(listOf("SYN-101", "SYN-103"))
            refresh.show(listOf("SYN-103", "SYN-104"))
            advanceUntilIdle()

            assertEquals(listOf(listOf("SYN-101", "SYN-103", "SYN-104")), chunks())
        }

    @Test
    fun manyItemsGoInChunksOneAfterAnother() =
        runTest {
            val refresh = refresh(chunkSize = 2)

            refresh.show(listOf("SYN-101", "SYN-102", "SYN-103", "SYN-104", "SYN-105"))
            advanceUntilIdle()

            assertEquals(listOf(listOf("SYN-101", "SYN-102"), listOf("SYN-103", "SYN-104"), listOf("SYN-105")), chunks())
            assertEquals(1, mostActive, "never two requests at once")
        }

    @Test
    fun anItemAskedLessThanAMinuteAgoIsNotAskedAgain() =
        runTest {
            val refresh = refresh()
            refresh.show(listOf("SYN-101"))
            advanceUntilIdle()

            advanceTimeBy(30_000)
            assertNull(refresh.show(listOf("SYN-101")), "nothing due 30 seconds later")
            advanceTimeBy(30_000)
            refresh.show(listOf("SYN-101"))
            advanceUntilIdle()

            assertEquals(listOf(0L, 60_400L), asked.map { it.first }, "asked again once the minute passed")
        }

    @Test
    fun anItemAnotherViewJustAskedAboutIsSkipped() =
        runTest {
            val refresh = refresh()
            refresh.noteAsked("SYN-101", currentTime)

            refresh.show(listOf("SYN-101", "SYN-103"))
            advanceUntilIdle()

            assertEquals(listOf(listOf("SYN-103")), chunks())
            assertEquals(0L, refresh.lastAskedMillis("SYN-101"))
        }

    @Test
    fun anItemOnItsWayIsNotAskedTwice() =
        runTest {
            val refresh = refresh(latencyMillis = 1_000)
            refresh.show(listOf("SYN-101"))
            runCurrent()
            assertEquals(1, active, "the first request is on its way")

            // A second screen shows the same ticket while the answer is on its way.
            refresh.show(listOf("SYN-101", "SYN-103"))
            advanceUntilIdle()

            assertEquals(listOf(listOf("SYN-101"), listOf("SYN-103")), chunks())
        }

    @Test
    fun nothingGoesOutOfflineHiddenWithFixturesAfterA401OrInsideARateLimit() =
        runTest {
            val refresh = refresh()
            val blocked =
                listOf(
                    conditions.copy(online = false),
                    conditions.copy(visible = false),
                    conditions.copy(fixtures = true),
                    conditions.copy(accessExpired = true),
                    conditions.copy(blockedUntilMillis = currentTime + 30_000),
                    conditions.copy(connected = false),
                    conditions.copy(signedIn = false),
                )
            for (each in blocked) {
                conditions = each
                assertNull(refresh.show(listOf("SYN-101")), each.toString())
                advanceUntilIdle()
            }

            assertEquals(emptyList(), chunks())
            assertNull(refresh.lastAskedMillis("SYN-101"), "an item that did not go stays due")
        }

    @Test
    fun a401InTheMiddleStopsTheRestUntilTheNextShow() =
        runTest {
            val refresh =
                refresh(chunkSize = 1) { keys ->
                    if (keys == listOf("SYN-101")) conditions = conditions.copy(accessExpired = true)
                }

            refresh.show(listOf("SYN-101", "SYN-103", "SYN-104"))
            advanceUntilIdle()
            assertEquals(listOf(listOf("SYN-101")), chunks())

            // Connected again: the rows that did not go are still due.
            conditions = conditions.copy(accessExpired = false)
            refresh.show(listOf("SYN-101", "SYN-103", "SYN-104"))
            advanceUntilIdle()
            assertEquals(listOf(listOf("SYN-101"), listOf("SYN-103"), listOf("SYN-104")), chunks())
        }

    @Test
    fun aRateLimitAnswerStopsTheRest() =
        runTest {
            val refresh =
                refresh(chunkSize = 1) { keys ->
                    if (keys == listOf("SYN-101")) conditions = conditions.copy(blockedUntilMillis = currentTime + 30_000)
                }

            refresh.show(listOf("SYN-101", "SYN-103"))
            advanceUntilIdle()

            assertEquals(listOf(listOf("SYN-101")), chunks())
        }

    @Test
    fun theBudgetLetsAFewChunksAMinuteAndTheScreenGetsTheRestLater() =
        runTest {
            val refresh = refresh(chunkSize = 1, budget = RequestBudget(2, 60_000))
            val keys = listOf("SYN-101", "SYN-103", "SYN-104", "SYN-105")

            val screen = launch { refresh.keepShowing(keys) }
            advanceUntilIdle()

            assertEquals(keys.map { listOf(it) }, chunks(), "each item once, also after the budget allows again")
            assertEquals(listOf(0L, 400L, 60_000L, 60_400L), asked.map { it.first })
            assertEquals(true, screen.isCompleted, "the screen stops asking once every item was asked")
        }

    @Test
    fun aScreenThatLeavesStopsWaitingForTheBudget() =
        runTest {
            val refresh = refresh(chunkSize = 1, budget = RequestBudget(1, 60_000))

            val screen = launch { refresh.keepShowing(listOf("SYN-101", "SYN-103")) }
            advanceTimeBy(1_000)
            screen.cancel()
            advanceUntilIdle()

            assertEquals(listOf(listOf("SYN-101")), chunks())
        }

    @Test
    fun anUnexpectedErrorKeepsTheOtherChunksGoing() =
        runTest {
            val refresh =
                refresh(chunkSize = 1) { keys ->
                    if (keys == listOf("SYN-101")) throw IllegalStateException("synthetic failure")
                }

            refresh.show(listOf("SYN-101", "SYN-103"))
            advanceUntilIdle()

            assertEquals(listOf(listOf("SYN-101"), listOf("SYN-103")), chunks())
            assertNotNull(refresh.lastAskedMillis("SYN-101"), "a failed item waits for the skip window too")
        }

    @Test
    fun clearForgetsWhatWasAskedAfterTheConnectionChanged() =
        runTest {
            val refresh = refresh()
            refresh.show(listOf("SYN-101"))
            advanceUntilIdle()

            refresh.clear()
            refresh.show(listOf("SYN-101"))
            advanceUntilIdle()

            assertEquals(listOf(listOf("SYN-101"), listOf("SYN-101")), chunks())
        }
}
