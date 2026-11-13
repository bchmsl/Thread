package thread.web.platform

import kotlinx.browser.window

/** Copies [text] to the clipboard. Calls [onResult] with false when the browser refuses or has no clipboard API. */
fun copyText(
    text: String,
    onResult: (Boolean) -> Unit,
) {
    val clipboard = window.navigator.asDynamic().clipboard
    if (clipboard == null || clipboard.writeText == null) {
        onResult(false)
        return
    }
    clipboard.writeText(text).then({ _: dynamic -> onResult(true) }, { _: dynamic -> onResult(false) })
}
