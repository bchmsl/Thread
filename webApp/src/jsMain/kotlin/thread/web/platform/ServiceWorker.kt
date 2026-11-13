package thread.web.platform

import kotlinx.browser.window
import org.w3c.dom.MessageChannel
import org.w3c.workers.ACTIVATED
import org.w3c.workers.ServiceWorker
import org.w3c.workers.ServiceWorkerState
import kotlin.js.json

/**
 * Registers the offline app shell worker (webApp/serviceWorker/sw.js, adr/ADR-008-offline-shell-and-token-storage.md).
 * Only the production distribution has the worker, so development builds skip this. Every load also asks the
 * browser to look for a new build, which installs in the background and waits. The app works the same without it.
 * The path is relative, so the worker's scope is the folder the app is served from: / on 127.0.0.1 and /Thread/ on
 * GitHub Pages.
 */
fun registerOfflineShell() {
    if (!isProductionBuild() || !hasServiceWorkers()) return
    window.navigator.serviceWorker
        .register("sw.js")
        .then { registration ->
            registration.waiting?.let(::takeOverIfAlone)
            registration.update().catch { }
        }.catch { }
}

/**
 * A build that installed during an earlier visit is [waiting]. If this is the only open Thread tab, it takes over and
 * the page reloads once, so the HTML, the script and the styles all come from the new build. With other tabs open,
 * nothing changes: every tab keeps the build it has until the others close.
 */
private fun takeOverIfAlone(waiting: ServiceWorker) {
    val channel = MessageChannel()
    channel.port1.onmessage = { event ->
        if (event.data == true) reloadWhenActive(waiting)
    }
    waiting.postMessage(json("type" to "TAKE_OVER_IF_ALONE"), arrayOf(channel.port2))
}

private fun reloadWhenActive(worker: ServiceWorker) {
    if (worker.state == ServiceWorkerState.ACTIVATED) {
        window.location.reload()
        return
    }
    worker.onstatechange = {
        if (worker.state == ServiceWorkerState.ACTIVATED) window.location.reload()
    }
}

// Webpack replaces process.env.NODE_ENV with the build mode, so this is a constant in the bundle.
private fun isProductionBuild(): Boolean = js("process.env.NODE_ENV") == "production"

// Browsers leave serviceWorker out on insecure origins. localhost and 127.0.0.1 count as secure.
private fun hasServiceWorkers(): Boolean = js("'serviceWorker' in navigator") as Boolean
