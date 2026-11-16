package thread.domain.external

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalLoaderTest {
    /** In-memory source with SYNTHETIC records. */
    private class FakeSource(
        var connected: Boolean = true,
        var saved: Saved<String>? = null,
        var answer: ExternalResult<String> = ExternalResult.Ok("synthetic fresh"),
        var failReads: Boolean = false,
        var failSaves: Boolean = false,
    ) : ExternalRecordSource<String, String> {
        var refreshCalls = 0
        val forgotten = mutableListOf<String>()

        override suspend fun isConnected(): Boolean = connected

        override suspend fun readSaved(key: String): Saved<String>? {
            if (failReads) throw IllegalStateException("synthetic read failure")
            return saved
        }

        override suspend fun refresh(
            key: String,
            nowMillis: Long,
        ): ExternalResult<Refreshed<String>> {
            refreshCalls++
            return when (val current = answer) {
                is ExternalResult.Ok -> {
                    val fresh = Saved(current.value, nowMillis)
                    if (!failSaves) saved = fresh
                    ExternalResult.Ok(Refreshed(fresh, storedOnDevice = !failSaves))
                }
                is ExternalResult.Problem -> current
            }
        }

        override suspend fun forgetSaved(key: String) {
            forgotten += key
            saved = null
        }
    }

    private var online = true
    private var now = 1_000L

    private fun loader(
        source: FakeSource,
        gate: RateLimitGate = RateLimitGate(),
    ) = ExternalLoader(source, isOnline = { online }, nowMillis = { now }, rateLimit = gate)

    private val old = Saved("synthetic saved", savedAtMillis = 500L)

    @Test
    fun savedCopyShowsFirstThenTheFreshAnswer() =
        runTest {
            val source = FakeSource(saved = old)

            val views = loader(source).load("SYN-1").toList()

            assertEquals(listOf(ExternalView.Refreshing(old), ExternalView.Fresh(Saved("synthetic fresh", 1_000L), true)), views)
            assertEquals(Saved("synthetic fresh", 1_000L), source.saved)
        }

    @Test
    fun withoutASavedCopyItLoadsFirst() =
        runTest {
            val views = loader(FakeSource()).load("SYN-1").toList()

            assertEquals(ExternalView.Loading, views.first())
            assertEquals(ExternalView.Fresh(Saved("synthetic fresh", 1_000L), true), views.last())
        }

    @Test
    fun offlineShowsTheSavedCopyWithoutARequest() =
        runTest {
            online = false
            val source = FakeSource(saved = old)

            val views = loader(source).load("SYN-1").toList()

            assertEquals(listOf(ExternalView.SavedCopy(old, ExternalProblem.Offline)), views)
            assertEquals(0, source.refreshCalls)
        }

    @Test
    fun offlineWithoutASavedCopyShowsNothing() =
        runTest {
            online = false

            val views = loader(FakeSource()).load("SYN-1").toList()

            assertEquals(listOf(ExternalView.Missing(ExternalProblem.Offline)), views)
        }

    @Test
    fun notConnectedHidesTheSavedCopy() =
        runTest {
            val source = FakeSource(connected = false, saved = old)

            val views = loader(source).load("SYN-1").toList()

            assertEquals(listOf(ExternalView.Missing(ExternalProblem.NotConnected)), views)
            assertEquals(0, source.refreshCalls)
        }

    @Test
    fun accessProblemsKeepTheSavedCopyReadable() =
        runTest {
            for (problem in listOf(ExternalProblem.Unauthorized, ExternalProblem.Forbidden, ExternalProblem.Failed("http-500"))) {
                val source = FakeSource(saved = old, answer = ExternalResult.Problem(problem))

                val views = loader(source).load("SYN-1").toList()

                assertEquals(ExternalView.SavedCopy(old, problem), views.last())
                assertEquals(old, source.saved)
            }
        }

    @Test
    fun notFoundDeletesTheSavedCopy() =
        runTest {
            val source = FakeSource(saved = old, answer = ExternalResult.Problem(ExternalProblem.NotFound))

            val views = loader(source).load("SYN-1").toList()

            assertEquals(ExternalView.Missing(ExternalProblem.NotFound), views.last())
            assertEquals(listOf("SYN-1"), source.forgotten)
        }

    @Test
    fun aRateLimitStopsRequestsUntilItEnds() =
        runTest {
            val gate = RateLimitGate()
            val source = FakeSource(saved = old, answer = ExternalResult.Problem(ExternalProblem.RateLimited(retryAtMillis = 121_000L)))
            val loader = loader(source, gate)

            assertEquals(ExternalView.SavedCopy(old, ExternalProblem.RateLimited(121_000L)), loader.load("SYN-1").toList().last())
            now = 60_000L
            source.answer = ExternalResult.Ok("synthetic fresh")
            assertEquals(listOf(ExternalView.SavedCopy(old, ExternalProblem.RateLimited(121_000L))), loader.load("SYN-2").toList())
            assertEquals(1, source.refreshCalls)

            now = 121_001L
            assertEquals(ExternalView.Fresh(Saved("synthetic fresh", 121_001L), true), loader.load("SYN-1").toList().last())
            assertEquals(2, source.refreshCalls)
        }

    @Test
    fun noAnswerAfterGoingOfflineCountsAsOffline() =
        runTest {
            val source =
                object : ExternalRecordSource<String, String> by FakeSource(saved = old) {
                    override suspend fun refresh(
                        key: String,
                        nowMillis: Long,
                    ): ExternalResult<Refreshed<String>> {
                        online = false
                        return ExternalResult.Problem(ExternalProblem.Unreachable)
                    }
                }

            val views = ExternalLoader(source, { online }, { now }).load("SYN-1").toList()

            assertEquals(ExternalView.SavedCopy(old, ExternalProblem.Offline), views.last())
        }

    @Test
    fun noAnswerWhileOnlineIsReportedAsUnreachable() =
        runTest {
            val source = FakeSource(saved = old, answer = ExternalResult.Problem(ExternalProblem.Unreachable))

            val views = loader(source).load("SYN-1").toList()

            assertEquals(ExternalView.SavedCopy(old, ExternalProblem.Unreachable), views.last())
        }

    @Test
    fun deviceStorageFailuresDoNotHideTheRecord() =
        runTest {
            val source = FakeSource(failReads = true, failSaves = true)

            val views = loader(source).load("SYN-1").toList()

            assertEquals(ExternalView.Loading, views.first())
            val last = views.last()
            assertTrue(last is ExternalView.Fresh && !last.storedOnDevice)
        }

    @Test
    fun anUnexpectedErrorInTheSourceKeepsTheSavedCopy() =
        runTest {
            val source =
                object : ExternalRecordSource<String, String> by FakeSource(saved = old) {
                    override suspend fun refresh(
                        key: String,
                        nowMillis: Long,
                    ): ExternalResult<Refreshed<String>> = throw IllegalStateException("synthetic bug")
                }

            val views = ExternalLoader(source, { online }, { now }).load("SYN-1").toList()

            assertEquals(ExternalView.SavedCopy(old, ExternalProblem.Failed("client-error")), views.last())
        }

    @Test
    fun aRequestBudgetLetsTheLimitThroughPerWindowThenSaysWhenTheNextMayGo() {
        val budget = RequestBudget(limit = 2, windowMillis = 60_000)

        assertEquals(null, budget.take(1_000))
        assertEquals(null, budget.take(2_000))
        assertEquals(61_000, budget.take(3_000), "the third waits until the first leaves the window")
        assertEquals(61_000, budget.take(60_999))
        assertEquals(null, budget.take(61_000))
        assertEquals(62_000, budget.take(61_500))
    }

    /** A source whose answer waits until the test lets it go, to hold a request on its way. */
    private class HeldSource : ExternalRecordSource<String, String> {
        var refreshCalls = 0
        var gate = CompletableDeferred<Unit>()

        override suspend fun isConnected(): Boolean = true

        override suspend fun readSaved(key: String): Saved<String>? = null

        override suspend fun refresh(
            key: String,
            nowMillis: Long,
        ): ExternalResult<Refreshed<String>> {
            val call = ++refreshCalls
            gate.await()
            return ExternalResult.Ok(Refreshed(Saved("synthetic fresh $call", nowMillis), storedOnDevice = true))
        }

        override suspend fun forgetSaved(key: String) = Unit
    }

    @Test
    fun twoLoadsOfOneRecordAtOnceShareOneRequest() =
        runTest {
            val source = HeldSource()
            val loader = ExternalLoader(source, { online }, { now })

            // A detail view and a row show the same record while its answer is on its way (D67).
            val detail = async { loader.load("SYN-1").toList() }
            val row = async { loader.load("SYN-1").toList() }
            val other = async { loader.load("SYN-2").toList() }
            runCurrent()
            source.gate.complete(Unit)

            val fresh = ExternalView.Fresh(Saved("synthetic fresh 1", 1_000L), true)
            assertEquals(fresh, detail.await().last())
            assertEquals(fresh, row.await().last(), "the row got the same answer")
            assertEquals(ExternalView.Fresh(Saved("synthetic fresh 2", 1_000L), true), other.await().last())
            assertEquals(2, source.refreshCalls, "one request per record")
        }

    @Test
    fun whenTheLoadThatAskedStopsAWaitingLoadAsksItself() =
        runTest {
            val source = HeldSource()
            val loader = ExternalLoader(source, { online }, { now })

            val first = async { loader.load("SYN-1").toList() }
            runCurrent()
            val second = async { loader.load("SYN-1").toList() }
            runCurrent()
            first.cancel()
            runCurrent()
            source.gate.complete(Unit)

            assertEquals(ExternalView.Fresh(Saved("synthetic fresh 2", 1_000L), true), second.await().last())
            assertEquals(2, source.refreshCalls)
        }

    @Test
    fun aLoadAfterTheAnswerAsksAgain() =
        runTest {
            val source = HeldSource()
            source.gate.complete(Unit)
            val loader = ExternalLoader(source, { online }, { now })

            loader.load("SYN-1").toList()
            loader.load("SYN-1").toList()

            assertEquals(2, source.refreshCalls, "sharing covers only a request on its way")
        }
}
