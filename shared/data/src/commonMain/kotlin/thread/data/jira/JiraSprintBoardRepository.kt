package thread.data.jira

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
import thread.domain.external.JiraBoardId
import thread.domain.external.JiraColumnsNote
import thread.domain.external.JiraConnection
import thread.domain.external.JiraMySprints
import thread.domain.external.JiraSprintBoard
import thread.domain.external.JiraSprintId
import thread.domain.external.JiraSprintList
import thread.domain.external.Refreshed
import thread.domain.external.Saved

/**
 * The account's active sprints (D103 B), found through the Sprint field of its own tickets in open sprints, with the
 * keys of those tickets for "Only my issues". It uses the enhanced JQL search, so `read:jira-work` covers it. Saved
 * on this device in [meta] under `sprints:mine`. Nothing here is written to Firestore.
 */
class JiraMySprintRepository(
    private val connections: ConnectionStore,
    transport: HttpTransport,
    private val meta: KeyValueStore,
) : ExternalRecordSource<JiraMySprints, JiraSprintList> {
    private val client = JiraClient(transport)
    private val siteFields = JiraSprintField(client, meta)

    override suspend fun isConnected(): Boolean = connections.jira() != null

    override suspend fun readSaved(key: JiraMySprints): Saved<JiraSprintList>? {
        val connection = connections.jira() ?: return null
        return savedSprints(meta, connection)
    }

    override suspend fun refresh(
        key: JiraMySprints,
        nowMillis: Long,
    ): ExternalResult<Refreshed<JiraSprintList>> {
        val connection = connections.jira() ?: return ExternalResult.Problem(ExternalProblem.NotConnected)
        val sprintField =
            when (val result = siteFields.id(connection, nowMillis)) {
                is ExternalResult.Ok -> result.value
                is ExternalResult.Problem -> return result
            }
        val data =
            if (sprintField == null) {
                JiraJson.noSprintsBundle()
            } else {
                val issues = mutableListOf<JsonObject>()
                var token: String? = null
                for (page in 1..DISCOVERY_PAGES) {
                    val answer =
                        when (val result = client.search(connection, JiraQuery.MY_OPEN_SPRINTS, sprintField, token, nowMillis)) {
                            is ExternalResult.Ok -> JiraJson.searchPage(result.value) { it } ?: return ExternalResult.Problem(BAD_RESPONSE)
                            is ExternalResult.Problem -> return result
                        }
                    issues += answer.issues
                    token = answer.nextPageToken
                    if (answer.isLast || token == null) break
                }
                JiraJson.mySprintsBundle(issues, sprintField)
            }
        val stored = succeeded { meta.put(SPRINTS_KEY, SavedRecord.encode(nowMillis, connection.site.host, data)) }
        return ExternalResult.Ok(Refreshed(Saved(JiraJson.sprintList(data), nowMillis), stored))
    }

    override suspend fun forgetSaved(key: JiraMySprints) {
        meta.delete(SPRINTS_KEY)
    }

    internal companion object {
        /** At most 200 of the account's tickets in open sprints (4 pages of 50). */
        const val DISCOVERY_PAGES = 4
    }
}

/**
 * One sprint's board (D103 B): its tickets through the enhanced JQL search (`sprint = ID ORDER BY Rank ASC`, up to
 * [SPRINT_PAGES] pages), and the board's own columns from Jira Software's board settings, asked at most once a day
 * per board. The settings need two granular token scopes. When Jira refuses them, or does not answer, the board shows
 * one column per status and Thread's other Jira views are not touched: such an answer never counts as expired
 * access. Saved on this device in [meta] under `sprint:<id>` and `boardConfig:<boardId>`. Nothing is written to
 * Firestore.
 */
