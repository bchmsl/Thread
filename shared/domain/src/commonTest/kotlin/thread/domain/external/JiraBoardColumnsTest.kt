package thread.domain.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SYNTHETIC cards only (D103 B). */
class JiraBoardColumnsTest {
    private val toDo = JiraStatus("To Do", JiraStatusCategory.ToDo)
    private val inProgress = JiraStatus("In Progress", JiraStatusCategory.InProgress)
    private val inReview = JiraStatus("In Review", JiraStatusCategory.InProgress)
    private val done = JiraStatus("Done", JiraStatusCategory.Done)
    private val triage = JiraStatus("Triage", JiraStatusCategory.ToDo)

    private fun key(n: Int): JiraIssueKey = assertNotNull(JiraIssueKey.parse("SYN-$n"))

    private fun card(
        n: Int,
        status: JiraStatus?,
        statusId: String?,
        isSubtask: Boolean = false,
    ) = JiraBoardCard(
        key = key(n),
        type = if (isSubtask) "Sub-task" else "Story",
        isSubtask = isSubtask,
        title = "SYNTHETIC $n",
        status = status,
        statusId = statusId,
        priority = null,
        assignee = null,
        parentKey = null,
        parentTitle = null,
        storyPoints = null,
        updatedAtMillis = null,
    )

    private val cards =
        listOf(
            card(1, inProgress, "2"),
            card(2, toDo, "1"),
            card(3, inReview, "3"),
            card(4, inProgress, "2", isSubtask = true),
            card(5, triage, "5"),
            card(6, done, "4"),
            card(7, inProgress, "2"),
        )

    private fun config(
        limits: JiraColumnLimits = JiraColumnLimits.IssueCount,
        columns: List<JiraBoardConfigColumn> =
            listOf(
                JiraBoardConfigColumn("To Do", setOf("1"), min = null, max = null),
                JiraBoardConfigColumn("Doing", setOf("2", "3"), min = 4, max = 2),
                JiraBoardConfigColumn("Done", setOf("4"), min = null, max = null),
            ),
    ) = JiraBoardConfig("SYNTHETIC board", columns, limits)

    private fun board(config: JiraBoardConfig?) =
        JiraSprintBoard(cards, complete = true, config = config, columnsNote = if (config == null) JiraColumnsNote.Unavailable else null)

    private fun number(card: JiraBoardCard): Int =
        checkNotNull(
            card.key.value
                .substringAfter('-')
                .toIntOrNull(),
        )

    private fun JiraBoardLayout.keys(): List<List<Int>> = columns.map { column -> column.cards.map(::number) }

    @Test
    fun theBoardsOwnColumnsHoldTheirStatusesInRankOrder() {
        val layout = boardColumns(board(config()), mine = emptySet(), onlyMine = false)

        assertFalse(layout.byStatus)
        assertEquals(listOf("To Do", "Doing", "Done"), layout.columns.map { it.name })
        assertEquals(listOf(listOf(2), listOf(1, 3, 4, 7), listOf(6)), layout.keys())
        assertEquals(1, layout.hiddenCount, "Triage is on no column, so Jira hides it too")
        assertEquals(listOf(false, false, true), layout.columns.map { it.isDone }, "the last column is done")
    }

    @Test
    fun columnLimitsCountEveryCardTheWayTheBoardCounts() {
        val doing = boardColumns(board(config()), emptySet(), onlyMine = false).columns[1]
        assertEquals(4, doing.total)
        assertTrue(doing.isOverMax)
        assertFalse(doing.isUnderMin)

        val withoutSubtasks = boardColumns(board(config(JiraColumnLimits.IssueCountExcludingSubtasks)), emptySet(), false).columns[1]
        assertEquals(3, withoutSubtasks.total, "the sub-task is not counted")
        assertTrue(withoutSubtasks.isOverMax)
        assertTrue(withoutSubtasks.isUnderMin)

        val none = boardColumns(board(config(JiraColumnLimits.None)), emptySet(), false).columns[1]
        assertNull(none.max)
        assertNull(none.min)
        assertFalse(none.isOverMax)
    }

    @Test
    fun onlyMineHidesOtherCardsButKeepsTheColumnsAndTheirTotals() {
        val mine = setOf(key(3), key(5), key(6))

        val layout = boardColumns(board(config()), mine, onlyMine = true)

        assertEquals(listOf("To Do", "Doing", "Done"), layout.columns.map { it.name }, "an empty column stays")
        assertEquals(listOf(emptyList(), listOf(3), listOf(6)), layout.keys())
        assertEquals(listOf(1, 4, 1), layout.columns.map { it.total })
        assertTrue(layout.columns[1].isOverMax, "limits count every card, not only the shown ones")
        assertEquals(1, layout.hiddenCount)

        assertEquals(0, boardColumns(board(config()), setOf(key(1)), onlyMine = true).hiddenCount)
        assertEquals(boardColumns(board(config()), emptySet(), false), boardColumns(board(config()), mine, onlyMine = false))
    }

    @Test
    fun aStatusOnTwoColumnsGoesToTheFirst() {
        val twice =
            config(
                columns =
                    listOf(
                        JiraBoardConfigColumn("Open", setOf("1", "2"), null, null),
                        JiraBoardConfigColumn("Working", setOf("2", "3"), null, null),
                    ),
            )

        assertEquals(listOf(listOf(1, 2, 4, 7), listOf(3)), boardColumns(board(twice), emptySet(), false).keys())
    }

    @Test
    fun withoutTheBoardsColumnsThereIsOneColumnPerStatus() {
        val layout = boardColumns(board(null), emptySet(), onlyMine = false)

        assertTrue(layout.byStatus)
        assertEquals(0, layout.hiddenCount)
        assertEquals(listOf("To Do", "Triage", "In Progress", "In Review", "Done"), layout.columns.map { it.name })
        assertEquals(listOf(listOf(2), listOf(5), listOf(1, 4, 7), listOf(3), listOf(6)), layout.keys())
        assertEquals(listOf(false, false, false, false, true), layout.columns.map { it.isDone })
        assertTrue(layout.columns.all { it.max == null && it.min == null })
        assertEquals(layout, boardColumns(board(config(columns = emptyList())), emptySet(), false), "no columns: by status")
    }

    @Test
    fun cardsWithoutAStatusIdGroupByNameAndUnknownComesLast() {
        val odd = JiraStatus("Waiting", JiraStatusCategory.Unknown)
        val board =
            JiraSprintBoard(
                listOf(card(1, inProgress, null), card(2, null, null), card(3, odd, null), card(4, inProgress, null)),
                complete = false,
                config = null,
                columnsNote = JiraColumnsNote.NeedsScopes,
            )

        val layout = boardColumns(board, setOf(key(2)), onlyMine = true)

        assertEquals(listOf("In Progress", "No status", "Waiting"), layout.columns.map { it.name })
        assertEquals(listOf(emptyList(), listOf(2), emptyList()), layout.keys())
        assertEquals(listOf(2, 1, 1), layout.columns.map { it.total })
    }

    @Test
    fun sprintAndBoardIdsAreDigitsOnly() {
        assertEquals("37", JiraSprintId.parse(" 37 ")?.value)
        assertEquals("7", JiraBoardId.parse("7")?.value)
        listOf("", "0", "07", "-1", "3 7", "abc", "1".repeat(20)).forEach {
            assertNull(JiraSprintId.parse(it), it)
            assertNull(JiraBoardId.parse(it), it)
        }
    }
}
