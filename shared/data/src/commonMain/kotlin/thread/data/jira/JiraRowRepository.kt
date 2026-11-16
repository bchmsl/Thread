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
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraIssueSummary
import thread.domain.external.JiraRowAnswer
import thread.domain.external.JiraRowSource
import thread.domain.external.Saved

/**
 * Ticket rows refreshed on show (D67): one enhanced JQL search, `key in (...)`, for up to [JiraClient.SEARCH_PAGE]
 * keys, with the row fields only. Rows are saved in [meta] per key, next to the lists, and marked with the site; nothing
 * here is written to Firestore.
 *
 * Jira refuses a whole search (400) when one key does not exist or is not shared. The search is then split in halves
 * until the refused key stands alone, within [maxSearches] searches; the caller asks that key alone afterwards, so the
 * split does not repeat. A refused key's saved row stays: a search cannot tell a deleted ticket from a hidden one.
 */
class JiraRowRepository(
    private val connections: ConnectionStore,
    transport: HttpTransport,
    private val meta: KeyValueStore,
    private val maxSearches: Int = MAX_SEARCHES,
) : JiraRowSource {
    private val client = JiraClient(transport)

    override suspend fun allSavedRows(): List<Saved<JiraIssueSummary>> {
        val connection = connections.jira() ?: return emptyList()
        return meta
            .all()
            .filter { (key, _) -> key.startsWith(ROW_PREFIX) }
            .mapNotNull { (_, raw) ->
                val (savedAt, data) = SavedRecord.decode(raw, connection.site.host) ?: return@mapNotNull null
                JiraJson.summary(data)?.let { Saved(it, savedAt) }
            }
    }

    override suspend fun refreshRows(
        keys: List<JiraIssueKey>,
        alone: Set<JiraIssueKey>,
        nowMillis: Long,
    ): JiraRowAnswer {
        val connection =
            connections.jira() ?: return JiraRowAnswer(emptyList(), emptySet(), ExternalProblem.NotConnected, storedOnDevice = true)
        val asked = keys.distinct()
        val together = asked.filter { it !in alone }
        val groups = listOf(together).filter { it.isNotEmpty() } + asked.filter { it in alone }.map { listOf(it) }
        val search = Search(connection, nowMillis)
        for (group in groups) {
            if (!search.run(group)) break
        }
        var stored = true
        val rows =
            search.bundles.mapNotNull { bundle ->
                val row = JiraJson.summary(bundle) ?: return@mapNotNull null
                val raw = SavedRecord.encode(nowMillis, connection.site.host, bundle)
                if (!succeeded { meta.put(rowKey(row.key), raw) }) stored = false
                Saved(row, nowMillis)
            }
        return JiraRowAnswer(rows, search.refused, search.problem, stored)
    }

    /** The searches of one refresh: what Jira showed, what it refused, and what stopped it. */
    private inner class Search(
        private val connection: JiraConnection,
        private val nowMillis: Long,
    ) {
        val bundles = mutableListOf<JsonObject>()
        val refused = mutableSetOf<JiraIssueKey>()
        var problem: ExternalProblem? = null
        private var sent = 0

        /** Searches [keys]; false when the refresh must stop. */
        suspend fun run(keys: List<JiraIssueKey>): Boolean {
            if (sent >= maxSearches) return false
            sent++
            return when (val result = client.search(connection, JiraQuery.keys(keys), JiraJson.SUMMARY_FIELDS, null, nowMillis)) {
                is ExternalResult.Ok -> {
                    val page = JiraJson.searchPage(result.value)
                    if (page == null) {
                        problem = BAD_RESPONSE
                        false
                    } else {
                        // A key Jira moved to another project answers with its new key; only asked keys are kept.
                        val wanted = keys.map { it.value }.toSet()
                        bundles += page.issues.filter { it.str("key") in wanted }
                        true
                    }
                }
                is ExternalResult.Problem ->
                    when {
                        result.problem != REFUSED -> {
                            problem = result.problem
                            false
                        }
                        keys.size == 1 -> {
                            refused += keys.single()
                            true
                        }
                        else -> {
                            val half = keys.size / 2
                            run(keys.take(half)) && run(keys.drop(half))
                        }
                    }
            }
        }
    }

    private fun rowKey(key: JiraIssueKey): String = "$ROW_PREFIX${key.value}"

    companion object {
        /**
         * The most searches one refresh sends. One refused key among [JiraClient.SEARCH_PAGE] keys needs 13 (one,
         * then two per halving); a second refused key in the same refresh may need a few more.
         */
        const val MAX_SEARCHES: Int = 16

        private const val ROW_PREFIX = "row:"

        /** Jira's answer when a key in the search does not exist or is not shared. */
        private val REFUSED: ExternalProblem = ExternalProblem.Failed("http-400")
    }
}
