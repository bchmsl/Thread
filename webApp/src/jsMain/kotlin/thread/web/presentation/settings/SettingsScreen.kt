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
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import thread.domain.account.AccountException
import thread.domain.account.AccountSession
import thread.domain.account.AccountState
import thread.web.presentation.ConfirmDialog
import thread.web.presentation.LocalToasts
import thread.web.presentation.PageHeader
import thread.web.presentation.Place

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
            // Takes focus from script only, when a link at the top jumps here.
            H2({
                classes("set__h")
                id("$id-h")
                tabIndex(-1)
            }) { Text(title) }
            if (text != null) P({ classes("set__p") }) { Text(text) }
        }
        Div({ classes("set__body") }) { body() }
    }
}

/** The blocks in page order with their short names, for the row of links at the top (D84). */
private val SECTIONS =
    listOf(
        "connections" to "Connections",
        "calendar" to "Calendar",
        "statuses" to "Topic statuses",
        "today" to "Today",
        "topics" to "Topics",
        "jira" to "Jira",
        "general" to "General",
        "appearance" to "Appearance",
        "account" to "Account",
    )

/** The block a `#/settings/<segment>` link opens. The Preferences block from before D84 became Today and the rest. */
internal fun settingsSection(segment: String?): String? = if (segment == "prefs") "today" else segment

/**
 * Settings (v3 settings.html). [section] scrolls to that block once, for deep links such as `#/settings/statuses`.
 * A row of links at the top jumps to each block (D84).
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
    Nav({
        classes("set-jump")
        attr("aria-label", "Settings sections")
    }) {
        SECTIONS.forEach { (id, label) ->
            A(Place.of("settings", id).hash, {
                classes("chip")
                onClick { event ->
                    // A plain click stays on this page; a modified one opens the link as usual.
                    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return@onClick
                    event.preventDefault()
                    showSection(id)
                }
            }) { Text(label) }
        }
    }
    blocks()
    SettingsBlock("appearance", "Appearance", null) {
        P({ classes("pref__label") }) { Text("Dark") }
        P({ classes("pref__hint") }) { Text("Dark is the only theme in the pilot.") }
    }
    SettingsBlock("account", "Account", null) { AccountBlock(signedIn, account, usesEmulators) }
    val target = settingsSection(section)
    LaunchedEffect(target) {
        if (target != null) document.getElementById(target)?.asDynamic()?.scrollIntoView()
    }
}

/** Scrolls to block [id] and moves focus to its heading, so Tab continues inside it. */
private fun showSection(id: String) {
    document.getElementById(id)?.asDynamic()?.scrollIntoView()
    (document.getElementById("$id-h") as? HTMLElement)?.asDynamic()?.focus(js("({ preventScroll: true })"))
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
