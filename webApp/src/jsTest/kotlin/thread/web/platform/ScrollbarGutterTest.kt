package thread.web.platform

import kotlinx.browser.document
import org.w3c.dom.Element
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrollbarGutterTest {
    private var style: Element? = null

    @AfterTest
    fun cleanUp() {
        style?.remove()
        document.documentElement?.classList?.remove(NO_GUTTER_CLASS)
    }

    @Test
    fun aScrollbarThatTakesRoomTheGutterDidNotKeepIsMissing() {
        // Safari: the short probe kept no room, and its scrollbar takes 10 px.
        assertTrue(gutterMissing(kept = 100, scrolling = 90))
    }

    @Test
    fun aKeptGutterOrAnOverlayScrollbarIsNotMissing() {
        // Chrome and Firefox keep the room.
        assertFalse(gutterMissing(kept = 90, scrolling = 90))
        // Overlay scrollbars take no room.
        assertFalse(gutterMissing(kept = 100, scrolling = 100))
        // Headless Chrome hides the scrollbar but keeps the gutter.
        assertFalse(gutterMissing(kept = 90, scrolling = 100))
    }

    @Test
    fun theTestBrowserKeepsTheGutterSoThePageIsNotMarkedAndTheProbeIsRemoved() {
        // The probe's rules from app.css, which the test page does not load.
        style =
            document.createElement("style").also {
                it.textContent =
                    ".gutter-probe { position: absolute; top: 0; left: -200px; width: 100px; height: 40px; " +
                    "overflow-y: auto; scrollbar-gutter: stable; visibility: hidden; } " +
                    ".gutter-probe.is-scroll { overflow-y: scroll; scrollbar-gutter: auto; }"
                document.head?.appendChild(it)
            }

        markMissingScrollbarGutter()

        assertFalse(document.documentElement?.classList?.contains(NO_GUTTER_CLASS) ?: true)
        assertEquals(0, document.querySelectorAll(".gutter-probe").length)
    }
}
