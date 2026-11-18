package thread.domain.workspace

import thread.domain.workspace.TodayCard.Meetings
import thread.domain.workspace.TodayCard.Reminders
import thread.domain.workspace.TodayCard.ReviewRequested
import thread.domain.workspace.TodayCard.Tasks
import thread.domain.workspace.TodayCard.Topics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #29 L1 (D95): Today's desktop layout and one-column order, saved or not. */
class TodayLayoutTest {
    @Test
    fun theIdsAreTheStoredOnes() {
        assertEquals(listOf("meetings", "tasks", "reminders", "review", "topics"), TodayCard.entries.map { it.id })
        assertEquals(listOf("top", "left", "right", "bottom"), TodayZone.entries.map { it.id })
        TodayCard.entries.forEach { assertEquals(it, TodayCard.fromId(it.id)) }
        TodayZone.entries.forEach { assertEquals(it, TodayZone.fromId(it.id)) }
        assertNull(TodayCard.fromId("inbox"))
        assertNull(TodayZone.fromId("middle"))
        assertNull(TodayCard.fromId(null))
    }

    @Test
    fun notSavedIsTodaysLayoutBefore29() {
        val vertical = WorkspacePrefs()
        val horizontal = WorkspacePrefs(meetingsLayout = MeetingsLayout.Horizontal)

        assertNull(vertical.todayLayout)
        assertNull(vertical.todayPhoneLayout)
        assertEquals(
            TodayLayout(left = listOf(Tasks), right = listOf(Meetings, Reminders, ReviewRequested), bottom = listOf(Topics)),
            vertical.todayDesktopLayout,
        )
        assertEquals(
            TodayLayout(top = listOf(Meetings), left = listOf(Tasks), right = listOf(Reminders, ReviewRequested), bottom = listOf(Topics)),
            horizontal.todayDesktopLayout,
        )
        // One column reads the desktop layout Top, Left, Right, Bottom, as it did before #29.
        assertEquals(listOf(Tasks, Meetings, Reminders, ReviewRequested, Topics), vertical.todayPhoneOrder)
        assertEquals(listOf(Meetings, Tasks, Reminders, ReviewRequested, Topics), horizontal.todayPhoneOrder)
    }

    @Test
    fun aWholeSavedLayoutIsUsedAsSaved() {
        val saved = TodayLayout(top = listOf(Topics, Tasks), right = listOf(ReviewRequested), bottom = listOf(Reminders, Meetings))
        val prefs = WorkspacePrefs(todayLayout = saved, todayPhoneLayout = listOf(Reminders, Topics, Meetings, Tasks, ReviewRequested))

        assertEquals(saved, prefs.todayDesktopLayout)
        assertEquals(saved, prefs.copy(meetingsLayout = MeetingsLayout.Horizontal).todayDesktopLayout, "Meetings stays where saved")
        assertEquals(listOf(Reminders, Topics, Meetings, Tasks, ReviewRequested), prefs.todayPhoneOrder)
    }

    @Test
    fun aCardSavedTwiceKeepsItsFirstPlace() {
        val prefs =
            WorkspacePrefs(
                todayLayout =
                    TodayLayout(
                        top = listOf(Tasks),
                        left = listOf(Topics, Tasks),
                        right = listOf(Meetings, Reminders, ReviewRequested, Meetings),
                        bottom = listOf(Topics),
                    ),
                todayPhoneLayout = listOf(Topics, Tasks, Topics, Meetings, Reminders, ReviewRequested, Tasks),
            )

        assertEquals(
            TodayLayout(top = listOf(Tasks), left = listOf(Topics), right = listOf(Meetings, Reminders, ReviewRequested)),
            prefs.todayDesktopLayout,
        )
        assertEquals(listOf(Topics, Tasks, Meetings, Reminders, ReviewRequested), prefs.todayPhoneOrder)
    }

    @Test
    fun aCardNotSavedGoesToItsDefaultPlaceAndPosition() {
        // Meetings and Reminders are missing: back to Right, at their default positions around what Right holds.
        val partly = WorkspacePrefs(todayLayout = TodayLayout(left = listOf(Topics, Tasks), right = listOf(ReviewRequested)))
        assertEquals(
            TodayLayout(left = listOf(Topics, Tasks), right = listOf(Meetings, Reminders, ReviewRequested)),
            partly.todayDesktopLayout,
        )

        // A default position past the end of what the place holds is the end.
        val moved = WorkspacePrefs(todayLayout = TodayLayout(top = listOf(Tasks, Topics, Meetings), right = emptyList()))
        assertEquals(
            TodayLayout(top = listOf(Tasks, Topics, Meetings), right = listOf(Reminders, ReviewRequested)),
            moved.todayDesktopLayout,
        )

        // Horizontal puts a missing Meetings at the top.
        val horizontal = partly.copy(meetingsLayout = MeetingsLayout.Horizontal)
        assertEquals(
            TodayLayout(top = listOf(Meetings), left = listOf(Topics, Tasks), right = listOf(Reminders, ReviewRequested)),
            horizontal.todayDesktopLayout,
        )

        // An empty saved layout is the default one.
        val empty = WorkspacePrefs(todayLayout = TodayLayout())
        assertEquals(WorkspacePrefs().todayDesktopLayout, empty.todayDesktopLayout)
    }

    @Test
    fun aCardNotSavedInTheOneColumnOrderGoesWhereTheDesktopOrderHasIt() {
        val desktop = TodayLayout(top = listOf(Topics), left = listOf(Tasks, Meetings), right = listOf(Reminders, ReviewRequested))

        // The desktop order is Topics, Tasks, Meetings, Reminders, Review requested.
        val prefs = WorkspacePrefs(todayLayout = desktop, todayPhoneLayout = listOf(ReviewRequested, Reminders))
        assertEquals(listOf(Topics, Tasks, Meetings, ReviewRequested, Reminders), prefs.todayPhoneOrder)

        // An empty saved order is the desktop order.
        assertEquals(desktop.inOrder, prefs.copy(todayPhoneLayout = emptyList()).todayPhoneOrder)
        // Not saved follows the desktop layout, also when it changes.
        assertEquals(desktop.inOrder, prefs.copy(todayPhoneLayout = null).todayPhoneOrder)
    }

    @Test
    fun theDrawnPartLeavesOutCardsTodayDoesNotDraw() {
        val layout = WorkspacePrefs().todayDesktopLayout

        assertEquals(
            TodayLayout(left = listOf(Tasks), right = listOf(Reminders), bottom = listOf(Topics)),
            layout.only(setOf(Tasks, Reminders, Topics)),
        )
        assertEquals(TodayLayout(), layout.only(emptySet()))
        assertEquals(listOf(Meetings, Reminders, ReviewRequested), layout[TodayZone.Right])
        assertEquals(emptyList(), layout[TodayZone.Top])
    }

    @Test
    fun oneSideTakesTheFullWidthOnlyWhenTheOtherColumnIsEmpty() {
        assertFalse(TodayLayout(left = listOf(Tasks), right = listOf(Reminders)).oneSide)
        assertTrue(TodayLayout(left = listOf(Tasks)).oneSide)
        assertTrue(TodayLayout(top = listOf(Meetings), right = listOf(Reminders)).oneSide)
        assertFalse(TodayLayout(top = listOf(Meetings), bottom = listOf(Topics)).oneSide, "No column, nothing to widen")
    }
}
