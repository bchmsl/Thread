package thread.domain.external

/**
 * The rows of tickets that screens show with their status, such as a Topic's linked tickets (D67). Refreshed by key
 * when a screen shows them, and kept on this device so the next open shows the newest copy at once. Never synced.
 */
interface JiraRowSource {
    /** Every row saved on this device for the connected site. Empty when Jira is not connected. */
    suspend fun allSavedRows(): List<Saved<JiraIssueSummary>>

    /**
     * Asks Jira for the rows of [keys] and saves them as fetched at [nowMillis]. Keys in [alone] are asked one at a
     * time: Jira refused them before, and one refused key refuses a whole search.
     */
    suspend fun refreshRows(
        keys: List<JiraIssueKey>,
        alone: Set<JiraIssueKey>,
        nowMillis: Long,
    ): JiraRowAnswer
}

/**
 * What Jira answered about some keys. [rows] are the rows Jira showed. [refused] are keys Jira refused on their own:
 * the ticket does not exist, or the token may not see it (a search cannot tell which). A key in neither keeps the copy
 * on this device. [problem] is what stopped the rest of the refresh, such as a 401 or a rate limit, or null.
 * [storedOnDevice] is false when saving a row on this device failed.
 */
data class JiraRowAnswer(
    val rows: List<Saved<JiraIssueSummary>>,
    val refused: Set<JiraIssueKey>,
    val problem: ExternalProblem?,
    val storedOnDevice: Boolean,
)
