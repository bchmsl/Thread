package thread.data.http

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import thread.domain.external.RequestLog
import thread.domain.external.RequestLogEntry

/**
 * Wraps a transport and writes the metadata of every request to [log], for the M1 probes (adr/M1-RESULTS.md, J1
 * and G3). It records the endpoint template, the status, the time, and which rate-limit and permission headers the
 * browser let the app read. It never records a URL, a header value that could identify a person or a record, or a
 * body.
 */
class RecordingTransport(
    private val provider: String,
    private val inner: HttpTransport,
    private val log: InMemoryRequestLog,
    private val nowMillis: () -> Long,
    private val isOnline: () -> Boolean,
) : HttpTransport {
    override suspend fun get(request: HttpRequest): HttpResponse {
        val start = nowMillis()
        val online = isOnline()
        val response =
            try {
                inner.get(request)
            } catch (e: HttpNoResponseException) {
                log.add(RequestLogEntry(start, provider, request.endpoint, null, nowMillis() - start, online, emptyList(), emptyList()))
                throw e
            }
        val readable = INTERESTING_HEADERS.filter { it in response.headers }
        val values = VALUE_HEADERS.mapNotNull { name -> response.headers[name]?.let { name to it.take(MAX_VALUE_LENGTH) } }
        log.add(RequestLogEntry(start, provider, request.endpoint, response.status, nowMillis() - start, online, readable, values))
        return response
    }

    companion object {
        /** Headers whose presence matters for rate limits, paging and permissions (ADR-006, ADR-007). */
        val INTERESTING_HEADERS: List<String> =
            listOf(
                "retry-after",
                "x-ratelimit-limit",
                "x-ratelimit-remaining",
                "x-ratelimit-reset",
                "x-ratelimit-used",
                "x-ratelimit-resource",
                "x-ratelimit-nearlimit",
                "ratelimit-reason",
                "beta-retry-after",
                "link",
                "etag",
                "x-accepted-github-permissions",
                "x-oauth-scopes",
                "x-github-sso",
            )

        /** The headers whose values are plain counters, times or permission names, so the log may keep them. */
        val VALUE_HEADERS: List<String> =
            listOf(
                "retry-after",
                "x-ratelimit-limit",
                "x-ratelimit-remaining",
                "x-ratelimit-reset",
                "x-ratelimit-used",
                "x-ratelimit-resource",
                "x-ratelimit-nearlimit",
                "ratelimit-reason",
                "beta-retry-after",
                "x-accepted-github-permissions",
            )

        private const val MAX_VALUE_LENGTH = 200
    }
}

/** The request log of this page load. Kept in memory only; it is gone after a reload. */
class InMemoryRequestLog(
    private val limit: Int = 200,
) : RequestLog {
    private val state = MutableStateFlow<List<RequestLogEntry>>(emptyList())

    override val entries: StateFlow<List<RequestLogEntry>> = state.asStateFlow()

    fun add(entry: RequestLogEntry) {
        state.update { (it + entry).takeLast(limit) }
    }

    override fun clear() {
        state.value = emptyList()
    }
}
