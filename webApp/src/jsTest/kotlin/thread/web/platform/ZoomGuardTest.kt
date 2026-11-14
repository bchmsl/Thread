package thread.web.platform

import kotlinx.browser.document
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import org.w3c.dom.events.WheelEvent
import org.w3c.dom.events.WheelEventInit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZoomGuardTest {
    private fun zoomKey(
        key: String,
        code: String,
        ctrlOrMeta: Boolean = true,
        alt: Boolean = false,
    ) = isZoomKey(key, code, ctrlOrMeta, alt)

    @Test
    fun ctrlOrCommandWithAZoomKeyIsAZoomKey() {
        assertTrue(zoomKey("=", "Equal"))
        // Ctrl+Shift+= types "+".
        assertTrue(zoomKey("+", "Equal"))
        assertTrue(zoomKey("-", "Minus"))
        assertTrue(zoomKey("_", "Minus"))
        assertTrue(zoomKey("0", "Digit0"))
        assertTrue(zoomKey("+", "NumpadAdd"))
        assertTrue(zoomKey("-", "NumpadSubtract"))
        assertTrue(zoomKey("0", "Numpad0"))
        // German layout: "+" and "-" are on other physical keys.
        assertTrue(zoomKey("+", "BracketRight"))
        assertTrue(zoomKey("-", "Slash"))
        // A layout that types another character on the = key.
        assertTrue(zoomKey("ჭ", "Equal"))
    }

    @Test
    fun otherKeysAndAltGrAreNotZoomKeys() {
        assertFalse(zoomKey("=", "Equal", ctrlOrMeta = false))
        assertFalse(zoomKey("-", "Minus", ctrlOrMeta = false))
        // AltGr+0 types "}" on a German keyboard and reports Ctrl+Alt on Windows.
        assertFalse(zoomKey("}", "Digit0", alt = true))
        assertFalse(zoomKey("=", "Equal", alt = true))
        assertFalse(zoomKey("k", "KeyK"))
        assertFalse(zoomKey("1", "Digit1"))
        assertFalse(zoomKey("9", "Digit9"))
    }

    @Test
    fun wheelZoomsOnlyWithCtrlOrCommand() {
        assertTrue(isZoomWheel(ctrl = true, meta = false))
        assertTrue(isZoomWheel(ctrl = false, meta = true))
        assertFalse(isZoomWheel(ctrl = false, meta = false))
    }

    @Test
    fun aTwoFingerMoveThatScalesIsAPinch() {
        assertTrue(isPinchMove(touchCount = 2, scale = 1.4))
        assertTrue(isPinchMove(touchCount = 2, scale = 0.7))
        assertFalse(isPinchMove(touchCount = 2, scale = 1.0))
        assertFalse(isPinchMove(touchCount = 1, scale = 1.4))
        // Browsers that report no scale honour touch-action, so a two-finger scroll stays.
        assertFalse(isPinchMove(touchCount = 2, scale = null))
    }

    private fun key(
        target: HTMLElement,
        key: String,
        code: String,
        ctrl: Boolean = false,
        meta: Boolean = false,
    ): KeyboardEvent {
        val event =
            KeyboardEvent(
                "keydown",
                KeyboardEventInit(
                    key = key,
                    code = code,
                    ctrlKey = ctrl,
                    metaKey = meta,
                    bubbles = true,
                    cancelable = true,
                ),
            )
        target.dispatchEvent(event)
        return event
    }

    private fun wheel(
        target: HTMLElement,
        ctrl: Boolean = false,
        meta: Boolean = false,
    ): WheelEvent {
        val event =
            WheelEvent(
                "wheel",
                WheelEventInit(deltaY = -40.0, ctrlKey = ctrl, metaKey = meta, bubbles = true, cancelable = true),
            )
        target.dispatchEvent(event)
        return event
    }

    private fun gesture(
        target: HTMLElement,
        type: String,
    ): Event {
        val event = Event(type, EventInit(bubbles = true, cancelable = true))
        target.dispatchEvent(event)
        return event
    }

    @Test
    fun theGuardCancelsZoomInputsAndLeavesTheSearchShortcutWorking() {
        var searches = 0
        val field = document.createElement("input") as HTMLElement
        document.body!!.appendChild(field)
        // A field that stops the event in the bubble phase cannot hide it from the guard.
        field.addEventListener("keydown", { it.stopPropagation() })
        field.addEventListener("wheel", { it.stopPropagation() })
        val removeGuard = installZoomGuard()
        val removeSearch = installSearchShortcut { searches++ }
        try {
            assertTrue(key(field, "=", "Equal", ctrl = true).defaultPrevented)
            assertTrue(key(field, "-", "Minus", meta = true).defaultPrevented)
            assertTrue(key(field, "0", "Digit0", ctrl = true).defaultPrevented)
            assertTrue(key(field, "+", "NumpadAdd", ctrl = true).defaultPrevented)
            assertFalse(key(field, "=", "Equal").defaultPrevented)
            assertFalse(key(field, "a", "KeyA", ctrl = true).defaultPrevented)

            assertTrue(wheel(field, ctrl = true).defaultPrevented)
            assertTrue(wheel(field, meta = true).defaultPrevented)
            assertFalse(wheel(field).defaultPrevented)

            assertTrue(gesture(field, "gesturestart").defaultPrevented)
            assertTrue(gesture(field, "gesturechange").defaultPrevented)
            assertTrue(gesture(field, "gestureend").defaultPrevented)

            // ⌘K still reaches the search shortcut.
            assertTrue(key(field, "k", "KeyK", meta = true).defaultPrevented)
            assertEquals(1, searches)
        } finally {
            removeSearch()
            removeGuard()
        }

        assertFalse(key(field, "=", "Equal", ctrl = true).defaultPrevented)
        assertFalse(wheel(field, ctrl = true).defaultPrevented)
        assertFalse(gesture(field, "gesturestart").defaultPrevented)
        field.remove()
    }
}
