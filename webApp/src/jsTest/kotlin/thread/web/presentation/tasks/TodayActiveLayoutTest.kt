package thread.web.presentation.tasks

import kotlinx.browser.window
import thread.domain.workspace.TopicsView
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Owner change #1 kept Today's Topics layout on this device. Since D84 it syncs; the old device pick is only read, as
// the starting value until the synced choice is first set.
class TodayActiveLayoutTest {
    @AfterTest
    fun forget() = window.localStorage.removeItem(ACTIVE_LAYOUT_NAME)

    @Test
    fun nothingPickedHereReadsAsNotSet() {
        assertNull(deviceTodayTopicsView())
    }

    @Test
    fun anOlderPickOnThisDeviceIsRead() {
        window.localStorage.setItem(ACTIVE_LAYOUT_NAME, "board")
        assertEquals(TopicsView.Board, deviceTodayTopicsView())
        window.localStorage.setItem(ACTIVE_LAYOUT_NAME, "list")
        assertEquals(TopicsView.List, deviceTodayTopicsView())
    }

    @Test
    fun anUnknownStoredValueReadsAsNotSet() {
        window.localStorage.setItem(ACTIVE_LAYOUT_NAME, "grid")
        assertNull(deviceTodayTopicsView())
    }
}
