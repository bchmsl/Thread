package thread.web.platform

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.AddEventListenerOptions
import org.w3c.dom.Element
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.pointerevents.PointerEvent
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min

/** One drag after it started (D99). Points are in window coordinates, as `clientX` and `clientY`. */
interface DragSession {
    /** The pointer is at ([x], [y]): it moved, or the page scrolled under it. */
    fun move(
        x: Double,
        y: Double,
    )

    /** The pointer was released at ([x], [y]). The session ends. */
    fun drop(
        x: Double,
        y: Double,
    )

    /** The drag was called off: Esc, `pointercancel`, the window losing focus or the page hiding. The session ends. */
    fun cancel()
}

/**
 * One pointer-events drag engine (D99), for mouse, pen and touch. It knows nothing about what is dragged.
 *
 * - A press on an element [pick] accepts can start a drag. [pick] gets the press's target and returns the element to
 *   drag, or null.
 * - Mouse and pen: the drag starts once the pointer moved [MOUSE_SLOP_PX], so a click is still a click.
 * - Touch: the drag starts after a press held [LONG_PRESS_MS] within [TOUCH_SLOP_PX]. A finger that moves further
 *   first scrolls the page as usual. Once the drag started, `touchmove` is cancelled so the page does not scroll, and
 *   the context menu is off while the finger is down.
 * - [start] makes the session when the drag starts, or returns null to not drag.
 * - While dragging, the page scrolls when the pointer is near the top or bottom of the area [scrollArea] gives
 *   ([autoScrollStep]), and the session hears about every scroll.
 * - Esc (only the drag: the key is marked handled), `pointercancel`, the window losing focus and the page hiding
 *   cancel the drag. The click a released drag would make is dropped.
 *
 * Returns a function that removes every listener and cancels a drag in progress.
 */
fun installPointerDrag(
    pick: (Element) -> Element?,
    start: (item: Element, x: Double, y: Double) -> DragSession?,
    scrollArea: () -> Pair<Double, Double>,
): () -> Unit {
    var gesture: Gesture? = null
    val active = AddEventListenerOptions(passive = false, capture = true)
    // Registered for the whole time, before any touch starts: iOS Safari cancels `touchmove` only from a listener that
    // was there when the touch began.
    val touchMove: (Event) -> Unit = { event -> if (gesture?.dragging == true && event.cancelable) event.preventDefault() }
    val contextMenu: (Event) -> Unit = { event -> if (gesture?.touch == true) event.preventDefault() }
    val pointerDown: (Event) -> Unit = down@{ event ->
        if (event !is PointerEvent || gesture != null || !event.isPrimary) return@down
        if (event.pointerType == "mouse" && event.button.toInt() != 0) return@down
        val target = event.target as? Element ?: return@down
        val item = pick(target) ?: return@down
        gesture = Gesture(event, item, start, scrollArea, onEnd = { gesture = null })
    }
    window.addEventListener("touchmove", touchMove, active)
    document.addEventListener("contextmenu", contextMenu, active)
    document.addEventListener("pointerdown", pointerDown)
    return {
        gesture?.cancel()
        window.removeEventListener("touchmove", touchMove, active)
        document.removeEventListener("contextmenu", contextMenu, active)
        document.removeEventListener("pointerdown", pointerDown)
    }
}

