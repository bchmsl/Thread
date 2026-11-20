package thread.domain.external

import kotlin.jvm.JvmInline

/** A Jira filter id: digits only, as Jira gives it. Create it with [parse]. */
@JvmInline
value class JiraFilterId private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val ID = Regex("^[1-9][0-9]{0,18}$")

        /** Returns null for anything that is not a filter id. */
        fun parse(raw: String): JiraFilterId? {
            val id = raw.trim()
            return if (ID.matches(id)) JiraFilterId(id) else null
        }
    }
}

/**
 * A filter the account starred in Jira (D103). [jql] is the filter's own query, sent back to Jira as it is so its
 * ORDER BY holds; null when Jira did not send it. Kept on this device only; never synced.
 */
data class JiraFilter(
    val id: JiraFilterId,
    val name: String,
    val jql: String?,
)

/** The starred filters, in Jira's order. The one key of their saved list. */
data object JiraStarredFilters
