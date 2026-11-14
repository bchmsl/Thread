package thread.data.github

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import thread.data.external.KeyValueStore
import thread.data.external.SavedRecord
import thread.data.external.succeeded
import thread.data.http.BAD_RESPONSE
import thread.data.http.HttpTransport
import thread.domain.external.ConnectionStore
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalRecordSource
import thread.domain.external.ExternalResult
import thread.domain.external.JiraIssueKey
import thread.domain.external.PullRequestDetail
import thread.domain.external.PullRequestList
import thread.domain.external.PullRequestListPage
import thread.domain.external.PullRequestMentionSource
import thread.domain.external.PullRequestMentions
import thread.domain.external.PullRequestRef
import thread.domain.external.PullRequestSource
import thread.domain.external.Refreshed
import thread.domain.external.RequestBudget
import thread.domain.external.Saved
import thread.domain.suggest.MENTION_KEYS_MAX
import thread.domain.suggest.MENTION_REPOS_MAX
import thread.domain.topic.TopicId

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
 * Pull requests that name a Topic's linked tickets, for Topic suggestions (D49 S5, adr/ADR-017-topic-suggestions.md).
 * One refresh sends one GET search per repository, at most [MENTION_REPOS_MAX], each for at most [MENTION_KEYS_MAX]
 * keys, one page of [GitHubClient.MENTION_PAGE]. Only repository names and Jira keys go to GitHub. Thread then checks
 * each result itself (a pull request, in that repository, naming a key) and saves the checked rows, without their
 * descriptions, per Topic in [lists]. Nothing here is written to Firestore.
 *
 * GitHub refusing one repository (422 when the token cannot search it, 403 or 404, or an answer Thread cannot read)
 * gives no rows for it and is saved; a problem that may pass (401, a rate limit, no answer, 5xx) is returned and
 * nothing is saved. At most [REFRESHES_PER_MINUTE] refreshes a minute, so with the lists Thread stays well inside
 * GitHub's 30 searches a minute; past that the refresh fails quietly.
 */
class GitHubPullRequestMentions(
    private val connections: ConnectionStore,
    transport: HttpTransport,
    private val lists: KeyValueStore,
    private val budget: RequestBudget = RequestBudget(REFRESHES_PER_MINUTE, 60_000),
) : PullRequestMentionSource {
    private val client = GitHubClient(transport)

    override suspend fun readSaved(topicId: TopicId): Saved<PullRequestMentions>? {
        val (savedAt, bundle) = SavedRecord.decode(lists.get(storageKey(topicId)) ?: return null, SCOPE) ?: return null
        return Saved(GitHubJson.mentions(bundle), savedAt)
    }

    override suspend fun refresh(
        topicId: TopicId,
        repos: List<String>,
        keys: List<JiraIssueKey>,
        nowMillis: Long,
    ): ExternalResult<Refreshed<PullRequestMentions>> {
        val connection = connections.gitHub() ?: return ExternalResult.Problem(ExternalProblem.NotConnected)
        val askedRepos = repos.distinct().take(MENTION_REPOS_MAX)
        val askedKeys = keys.distinct().take(MENTION_KEYS_MAX)
        val items = mutableListOf<JsonObject>()
        if (askedRepos.isNotEmpty() && askedKeys.isNotEmpty()) {
            if (budget.take(nowMillis) != null) return ExternalResult.Problem(BUDGET_SPENT)
            for (repo in askedRepos) {
                val repoKeys = GitHubClient.mentionKeys(repo, askedKeys)
                val query = GitHubClient.mentionQuery(repo, repoKeys) ?: continue
                when (val result = client.searchMentions(connection, query, nowMillis)) {
                    is ExternalResult.Ok -> items += GitHubJson.mentionItems(result.value, repo, repoKeys)
                    is ExternalResult.Problem -> if (!refused(result.problem)) return result
                }
            }
        }
        val bundle = GitHubJson.mentionBundle(askedRepos, askedKeys, items)
        val stored = succeeded { lists.put(storageKey(topicId), SavedRecord.encode(nowMillis, SCOPE, bundle)) }
        return ExternalResult.Ok(Refreshed(Saved(GitHubJson.mentions(bundle), nowMillis), stored))
    }

    private fun refused(problem: ExternalProblem): Boolean =
        problem == ExternalProblem.Failed("http-422") ||
            problem == ExternalProblem.Forbidden ||
            problem == ExternalProblem.NotFound ||
            problem == BAD_RESPONSE

    /** Next to the two lists, which are read by name, so these keys never mix with them. */
    private fun storageKey(topicId: TopicId): String = "mentions:${topicId.value}"

    companion object {
        /** Each refresh is up to two searches, so at most six GitHub searches a minute from suggestions. */
        const val REFRESHES_PER_MINUTE: Int = 3

        /** The refresh budget is spent. Quiet in the UI: the saved answer stays. */
        val BUDGET_SPENT: ExternalProblem = ExternalProblem.Failed("search-budget")
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