/** One press, from `pointerdown` until it is released or cancelled, and the drag it may start. */
private class Gesture(
    down: PointerEvent,
    private val item: Element,
    private val start: (Element, Double, Double) -> DragSession?,
    private val scrollArea: () -> Pair<Double, Double>,
    private val onEnd: () -> Unit,
) {
    private val pointerId = down.pointerId
    val touch = down.pointerType == "touch"
    private val startX = down.clientX.toDouble()
    private val startY = down.clientY.toDouble()
    private var x = startX
    private var y = startY
    private var session: DragSession? = null
    val dragging: Boolean get() = session != null
    private var pressTimer: Int? = null
    private var frame: Int? = null
    private var stopped = false
    private val capture = AddEventListenerOptions(capture = true)

    private val onMove: (Event) -> Unit = { event -> if (event is PointerEvent && event.pointerId == pointerId) moved(event) }
    private val onUp: (Event) -> Unit = { event ->
        if (event is PointerEvent && event.pointerId == pointerId) {
            val current = session
            if (current != null) {
                session = null
                current.drop(event.clientX.toDouble(), event.clientY.toDouble())
                dropNextClick()
            }
            finish()
        }
    }
    private val onPointerCancel: (Event) -> Unit = { event -> if (event is PointerEvent && event.pointerId == pointerId) cancel() }
    private val onKey: (Event) -> Unit = { event ->
        if (event is KeyboardEvent && event.key == "Escape" && !event.isComposing && session != null) {
            event.preventDefault()
            cancel()
        }
    }
    private val onBlur: (Event) -> Unit = { cancel() }
    private val onVisibility: (Event) -> Unit = { if (document.asDynamic().visibilityState == "hidden") cancel() }
    private val onScroll: (Event) -> Unit = { session?.move(x, y) }

    init {
        window.addEventListener("pointermove", onMove, capture)
        window.addEventListener("pointerup", onUp, capture)
        window.addEventListener("pointercancel", onPointerCancel, capture)
        if (touch) pressTimer = window.setTimeout({ begin() }, LONG_PRESS_MS)
    }

    private fun moved(event: PointerEvent) {
        x = event.clientX.toDouble()
        y = event.clientY.toDouble()
        val current = session
        if (current != null) {
            event.preventDefault()
            current.move(x, y)
            return
        }
        val distance = hypot(x - startX, y - startY)
        when {
            touch && distance > TOUCH_SLOP_PX -> finish()
            !touch && distance >= MOUSE_SLOP_PX -> begin()
        }
    }

    private fun begin() {
        pressTimer = null
        if (stopped || session != null) return
        val made = start(item, x, y)
        if (made == null) {
            finish()
            return
        }
        session = made
        if (!touch) runCatching { item.asDynamic().setPointerCapture(pointerId) }
        window.addEventListener("keydown", onKey, capture)
        window.addEventListener("blur", onBlur)
        document.addEventListener("visibilitychange", onVisibility)
        window.addEventListener("scroll", onScroll, capture)
        made.move(x, y)
        scrollFrame()
    }

    /** Scrolls the page while the pointer is near an edge of [scrollArea]; each scroll reaches the session as a move. */
    private fun scrollFrame() {
        frame =
            window.requestAnimationFrame {
                if (session != null) {
                    val (top, bottom) = scrollArea()
                    val step = autoScrollStep(y, top, bottom)
                    if (step != 0.0) window.scrollBy(0.0, step)
                    scrollFrame()
                }
            }
    }

    /** Calls the drag off, if one started, and ends the press. */
    fun cancel() {
        val current = session
        session = null
        current?.cancel()
        finish()
    }

    private fun finish() {
        if (stopped) return
        stop()
        onEnd()
    }

    private fun stop() {
        stopped = true
        pressTimer?.let { window.clearTimeout(it) }
        frame?.let { window.cancelAnimationFrame(it) }
        if (!touch) runCatching { item.asDynamic().releasePointerCapture(pointerId) }
        window.removeEventListener("pointermove", onMove, capture)
        window.removeEventListener("pointerup", onUp, capture)
        window.removeEventListener("pointercancel", onPointerCancel, capture)
        window.removeEventListener("keydown", onKey, capture)
        window.removeEventListener("blur", onBlur)
        document.removeEventListener("visibilitychange", onVisibility)
        window.removeEventListener("scroll", onScroll, capture)
    }
}

/** A released drag would also click where it ended. That one click is dropped. */
private fun dropNextClick() {
    val options = AddEventListenerOptions(capture = true, once = true)
    val swallow: (Event) -> Unit = { event ->
        event.preventDefault()
        event.stopPropagation()
    }
    window.addEventListener("click", swallow, options)
    window.setTimeout({ window.removeEventListener("click", swallow, options) }, 0)
}

/**
 * How far the page scrolls this frame while dragging at [y], in the area from [top] to [bottom] (window coordinates):
 * up (negative) within [AUTO_SCROLL_EDGE_PX] of the top, down within it of the bottom, faster nearer the edge and past
 * it, up to [AUTO_SCROLL_MAX_PX]. Zero elsewhere, and in an area too short for two edges.
 */
internal fun autoScrollStep(
    y: Double,
    top: Double,
    bottom: Double,
): Double {
    if (bottom - top < 2 * AUTO_SCROLL_EDGE_PX) return 0.0
    val depth =
        when {
            y < top + AUTO_SCROLL_EDGE_PX -> -(top + AUTO_SCROLL_EDGE_PX - y)
            y > bottom - AUTO_SCROLL_EDGE_PX -> y - (bottom - AUTO_SCROLL_EDGE_PX)
            else -> return 0.0
        }
    val speed = ceil(AUTO_SCROLL_MAX_PX * min(abs(depth), AUTO_SCROLL_EDGE_PX) / AUTO_SCROLL_EDGE_PX)
    return if (depth < 0) -speed else speed
}

/** A mouse or pen press becomes a drag after moving this far. */
internal const val MOUSE_SLOP_PX = 4.0

/** A touch press becomes a drag after this long, if it moved no further than [TOUCH_SLOP_PX]. */
internal const val LONG_PRESS_MS = 500
internal const val TOUCH_SLOP_PX = 8.0

/** Auto-scroll starts this near an edge, and goes up to this many pixels a frame. */
internal const val AUTO_SCROLL_EDGE_PX = 56.0
internal const val AUTO_SCROLL_MAX_PX = 16.0
