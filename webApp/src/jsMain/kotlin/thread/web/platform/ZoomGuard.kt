package thread.web.platform

import kotlinx.browser.window
import org.w3c.dom.AddEventListenerOptions
import org.w3c.dom.TouchEvent
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.WheelEvent

/**
 * Stops the page from zooming (D48). Together with the viewport meta in index.html (`maximum-scale=1,
 * user-scalable=no`) and `touch-action: pan-x pan-y` on `html` in app.css, this cancels every zoom a page can see:
 * - Ctrl or ⌘ with +, =, -, _ or 0, on the main keys or the number pad ([isZoomKey]);
 * - Ctrl or ⌘ with the mouse wheel, which is also how Chrome and Firefox report a trackpad pinch ([isZoomWheel]);
 * - Safari's `gesturestart`, `gesturechange` and `gestureend`, sent for a pinch on iOS and on a Mac trackpad;
 * - a two-finger `touchmove` that scales the page on iOS, which ignores `user-scalable=no` ([isPinchMove]).
 *
 * Page code cannot stop the browser's own menu zoom, the operating system's accessibility zoom, or a browser setting
 * that forces zoom on (such as Safari's "ignore zoom restrictions").
 *
 * Every listener is on `window` in the capture phase, so no field or the editor can stop the event first, and is not
 * passive, so it may cancel the event. It never stops the event, so the shortcuts in GlobalShortcuts.kt and the
 * editor still see every key. Returns a function that removes the listeners.
 */
fun installZoomGuard(): () -> Unit {
    val options = AddEventListenerOptions(passive = false, capture = true)
    val cancel: (Event) -> Unit = { it.preventDefault() }
    val listeners =
        listOf<Pair<String, (Event) -> Unit>>(
            "keydown" to { event ->
                if (event is KeyboardEvent && isZoomKeyEvent(event)) event.preventDefault()
            },
            "wheel" to { event ->
                if (event is WheelEvent && isZoomWheel(ctrl = event.ctrlKey, meta = event.metaKey)) event.preventDefault()
            },
            "gesturestart" to cancel,
            "gesturechange" to cancel,
            "gestureend" to cancel,
            "touchmove" to { event ->
                if (event is TouchEvent && isPinchMove(event.touches.length, event.asDynamic().scale as? Double)) {
                    event.preventDefault()
                }
            },
        )
    listeners.forEach { (type, listener) -> window.addEventListener(type, listener, options) }
    return { listeners.forEach { (type, listener) -> window.removeEventListener(type, listener, options) } }
}

/** Keys that are part of an IME composition never count. */
private fun isZoomKeyEvent(event: KeyboardEvent): Boolean {
    if (event.isComposing || event.keyCode == IME_PROCESS_KEY_CODE) return false
    return isZoomKey(event.key, event.code, ctrlOrMeta = event.ctrlKey || event.metaKey, alt = event.altKey)
}

/**
 * True for a key that zooms the page: Ctrl or ⌘ with +, =, -, _ or 0. The key is matched by character and by physical
 * key, so the number pad and layouts that type another character on those keys are covered. Shift is allowed
 * (Ctrl+Shift+= is Ctrl++). Alt is not: on Windows AltGr reports Ctrl+Alt, and AltGr+0 types "}" on a German keyboard.
 */
internal fun isZoomKey(
    key: String,
    code: String,
    ctrlOrMeta: Boolean,
    alt: Boolean,
): Boolean = ctrlOrMeta && !alt && (key in ZOOM_KEYS || code in ZOOM_CODES)

/**
 * True for a wheel event that zooms the page. Chrome and Firefox zoom on Ctrl+wheel and report a trackpad pinch as a
 * wheel event with Ctrl set. Firefox on macOS also zooms on ⌘+wheel.
 */
internal fun isZoomWheel(
    ctrl: Boolean,
    meta: Boolean,
): Boolean = ctrl || meta

/**
 * True for a `touchmove` that pinches the page on iOS: two or more fingers and a `scale` other than 1. [scale] is
 * null where the browser does not report it; those browsers honour the viewport meta and `touch-action`, so a
 * two-finger scroll there is left alone.
 */
internal fun isPinchMove(
    touchCount: Int,
    scale: Double?,
): Boolean = touchCount > 1 && scale != null && scale != 1.0

private val ZOOM_KEYS = setOf("+", "=", "-", "_", "0")
private val ZOOM_CODES = setOf("Equal", "Minus", "Digit0", "NumpadAdd", "NumpadSubtract", "Numpad0")
