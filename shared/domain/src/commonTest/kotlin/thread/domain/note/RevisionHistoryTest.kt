package thread.domain.note

import kotlin.test.Test
import kotlin.test.assertEquals

/** All content below is synthetic test data. */
class RevisionHistoryTest {
    private val device = DeviceId("synthetic-device")

    private fun revision(
        id: String,
        parent: String?,
        at: Long? = 0,
        discarded: Boolean = false,
    ) = NoteRevision(RevisionId(id), parent?.let(::RevisionId), "Synthetic", RichTextDocument.EMPTY, device, at, true, discarded)

    private fun RevisionHistory.leafIds() = leaves.map { it.id.value }

    @Test
    fun aLinearHistoryHasOneLeaf() {
        val history = RevisionHistory(listOf(revision("a", null), revision("b", "a"), revision("c", "b")))

        assertEquals(listOf("c"), history.leafIds())
    }

    @Test
    fun twoChildrenOfOneRevisionAreTwoLeavesNewestFirst() {
        val history = RevisionHistory(listOf(revision("a", null), revision("b", "a", at = 5), revision("c", "a", at = 9)))

        assertEquals(listOf("c", "b"), history.leafIds())
    }

    @Test
    fun aRevisionWithoutAKnownTimeCountsAsNewest() {
        val history = RevisionHistory(listOf(revision("a", null), revision("b", "a", at = 5), revision("c", "a", at = null)))

        assertEquals(listOf("c", "b"), history.leafIds())
    }

    @Test
    fun aDiscardedRevisionIsNotALeafAndItsParentDoesNotBecomeOne() {
        val history = RevisionHistory(listOf(revision("a", null), revision("b", "a"), revision("c", "a", discarded = true)))

        assertEquals(listOf("b"), history.leafIds())
    }

    @Test
    fun ancestorsStopAtAMissingParentAndAtACycle() {
        val missing = RevisionHistory(listOf(revision("b", "missing"), revision("c", "b")))
        val cycle = RevisionHistory(listOf(revision("x", "y"), revision("y", "x")))

        assertEquals(listOf("c", "b"), missing.ancestorsOrSelf(RevisionId("c")).map { it.id.value })
        assertEquals(listOf("x", "y"), cycle.ancestorsOrSelf(RevisionId("x")).map { it.id.value })
    }
}
