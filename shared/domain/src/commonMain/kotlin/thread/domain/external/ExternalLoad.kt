package thread.domain.external

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A copy of an external record saved on this device, and when it was fetched. */
data class Saved<out T>(
    val value: T,
    val savedAtMillis: Long,
)

/** Why a Jira or GitHub record could not be refreshed (adr/ADR-006, ADR-007). */
sealed interface ExternalProblem {
    /** This device is offline. */
    data object Offline : ExternalProblem

    /** No connection is set up, or it was cleared. Saved copies stay hidden until it is set up again. */
    data object NotConnected : ExternalProblem

    /** HTTP 401: the token expired, was revoked or is wrong. */
    data object Unauthorized : ExternalProblem

    /** HTTP 403: the token may not read this. */
    data object Forbidden : ExternalProblem

    /** HTTP 404: it does not exist, or it is not shared with this account. */
    data object NotFound : ExternalProblem

    /** The provider limits requests. No request is sent before [retryAtMillis]. */
    data class RateLimited(
        val retryAtMillis: Long,
    ) : ExternalProblem

    /** No answer while online: the browser blocked the request (for example CORS) or the network failed. */
    data object Unreachable : ExternalProblem

    /** Any other answer. [code] is short and never holds content or secrets, for example `http-500`. */
    data class Failed(
        val code: String,
    ) : ExternalProblem
}

sealed interface ExternalResult<out T> {
    data class Ok<out T>(
        val value: T,
    ) : ExternalResult<T>

    data class Problem(
        val problem: ExternalProblem,
    ) : ExternalResult<Nothing>
}

/** A refreshed record. [storedOnDevice] is false when saving the copy on this device failed. */
data class Refreshed<out T>(
    val saved: Saved<T>,
    val storedOnDevice: Boolean,
)

/**
 * One kind of external record: the provider's read-only API plus the copies saved on this device. Data code
 * implements it. The copies stay on this device; they are never synced (D08, D09).
 */
interface ExternalRecordSource<K, T> {
    suspend fun isConnected(): Boolean

    /** The copy saved on this device, or null. */
    suspend fun readSaved(key: K): Saved<T>?

    /** Asks the provider and, on success, saves the answer on this device as fetched at [nowMillis]. */
    suspend fun refresh(
        key: K,
        nowMillis: Long,
    ): ExternalResult<Refreshed<T>>

    suspend fun forgetSaved(key: K)
}

/** What a screen shows for one external record (the v3 external states). */
sealed interface ExternalView<out T> {
    /** Nothing saved yet, and the first answer is on its way. */
    data object Loading : ExternalView<Nothing>

    /** Just refreshed. */
    data class Fresh<out T>(
        val saved: Saved<T>,
        val storedOnDevice: Boolean,
    ) : ExternalView<T>

    /** The saved copy, while a refresh is on its way. */
    data class Refreshing<out T>(
        val saved: Saved<T>,
    ) : ExternalView<T>

    /** The saved copy, because the refresh failed or could not start. */
    data class SavedCopy<out T>(
        val saved: Saved<T>,
        val problem: ExternalProblem,
    ) : ExternalView<T>

    /** Nothing to show. */
    data class Missing(
        val problem: ExternalProblem,
    ) : ExternalView<Nothing>
}

/** Remembers a provider's rate limit, so no request is sent before it ends. One gate per limit. */
class RateLimitGate {
    private var blockedUntilMillis: Long = 0

    fun blockedUntil(nowMillis: Long): Long? = blockedUntilMillis.takeIf { it > nowMillis }

    fun block(untilMillis: Long) {
        blockedUntilMillis = maxOf(blockedUntilMillis, untilMillis)
    }
}

/**
 * Lets at most [limit] requests go in any [windowMillis], so Thread stays inside a provider's documented limit before
 * the provider has to refuse anything (GitHub search: 30 a minute, adr/ADR-014-github-in-the-workspace.md).
 */
class RequestBudget(
    private val limit: Int,
    private val windowMillis: Long,
) {
    private val sent = ArrayDeque<Long>()

    init {
        require(limit > 0 && windowMillis > 0) { "A budget needs a positive limit and window" }
    }

    /** Counts a request at [nowMillis] and returns null, or returns when the next request may go if none is left. */
    fun take(nowMillis: Long): Long? {
        while (sent.isNotEmpty() && sent.first() <= nowMillis - windowMillis) sent.removeFirst()
        if (sent.size >= limit) return sent.first() + windowMillis
        sent.addLast(nowMillis)
        return null
    }
}

/**
 * Cached first, then revalidate (adr/ADR-006, ADR-007): shows the copy saved on this device at once, then asks the
 * provider and shows the answer.
 *
 * - Not connected: nothing is shown, also not a saved copy (v3: saved copies hide until Jira is connected again).
 * - Offline, or inside a rate limit: the saved copy with the reason, and no request.
 * - Not found: the saved copy is deleted, because the record is gone or no longer shared.
 * - Any other problem: the saved copy with the reason.
 *
 * Device storage errors never stop the record from showing: a copy that cannot be read counts as no copy, and a
 * copy that cannot be saved is shown with `storedOnDevice = false`.
 */
class ExternalLoader<K, T>(
    private val source: ExternalRecordSource<K, T>,
    private val isOnline: () -> Boolean,
    private val nowMillis: () -> Long,
    private val rateLimit: RateLimitGate = RateLimitGate(),
) {
    fun load(key: K): Flow<ExternalView<T>> =
        flow {
            if (!guard(false) { source.isConnected() }) {
                emit(ExternalView.Missing(ExternalProblem.NotConnected))
                return@flow
            }
            val saved = guard(null) { source.readSaved(key) }
            val blockedUntil = rateLimit.blockedUntil(nowMillis())
            when {
                !isOnline() -> emit(withProblem(saved, ExternalProblem.Offline))
                blockedUntil != null -> emit(withProblem(saved, ExternalProblem.RateLimited(blockedUntil)))
                else -> {
                    emit(if (saved != null) ExternalView.Refreshing(saved) else ExternalView.Loading)
                    emit(refresh(key, saved))
                }
            }
        }

    private suspend fun refresh(
        key: K,
        saved: Saved<T>?,
    ): ExternalView<T> {
        val result =
            try {
                source.refresh(key, nowMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ExternalResult.Problem(ExternalProblem.Failed("client-error"))
            }
        return when (result) {
            is ExternalResult.Ok -> ExternalView.Fresh(result.value.saved, result.value.storedOnDevice)
            is ExternalResult.Problem -> {
                // A request that got no answer while the device went offline is an offline case, not a blocked one.
                val problem = if (result.problem == ExternalProblem.Unreachable && !isOnline()) ExternalProblem.Offline else result.problem
                when (problem) {
                    is ExternalProblem.RateLimited -> rateLimit.block(problem.retryAtMillis)
                    ExternalProblem.NotFound -> guard(Unit) { source.forgetSaved(key) }
                    else -> Unit
                }
                when (problem) {
                    ExternalProblem.NotFound, ExternalProblem.NotConnected -> ExternalView.Missing(problem)
                    else -> withProblem(saved, problem)
                }
            }
        }
    }

    private fun withProblem(
        saved: Saved<T>?,
        problem: ExternalProblem,
    ): ExternalView<T> = if (saved != null) ExternalView.SavedCopy(saved, problem) else ExternalView.Missing(problem)

    private suspend fun <R> guard(
        fallback: R,
        block: suspend () -> R,
    ): R =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fallback
        }
}
