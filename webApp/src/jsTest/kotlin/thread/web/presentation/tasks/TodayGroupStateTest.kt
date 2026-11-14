package thread.web.presentation.tasks

import kotlinx.browser.window
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// D53: whether a Today group is open is kept on this device only. The keys are SYNTHETIC test groups.
class TodayGroupStateTest {
    private val keys = listOf("synthetic-a", "synthetic-b")

    @AfterTest
    fun forget() = keys.forEach { window.localStorage.removeItem("$GROUP_OPEN_NAME.$it") }

    @Test
    fun aGroupNeverToggledHereUsesItsDefault() {
        assertTrue(isGroupOpen("synthetic-a", default = true))
        assertFalse(isGroupOpen("synthetic-a", default = false))
    }

    @Test
    fun theLastChoiceIsKeptPerGroupAndWinsOverTheDefault() {
        keepGroupOpen("synthetic-a", open = true)
        keepGroupOpen("synthetic-b", open = false)
        assertTrue(isGroupOpen("synthetic-a", default = false))
        assertFalse(isGroupOpen("synthetic-b", default = true))
        assertEquals("1", window.localStorage.getItem("$GROUP_OPEN_NAME.synthetic-a"))
        assertEquals("0", window.localStorage.getItem("$GROUP_OPEN_NAME.synthetic-b"))
    }

    @Test
    fun anUnknownStoredValueFallsBackToTheDefault() {
        window.localStorage.setItem("$GROUP_OPEN_NAME.synthetic-a", "yes")
        assertTrue(isGroupOpen("synthetic-a", default = true))
        assertFalse(isGroupOpen("synthetic-a", default = false))
    }
}
