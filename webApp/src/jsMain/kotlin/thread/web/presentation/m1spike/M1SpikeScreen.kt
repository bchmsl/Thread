package thread.web.presentation.m1spike

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.CheckboxInput
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import thread.domain.account.AccountException
import thread.domain.account.AccountId
import thread.domain.account.AccountSession
import thread.domain.account.AccountState
import thread.domain.note.LocalDraft
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.ToastState

internal sealed interface SpikeLoad {
    data object Loading : SpikeLoad

    data class Ready(
        val draft: LocalDraft,
        val isStored: Boolean,
    ) : SpikeLoad

    data class Failed(
        val reason: String,
    ) : SpikeLoad
}

/**
 * The M1 spike route: one Note on the v3 dot-grid page, edited with the note editor, saved on this device and, when
 * signed in, synced through the local Firebase emulators (adr/ADR-004-note-revisions-and-conflicts.md). Not a real
 * screen: no Notes index or Topics.
 */
@Composable
internal fun M1SpikeScreen(
    services: SpikeServices,
    testHooks: SpikeTestHooks,
    toasts: ToastState,
) {
    val account by services.account.state.collectAsState()
    // As in v3, the compare view takes the whole pane, so the spike's own header and controls step aside.
    var comparing by remember { mutableStateOf(false) }
    val onCompareShown = { shown: Boolean -> comparing = shown }

    Section({ classes("npane", "spike") }) {
        if (!comparing) {
            Div({ classes("spike__head") }) {
                A("#/", { classes("back") }) {
                    IconView(Icon.ChevLeft, 16)
                    Text("Home")
                }
                H1({ classes("ph__title") }) { Text("M1 spike") }
                P({ classes("ph__sub") }) {
                    Text("Editor, local drafts and sync. One synthetic note. Sync uses only the local Firebase emulators.")
                }
            }
            AccountBar(account, services.account, toasts)
        }
        when (val current = account) {
            AccountState.Checking -> P({ classes("pagebar", "muted") }) { Text("Checking sign-in…") }
            AccountState.SignedOut -> key("signed-out") { NoteLoader(services, null, toasts, onCompareShown) }
            // A new account gets its own drafts, and its own sync, from the start.
            is AccountState.SignedIn -> key(current.accountId) { NoteLoader(services, current.accountId, toasts, onCompareShown) }
        }
        if (!comparing) TestHookBar(testHooks, signedOut = account == AccountState.SignedOut, toasts)
    }
}

@Composable
private fun NoteLoader(
    services: SpikeServices,
    accountId: AccountId?,
    toasts: ToastState,
    onCompareShown: (Boolean) -> Unit,
) {
    val store = remember { services.draftStore(accountId) }
    var attempt by remember { mutableStateOf(0) }
    var load by remember { mutableStateOf<SpikeLoad>(SpikeLoad.Loading) }
    LaunchedEffect(attempt) {
        load = SpikeLoad.Loading
        load =
            try {
                val stored = store.read(SPIKE_NOTE_ID)
                if (stored != null) SpikeLoad.Ready(stored, isStored = true) else SpikeLoad.Ready(syntheticSpikeNote(), isStored = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SpikeLoad.Failed(e.message ?: "unknown error")
            }
    }
    when (val current = load) {
        SpikeLoad.Loading -> P({ classes("pagebar", "muted") }) { Text("Opening the note…") }
        is SpikeLoad.Failed -> LoadFailedBanner(current.reason) { attempt++ }
        is SpikeLoad.Ready -> key(attempt) { NoteSession(services, accountId, store, current, toasts, onCompareShown) }
    }
}

@Composable
private fun AccountBar(
    state: AccountState,
    session: AccountSession,
    toasts: ToastState,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    fun run(action: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                action()
            } catch (e: AccountException) {
                toasts.show("${e.message}. Nothing changed.")
            } finally {
                busy = false
            }
        }
    }

    when (state) {
        AccountState.Checking -> Unit
        AccountState.SignedOut ->
            Div({ classes("acct") }) {
                IconView(Icon.Device, 18)
                // The Local only banner below says what that means for the note.
                Span({ classes("acct__text") }) { Text("Not signed in.") }
                Button({
                    classes("btn", "btn--primary", "btn--sm")
                    type(ButtonType.Button)
                    if (busy) disabled()
                    onClick { run { session.signIn() } }
                }) { Text("Sign in with Google") }
            }
        is AccountState.SignedIn ->
            Div({ classes("acct") }) {
                IconView(Icon.User, 18)
                Span({ classes("acct__text") }) { Text("Signed in as ${state.label ?: "your Google account"}.") }
                Button({
                    classes("btn", "btn--text", "btn--sm")
                    type(ButtonType.Button)
                    if (busy) disabled()
                    onClick { run { session.signOut() } }
                }) { Text("Sign out") }
            }
    }
}

@Composable
private fun LoadFailedBanner(
    reason: String,
    onRetry: () -> Unit,
) {
    Banner(
        tone = BannerTone.Bad,
        icon = Icon.Alert,
        title = "Could not open this note on this device",
        text = "Local storage did not answer ($reason). Nothing was changed.",
    ) {
        BannerButton(Icon.Refresh, "Try again", onRetry)
    }
}

/** Prototype-only controls under the page, styled like the v3 demo bar. */
@Composable
private fun TestHookBar(
    testHooks: SpikeTestHooks,
    signedOut: Boolean,
    toasts: ToastState,
) {
    val scope = rememberCoroutineScope()
    Div({ classes("pagedemo") }) {
        Div({
            classes("demo")
            attr("role", "group")
            attr("aria-label", "Test hooks")
        }) {
            Span({ classes("demo__tag") }) {
                IconView(Icon.Flask, 14)
                Text("Test hook")
            }
            HookSwitch("fail-local-saves", "Make local saves fail", testHooks.failLocalSaves) { testHooks.failLocalSaves = it }
            HookSwitch("fail-server-writes", "Make the server refuse writes", testHooks.failServerWrites) {
                testHooks.failServerWrites = it
            }
            if (signedOut) {
                for (user in SYNTHETIC_USERS) {
                    Button({
                        classes("btn", "btn--line", "btn--sm")
                        id("sign-in-$user")
                        type(ButtonType.Button)
                        onClick {
                            scope.launch {
                                try {
                                    testHooks.signInAsSyntheticUser(user)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    toasts.show("Emulator sign-in failed. Is the Auth emulator running?")
                                }
                            }
                        }
                    }) { Text("Sign in as $user") }
                }
            }
        }
    }
}

@Composable
private fun HookSwitch(
    id: String,
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Label(attrs = { classes("demo__switch") }) {
        CheckboxInput(checked) {
            classes("switch")
            id(id)
            onInput { onChange(it.value) }
        }
        Text(label)
    }
}

/** SYNTHETIC emulator accounts for the test hook. Not real people. */
private val SYNTHETIC_USERS = listOf("synthetic-user-a", "synthetic-user-b")
