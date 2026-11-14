package thread.domain.external

/**
 * When Thread refreshes the Jira and pull request lists by itself (D51). "Background" means while Thread is open in a
 * tab: there is no service worker or server job, so nothing refreshes while the app is closed.
 *
 * A refresh runs when the app opens, then every [INTERVAL_MILLIS] while it is signed in, connected, online and
 * visible, and as soon as it becomes visible or comes back online with a list older than that. It never runs with the
 * SYNTHETIC fixtures, after a 401 until the connection changes, or inside a rate limit. Failed refreshes make it wait
 * longer: the interval doubles per failure in a row, up to [MAX_WAIT_MILLIS].
 */
object BackgroundRefresh {
    const val INTERVAL_MILLIS: Long = 5 * 60 * 1_000L
    const val MAX_WAIT_MILLIS: Long = 30 * 60 * 1_000L

    /** What the app knows about one provider at a moment. */
    data class Conditions(
        val signedIn: Boolean,
        val fixtures: Boolean,
        val connected: Boolean,
        val online: Boolean,
        val visible: Boolean,
        /** The provider answered 401; nothing goes out on its own until the person connects again (no retry loop). */
        val accessExpired: Boolean,
        /** The end of the provider's rate limit, or null when it does not limit Thread now. */
        val blockedUntilMillis: Long?,
    )

    /** Whether a background request may go out now at all. */
    fun allowed(
        conditions: Conditions,
        nowMillis: Long,
    ): Boolean =
        with(conditions) {
            signedIn &&
                !fixtures &&
                connected &&
                online &&
                visible &&
                !accessExpired &&
                (blockedUntilMillis == null || blockedUntilMillis <= nowMillis)
        }

    /** How long to wait after a request when the last [failures] refreshes in a row failed. */
    fun waitMillis(
        failures: Int,
        intervalMillis: Long = INTERVAL_MILLIS,
    ): Long {
        var wait = intervalMillis
        repeat(failures.coerceIn(0, MAX_DOUBLINGS)) { wait *= 2 }
        return minOf(wait, maxOf(MAX_WAIT_MILLIS, intervalMillis))
    }

    /**
     * Whether a list is due: allowed now, and never asked in this page load ([lastAskedMillis] null) or asked at
     * least [waitMillis] ago.
     */
    fun isDue(
        conditions: Conditions,
        lastAskedMillis: Long?,
        failures: Int,
        nowMillis: Long,
        intervalMillis: Long = INTERVAL_MILLIS,
    ): Boolean =
        allowed(conditions, nowMillis) &&
            (lastAskedMillis == null || nowMillis - lastAskedMillis >= waitMillis(failures, intervalMillis))

    /**
     * Whether a refresh that ended with [problem] counts as a failure for the wait. Offline and rate limits send
     * nothing and have their own rule; a 401 stops refreshes through [Conditions.accessExpired].
     */
    fun isFailure(problem: ExternalProblem?): Boolean =
        when (problem) {
            null, ExternalProblem.Offline, ExternalProblem.Unauthorized, ExternalProblem.NotConnected,
            is ExternalProblem.RateLimited,
            -> false
            ExternalProblem.Forbidden, ExternalProblem.NotFound, ExternalProblem.Unreachable, is ExternalProblem.Failed -> true
        }

    private const val MAX_DOUBLINGS = 3
}
