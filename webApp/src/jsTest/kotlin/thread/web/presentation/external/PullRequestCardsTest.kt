package thread.web.presentation.external

import thread.domain.external.GitHubPerson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Owner change #13 (D69): the row author and the size numbers of a pull request card. SYNTHETIC logins only. */
class PullRequestCardsTest {
    private val me = GitHubPerson("synthetic-me", null)
    private val teammate = GitHubPerson("synthetic-teammate", null)

    @Test
    fun mineHidesTheAuthorOnlyWhenItIsTheViewer() {
        assertNull(rowAuthor(me, mine = true, viewer = "synthetic-me"))
        assertNull(rowAuthor(me, mine = true, viewer = "Synthetic-Me"), "GitHub logins ignore case")
        assertNull(rowAuthor(me, mine = true, viewer = null), "Mine searches author:@me, so an unknown viewer is the author")
        assertEquals(teammate, rowAuthor(teammate, mine = true, viewer = "synthetic-me"))
    }

    @Test
    fun reviewRequestedAlwaysShowsTheAuthor() {
        assertEquals(teammate, rowAuthor(teammate, mine = false, viewer = "synthetic-me"))
        assertEquals(me, rowAuthor(me, mine = false, viewer = "synthetic-me"))
        assertEquals(teammate, rowAuthor(teammate, mine = false, viewer = null))
        assertNull(rowAuthor(null, mine = false, viewer = null))
    }

    @Test
    fun largeCountsAreGroupedInThousands() {
        assertEquals("0", grouped(0))
        assertEquals("999", grouped(999))
        assertEquals("1,000", grouped(1_000))
        assertEquals("12,345", grouped(12_345))
        assertEquals("1,234,567", grouped(1_234_567))
    }

    @Test
    fun theFiveBlocksSplitByShareAndEachSideWithLinesKeepsOne() {
        assertEquals(0 to 0, diffBlocks(0, 0), "no lines, no colored blocks")
        assertEquals(5 to 0, diffBlocks(10, 0))
        assertEquals(0 to 5, diffBlocks(0, 10))
        assertEquals(3 to 2, diffBlocks(12_345, 6_789))
        assertEquals(4 to 1, diffBlocks(128, 14), "14 removed lines still show")
        assertEquals(1 to 4, diffBlocks(1, 1_000), "one added line still shows")
        assertEquals(4 to 1, diffBlocks(Int.MAX_VALUE, Int.MAX_VALUE / 1_000), "no overflow")
    }
}
