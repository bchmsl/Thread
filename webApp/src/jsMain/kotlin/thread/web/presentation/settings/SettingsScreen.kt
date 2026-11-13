package thread.web.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Text
import thread.domain.account.AccountException
import thread.domain.account.AccountSession
import thread.domain.account.AccountState
import thread.web.presentation.ConfirmDialog
import thread.web.presentation.LocalToasts
import thread.web.presentation.PageHeader

/** One Settings block (v3 settings.js `block`). */
@Composable
internal fun SettingsBlock(
    id: String,
    title: String,
    text: String?,
    body: @Composable () -> Unit,
) {
    Section({
        classes("set")
        id(id)
        attr("aria-labelledby", "$id-h")
    }) {
        Div {
            H2({
                classes("set__h")
                id("$id-h")
            }) { Text(title) }
            if (text != null) P({ classes("set__p") }) { Text(text) }
        }
        Div({ classes("set__body") }) { body() }
    }
}

/**
 * Settings (v3 settings.html). [section] scrolls to that block once, for deep links such as `#/settings/statuses`.
 * Later phases add Connections, Topic statuses and Preferences above Appearance and Account.
 */
@Composable
internal fun SettingsScreen(
    signedIn: AccountState.SignedIn,
    account: AccountSession,
    usesEmulators: Boolean,
    section: String?,
    blocks: @Composable () -> Unit,
) {
    PageHeader("Settings", "Applies to your workspace on every device, unless a block says this device.")
    blocks()
    SettingsBlock("appearance", "Appearance", null) {
        P({ classes("pref__label") }) { Text("Dark") }
        P({ classes("pref__hint") }) { Text("Dark is the only theme in the pilot.") }
    }
    SettingsBlock("account", "Account", null) { AccountBlock(signedIn, account, usesEmulators) }
    LaunchedEffect(section) {
        if (section != null) document.getElementById(section)?.asDynamic()?.scrollIntoView()
    }
}

@Composable
private fun AccountBlock(
    signedIn: AccountState.SignedIn,
    account: AccountSession,
    usesEmulators: Boolean,
) {
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    Div({ classes("pref") }) {
        Div {
            P({ classes("pref__label") }) { Text("Signed in as ${signedIn.label ?: "your Google account"}") }
            P({ classes("pref__hint") }) {
                Text(
                    if (usesEmulators) {
                        "Test mode: your data syncs to the local Firebase emulators on this Mac, not the cloud."
                    } else {
                        "Your Topics, Tasks and Notes sync to this account."
                    },
                )
            }
        }
        Button({
            type(ButtonType.Button)
            classes("btn", "btn--line")
            onClick { confirming = true }
        }) { Text("Sign out") }
    }
    if (confirming) {
        ConfirmDialog(
            title = "Sign out?",
            body = "Changes that have not synced yet stay on this device. They sync the next time you sign in with this account.",
            confirmLabel = "Sign out",
        ) { ok ->
            confirming = false
            if (ok) {
                scope.launch {
                    try {
                        account.signOut()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: AccountException) {
                        toasts.show((e.message ?: "Sign-out failed") + ". Try again.")
                    }
                }
            }
        }
    }
}
