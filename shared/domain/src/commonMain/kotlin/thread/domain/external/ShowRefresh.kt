package thread.domain.external

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Refresh on show (D67, owner change #15): a screen that shows Jira tickets or pull requests with their status shows
 * the copies on this device at once, and the latest answers replace them in place when they land. This decides which
 * of the shown items to ask the provider about, and when:
 *
 * - Nothing goes out while [BackgroundRefresh.allowed] says no for [conditions]: signed out, the SYNTHETIC fixtures,
 *   not connected, offline, a hidden page, after a 401, or inside a rate limit. The conditions are read again before
 *   each request, so a 401 or a rate limit in the middle stops the rest.
 * - An item asked less than [skipMillis] ago is not asked again, also when another view asked ([noteAsked]).
 * - An item already waiting is not asked twice: a second screen that shows it gets the same answer through the
 *   shared state, without a request of its own.
 * - Every screen's items wait in one queue per provider and go to [fetch] in chunks of up to [chunkSize], one chunk
 *   after another, so rows that show together share requests and never burst.
 * - [budget] limits the chunks a minute. Items it leaves out keep their copy; [keepShowing] asks about them when the
 *   budget allows again, as long as the screen still shows them.
 *
 * Requests run in [scope], which lives as long as the signed-in workspace, so leaving a screen does not cancel an
 * answer on its way. [fetch] applies the answer to the shared state itself; this only plans the requests.
 */
class ShowRefresh<K>(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val chunkSize: Int,
    private val budget: RequestBudget,
    private val conditions: () -> BackgroundRefresh.Conditions,
    private val skipMillis: Long = SKIP_MILLIS,
    private val fetch: suspend (List<K>) -> Unit,
) {
    private val askedAt = mutableMapOf<K, Long>()
    private val queue = LinkedHashSet<K>()
    private val asking = mutableSetOf<K>()
    private var worker: Job? = null

    /** When the budget allows the next chunk, after the last work stopped on it; null when it did not. */
    private var budgetUntil: Long? = null

    init {
        require(chunkSize > 0) { "A chunk holds at least one item" }
    }

    /**
     * [keys] are on screen now. Queues those that are due and returns the work that asks about them, or null when
     * nothing is due or nothing may go out now.
     */
    fun show(keys: Collection<K>): Job? {
        val now = nowMillis()
        if (!BackgroundRefresh.allowed(conditions(), now)) return null
        val due = keys.filter { it !in queue && it !in asking && isDue(it, now) }
        if (due.isEmpty()) return worker
        queue += due
        return worker ?: scope.launch { work() }.also { job ->
            worker = job
            job.invokeOnCompletion { if (worker === job) worker = null }
        }
    }

    /**
     * Shows [keys] for as long as the caller runs (a screen's effect): asks about the due ones, and when the budget
     * ran out first, waits for it and asks about the rest. Each item goes at most once per call. Returns when every
     * item was asked or is fresh, or when nothing may go out.
     */
    suspend fun keepShowing(keys: Collection<K>) {
        val since = nowMillis()
        var waiting = keys.toList()
        while (true) {
            val job = show(waiting) ?: return
            job.join()
            val until = budgetUntil ?: return
            val now = nowMillis()
            waiting = waiting.filter { key -> askedAt[key].let { it == null || it < since } && isDue(key, now) }
            if (waiting.isEmpty()) return
            delay((until - now).coerceAtLeast(0))
        }
    }

    private suspend fun work() {
        budgetUntil = null
        try {
            while (queue.isNotEmpty()) {
                val at = nowMillis()
                if (!BackgroundRefresh.allowed(conditions(), at)) break
                val next = budget.take(at)
                if (next != null) {
                    budgetUntil = next
                    break
                }
                val chunk = queue.take(chunkSize)
                queue -= chunk.toSet()
                asking += chunk
                chunk.forEach { askedAt[it] = at }
                try {
                    fetch(chunk)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // The copies on screen stay; the next show asks again after the skip window.
                } finally {
                    asking -= chunk.toSet()
                }
            }
        } finally {
            // What could not go now goes at the next show.
            queue.clear()
        }
    }

    /** Another view asked about [key] at [atMillis] (for example its detail opened), so a row need not ask again. */
    fun noteAsked(
        key: K,
        atMillis: Long,
    ) {
        askedAt[key] = maxOf(askedAt[key] ?: atMillis, atMillis)
    }

    /** When [key] last went to the provider in this page load, or null. */
    fun lastAskedMillis(key: K): Long? = askedAt[key]

    /** Forgets every item and stops the work on its way, after the connection changed. */
    fun clear() {
        worker?.cancel()
        worker = null
        budgetUntil = null
        queue.clear()
        asking.clear()
        askedAt.clear()
    }

    private fun isDue(
        key: K,
        now: Long,
    ): Boolean = askedAt[key]?.let { now - it < skipMillis } != true

    companion object {
        /** An item asked less than a minute ago is not asked again on show, like a list opened again (D51). */
        const val SKIP_MILLIS: Long = 60_000L
    }
}
