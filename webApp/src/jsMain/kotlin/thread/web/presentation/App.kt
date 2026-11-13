package thread.web.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import thread.domain.account.AccountSession
import thread.domain.account.AccountState
import thread.web.platform.installCaptureShortcut
import thread.web.platform.installSearchShortcut
import thread.web.presentation.settings.SettingsScreen
import thread.web.presentation.shell.CaptureDestination
import thread.web.presentation.shell.CaptureState
import thread.web.presentation.shell.CaptureType
import thread.web.presentation.shell.SearchOption
import thread.web.presentation.shell.SearchPalette
import thread.web.presentation.shell.StatusLine
import thread.web.presentation.shell.SyncPanel
import thread.web.presentation.shell.ViewsSheet
import thread.web.presentation.shell.focusStatusLine

/** What the app reads from the composition root (Main.kt). */
class AppServices(
    val account: AccountSession,
    val usesEmulators: Boolean,
)

@Composable
fun App(services: AppServices) {
    val accountState by services.account.state.collectAsState()
    val toasts = remember { ToastState() }
    val dialogs = remember { DialogStack() }
    val signInShown = accountState == AccountState.SignedOut
    SideEffect {
        // v3 styles the first-run page through body[data-page="index"].
        if (signInShown) document.body?.setAttribute("data-page", "index") else document.body?.removeAttribute("data-page")
    }
    CompositionLocalProvider(LocalToasts provides toasts, LocalDialogStack provides dialogs) {
        when (val state = accountState) {
            // Firebase restores the session from this device in a moment; nothing is drawn meanwhile.
            AccountState.Checking -> Unit
            AccountState.SignedOut -> {
                LaunchedEffect(Unit) { document.title = "Sign in | Thread" }
                SignInScreen(services.account, services.usesEmulators)
                if (dialogs.isEmpty) ToastHost(toasts)
            }
            // Everything below belongs to one account, so another account starts from scratch.
            is AccountState.SignedIn -> key(state.accountId) { Workspace(services, state) }
        }
    }
}

@Composable
private fun Workspace(
    services: AppServices,
    signedIn: AccountState.SignedIn,
) {
    val toasts = LocalToasts.current
    val dialogs = LocalDialogStack.current
    val router = remember { Router() }
    val syncCenter = remember { SyncCenter() }
    val capture = remember { CaptureState(CaptureType.Task) }
    val online by rememberOnline()
    var searchOpen by remember { mutableStateOf(false) }
    var syncOpen by remember { mutableStateOf(false) }
    var viewsOpen by remember { mutableStateOf(false) }

    DisposableEffect(router) {
        val stopRouter = router.start()
        // ⌘K toggles search everywhere, also inside the editor (D40).
        val stopSearch = installSearchShortcut { searchOpen = !searchOpen }
        val stopCapture = installCaptureShortcut { capture.requestFocus() }
        onDispose {
            stopRouter()
            stopSearch()
            stopCapture()
        }
    }

    val place = router.place
    if (place.path.isEmpty()) {
        SideEffect { router.replace(View.Today.place) }
    }
    val view = View.of(place)
    var firstPlace by remember { mutableStateOf(true) }
    LaunchedEffect(place) {
        document.title = view.label + " | Thread"
        // Announce a new view by moving focus to its title, but not on first load.
        if (firstPlace) {
            firstPlace = false
        } else if (!searchOpen && !viewsOpen) {
            (document.querySelector("main h1") as? HTMLElement)?.focus()
        }
    }
    val syncState = syncCenter.overall(online)

    A("#line", {
        classes("skip")
        onClick { event ->
            event.preventDefault()
            focusStatusLine()
        }
    }) { Text("Skip to workspace bar") }
    Div({ classes("app") }) {
        Div({ id("banner") })
        Main({
            id("main")
            tabIndex(-1)
        }) {
            when (view) {
                View.Settings ->
                    SettingsScreen(signedIn, services.account, services.usesEmulators, place.segment(1)) {}
                else -> NotBuiltYet(view)
            }
        }
    }
    StatusLine(
        current = view,
        inboxCount = 0,
        sync = syncText(syncState),
        capture = capture,
        destinations = emptyList<CaptureDestination>(),
        defaultDestination = null,
        onCapture = { _, title, _ ->
            if (title.isEmpty()) {
                toasts.show("Type a title first.")
            } else {
                toasts.show("Capture is not connected yet.")
            }
        },
        onOpenViews = { viewsOpen = true },
        onOpenSearch = { searchOpen = true },
        onOpenSync = { syncOpen = true },
    )
    if (dialogs.isEmpty) ToastHost(toasts)

    if (viewsOpen) {
        ViewsSheet(
            current = view,
            inboxCount = 0,
            onSearch = {
                viewsOpen = false
                searchOpen = true
            },
            onSync = {
                viewsOpen = false
                syncOpen = true
            },
            onClose = { viewsOpen = false },
        )
    }
    if (syncOpen) {
        SyncPanel(syncState, syncCenter.problems, services.usesEmulators) { syncOpen = false }
    }
    if (searchOpen) {
        SearchPalette(
            initialQuery = "",
            goTo =
                View.entries.map { target ->
                    SearchOption(target.label, "", { IconView(target.icon, 18) }) {
                        searchOpen = false
                        router.go(target.place)
                    }
                },
            search = { query ->
                val q = query.trim().lowercase()
                listOf(
                    thread.web.presentation.shell.SearchGroup(
                        "Go to",
                        View.entries.filter { it.label.lowercase().contains(q) }.map { target ->
                            SearchOption(target.label, "", { IconView(target.icon, 18) }) {
                                searchOpen = false
                                router.go(target.place)
                            }
                        },
                    ),
                )
            },
            noResults = { query -> NoSearchResults(query) },
            onQueryChange = {},
            onClose = { searchOpen = false },
        )
    }
}

@Composable
private fun NoSearchResults(query: String) {
    Div({ classes("pal__none") }) {
        org.jetbrains.compose.web.dom.P {
            org.jetbrains.compose.web.dom
                .B { Text("No local results for “$query”.") }
        }
    }
}

/** A view whose phase has not been built yet in this run. Replaced phase by phase. */
@Composable
private fun NotBuiltYet(view: View) {
    PageHeader(view.label)
    EmptyState(Icon.Sparkle, "Not built yet", "This view comes in a later phase of the build.")
}
