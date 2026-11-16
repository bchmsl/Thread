package thread.web.presentation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** A dialog gives focus back when it goes away, also when it is removed rather than closed, as an item card is (D70). */
class ModalDialogTest {
    @Test
    fun aRemovedDialogGivesFocusBackToWhereItWas() =
        runTest {
            val body = document.body!!
            val opener = (document.createElement("button") as HTMLElement).also { it.textContent = "Synthetic opener" }
            val root = document.createElement("div")
            body.appendChild(opener)
            body.appendChild(root)
            var shown by mutableStateOf(true)
            opener.focus()
            val composition =
                renderComposable(root) {
                    CompositionLocalProvider(LocalDialogStack provides DialogStack(), LocalToasts provides ToastState()) {
                        if (shown) ModalDialog(label = "Synthetic dialog", onClose = {}) { Button { Text("Inside") } }
                    }
                }
            try {
                awaitFrames { document.activeElement != opener }
                assertNotEquals<Any?>(opener, document.activeElement, "The open dialog takes focus")

                shown = false
                awaitFrames { root.querySelector("dialog") == null }

                assertEquals<Any?>(opener, document.activeElement)
            } finally {
                composition.dispose()
                root.remove()
                opener.remove()
            }
        }

    /** Waits frame by frame until [done], since Compose applies changes on animation frames. */
    private suspend fun awaitFrames(done: () -> Boolean) {
        repeat(30) {
            if (done()) return
            Promise<Double> { resolve, _ -> window.requestAnimationFrame { resolve(it) } }.await()
        }
    }
}
