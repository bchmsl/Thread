package thread.web.presentation.tasks

import kotlinx.browser.window
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Owner change #1: the Active Topics layout is kept on this device only, like the open groups (D53).
class TodayActiveLayoutTest {
    @AfterTest
    fun forget() = window.localStorage.removeItem(ACTIVE_LAYOUT_NAME)

    @Test
    fun nothingPickedHereShowsTheList() {
        assertEquals(ActiveLayout.List, activeLayout())
    }

    @Test
    fun theLastPickIsKept() {
        keepActiveLayout(ActiveLayout.Board)
        assertEquals(ActiveLayout.Board, activeLayout())
        assertEquals("board", window.localStorage.getItem(ACTIVE_LAYOUT_NAME))
        keepActiveLayout(ActiveLayout.List)
        assertEquals(ActiveLayout.List, activeLayout())
    }

    @Test
    fun anUnknownStoredValueShowsTheList() {
        window.localStorage.setItem(ACTIVE_LAYOUT_NAME, "grid")
        assertEquals(ActiveLayout.List, activeLayout())
    }
}
