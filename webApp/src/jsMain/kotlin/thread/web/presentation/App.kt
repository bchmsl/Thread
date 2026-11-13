package thread.web.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.events.Event
import thread.web.platform.installSearchShortcut
import thread.web.presentation.m1spike.M1SpikeScreen
import thread.web.presentation.m1spike.SpikeServices
import thread.web.presentation.m1spike.SpikeTestHooks

/** Hash routes. Real screens and routing come in later phases. */
internal enum class Route(
    val hash: String,
    val title: String,
) {
    Home("#/", "Thread"),
    M1Spike("#/m1-spike", "M1 spike | Thread"),
    ;

    companion object {
        fun fromHash(hash: String): Route = entries.firstOrNull { it.hash == hash } ?: Home
    }
}

@Composable
fun App(
    spikeServices: SpikeServices,
    spikeTestHooks: SpikeTestHooks,
) {
    var route by remember { mutableStateOf(Route.fromHash(window.location.hash)) }
    val toasts = remember { ToastState() }

    DisposableEffect(Unit) {
        val onHashChange: (Event) -> Unit = { route = Route.fromHash(window.location.hash) }
        window.addEventListener("hashchange", onHashChange)
        // Global search shortcut (D40). Search itself comes later, so the spike only confirms the key arrived.
        val removeSearchShortcut = installSearchShortcut { toasts.show("Search is not built yet. The shortcut reached the app.") }
        onDispose {
            window.removeEventListener("hashchange", onHashChange)
            removeSearchShortcut()
        }
    }
    LaunchedEffect(route) { document.title = route.title }

    Main({ id("main") }) {
        when (route) {
            Route.Home -> HomeScreen()
            Route.M1Spike -> M1SpikeScreen(spikeServices, spikeTestHooks, toasts)
        }
    }
    ToastHost(toasts)
}

@Composable
private fun HomeScreen() {
    Div({ classes("ph") }) {
        Div {
            H1({ classes("ph__title") }) { Text("Thread") }
            P({ classes("ph__sub") }) { Text("Phase 2 build. The real screens come in later phases.") }
        }
    }
    A(Route.M1Spike.hash, { classes("btn", "btn--line") }) { Text("Open the M1 spike") }
}