class JiraSprintBoardRepository(
    private val connections: ConnectionStore,
    transport: HttpTransport,
    private val meta: KeyValueStore,
) : ExternalRecordSource<JiraSprintId, JiraSprintBoard> {
    private val client = JiraClient(transport)
    private val siteFields = JiraSprintField(client, meta)

    override suspend fun isConnected(): Boolean = connections.jira() != null

    override suspend fun readSaved(key: JiraSprintId): Saved<JiraSprintBoard>? {
        val connection = connections.jira() ?: return null
        val raw = meta.get(boardKey(key)) ?: return null
        val (savedAt, data) = SavedRecord.decode(raw, connection.site.host) ?: return null
        return Saved(JiraJson.sprintBoard(data), savedAt)
    }

    override suspend fun refresh(
        key: JiraSprintId,
        nowMillis: Long,
    ): ExternalResult<Refreshed<JiraSprintBoard>> {
        val connection = connections.jira() ?: return ExternalResult.Problem(ExternalProblem.NotConnected)
        val fields =
            when (val result = siteFields.ids(connection, nowMillis)) {
                is ExternalResult.Ok -> result.value
                is ExternalResult.Problem -> return result
            }
        val boardId =
            savedSprints(meta, connection)
                ?.value
                ?.sprints
                ?.firstOrNull { it.id == key }
                ?.boardId
        val (config, note) =
            when (val result = boardConfig(connection, boardId, nowMillis)) {
                is ExternalResult.Ok -> result.value
                is ExternalResult.Problem -> return result
            }
        // The board's own estimate field when its settings are known; else the site's story points field.
        val pointsField = if (config != null) JiraJson.estimationField(config) else fields.storyPoints
        val asked = listOfNotNull(JiraJson.BOARD_FIELDS, pointsField).joinToString(",")
        val issues = mutableListOf<JsonObject>()
        var token: String? = null
        var complete = false
        for (page in 1..SPRINT_PAGES) {
            val answer =
                when (val result = client.search(connection, JiraQuery.sprint(key), asked, token, nowMillis)) {
                    is ExternalResult.Ok ->
                        JiraJson.searchPage(result.value) { JiraJson.boardCardBundle(it, pointsField) }
                            ?: return ExternalResult.Problem(BAD_RESPONSE)
                    is ExternalResult.Problem -> return result
                }
            issues += answer.issues
            token = answer.nextPageToken
            complete = answer.isLast || token == null
            if (complete) break
        }
        val data = JiraJson.sprintBoardBundle(issues, complete, config, note)
        val stored = succeeded { meta.put(boardKey(key), SavedRecord.encode(nowMillis, connection.site.host, data)) }
        return ExternalResult.Ok(Refreshed(Saved(JiraJson.sprintBoard(data), nowMillis), stored))
    }

    override suspend fun forgetSaved(key: JiraSprintId) {
        meta.delete(boardKey(key))
    }

    /**
     * The board's settings and, when there are none, why: saved less than a day ago, else asked of Jira, else the
     * saved ones of any age. Only a rate limit stops the board; any other failure leaves it with status columns.
     */
    private suspend fun boardConfig(
        connection: JiraConnection,
        boardId: JiraBoardId?,
        nowMillis: Long,
    ): ExternalResult<Pair<JsonObject?, JiraColumnsNote?>> {
        if (boardId == null) return ExternalResult.Ok(null to JiraColumnsNote.Unavailable)
        val saved = meta.get(configKey(boardId))?.let { SavedRecord.decode(it, connection.site.host) }
        if (saved != null && nowMillis - saved.first in 0 until CONFIG_MILLIS) return ExternalResult.Ok(saved.second to null)
        val refused =
            when (val result = client.boardConfiguration(connection, boardId, nowMillis)) {
                is ExternalResult.Ok -> {
                    val config = JiraJson.boardConfigBundle(result.value)
                    if (config != null) {
                        succeeded { meta.put(configKey(boardId), SavedRecord.encode(nowMillis, connection.site.host, config)) }
                        return ExternalResult.Ok(config to null)
                    }
                    JiraColumnsNote.Unavailable
                }
                is ExternalResult.Problem ->
                    when (result.problem) {
                        is ExternalProblem.RateLimited -> return result
                        ExternalProblem.Unauthorized, ExternalProblem.Forbidden -> JiraColumnsNote.NeedsScopes
                        else -> JiraColumnsNote.Unavailable
                    }
            }
        return ExternalResult.Ok(if (saved != null) saved.second to null else null to refused)
    }

    private fun boardKey(id: JiraSprintId): String = "sprint:${id.value}"

    private fun configKey(id: JiraBoardId): String = "boardConfig:${id.value}"

    internal companion object {
        /** At most 200 tickets (4 pages of 50), in the board's rank order. */
        const val SPRINT_PAGES = 4

        /** The board settings are asked again after a day. */
        const val CONFIG_MILLIS = 24 * 60 * 60 * 1_000L
    }
}

private const val SPRINTS_KEY = "sprints:mine"

private suspend fun savedSprints(
    meta: KeyValueStore,
    connection: JiraConnection,
): Saved<JiraSprintList>? {
    val raw = meta.get(SPRINTS_KEY) ?: return null
    val (savedAt, data) = SavedRecord.decode(raw, connection.site.host) ?: return null
    return Saved(JiraJson.sprintList(data), savedAt)
}
