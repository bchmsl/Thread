package thread.web.platform

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * Marks the page `no-gutter` where the browser keeps no room for a scrollbar that takes room, although `html` asks
 * for `scrollbar-gutter: stable` (D76). Safari does this (D94): it computes the gutter but drops the room on a page
 * shorter than the window and under the page lock, so the page moved sideways by half a scrollbar. For a marked page,
 * app.css always shows the page's scrollbar and, under the lock, keeps its width free beside the page and the bars
 * fixed to the window's right edge.
 *
 * A hidden probe compares the room a short scroller keeps with the room its scrollbar takes (`.gutter-probe` in
 * app.css). Chrome and Firefox keep the room, and overlay scrollbars take none, so the page is never marked there.
 * Runs once at start, after the stylesheets have loaded.
 */
fun markMissingScrollbarGutter() {
    val body = document.body ?: return
    val probe = document.createElement("div") as HTMLElement
    probe.className = "gutter-probe"
    body.appendChild(probe)
    val kept = probe.clientWidth
    probe.classList.add("is-scroll")
    val scrolling = probe.clientWidth
    probe.remove()
    if (gutterMissing(kept, scrolling)) document.documentElement?.classList?.add(NO_GUTTER_CLASS)
}

/**
 * True when a scrollbar takes room ([scrolling] is narrower than the probe) that the stable gutter did not keep
 * ([kept] is wider). Equal widths mean the gutter is kept or the scrollbar is an overlay. A [scrolling] wider than
 * [kept] is a browser that hides scrollbars but keeps the gutter, as headless Chrome does.
 */
internal fun gutterMissing(
    kept: Int,
    scrolling: Int,
): Boolean = kept > scrolling

internal const val NO_GUTTER_CLASS = "no-gutter"
