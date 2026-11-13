package thread.web.platform

import kotlinx.browser.window
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
