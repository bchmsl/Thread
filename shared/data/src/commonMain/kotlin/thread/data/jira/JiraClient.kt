package thread.data.jira

import kotlinx.serialization.json.JsonElement
import thread.data.external.ExternalTime
import thread.data.external.asObject
import thread.data.external.str
import thread.data.http.BAD_RESPONSE
import thread.data.http.HttpRequest
import thread.data.http.HttpResponse
import thread.data.http.HttpTransport
import thread.data.http.getJson
import thread.data.http.urlEncode
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalResult
import thread.domain.external.JiraCloudId
import thread.domain.external.JiraConnection
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraSite
import kotlin.io.encoding.Base64

/**
 * Read-only Jira Cloud REST v3 calls with a scoped API token (adr/ADR-006).
 *
 * Scoped tokens work only through `api.atlassian.com/ex/jira/{cloudId}`, with basic auth (account e-mail and
 * token). Requests carry only `Accept` and `Authorization`. The token is revealed only into that header.
 */
internal class JiraClient(
    private val transport: HttpTransport,
) {
    suspend fun fields(
        connection: JiraConnection,
        nowMillis: Long,
    ): ExternalResult<JsonElement> = get(connection, "${api(connection)}/field", "GET /rest/api/3/field", nowMillis)

    suspend fun issue(
        connection: JiraConnection,
        key: JiraIssueKey,
        sprintFieldId: String?,
        nowMillis: Long,
    ): ExternalResult<JsonElement> {
        val fields = listOfNotNull(JiraJson.ISSUE_FIELDS, sprintFieldId).joinToString(",")
        val url = "${api(connection)}/issue/${urlEncode(key.value)}?fields=${urlEncode(fields)}"
        return get(connection, url, "GET /rest/api/3/issue/{key}", nowMillis)
    }

    /** The newest [COMMENT_PAGE] comments, newest first, with the total count. */
    suspend fun comments(
        connection: JiraConnection,
        key: JiraIssueKey,
        nowMillis: Long,
    ): ExternalResult<JsonElement> {
        val url = "${api(connection)}/issue/${urlEncode(key.value)}/comment?orderBy=-created&maxResults=$COMMENT_PAGE"
        return get(connection, url, "GET /rest/api/3/issue/{key}/comment", nowMillis)
    }

    /** The site's cloud id from its public tenant info, without a token. The browser may block it (CORS). */
    suspend fun cloudId(
        site: JiraSite,
        nowMillis: Long,
    ): ExternalResult<JiraCloudId> {
        val request = HttpRequest("https://${site.host}/_edge/tenant_info", mapOf("Accept" to "application/json"), "GET /_edge/tenant_info")
        return when (val result = transport.getJson(request) { problemFor(it, nowMillis) }) {
            is ExternalResult.Problem -> result
            is ExternalResult.Ok ->
                result.value
                    .asObject()
                    ?.str("cloudId")
                    ?.let(JiraCloudId::parse)
                    ?.let { ExternalResult.Ok(it) }
                    ?: ExternalResult.Problem(BAD_RESPONSE)
        }
    }

    private suspend fun get(
        connection: JiraConnection,
        url: String,
        endpoint: String,
        nowMillis: Long,
    ): ExternalResult<JsonElement> {
        val headers = mapOf("Accept" to "application/json", "Authorization" to basicAuth(connection))
        return transport.getJson(HttpRequest(url, headers, endpoint)) { problemFor(it, nowMillis) }
    }

    private fun api(connection: JiraConnection): String = "https://api.atlassian.com/ex/jira/${connection.cloudId.value}/rest/api/3"

    private fun basicAuth(connection: JiraConnection): String {
        val credentials = "${connection.email}:${connection.token.reveal()}"
        return "Basic ${Base64.encode(credentials.encodeToByteArray())}"
    }

    companion object {
        const val COMMENT_PAGE: Int = 100
        private const val DEFAULT_WAIT_MILLIS = 60_000L
        private const val MAX_WAIT_SECONDS = 3_600L

        /** Maps a failed answer. 429 waits for `Retry-After`, else `X-RateLimit-Reset`, else one minute. */
        fun problemFor(
            response: HttpResponse,
            nowMillis: Long,
        ): ExternalProblem =
            when (response.status) {
                401 -> ExternalProblem.Unauthorized
                403 -> ExternalProblem.Forbidden
                404 -> ExternalProblem.NotFound
                429 -> ExternalProblem.RateLimited(retryAt(response, nowMillis))
                else -> ExternalProblem.Failed("http-${response.status}")
            }

        private fun retryAt(
            response: HttpResponse,
            nowMillis: Long,
        ): Long {
            val seconds = response.headers["retry-after"]?.trim()?.toLongOrNull()
            if (seconds != null) return nowMillis + seconds.coerceIn(1, MAX_WAIT_SECONDS) * 1_000
            val reset = ExternalTime.parseMillis(response.headers["x-ratelimit-reset"])
            if (reset != null && reset > nowMillis) return reset
            return nowMillis + DEFAULT_WAIT_MILLIS
        }
    }
}
