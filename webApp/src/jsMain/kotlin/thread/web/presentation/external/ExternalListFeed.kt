package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import thread.domain.external.BackgroundRefresh
import thread.domain.external.ExternalLoader
import thread.domain.external.ExternalView

/** A page that opens a list asks again only when its last request is older than this. */
internal const val OPEN_REFRESH_AFTER_MILLIS = 60_000L

/**
 * The lists of one provider, shared by its pages and the background refresh (D51): the view each list shows, at most
 * one load per list at a time, and when each list last sent a request. Loads run in [scope], which lives as long as
 * the signed-in workspace, so leaving a page does not cancel a refresh on its way. [onView] sees every view, from a
 * page or from the background.
 */
internal class ExternalListFeed<K : Any, T>(
    private val loader: ExternalLoader<K, T>,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val onView: (K, ExternalView<T>) -> Unit,
) {
    private var views by mutableStateOf<Map<K, ExternalView<T>>>(emptyMap())
    private val jobs = mutableMapOf<K, Job>()
    private val asked = mutableMapOf<K, Long>()
    private val failures = mutableMapOf<K, Int>()

    /** What [key] shows now, or null before its first load. */
    fun view(key: K): ExternalView<T>? = views[key]

    /** When [key] last sent a request in this page load, or null. Offline and rate-limited loads send none. */
    fun lastAskedMillis(key: K): Long? = asked[key]

    /** Refreshes of [key] in a row that failed (BackgroundRefresh.isFailure). */
    fun failures(key: K): Int = failures[key] ?: 0

    /** A page opened [key]: load it, unless a load is on its way or the last request is less than a minute old. */
    fun open(key: K) {
        val last = asked[key]
        if (views[key] != null && last != null && nowMillis() - last < OPEN_REFRESH_AFTER_MILLIS) return
        load(key)
    }

    /** Loads [key]: the saved copy first, then the provider's answer. Nothing new starts while a load is on its way. */
    fun load(key: K) {
        if (jobs[key]?.isActive == true) return
        jobs[key] =
            scope.launch {
                var last: ExternalView<T>? = null
                var sent = false
                loader.load(key).collect { view ->
                    if (view == ExternalView.Loading || view is ExternalView.Refreshing) {
                        sent = true
                        asked[key] = nowMillis()
                    }
                    last = view
                    views = views + (key to view)
                    onView(key, view)
                }
                if (sent) {
                    val problem =
                        when (val end = last) {
                            is ExternalView.SavedCopy -> end.problem
                            is ExternalView.Missing -> end.problem
                            else -> null
                        }
                    failures[key] = if (BackgroundRefresh.isFailure(problem)) failures(key) + 1 else 0
                }
            }
    }

    /** Forgets every view and request, after the connection changed. */
    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        asked.clear()
        failures.clear()
        views = emptyMap()
    }
}

/**
 * The loads a page asks [feed] for while it shows [key]: on open, on a connection change ([version]) and on the
 * page's five-minute tick ([auto]), each skipped when the list asked less than a minute ago; and always when
 * [refresh] changes (the Refresh or Retry button).
 */
@Composable
internal fun <K : Any, T> rememberListLoads(
    feed: ExternalListFeed<K, T>,
    key: K,
    refresh: Int,
    auto: Int,
    version: Int,
) {
    val opened = remember(feed, key) { refresh }
    LaunchedEffect(feed, key, version, auto) { feed.open(key) }
    LaunchedEffect(feed, key, refresh) { if (refresh != opened) feed.load(key) }
}
