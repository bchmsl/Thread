package thread.web.platform

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.events.Event

/**
 * Calls [onLeave] when the page is hidden or unloaded: `visibilitychange` to hidden (tab switch, app switch, mobile
 * background) and `pagehide` (close, reload, navigation). Mobile browsers may skip `pagehide`, so both are used.
 * [onLeave] may run twice for one exit and must be cheap. Returns a function that removes the listeners.
 */
fun onPageLeave(onLeave: () -> Unit): () -> Unit {
    val visibility: (Event) -> Unit = { if (document.asDynamic().visibilityState == "hidden") onLeave() }
    val pageHide: (Event) -> Unit = { onLeave() }
    document.addEventListener("visibilitychange", visibility)
    window.addEventListener("pagehide", pageHide)
    return {
        document.removeEventListener("visibilitychange", visibility)
        window.removeEventListener("pagehide", pageHide)
    }
}

/**
 * Asks the browser to confirm before the page is closed or reloaded, until the returned function is called.
 *
 * Add it only while there is something to lose: a `beforeunload` listener keeps the page out of the back/forward
 * cache in some browsers (Firefox).
 */
fun warnBeforeUnload(): () -> Unit {
    val listener: (Event) -> Unit = { event ->
        event.preventDefault()
        // Older browsers show the prompt only when returnValue is set.
        event.asDynamic().returnValue = ""
    }
    window.addEventListener("beforeunload", listener)
    return { window.removeEventListener("beforeunload", listener) }
}
