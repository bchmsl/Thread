package thread.domain.workspace

import thread.domain.topic.StatusCategory
import thread.domain.workspace.TodayCard.Meetings
import thread.domain.workspace.TodayCard.Reminders
import thread.domain.workspace.TodayCard.ReviewRequested
import thread.domain.workspace.TodayCard.Tasks
import thread.domain.workspace.TodayCard.Topics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** #29 L3 (D98) and L4 (D99): Today's edit mode moves, drops and what Done saves. */
class TodayLayoutEditTest {
    private val vertical = defaultTodayLayout(MeetingsLayout.Vertical)

    @Test
    fun aCardMovedToAnotherPlaceGoesToItsEnd() {
        val moved = vertical.movedTo(Topics, TodayZone.Right)

        assertEquals(listOf(Meetings, Reminders, ReviewRequested, Topics), moved.right)
        assertEquals(emptyList(), moved.bottom)
        assertEquals(TodayZone.Right, moved.zoneOf(Topics))
        assertEquals(listOf(Tasks, Meetings), moved.movedTo(Meetings, TodayZone.Left).left)
        assertSame(vertical, vertical.movedTo(Meetings, TodayZone.Right), "Already there: it stays where it is")
        assertNull(TodayLayout().zoneOf(Meetings))
        assertEquals(TodayLayout(), TodayLayout().movedTo(Meetings, TodayZone.Top), "A card not in the layout is not added")
    }

    @Test
    fun upAndDownMoveInsideThePlaceAndStayAtItsEnds() {
        assertEquals(listOf(Reminders, Meetings, ReviewRequested), vertical.movedUp(Reminders).right)
        assertEquals(listOf(Meetings, ReviewRequested, Reminders), vertical.movedDown(Reminders).right)
        assertEquals(vertical, vertical.movedUp(Meetings), "First in Right: Move up does nothing")
        assertEquals(vertical, vertical.movedDown(ReviewRequested), "Last in Right: Move down does nothing")
        assertEquals(vertical, vertical.movedUp(Tasks), "Alone in Left: it never leaves its place")
        assertEquals(vertical, vertical.movedDown(Tasks))
    }

    @Test
    fun thePhoneOrderMovesUpDownFirstAndLast() {
        val order = listOf(Tasks, Meetings, Reminders, ReviewRequested, Topics)

        assertEquals(listOf(Tasks, Reminders, Meetings, ReviewRequested, Topics), order.movedUp(Reminders))
        assertEquals(listOf(Tasks, Meetings, ReviewRequested, Reminders, Topics), order.movedDown(Reminders))
        assertEquals(listOf(Reminders, Tasks, Meetings, ReviewRequested, Topics), order.movedFirst(Reminders))
        assertEquals(listOf(Tasks, Meetings, ReviewRequested, Topics, Reminders), order.movedLast(Reminders))
        assertEquals(order, order.movedUp(Tasks))
        assertEquals(order, order.movedDown(Topics))
        assertEquals(order, order.movedFirst(Tasks))
        assertEquals(order, order.movedLast(Topics))
    }

    @Test
    fun doneWithNothingChangedSavesNothing() {
        val unsaved = WorkspacePrefs()
        val saved = WorkspacePrefs(todayLayout = vertical.movedTo(Topics, TodayZone.Top), todayPhoneLayout = listOf(Topics))
        for (prefs in listOf(unsaved, saved)) {
            for (kind in TodayLayoutKind.entries) assertEquals(prefs, TodayLayoutEdit.start(prefs, kind).done(prefs))
        }
    }

    @Test
    fun aLayoutNotSavedStaysNotSavedWhenNothingEndsMoved() {
        val prefs = WorkspacePrefs(meetingsLayout = MeetingsLayout.Horizontal)
        val edit = TodayLayoutEdit.start(prefs, TodayLayoutKind.Desktop)
        val backAgain = edit.movedDown(Reminders).movedUp(Reminders)

        assertEquals(prefs, backAgain.done(prefs))
        assertNull(backAgain.done(prefs).todayLayout, "Meetings still follows Meetings layout")
        assertFalse(backAgain.canReset, "Nothing saved and nothing moved: nothing to reset")
    }

