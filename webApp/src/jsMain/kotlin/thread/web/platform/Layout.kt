package thread.web.platform

import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.events.Event

/** Calls [onResize] whenever [element] changes size. Returns a function that stops observing. */
fun observeSize(
    element: Element,
    onResize: () -> Unit,
): () -> Unit {
    val callback: () -> Unit = onResize
    val observer: dynamic = js("new ResizeObserver(callback)")
    observer.observe(element)
    return { observer.disconnect() }
}

/** Whether the CSS media [query] matches now. */
fun mediaMatches(query: String): Boolean = window.matchMedia(query).matches

/** Calls [onChange] whenever the CSS media [query] starts or stops matching. Returns a function that stops listening. */
fun observeMedia(
    query: String,
    onChange: (Boolean) -> Unit,
): () -> Unit {
    val list = window.matchMedia(query)
    val listener: (Event) -> Unit = { onChange(list.matches) }
    list.addEventListener("change", listener)
    return { list.removeEventListener("change", listener) }
}
