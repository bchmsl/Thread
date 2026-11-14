package thread.data.jira

import kotlinx.serialization.json.JsonObject
import thread.data.external.KeyValueStore
import thread.data.external.SavedRecord
import thread.data.external.str
import thread.data.external.succeeded
import thread.data.http.BAD_RESPONSE
import thread.data.http.HttpTransport
import thread.domain.external.ConnectionStore
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalResult
import thread.domain.external.JiraConnection
import thread.domain.external.JiraFamily
import thread.domain.external.JiraFamilySource
import thread.domain.external.JiraIssueKey
import thread.domain.external.Refreshed
import thread.domain.external.RequestBudget
import thread.domain.external.Saved
import thread.domain.suggest.FAMILY_KEYS_MAX
import thread.domain.suggest.familyParents
import thread.domain.topic.TopicId

/**
 * The parents, children and siblings of a Topic's linked tickets, for Topic suggestions (D49 S4,
 * adr/ADR-017-topic-suggestions.md). One refresh sends at most three GET searches, one page each:
 *
 * 1. `key in (linked)` for each linked ticket's parent;
 * 2. `key in (parents)` for the parents' own rows, when there are parents;
 * 3. `parent in (linked, parents Jira showed)` for the children and siblings, newest change first.
 *
 * Only keys go to Jira. The answer is saved in [meta] per Topic, next to the lists, and marked with the site; nothing
 * here is written to Firestore. Jira refusing a search (400, 403, 404 or an answer Thread cannot read) counts as no
 * rows and is saved, so it is not asked again at once; a problem that may pass (401, 429, no answer, 5xx) is returned
 * and nothing is saved. At most [REFRESHES_PER_MINUTE] refreshes a minute; past that the refresh fails quietly.
 */
class JiraFamilyRepository(
    private val connections: ConnectionStore,
    transport: HttpTransport,
    private val meta: KeyValueStore,
    private val budget: RequestBudget = RequestBudget(REFRESHES_PER_MINUTE, 60_000),
) : JiraFamilySource {
    private val client = JiraClient(transport)

    override suspend fun readSaved(topicId: TopicId): Saved<JiraFamily>? {
        val connection = connections.jira() ?: return null
        val raw = meta.get(metaKey(topicId)) ?: return null
        val (savedAt, data) = SavedRecord.decode(raw, connection.site.host) ?: return null
        return Saved(JiraJson.family(data), savedAt)
    }

    override suspend fun refresh(
        topicId: TopicId,
        keys: List<JiraIssueKey>,
        nowMillis: Long,
    ): ExternalResult<Refreshed<JiraFamily>> {
        val connection = connections.jira() ?: return ExternalResult.Problem(ExternalProblem.NotConnected)
        val asked = keys.distinct().take(FAMILY_KEYS_MAX)
        val rows = mutableListOf<JsonObject>()
        if (asked.isNotEmpty()) {
            if (budget.take(nowMillis) != null) return ExternalResult.Problem(BUDGET_SPENT)
            when (val linked = rows(connection, JiraQuery.keys(asked), nowMillis)) {
                is ExternalResult.Problem -> return linked
                is ExternalResult.Ok -> rows += linked.value
            }
            val parents = familyParents(asked, rows.mapNotNull(JiraJson::summary))
            var shownParents = emptyList<JiraIssueKey>()
            if (parents.isNotEmpty()) {
                when (val parentRows = rows(connection, JiraQuery.keys(parents), nowMillis)) {
                    is ExternalResult.Problem -> return parentRows
                    is ExternalResult.Ok -> rows += parentRows.value
                }
                // A parent Jira did not show (refused, or not shared) is left out, so it cannot refuse the next search.
                val shown = rows.mapNotNull(JiraJson::summary).map { it.key }.toSet()
                shownParents = parents.filter { it in shown }
            }
            if (rows.isNotEmpty()) {
                when (val children = rows(connection, JiraQuery.children(asked + shownParents), nowMillis)) {
                    is ExternalResult.Problem -> return children
                    is ExternalResult.Ok -> rows += children.value
                }
            }
        }
        // A linked ticket or parent also comes back as a child: its first row stays.
        val data = JiraJson.familyBundle(asked, rows.distinctBy { it.str("key") })
        val stored = succeeded { meta.put(metaKey(topicId), SavedRecord.encode(nowMillis, connection.site.host, data)) }
        return ExternalResult.Ok(Refreshed(Saved(JiraJson.family(data), nowMillis), stored))
    }

    /** One page of [jql]'s rows. A refused search is no rows; anything that may pass is returned as it is. */
    private suspend fun rows(
        connection: JiraConnection,
        jql: String,
        nowMillis: Long,
    ): ExternalResult<List<JsonObject>> =
        when (val result = client.search(connection, jql, JiraJson.FAMILY_FIELDS, null, nowMillis)) {
            is ExternalResult.Ok -> ExternalResult.Ok(JiraJson.searchPage(result.value)?.issues.orEmpty())
            is ExternalResult.Problem -> if (refused(result.problem)) ExternalResult.Ok(emptyList()) else result
        }

    private fun refused(problem: ExternalProblem): Boolean =
        problem == ExternalProblem.Failed("http-400") ||
            problem == ExternalProblem.Forbidden ||
            problem == ExternalProblem.NotFound ||
            problem == BAD_RESPONSE

    private fun metaKey(topicId: TopicId): String = "family:${topicId.value}"

    companion object {
        /** Each refresh is up to three searches, so at most nine Jira searches a minute from suggestions. */
        const val REFRESHES_PER_MINUTE: Int = 3

        /** The refresh budget is spent. Quiet in the UI: the saved answer stays. */
        val BUDGET_SPENT: ExternalProblem = ExternalProblem.Failed("search-budget")
    }
}
