package thread.web.platform

import kotlinx.browser.window
import org.w3c.dom.events.Event

/** Whether the browser thinks it has a network. False is reliable; true only means "maybe". */
fun isOnline(): Boolean = window.navigator.onLine

/** Calls [onChange] when the browser goes online or offline. Returns a function that removes the listeners. */
fun onOnlineChange(onChange: (Boolean) -> Unit): () -> Unit {
    val online: (Event) -> Unit = { onChange(true) }
    val offline: (Event) -> Unit = { onChange(false) }
    window.addEventListener("online", online)
    window.addEventListener("offline", offline)
    return {
        window.removeEventListener("online", online)
        window.removeEventListener("offline", offline)
    }
}
