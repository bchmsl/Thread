package thread.web.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import thread.domain.account.AccountException
import thread.domain.account.AccountSession
import thread.web.platform.isOnline
import thread.web.presentation.shell.BrandMark

/**
 * Sign-in and first run (v3 screen 1, pages/index.js). Google sign-in only (ADR-005). There is no "Skip for now":
 * the app needs an account (O10, queued as Q4). The prototype's "Jump to a screen" list is for review only.
 */
@Composable
internal fun SignInScreen(
    account: AccountSession,
    usesEmulators: Boolean,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    Div({ classes("app") }) {
        Main({
            id("main")
            tabIndex(-1)
        }) {
            Div({ classes("signin") }) {
                Section({ attr("aria-labelledby", "si-h") }) {
                    P({ classes("brandbig") }) {
                        BrandMark(34, 10)
                        Text("Thread")
                        if (usesEmulators) {
                            Text(" ")
                            Span({ classes("tag") }) { Text("Test mode") }
                        }
                    }
                    H1({
                        classes("signin__h")
                        id("si-h")
                    }) { Text("Sign in to your workspace") }
                    P({ classes("signin__p") }) {
                        Text("Topics, tasks, notes, Jira tickets and pull requests in one place.")
                    }
                    problem?.let { text ->
                        Div({
                            classes("signin__state")
                            attr("role", "alert")
                        }) {
                            IconView(Icon.Alert, 18)
                            Span { Text(text) }
                        }
                    }
                    Div({ classes("signin__acts") }) {
                        Button({
                            type(ButtonType.Button)
                            classes("btn", "btn--primary")
                            if (busy) attr("disabled", "")
                            onClick {
                                if (!isOnline()) {
                                    problem = "You are offline. Signing in needs a connection the first time."
                                    return@onClick
                                }
                                busy = true
                                problem = null
                                scope.launch {
                                    try {
                                        account.signIn()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: AccountException) {
                                        problem = (e.message ?: "Sign-in failed") + ". Try again."
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        }) {
                            IconView(Icon.Signin, 18)
                            Text(if (busy) " Signing in…" else " Sign in with Google")
                        }
                    }
                    P({ classes("safe") }) {
                        IconView(Icon.Lock, 16)
                        Span { Text("Google handles sign-in. Thread never sees your Google password.") }
                    }
                    if (usesEmulators) {
                        P({ classes("safe") }) {
                            IconView(Icon.Flask, 16)
                            Span {
                                Text(
                                    "Test mode: sign-in and your data use the local Firebase emulators on this Mac. " +
                                        "Nothing goes to the cloud.",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
