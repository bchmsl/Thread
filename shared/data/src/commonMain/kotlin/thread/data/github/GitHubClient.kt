package thread.data.github

import kotlinx.serialization.json.JsonElement
import thread.data.http.HttpRequest
import thread.data.http.HttpResponse
import thread.data.http.HttpTransport
import thread.data.http.getJson
import thread.data.http.urlEncode
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalResult
import thread.domain.external.GitHubConnection
import thread.domain.external.PullRequestList
import thread.domain.external.PullRequestRef

/**
 * Read-only GitHub REST calls with a fine-grained personal access token (adr/ADR-007).
 *
 * Requests carry only `Accept` and `Authorization`. No `X-GitHub-Api-Version` header: GitHub then uses its default
 * version (2022-11-28), and the CORS preflight stays as small as possible.
 */
internal class GitHubClient(
    private val transport: HttpTransport,
) {
    suspend fun search(
        connection: GitHubConnection,
        list: PullRequestList,
        nowMillis: Long,
    ): ExternalResult<JsonElement> {
        val (query, label) =
            when (list) {
                PullRequestList.Mine -> "is:pr is:open author:@me archived:false" to "author:@me"
                PullRequestList.ReviewRequested -> "is:pr is:open review-requested:@me archived:false" to "review-requested:@me"
            }
        val url = "$API/search/issues?q=${urlEncode(query)}&sort=updated&order=desc&per_page=$LIST_PAGE"
        return get(connection, url, "GET /search/issues ($label)", nowMillis)
    }

    suspend fun pull(
        connection: GitHubConnection,
        ref: PullRequestRef,
        nowMillis: Long,
    ): ExternalResult<JsonElement> =
        get(connection, "${repo(ref)}/pulls/${ref.number}", "GET /repos/{owner}/{repo}/pulls/{number}", nowMillis)

    suspend fun reviews(
        connection: GitHubConnection,
        ref: PullRequestRef,
        nowMillis: Long,
    ): ExternalResult<JsonElement> =
        get(
            connection,
            "${repo(ref)}/pulls/${ref.number}/reviews?per_page=100",
            "GET /repos/{owner}/{repo}/pulls/{number}/reviews",
            nowMillis,
        )

    suspend fun combinedStatus(
        connection: GitHubConnection,
        ref: PullRequestRef,
        sha: String,
        nowMillis: Long,
    ): ExternalResult<JsonElement> =
        get(connection, "${repo(ref)}/commits/$sha/status?per_page=100", "GET /repos/{owner}/{repo}/commits/{sha}/status", nowMillis)

    suspend fun checkRuns(
        connection: GitHubConnection,
        ref: PullRequestRef,
        sha: String,
        nowMillis: Long,
    ): ExternalResult<JsonElement> =
        get(
            connection,
            "${repo(ref)}/commits/$sha/check-runs?per_page=100",
            "GET /repos/{owner}/{repo}/commits/{sha}/check-runs",
            nowMillis,
        )

    private suspend fun get(
        connection: GitHubConnection,
        url: String,
        endpoint: String,
        nowMillis: Long,
    ): ExternalResult<JsonElement> {
        val headers = mapOf("Accept" to "application/vnd.github+json", "Authorization" to "Bearer ${connection.token.reveal()}")
        return transport.getJson(HttpRequest(url, headers, endpoint)) { problemFor(it, nowMillis) }
    }

    /** [PullRequestRef] allows only `A-Z a-z 0-9 _ . -` in names, so they are safe in a path as they are. */
    private fun repo(ref: PullRequestRef): String = "$API/repos/${ref.owner}/${ref.repo}"

    companion object {
        private const val API = "https://api.github.com"
        const val LIST_PAGE: Int = 50
        private const val DEFAULT_WAIT_MILLIS = 60_000L

        /**
         * Maps a failed answer. GitHub reports rate limits as 403 or 429: `x-ratelimit-remaining: 0` waits until
         * `x-ratelimit-reset`; `retry-after` waits that long; a secondary limit without headers waits one minute.
         */
        fun problemFor(
            response: HttpResponse,
            nowMillis: Long,
        ): ExternalProblem {
            val status = response.status
            if (status == 403 || status == 429) {
                val reset = response.headers["x-ratelimit-reset"]?.trim()?.toLongOrNull()
                if (response.headers["x-ratelimit-remaining"]?.trim() == "0" && reset != null) {
                    return ExternalProblem.RateLimited(maxOf(reset * 1_000, nowMillis + 1_000))
                }
                val seconds = response.headers["retry-after"]?.trim()?.toLongOrNull()
                if (seconds != null) return ExternalProblem.RateLimited(nowMillis + seconds.coerceAtLeast(1) * 1_000)
                if (status == 429 || response.body.contains("rate limit", ignoreCase = true)) {
                    return ExternalProblem.RateLimited(nowMillis + DEFAULT_WAIT_MILLIS)
                }
            }
            return when (status) {
                401 -> ExternalProblem.Unauthorized
                403 -> ExternalProblem.Forbidden
                404 -> ExternalProblem.NotFound
                else -> ExternalProblem.Failed("http-$status")
            }
        }
    }
}
