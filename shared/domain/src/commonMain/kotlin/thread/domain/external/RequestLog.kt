package thread.domain.external

import kotlinx.coroutines.flow.StateFlow

/**
 * Metadata of one request to Jira or GitHub, for the M1 request log (adr/M1-RESULTS.md, J1 and G3).
 *
 * It never holds a URL, a token, a title, a text or an id from the provider: [endpoint] is a template such as
 * `GET /rest/api/3/issue/{key}`. [readableHeaders] lists which of the rate-limit and permission headers the browser
 * let the page read; [headerValues] has the values of the few of them that are plain counters or permission names.
 * [status] is null when no answer reached the page.
 */
data class RequestLogEntry(
    val atMillis: Long,
    val provider: String,
    val endpoint: String,
    val status: Int?,
    val durationMillis: Long,
    val onlineAtStart: Boolean,
    val readableHeaders: List<String>,
    val headerValues: List<Pair<String, String>>,
)

interface RequestLog {
    val entries: StateFlow<List<RequestLogEntry>>

    fun clear()
}
