package thread.web.platform

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/**
 * Listens for ⌘K (Mac) and Ctrl+K anywhere in the app, also inside the note editor and text fields (D40), and calls
 * [onSearch]. Returns a function that removes the listener.
 *
 * The listener runs in the capture phase on `window`, so it sees the key before the editor or any focused field can
 * handle or stop it. The key is matched by character, and by physical key when the layout types no Latin letter
 * (for example Georgian), so the shortcut works with every keyboard layout. Keys that are part of an IME composition
 * are ignored.
 */
fun installSearchShortcut(onSearch: () -> Unit): () -> Unit {
    val listener: (Event) -> Unit = { event ->
        if (event is KeyboardEvent && isSearchShortcut(event)) {
            event.preventDefault()
            event.stopPropagation()
            onSearch()
        }
    }
    window.addEventListener("keydown", listener, true)
    return { window.removeEventListener("keydown", listener, true) }
}

private fun isSearchShortcut(event: KeyboardEvent): Boolean {
    if (event.isComposing || event.keyCode == IME_PROCESS_KEY_CODE) return false
    if (!(event.metaKey || event.ctrlKey) || event.altKey || event.shiftKey) return false
    val key = event.key
    val latinLetter = key.length == 1 && key[0].lowercaseChar() in 'a'..'z'
    return if (latinLetter) key.equals("k", ignoreCase = true) else event.code == "KeyK"
}

/** keyCode browsers report for keys that an IME is processing. */
private const val IME_PROCESS_KEY_CODE = 229

/**
 * Listens for C, which focuses quick capture (v3 shell.js), and calls [onCapture]. Ignored while the person types in a
 * field or the editor, with a modifier key, or while a dialog is open. Matches the physical key when the layout
 * types no Latin letter. Returns a function that removes the listener.
 */
fun installCaptureShortcut(onCapture: () -> Unit): () -> Unit {
    val listener: (Event) -> Unit = { event ->
        if (event is KeyboardEvent && isCaptureShortcut(event)) {
            event.preventDefault()
            onCapture()
        }
    }
    document.addEventListener("keydown", listener)
    return { document.removeEventListener("keydown", listener) }
}

private fun isCaptureShortcut(event: KeyboardEvent): Boolean {
    if (event.isComposing || event.keyCode == IME_PROCESS_KEY_CODE) return false
    if (event.metaKey || event.ctrlKey || event.altKey) return false
    val key = event.key
    val latinLetter = key.length == 1 && key[0].lowercaseChar() in 'a'..'z'
    val isC = if (latinLetter) key.equals("c", ignoreCase = true) else event.code == "KeyC"
    if (!isC) return false
    val target = event.target as? Element
    if (target?.closest("input, textarea, select, [contenteditable]:not([contenteditable='false'])") != null) return false
    return document.querySelector("dialog[open]") == null
}
