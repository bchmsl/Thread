package thread.web.platform

import kotlinx.browser.window

/** A yes/no setting of this tab only (sessionStorage). Reads false when the browser blocks storage. */
fun readSessionFlag(name: String): Boolean =
    try {
        window.sessionStorage.getItem(name) == "1"
    } catch (e: Throwable) {
        false
    }

fun writeSessionFlag(
    name: String,
    on: Boolean,
) {
    try {
        if (on) window.sessionStorage.setItem(name, "1") else window.sessionStorage.removeItem(name)
    } catch (e: Throwable) {
        // The flag only lasts for this page then.
    }
}

/** Milliseconds since the epoch, from the browser clock. */
fun nowMillis(): Long =
    kotlin.js.Date
        .now()
        .toLong()
