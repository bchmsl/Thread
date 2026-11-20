package thread.domain.external

import kotlin.jvm.JvmInline

/** A Jira sprint id: digits only, as Jira gives it. Create it with [parse]. */
@JvmInline
value class JiraSprintId private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val ID = Regex("^[1-9][0-9]{0,18}$")

        /** Returns null for anything that is not a sprint id. */
        fun parse(raw: String): JiraSprintId? {
            val id = raw.trim()
            return if (ID.matches(id)) JiraSprintId(id) else null
        }
    }
}

/** A Jira Software board id: digits only, as Jira gives it. Create it with [parse]. */
@JvmInline
value class JiraBoardId private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val ID = Regex("^[1-9][0-9]{0,18}$")

        /** Returns null for anything that is not a board id. */
        fun parse(raw: String): JiraBoardId? {
            val id = raw.trim()
            return if (ID.matches(id)) JiraBoardId(id) else null
        }
    }
}

/**
 * An active sprint the account has tickets in (D103 B), as the Sprint field of those tickets names it. [myKeys] are
 * the account's own tickets in it, for "Only my issues". Kept on this device only; never synced.
 */
data class JiraSprint(
    val id: JiraSprintId,
    val name: String,
    val boardId: JiraBoardId?,
    val startMillis: Long?,
    val endMillis: Long?,
    val goal: String?,
    val myKeys: Set<JiraIssueKey>,
)

/** The key of the account's active sprints list. */
data object JiraMySprints

/**
 * The account's active sprints, the one with most of its tickets first. [siteHasSprints] is false when the site has
 * no Sprint field (no Jira Software), so there is nothing to look for.
 */
data class JiraSprintList(
    val sprints: List<JiraSprint>,
    val siteHasSprints: Boolean,
)

/** One ticket on a sprint board. Person fields hold display names. [storyPoints] is the board's estimate, if any. */
data class JiraBoardCard(
    val key: JiraIssueKey,
    val type: String?,
    val isSubtask: Boolean,
    val title: String,
    val status: JiraStatus?,
    val statusId: String?,
    val priority: String?,
    val assignee: String?,
    val parentKey: JiraIssueKey?,
    val parentTitle: String?,
    val storyPoints: Double?,
    val updatedAtMillis: Long?,
) {
    fun summary(): JiraIssueSummary = JiraIssueSummary(key, type, title, status, updatedAtMillis, parentKey)
}

/** How a board counts its column limits, as Jira's board settings say. */
enum class JiraColumnLimits {
    None,
    IssueCount,
    IssueCountExcludingSubtasks,
}

/** One column of the board's own settings: the statuses it holds and its limits. */
data class JiraBoardConfigColumn(
    val name: String,
    val statusIds: Set<String>,
    val min: Int?,
    val max: Int?,
)

/** The board's own columns (`GET /rest/agile/1.0/board/{id}/configuration`), cut down to what the board shows. */
data class JiraBoardConfig(
    val name: String?,
    val columns: List<JiraBoardConfigColumn>,
    val limits: JiraColumnLimits,
)

/** Why a board shows one column per status instead of its own columns. */
enum class JiraColumnsNote {
    /** Jira refused the board settings (401 or 403): the token lacks the board scopes. */
    NeedsScopes,

    /** Jira did not send the board settings, or the sprint names no board. */
    Unavailable,
}

/**
 * One sprint's board (D103 B): its tickets in Jira's rank order and, when Jira sent them, the board's own columns.
 * [complete] is false when the sprint has more tickets than Thread fetched. [columnsNote] says why [config] is null.
 */
data class JiraSprintBoard(
    val cards: List<JiraBoardCard>,
    val complete: Boolean,
    val config: JiraBoardConfig?,
    val columnsNote: JiraColumnsNote?,
)

/**
 * A column as the board shows it. [cards] are the ones shown (all, or the account's own), [total] counts every card
 * in the column the way the board's limits count. [isDone] marks the done column: the last one, like Jira's.
 */
data class JiraBoardColumn(
    val name: String,
    val cards: List<JiraBoardCard>,
    val total: Int,
    val min: Int?,
    val max: Int?,
    val isDone: Boolean,
) {
    val isOverMax: Boolean get() = max != null && total > max
    val isUnderMin: Boolean get() = min != null && total < min
}

/**
 * The columns a board shows. [hiddenCount] counts the shown cards whose status is on none of the board's columns
 * (Jira hides them too). [byStatus] is true when the columns follow status because the board's own are not known.
 */
data class JiraBoardLayout(
    val columns: List<JiraBoardColumn>,
    val hiddenCount: Int,
    val byStatus: Boolean,
)

/**
 * Places [board]'s cards in columns, keeping Jira's rank order in each. With the board's own columns, a card goes to
 * the column that holds its status id; limits are checked on every card, not only the shown ones. Without them,
 * there is one column per status on the board, To Do, In Progress and Done categories in that order, then by name.
 * [onlyMine] shows only [mine]; the columns stay the same, so they do not move when it is switched.
 */
fun boardColumns(
    board: JiraSprintBoard,
    mine: Set<JiraIssueKey>,
    onlyMine: Boolean,
): JiraBoardLayout {
    fun shown(card: JiraBoardCard): Boolean = !onlyMine || card.key in mine
    val config = board.config
    if (config != null && config.columns.isNotEmpty()) {
        val columnOf = mutableMapOf<String, Int>()
        config.columns.forEachIndexed { index, column -> column.statusIds.forEach { columnOf.getOrPut(it) { index } } }
        val placed = board.cards.groupBy { card -> card.statusId?.let(columnOf::get) }
        val columns =
            config.columns.mapIndexed { index, column ->
                val all = placed[index].orEmpty()
                val counted =
                    when (config.limits) {
                        JiraColumnLimits.IssueCountExcludingSubtasks -> all.count { !it.isSubtask }
                        else -> all.size
                    }
                val limited = config.limits != JiraColumnLimits.None
                JiraBoardColumn(
                    name = column.name,
                    cards = all.filter(::shown),
                    total = counted,
                    min = column.min.takeIf { limited },
                    max = column.max.takeIf { limited },
                    isDone = index == config.columns.lastIndex,
                )
            }
        return JiraBoardLayout(columns, hiddenCount = placed[null].orEmpty().count(::shown), byStatus = false)
    }
    val columns =
        board.cards
            .groupBy { card -> card.statusId ?: card.status?.name ?: NO_STATUS }
            .values
            .map { cards -> (cards.first().status ?: JiraStatus(NO_STATUS, JiraStatusCategory.Unknown)) to cards }
            .sortedWith(compareBy({ categoryOrder(it.first.category) }, { it.first.name.lowercase() }))
            .map { (status, cards) ->
                JiraBoardColumn(
                    name = status.name,
                    cards = cards.filter(::shown),
                    total = cards.size,
                    min = null,
                    max = null,
                    isDone = status.category == JiraStatusCategory.Done,
                )
            }
    return JiraBoardLayout(columns, hiddenCount = 0, byStatus = true)
}

private const val NO_STATUS = "No status"

private fun categoryOrder(category: JiraStatusCategory): Int =
    when (category) {
        JiraStatusCategory.ToDo -> 0
        JiraStatusCategory.InProgress -> 1
        JiraStatusCategory.Done -> 2
        JiraStatusCategory.Unknown -> 3
    }
