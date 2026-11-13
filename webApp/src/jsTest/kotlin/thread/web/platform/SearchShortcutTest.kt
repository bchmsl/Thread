package thread.web.platform

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchShortcutTest {
    private fun press(
        target: HTMLElement,
        key: String,
        code: String,
        meta: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
        composing: Boolean = false,
    ): KeyboardEvent {
        val event =
            KeyboardEvent(
                "keydown",
                KeyboardEventInit(
                    key = key,
                    code = code,
                    metaKey = meta,
                    ctrlKey = ctrl,
                    shiftKey = shift,
                    isComposing = composing,
                    bubbles = true,
                    cancelable = true,
                ),
            )
        target.dispatchEvent(event)
        return event
    }

    @Test
    fun commandKAndControlKReachTheHandlerFromAFocusedField() {
        var searches = 0
        val field = document.createElement("input") as HTMLElement
        document.body!!.appendChild(field)
        // A field that stops the key in the bubble phase cannot hide it from the app.
        field.addEventListener("keydown", { it.stopPropagation() })
        val remove = installSearchShortcut { searches++ }
        try {
            assertTrue(press(field, "k", "KeyK", meta = true).defaultPrevented)
            assertTrue(press(field, "K", "KeyK", ctrl = true).defaultPrevented)
            // Georgian layout: the K key types "კ".
            assertTrue(press(field, "კ", "KeyK", meta = true).defaultPrevented)
            // Dvorak: the letter k is on the physical V key.
            assertTrue(press(field, "k", "KeyV", meta = true).defaultPrevented)
            assertEquals(4, searches)

            assertFalse(press(field, "k", "KeyK").defaultPrevented)
            assertFalse(press(field, "k", "KeyK", meta = true, shift = true).defaultPrevented)
            assertFalse(press(field, "k", "KeyK", meta = true, composing = true).defaultPrevented)
            // Dvorak: the physical K key types "t", so it is not the shortcut.
            assertFalse(press(field, "t", "KeyK", meta = true).defaultPrevented)
            assertEquals(4, searches)
        } finally {
            remove()
        }

        press(field, "k", "KeyK", meta = true)
        assertEquals(4, searches)
        field.remove()
    }
}