    @Test
    fun aMoveSavesTheWholeDesktopLayoutAndLeavesThePhoneOrder() {
        val prefs = WorkspacePrefs()
        val edit = TodayLayoutEdit.start(prefs, TodayLayoutKind.Desktop).movedTo(Meetings, TodayZone.Top)
        val saved = edit.done(prefs)

        assertEquals(defaultTodayLayout(MeetingsLayout.Horizontal), saved.todayLayout)
        assertNull(saved.todayPhoneLayout, "The phone order is not touched")
        val phone = saved.todayPhoneOrder
        assertEquals(listOf(Meetings, Tasks, Reminders, ReviewRequested, Topics), phone, "A phone following the desktop follows the move")
        assertEquals(prefs.copy(todayLayout = saved.todayLayout), saved, "Only the layout changes")
    }

    @Test
    fun aPhoneMoveSavesThePhoneOrderAndLeavesTheDesktop() {
        val prefs = WorkspacePrefs()
        val edit = TodayLayoutEdit.start(prefs, TodayLayoutKind.Phone)
        assertEquals(listOf(Tasks, Meetings, Reminders, ReviewRequested, Topics), edit.order, "It starts from the desktop order")

        val saved = edit.movedFirst(Topics).done(prefs)

        assertEquals(listOf(Topics, Tasks, Meetings, Reminders, ReviewRequested), saved.todayPhoneLayout)
        assertNull(saved.todayLayout)
        assertEquals(prefs.copy(todayPhoneLayout = saved.todayPhoneLayout), saved)
    }

    @Test
    fun resetToDefaultSavesTheDesktopLayoutAsNotSaved() {
        val prefs = WorkspacePrefs(todayLayout = TodayLayout(top = TodayCard.entries))
        val edit = TodayLayoutEdit.start(prefs, TodayLayoutKind.Desktop)
        assertTrue(edit.canReset)

        val reset = edit.resetLayout()
        assertEquals(vertical, reset.layout, "The draft shows the default layout")
        assertFalse(reset.canReset)
        assertNull(reset.done(prefs).todayLayout)

        val movedAfter = reset.movedTo(Tasks, TodayZone.Bottom)
        assertTrue(movedAfter.canReset)
        assertEquals(vertical.movedTo(Tasks, TodayZone.Bottom), movedAfter.done(prefs).todayLayout, "A move after Reset is saved")
    }

    @Test
    fun matchDesktopSavesThePhoneOrderAsNotSaved() {
        val saved = listOf(Topics, Reminders, Meetings, Tasks, ReviewRequested)
        val prefs = WorkspacePrefs(meetingsLayout = MeetingsLayout.Horizontal, todayPhoneLayout = saved)
        val edit = TodayLayoutEdit.start(prefs, TodayLayoutKind.Phone)
        assertEquals(saved, edit.order)
        assertTrue(edit.canReset)

        val matched = edit.resetLayout()
        assertEquals(listOf(Meetings, Tasks, Reminders, ReviewRequested, Topics), matched.order)
        assertFalse(matched.canReset)
        assertEquals(prefs.copy(todayPhoneLayout = null), matched.done(prefs))

        val following = TodayLayoutEdit.start(WorkspacePrefs(), TodayLayoutKind.Phone)
        assertFalse(following.canReset, "Following the desktop already")
        assertTrue(following.movedDown(Tasks).canReset)
    }

