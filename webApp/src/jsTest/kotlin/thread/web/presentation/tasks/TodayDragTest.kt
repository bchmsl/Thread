package thread.web.presentation.tasks

import thread.domain.workspace.TodayCard.Meetings
import thread.domain.workspace.TodayCard.Reminders
import thread.domain.workspace.TodayCard.ReviewRequested
import thread.domain.workspace.TodayCard.Tasks
import thread.domain.workspace.TodayCard.Topics
import thread.domain.workspace.TodayLayoutEdit
import thread.domain.workspace.TodayLayoutKind
import thread.domain.workspace.WorkspacePrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** #29 L4 (D99): where a dragged card lands in Today's edit mode, where the insert line shows, and what a drop says. */
class TodayDragTest {
    private val desktop = TodayLayoutEdit.start(WorkspacePrefs(), TodayLayoutKind.Desktop)
    private val phone = TodayLayoutEdit.start(WorkspacePrefs(), TodayLayoutKind.Phone)

    @Test
    fun theDropPositionCountsTheCardsAboveThePointer() {
        // Middles of three cards, top to bottom, the dragged card already left out.
        val middles = listOf(100.0, 160.0, 220.0)

        assertEquals(0, dropIndex(middles, 40.0), "Above the first card")
        assertEquals(0, dropIndex(middles, 99.0), "Over the first card's top half")
        assertEquals(1, dropIndex(middles, 101.0), "Over the first card's bottom half")
        assertEquals(2, dropIndex(middles, 190.0), "Between the second and third")
        assertEquals(3, dropIndex(middles, 400.0), "Below the last card")
        assertEquals(0, dropIndex(emptyList(), 50.0), "A place with no other card")
    }

    @Test
    fun theInsertLineShowsBeforeTheNextCardOrAfterTheLast() {
        val right = listOf(Meetings, Reminders, ReviewRequested)

        assertEquals(InsertLine(Meetings, after = false), insertLine(right, Topics, 0))
        assertEquals(InsertLine(ReviewRequested, after = false), insertLine(right, Topics, 2))
        assertEquals(InsertLine(ReviewRequested, after = true), insertLine(right, Topics, 3))
        // Reminders dragged inside its place: its own spot is left out, so 1 is before Review requested.
        assertEquals(InsertLine(ReviewRequested, after = false), insertLine(right, Reminders, 1))
        assertEquals(InsertLine(ReviewRequested, after = true), insertLine(right, Reminders, 2))
        assertNull(insertLine(emptyList(), Topics, 0), "An empty place lights up; it has no line")
        assertNull(insertLine(listOf(Tasks), Tasks, 0), "The dragged card alone in its place")
    }

    @Test
    fun aDropOnTheDesktopLayoutIsOneDraftChangeAndSaysWhereTheCardWent() {
        val toTop = desktop.dropped(Meetings, TodayDropTarget("top", 0))
        assertEquals(listOf(Meetings), toTop.layout.top)
        assertEquals("Moved Meetings to Top, 1 of 1", dropAnnouncement(toTop, Meetings))

        val between = desktop.dropped(Topics, TodayDropTarget("right", 1))
        assertEquals(listOf(Meetings, Topics, Reminders, ReviewRequested), between.layout.right)
        assertEquals("Moved Topics to Right, 2 of 4", dropAnnouncement(between, Topics))

        assertSame(desktop, desktop.dropped(Reminders, TodayDropTarget("right", 1)), "Dropped where it is: no change")
        assertSame(desktop, desktop.dropped(Reminders, TodayDropTarget("phone", 0)), "Not a place of this layout")
    }

    @Test
    fun aDropInThePhoneOrderSaysItsPlace() {
        val moved = phone.dropped(Topics, TodayDropTarget(PHONE_PLACE, 1))

        assertEquals(listOf(Tasks, Topics, Meetings, Reminders, ReviewRequested), moved.order)
        assertEquals("Moved Topics to place 2 of 5", dropAnnouncement(moved, Topics))
        assertSame(phone, phone.dropped(Tasks, TodayDropTarget(PHONE_PLACE, 0)), "Dropped where it is: no change")
        assertSame(phone, phone.dropped(Tasks, TodayDropTarget("top", 1)), "Not a place of this layout")
    }
}
