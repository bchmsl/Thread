package thread.data.github

import kotlinx.serialization.json.JsonElement
import thread.data.external.KeyValueStore
import thread.data.external.SavedRecord
import thread.data.external.succeeded
import thread.data.http.BAD_RESPONSE
import thread.data.http.HttpTransport
import thread.domain.external.ConnectionStore
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalRecordSource
import thread.domain.external.ExternalResult
import thread.domain.external.PullRequestDetail
import thread.domain.external.PullRequestList
import thread.domain.external.PullRequestListPage
import thread.domain.external.PullRequestRef
import thread.domain.external.PullRequestSource
import thread.domain.external.Refreshed
import thread.domain.external.RequestBudget
import thread.domain.external.Saved

private const val SCOPE = "github"

/**
 * The two v3 pull request lists from GitHub search (adr/ADR-007, ADR-014). One search request per refresh, and at most
 * [SEARCH_BUDGET_PER_MINUTE] a minute: GitHub allows 30, shared with everything else the person runs.
 */
class GitHubPullRequestLists(
    private val connections: ConnectionStore,
    transport: HttpTransport,
    private val lists: KeyValueStore,
    private val searchBudget: RequestBudget = RequestBudget(SEARCH_BUDGET_PER_MINUTE, 60_000),
) : ExternalRecordSource<PullRequestList, PullRequestListPage> {
    private val client = GitHubClient(transport)

    override suspend fun isConnected(): Boolean = connections.gitHub() != null

    override suspend fun readSaved(key: PullRequestList): Saved<PullRequestListPage>? {
        val (savedAt, bundle) = SavedRecord.decode(lists.get(storageKey(key)) ?: return null, SCOPE) ?: return null
        return GitHubJson.listPage(bundle)?.let { Saved(it, savedAt) }
    }

    override suspend fun refresh(
        key: PullRequestList,
        nowMillis: Long,
    ): ExternalResult<Refreshed<PullRequestListPage>> {
        val connection = connections.gitHub() ?: return ExternalResult.Problem(ExternalProblem.NotConnected)
        searchBudget.take(nowMillis)?.let { return ExternalResult.Problem(ExternalProblem.RateLimited(it)) }
        val answer =
            when (val result = client.search(connection, key, nowMillis)) {
                is ExternalResult.Ok -> result.value
                is ExternalResult.Problem -> return result
            }
        val bundle = GitHubJson.listBundle(answer) ?: return ExternalResult.Problem(BAD_RESPONSE)
        val page = GitHubJson.listPage(bundle) ?: return ExternalResult.Problem(BAD_RESPONSE)
        val stored = succeeded { lists.put(storageKey(key), SavedRecord.encode(nowMillis, SCOPE, bundle)) }
        return ExternalResult.Ok(Refreshed(Saved(page, nowMillis), stored))
    }

    override suspend fun forgetSaved(key: PullRequestList) {
        lists.delete(storageKey(key))
    }

    private fun storageKey(list: PullRequestList): String =
        when (list) {
            PullRequestList.Mine -> "mine"
            PullRequestList.ReviewRequested -> "review-requested"
        }

    companion object {
        /** Thread's own share of GitHub's 30 search requests a minute. */
        const val SEARCH_BUDGET_PER_MINUTE: Int = 10
    }
}

/**
 * One pull request with its reviews and CI state (adr/ADR-007). A refresh sends four GET requests: the pull
 * request, its reviews, the combined commit status and the check runs of the head commit. When the token may not
 * read statuses or check runs (403 or 404), the detail still loads and says which part is missing.
 */
class GitHubPullRequestDetails(
    private val connections: ConnectionStore,
    transport: HttpTransport,
    private val details: KeyValueStore,
) : PullRequestSource {
    private val client = GitHubClient(transport)

    override suspend fun isConnected(): Boolean = connections.gitHub() != null

    override suspend fun allSaved(): List<Saved<PullRequestDetail>> {
        if (connections.gitHub() == null) return emptyList()
        return details
            .all()
            .mapNotNull { (id, raw) ->
                val ref = PullRequestRef.parse(id) ?: return@mapNotNull null
                val (savedAt, bundle) = SavedRecord.decode(raw, SCOPE) ?: return@mapNotNull null
                GitHubJson.detail(bundle, ref)?.let { Saved(it, savedAt) }
            }.sortedByDescending { it.savedAtMillis }
    }

    override suspend fun readSaved(key: PullRequestRef): Saved<PullRequestDetail>? {
        val (savedAt, bundle) = SavedRecord.decode(details.get(key.id) ?: return null, SCOPE) ?: return null
        return GitHubJson.detail(bundle, key)?.let { Saved(it, savedAt) }
    }

    override suspend fun refresh(
        key: PullRequestRef,
        nowMillis: Long,
    ): ExternalResult<Refreshed<PullRequestDetail>> {
        val connection = connections.gitHub() ?: return ExternalResult.Problem(ExternalProblem.NotConnected)
        val pull =
            when (val result = client.pull(connection, key, nowMillis)) {
                is ExternalResult.Ok -> result.value
                is ExternalResult.Problem -> return result
            }
        val sha = GitHubJson.headSha(pull) ?: return ExternalResult.Problem(BAD_RESPONSE)
        val reviews =
            when (val result = client.reviews(connection, key, nowMillis)) {
                is ExternalResult.Ok -> result.value
                is ExternalResult.Problem -> return result
            }
        val statuses =
            when (val result = readable(client.combinedStatus(connection, key, sha, nowMillis))) {
                is ExternalResult.Ok -> result.value
                is ExternalResult.Problem -> return result
            }
        val checks =
            when (val result = readable(client.checkRuns(connection, key, sha, nowMillis))) {
                is ExternalResult.Ok -> result.value
                is ExternalResult.Problem -> return result
            }
        val bundle = GitHubJson.detailBundle(pull, reviews, statuses, checks) ?: return ExternalResult.Problem(BAD_RESPONSE)
        val detail = GitHubJson.detail(bundle, key) ?: return ExternalResult.Problem(BAD_RESPONSE)
        val stored = succeeded { details.put(key.id, SavedRecord.encode(nowMillis, SCOPE, bundle)) }
        return ExternalResult.Ok(Refreshed(Saved(detail, nowMillis), stored))
    }

    override suspend fun forgetSaved(key: PullRequestRef) {
        details.delete(key.id)
    }

    /** A part the token may not read (403 or 404) is null instead of a failure. */
    private fun readable(result: ExternalResult<JsonElement>): ExternalResult<JsonElement?> =
        when (result) {
            is ExternalResult.Ok -> result
            is ExternalResult.Problem ->
                when (result.problem) {
                    ExternalProblem.Forbidden, ExternalProblem.NotFound -> ExternalResult.Ok(null)
                    else -> result
                }
        }
}