    @Test
    fun aHideSwitchIsAppliedToTheLatestSetSoAChangeFromElsewhereStays() {
        val prefs = WorkspacePrefs(todayHideEmpty = setOf(TodayHideableSection.Reminders))
        val edit = TodayLayoutEdit.start(prefs, TodayLayoutKind.Desktop).withHidden(TodayHideableSection.Meetings, hide = true)

        // Meanwhile Settings on another device hides Review requested when empty.
        val latest = prefs.copy(todayHideEmpty = setOf(TodayHideableSection.Reminders, TodayHideableSection.ReviewRequested))

        val saved = edit.done(latest)

        assertEquals(TodayHideableSection.entries.toSet(), saved.todayHideEmpty)
        assertEquals(latest.copy(todayHideEmpty = saved.todayHideEmpty), saved, "Nothing else changes")
        assertEquals(latest, edit.withHidden(TodayHideableSection.Meetings, hide = false).done(latest), "Switched back: nothing to write")
    }

    @Test
    fun aHideSwitchStartsFromTheSwitchBeforeD83() {
        val prefs = WorkspacePrefs(hideEmptySections = true)
        val edit = TodayLayoutEdit.start(prefs, TodayLayoutKind.Phone)
        assertEquals(setOf(TodayHideableSection.Reminders, TodayHideableSection.ReviewRequested), edit.hidden)

        val saved = edit.withHidden(TodayHideableSection.Reminders, hide = false).done(prefs)

        assertEquals(setOf(TodayHideableSection.ReviewRequested), saved.todayHideEmpty)
    }

    @Test
    fun topicsShownIsSavedOnlyWhenItChangedHere() {
        val prefs = WorkspacePrefs()
        val none =
            TodayLayoutEdit
                .start(prefs, TodayLayoutKind.Desktop)
                .withCategory(StatusCategory.Active, shown = false)
                .withCategory(StatusCategory.Paused, shown = false)
        assertEquals(emptySet(), none.done(prefs).todayTopicCategories, "None checked: Topics is left out")

        val latest = prefs.copy(todayTopicCategories = setOf(StatusCategory.Done))
        val untouched =
            TodayLayoutEdit
                .start(prefs, TodayLayoutKind.Desktop)
                .withCategory(StatusCategory.Done, shown = true)
                .withCategory(StatusCategory.Done, shown = false)
        assertEquals(latest, untouched.done(latest), "Not changed here: the latest stays")
    }

    @Test
    fun resetLeavesTheHideChoices() {
        val prefs = WorkspacePrefs(todayLayout = TodayLayout(left = TodayCard.entries))
        val edit =
            TodayLayoutEdit
                .start(prefs, TodayLayoutKind.Desktop)
                .withHidden(TodayHideableSection.Meetings, hide = true)
                .withCategory(StatusCategory.Planned, shown = true)
                .resetLayout()

        val saved = edit.done(prefs)

        assertNull(saved.todayLayout)
        assertEquals(setOf(TodayHideableSection.Meetings), saved.todayHideEmpty)
        assertEquals(setOf(StatusCategory.Planned, StatusCategory.Active, StatusCategory.Paused), saved.todayTopicCategories)
    }

    @Test
    fun aCardDroppedInAnotherPlaceGoesWhereItIsDropped() {
        assertEquals(listOf(Meetings, Topics, Reminders, ReviewRequested), vertical.movedTo(Topics, TodayZone.Right, 1).right)
        assertEquals(listOf(Topics, Meetings, Reminders, ReviewRequested), vertical.movedTo(Topics, TodayZone.Right, 0).right)
        assertEquals(emptyList(), vertical.movedTo(Topics, TodayZone.Right, 1).bottom, "It leaves its place")
        val atEnd = vertical.movedTo(Topics, TodayZone.Right)
        assertEquals(atEnd, vertical.movedTo(Topics, TodayZone.Right, 3), "After the last card: the end")
        assertEquals(atEnd, vertical.movedTo(Topics, TodayZone.Right, 9), "Past the end is clamped")
        assertEquals(listOf(Topics, Meetings, Reminders, ReviewRequested), vertical.movedTo(Topics, TodayZone.Right, -1).right)
        assertEquals(TodayLayout(), TodayLayout().movedTo(Meetings, TodayZone.Top, 0), "A card not in the layout is not added")
    }

