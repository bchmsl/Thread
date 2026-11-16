package thread.domain.external

/**
 * One linked ticket in a Topic's Jira list, placed under its parent (owner change #10, D64). [depth] is 0 at the top.
 * [parentNotLinked] is the ticket's own parent when that parent is not linked to the Topic, so the row can say where it
 * belongs ("in SYN-105").
 */
data class NestedTicket<T>(
    val item: T,
    val depth: Int,
    val parentNotLinked: JiraIssueKey?,
)

/** How far up a ticket's parents are followed to find a linked one: Subtask, Story, Epic and two levels above. */
private const val ANCESTORS_FOLLOWED = 5

/**
 * [items] in Jira's hierarchy: each one goes under its closest linked ancestor, even when a level between them is not
 * linked; the rest stay at the top. Top-level items and the children of one parent keep their order in [items]. An item
 * with no key ([keyOf] null), or whose parent is unknown ([parentOf] null), stays at the top. A loop in the parents (an
 * old copy after a move in Jira) is cut, so every item shows exactly once.
 */
fun <T> nestUnderParents(
    items: List<T>,
    keyOf: (T) -> JiraIssueKey?,
    parentOf: (JiraIssueKey) -> JiraIssueKey?,
): List<NestedTicket<T>> {
    val linked = items.mapNotNull(keyOf).toSet()
    val anchor = mutableMapOf<JiraIssueKey, JiraIssueKey>()
    for (key in linked) {
        var current = parentOf(key)
        var steps = 0
        val seen = mutableSetOf(key)
        while (current != null && steps < ANCESTORS_FOLLOWED && seen.add(current)) {
            if (current in linked) {
                anchor[key] = current
                break
            }
            current = parentOf(current)
            steps++
        }
    }
    // A loop through the anchors would hide its items; the first one met stays at the top.
    for (key in linked) {
        val seen = mutableSetOf(key)
        var current = anchor[key]
        while (current != null) {
            if (!seen.add(current)) {
                anchor.remove(key)
                break
            }
            current = anchor[current]
        }
    }
    val children =
        items
            .mapNotNull { item -> keyOf(item)?.let(anchor::get)?.let { parent -> parent to item } }
            .groupBy({ it.first }, { it.second })
    val result = mutableListOf<NestedTicket<T>>()

    fun add(
        item: T,
        depth: Int,
    ) {
        val key = keyOf(item)
        val parent = key?.let(parentOf)
        result += NestedTicket(item, depth, parent?.takeIf { it !in linked })
        if (key != null) children[key].orEmpty().forEach { add(it, depth + 1) }
    }
    items.filter { item -> keyOf(item)?.let(anchor::get) == null }.forEach { add(it, 0) }
    return result
}
