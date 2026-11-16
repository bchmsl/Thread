package thread.web.presentation

import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The keys and clicks an item card reacts to (D70): E edits, typing in a field stays typing, a plain click opens. */
class ItemCardKeysTest {
    private val added = mutableListOf<Element>()

    @AfterTest
    fun cleanUp() {
        added.forEach { it.remove() }
    }

    private fun <T : Element> onPage(element: T): T = element.also { document.body!!.appendChild(it) }.also { added += it }

    private fun key(
        key: String,
        meta: Boolean = false,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
    ) = KeyboardEvent("keydown", KeyboardEventInit(key = key, metaKey = meta, ctrlKey = ctrl, altKey = alt, shiftKey = shift))

    @Test
    fun eWithoutACommandKeyIsEdit() {
        assertTrue(isEditKey(key("e")))
        assertTrue(isEditKey(key("E", shift = true)))
        assertFalse(isEditKey(key("e", meta = true)))
        assertFalse(isEditKey(key("e", ctrl = true)))
        assertFalse(isEditKey(key("e", alt = true)))
        assertFalse(isEditKey(key("Escape")))
    }

    @Test
    fun onlyFieldsThatTakeTypingAreTextEntry() {
        fun input(type: String) = (document.createElement("input") as HTMLInputElement).also { it.type = type }

        assertTrue(isTextEntry(input("text")))
        assertTrue(isTextEntry(input("search")))
        assertTrue(isTextEntry(document.createElement("textarea")))
        assertTrue(isTextEntry(document.createElement("select")))
        assertFalse(isTextEntry(input("checkbox")))
        assertFalse(isTextEntry(input("radio")))
        assertFalse(isTextEntry(input("button")))
        assertFalse(isTextEntry(document.createElement("button")))

        val writable = onPage(document.createElement("div") as HTMLElement).also { it.contentEditable = "true" }
        val viewed = onPage(document.createElement("div") as HTMLElement).also { it.contentEditable = "false" }
        assertTrue(isTextEntry(writable))
        assertFalse(isTextEntry(viewed))
    }

    @Test
    fun onlyAPlainLeftClickOpensACard() {
        fun click(
            button: Short = 0,
            meta: Boolean = false,
            ctrl: Boolean = false,
            shift: Boolean = false,
            alt: Boolean = false,
        ) = MouseEvent("click", MouseEventInit(button = button, metaKey = meta, ctrlKey = ctrl, shiftKey = shift, altKey = alt))

        assertTrue(isPlainClick(click()))
        assertFalse(isPlainClick(click(button = 1)))
        assertFalse(isPlainClick(click(meta = true)))
        assertFalse(isPlainClick(click(ctrl = true)))
        assertFalse(isPlainClick(click(shift = true)))
        assertFalse(isPlainClick(click(alt = true)))
        assertFalse(isPlainClick(key("Enter")))
    }
}
