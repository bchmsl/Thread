package thread.domain.external

import kotlin.test.Test
import kotlin.test.assertEquals

/** Owner change #10 (D64). SYNTHETIC keys only. */
class NestedTicketTest {
    private fun key(value: String) = requireNotNull(JiraIssueKey.parse(value))

    /** Each row as `depth:KEY` plus `>PARENT` when the row names a parent that is not linked. */
    private fun nest(
        linked: List<String>,
        parents: Map<String, String>,
    ): List<String> =
        nestUnderParents(linked, { JiraIssueKey.parse(it) }, { parents[it.value]?.let(::key) })
            .map { row -> "${row.depth}:${row.item}" + (row.parentNotLinked?.let { ">${it.value}" } ?: "") }

    @Test
    fun anEpicHoldsItsStoryAndTheStoryItsSubtask() {
        val parents = mapOf("SYN-2" to "SYN-1", "SYN-3" to "SYN-2")

        assertEquals(listOf("0:SYN-1", "1:SYN-2", "2:SYN-3"), nest(listOf("SYN-3", "SYN-1", "SYN-2"), parents))
    }

    @Test
    fun aTicketWhoseParentIsNotLinkedStaysAtTheTopAndNamesItsParent() {
        val parents = mapOf("SYN-5" to "SYN-9")

        assertEquals(listOf("0:SYN-4", "0:SYN-5>SYN-9"), nest(listOf("SYN-4", "SYN-5"), parents))
    }

    @Test
    fun aMissingMiddleLevelPutsTheTicketUnderItsClosestLinkedAncestorAndNamesItsParent() {
        // Epic SYN-1 and Subtask SYN-3 are linked; their Story SYN-2 is not.
        val parents = mapOf("SYN-2" to "SYN-1", "SYN-3" to "SYN-2")

        assertEquals(listOf("0:SYN-1", "1:SYN-3>SYN-2"), nest(listOf("SYN-3", "SYN-1"), parents))
    }

    @Test
    fun topLevelRowsAndSiblingsKeepTheirOrder() {
        val parents = mapOf("SYN-12" to "SYN-1", "SYN-11" to "SYN-1", "SYN-13" to "SYN-1")
        val linked = listOf("SYN-7", "SYN-12", "SYN-1", "SYN-13", "SYN-6", "SYN-11")

        assertEquals(
            listOf("0:SYN-7", "0:SYN-1", "1:SYN-12", "1:SYN-13", "1:SYN-11", "0:SYN-6"),
            nest(linked, parents),
        )
    }

    @Test
    fun anUnknownParentLeavesTheListFlat() {
        assertEquals(listOf("0:SYN-2", "0:SYN-1"), nest(listOf("SYN-2", "SYN-1"), emptyMap()))
    }

    @Test
    fun aLoopInTheParentsShowsEveryTicketOnce() {
        val parents = mapOf("SYN-1" to "SYN-2", "SYN-2" to "SYN-1")

        assertEquals(listOf("0:SYN-1", "1:SYN-2"), nest(listOf("SYN-1", "SYN-2"), parents))
    }

    @Test
    fun aLongUnlinkedChainIsFollowedOnlySoFar() {
        // SYN-1 is six levels above SYN-7, past the five followed.
        val parents = (2..7).associate { "SYN-$it" to "SYN-${it - 1}" }

        assertEquals(listOf("0:SYN-1", "0:SYN-7>SYN-6"), nest(listOf("SYN-1", "SYN-7"), parents))
    }

    @Test
    fun aRowWithoutAKeyStaysAtTheTopInItsPlace() {
        val rows =
            nestUnderParents(listOf("SYN-1", "other site", "SYN-2"), { JiraIssueKey.parse(it) }) {
                if (it.value == "SYN-2") key("SYN-1") else null
            }

        assertEquals(listOf("SYN-1" to 0, "SYN-2" to 1, "other site" to 0), rows.map { it.item to it.depth })
    }
}