    @Test
    fun aDropInsideItsPlaceReordersIt() {
        // The index counts the place's cards without the dragged one: Right without Meetings is Reminders, Review requested.
        assertEquals(listOf(Reminders, ReviewRequested, Meetings), vertical.movedTo(Meetings, TodayZone.Right, 2).right)
        assertEquals(listOf(Reminders, Meetings, ReviewRequested), vertical.movedTo(Meetings, TodayZone.Right, 1).right)
        assertEquals(listOf(ReviewRequested, Meetings, Reminders), vertical.movedTo(ReviewRequested, TodayZone.Right, 0).right)
        assertEquals(vertical, vertical.movedTo(Reminders, TodayZone.Right, 1), "Dropped where it is")
    }

    @Test
    fun aCardDroppedInAnEmptyPlaceIsItsOnlyCard() {
        val moved = vertical.movedTo(Tasks, TodayZone.Top, 0)

        assertEquals(listOf(Tasks), moved.top)
        assertEquals(emptyList(), moved.left, "Left is empty now")
        assertEquals(listOf(Tasks), vertical.movedTo(Tasks, TodayZone.Top, 4).top)
    }

    @Test
    fun aDropWhereTheCardIsChangesNothing() {
        val edit = TodayLayoutEdit.start(WorkspacePrefs(), TodayLayoutKind.Desktop)
        assertSame(edit, edit.droppedAt(Reminders, TodayZone.Right, 1))
        assertSame(edit, edit.droppedAt(Tasks, TodayZone.Left, 0))
        assertFalse(edit.droppedAt(Tasks, TodayZone.Left, 0).canReset)

        val phone = TodayLayoutEdit.start(WorkspacePrefs(), TodayLayoutKind.Phone)
        assertSame(phone, phone.droppedAt(Meetings, 1))
    }

    @Test
    fun thePhoneOrderTakesADropAtAnIndex() {
        val order = listOf(Tasks, Meetings, Reminders, ReviewRequested, Topics)

        assertEquals(listOf(Tasks, Reminders, ReviewRequested, Meetings, Topics), order.movedTo(Meetings, 3))
        assertEquals(listOf(Topics, Tasks, Meetings, Reminders, ReviewRequested), order.movedTo(Topics, 0))
        assertEquals(order.movedLast(Tasks), order.movedTo(Tasks, 4))
        assertEquals(order.movedLast(Tasks), order.movedTo(Tasks, 9))
        assertEquals(order, order.movedTo(Reminders, 2))
        assertEquals(listOf(Tasks), listOf(Tasks).movedTo(Topics, 0), "A card not in the order is not added")
    }

    @Test
    fun doneAfterADragSavesLikeAMove() {
        val prefs = WorkspacePrefs()
        val desktop = TodayLayoutEdit.start(prefs, TodayLayoutKind.Desktop)

        assertEquals(desktop.movedTo(Meetings, TodayZone.Top).done(prefs), desktop.droppedAt(Meetings, TodayZone.Top, 0).done(prefs))
        assertEquals(
            vertical.movedTo(Topics, TodayZone.Right, 0),
            desktop.droppedAt(Topics, TodayZone.Right, 0).done(prefs).todayLayout,
        )
        val saved = prefs.copy(todayLayout = TodayLayout(top = TodayCard.entries))
        val reset = TodayLayoutEdit.start(saved, TodayLayoutKind.Desktop).resetLayout()
        assertTrue(reset.droppedAt(Tasks, TodayZone.Bottom, 1).canReset, "A drop after Reset is a move again")

        val phone = TodayLayoutEdit.start(prefs, TodayLayoutKind.Phone).droppedAt(Topics, 1).done(prefs)
        assertEquals(listOf(Tasks, Topics, Meetings, Reminders, ReviewRequested), phone.todayPhoneLayout)
        assertNull(phone.todayLayout, "A phone drop leaves the desktop")
    }
}
