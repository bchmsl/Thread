package thread.web.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #29 L4 (D99): how fast the page scrolls while a card is dragged near an edge. */
class PointerDragTest {
    @Test
    fun autoScrollRunsOnlyNearAnEdgeAndFasterNearerIt() {
        // The area from 100 to 900: the edges are 100 to 156 and 844 to 900.
        assertEquals(0.0, autoScrollStep(500.0, 100.0, 900.0), "In the middle")
        assertEquals(0.0, autoScrollStep(156.0, 100.0, 900.0), "Just outside the top edge")
        assertEquals(0.0, autoScrollStep(844.0, 100.0, 900.0), "Just outside the bottom edge")

        val nearTop = autoScrollStep(150.0, 100.0, 900.0)
        val atTop = autoScrollStep(101.0, 100.0, 900.0)
        assertTrue(nearTop < 0 && atTop < nearTop, "Up, faster nearer the top: $nearTop, $atTop")
        val nearBottom = autoScrollStep(850.0, 100.0, 900.0)
        val atBottom = autoScrollStep(899.0, 100.0, 900.0)
        assertTrue(nearBottom > 0 && atBottom > nearBottom, "Down, faster nearer the bottom: $nearBottom, $atBottom")
    }

    @Test
    fun autoScrollIsCappedPastTheEdgeAndOffInAShortArea() {
        assertEquals(-AUTO_SCROLL_MAX_PX, autoScrollStep(20.0, 100.0, 900.0), "Above the area: full speed up")
        assertEquals(AUTO_SCROLL_MAX_PX, autoScrollStep(2000.0, 100.0, 900.0), "Below the area: full speed down")
        assertEquals(0.0, autoScrollStep(110.0, 100.0, 200.0), "An area too short for two edges")
    }
}
