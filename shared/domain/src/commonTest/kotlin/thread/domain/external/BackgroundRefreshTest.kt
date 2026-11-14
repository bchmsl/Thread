package thread.domain.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundRefreshTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val ready =
        BackgroundRefresh.Conditions(
            signedIn = true,
            fixtures = false,
            connected = true,
            online = true,
            visible = true,
            accessExpired = false,
            blockedUntilMillis = null,
        )

    @Test
    fun aListNeverAskedInThisPageLoadIsDueAtOnce() {
        assertTrue(BackgroundRefresh.isDue(ready, lastAskedMillis = null, failures = 0, nowMillis = now))
    }

    @Test
    fun aListIsDueAgainAfterTheInterval() {
        assertFalse(BackgroundRefresh.isDue(ready, now - 4 * minute, 0, now))
        assertTrue(BackgroundRefresh.isDue(ready, now - 5 * minute, 0, now))
    }

    @Test
    fun nothingGoesOutSignedOutInFixturesUnconnectedOfflineHiddenOrAfterA401() {
        val blocked =
            listOf(
                ready.copy(signedIn = false),
                ready.copy(fixtures = true),
                ready.copy(connected = false),
                ready.copy(online = false),
                ready.copy(visible = false),
                ready.copy(accessExpired = true),
            )
        blocked.forEach { assertFalse(BackgroundRefresh.isDue(it, null, 0, now), it.toString()) }
    }

    @Test
    fun nothingGoesOutInsideARateLimit() {
        assertFalse(BackgroundRefresh.isDue(ready.copy(blockedUntilMillis = now + 1), null, 0, now))
        assertTrue(BackgroundRefresh.isDue(ready.copy(blockedUntilMillis = now), null, 0, now))
    }

    @Test
    fun failuresInARowDoubleTheWaitUpToThirtyMinutes() {
        assertEquals(5 * minute, BackgroundRefresh.waitMillis(0))
        assertEquals(10 * minute, BackgroundRefresh.waitMillis(1))
        assertEquals(20 * minute, BackgroundRefresh.waitMillis(2))
        assertEquals(30 * minute, BackgroundRefresh.waitMillis(3))
        assertEquals(30 * minute, BackgroundRefresh.waitMillis(50))
        assertFalse(BackgroundRefresh.isDue(ready, now - 9 * minute, failures = 1, nowMillis = now))
        assertTrue(BackgroundRefresh.isDue(ready, now - 10 * minute, failures = 1, nowMillis = now))
    }

    @Test
    fun onlyAnsweredErrorsCountAsFailures() {
        assertFalse(BackgroundRefresh.isFailure(null))
        assertFalse(BackgroundRefresh.isFailure(ExternalProblem.Offline))
        assertFalse(BackgroundRefresh.isFailure(ExternalProblem.RateLimited(now)))
        assertFalse(BackgroundRefresh.isFailure(ExternalProblem.Unauthorized), "a 401 stops refreshes instead")
        assertTrue(BackgroundRefresh.isFailure(ExternalProblem.Failed("http-500")))
        assertTrue(BackgroundRefresh.isFailure(ExternalProblem.Unreachable))
        assertTrue(BackgroundRefresh.isFailure(ExternalProblem.Forbidden))
    }
}
