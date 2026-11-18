package thread.web.presentation

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A new view's title gets focus and is on screen, also when the last view was scrolled far down (D94). */
class ViewTitleFocusTest {
    @Test
    fun aTitleAboveTheWindowIsFocusedAndShown() {
        val page = document.createElement("div") as HTMLElement
        val title = (document.createElement("h1") as HTMLElement).also { it.textContent = "SYNTHETIC view" }
        title.tabIndex = -1
        page.appendChild(title)
        page.appendChild((document.createElement("div") as HTMLElement).also { it.style.height = "4000px" })
        document.body!!.appendChild(page)
        try {
            window.scrollTo(0.0, 3000.0)
            assertTrue(title.getBoundingClientRect().bottom < 0, "The title starts above the window")

            focusViewTitle(title)

            assertEquals<Any?>(title, document.activeElement)
            val box = title.getBoundingClientRect()
            assertTrue(box.top >= 0 && box.bottom <= window.innerHeight, "The title is on screen: ${box.top} to ${box.bottom}")
        } finally {
            page.remove()
            window.scrollTo(0.0, 0.0)
        }
    }

    @Test
    fun aTitleOnScreenIsFocusedAndThePageStaysWhereItIs() {
        val page = document.createElement("div") as HTMLElement
        page.appendChild((document.createElement("div") as HTMLElement).also { it.style.height = "40px" })
        val title = (document.createElement("h1") as HTMLElement).also { it.textContent = "SYNTHETIC view" }
        title.tabIndex = -1
        page.appendChild(title)
        page.appendChild((document.createElement("div") as HTMLElement).also { it.style.height = "4000px" })
        document.body!!.appendChild(page)
        try {
            window.scrollTo(0.0, 10.0)
            val before = window.scrollY

            focusViewTitle(title)

            assertEquals<Any?>(title, document.activeElement)
            assertEquals(before, window.scrollY)
        } finally {
            page.remove()
            window.scrollTo(0.0, 0.0)
        }
    }
}
