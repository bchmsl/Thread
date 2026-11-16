package thread.web.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.PasswordInput
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import thread.domain.external.ConnectionSyncProblem
import thread.domain.external.ConnectionSyncState
import thread.domain.external.SecretText
import thread.domain.external.SyncPassphrase
import thread.web.presentation.ConfirmDialog
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalToasts
import thread.web.presentation.external.ConnectionSyncModel
import thread.web.presentation.tasks.focusById

private enum class SyncForm { TurnOn, Unlock, Change }

private class SyncStatus(
    val icon: Icon,
    val text: String,
    val tone: String?,
    val hint: String,
)

private const val FORGOT =
    "Forgot it? Thread cannot recover it. Turn sync off, connect Jira and GitHub again, then turn sync on with a new passphrase."

/**
 * "Sync across devices" in Settings > Connections (adr/ADR-016-encrypted-connection-sync.md). v3 has no such row, so it
 * follows the Jira and GitHub rows: a status line, its actions, and a form under the row. Passphrases go only into
 * [SecretText] and the fields are emptied on submit.
 */
@Composable
internal fun ConnectionSyncRow(sync: ConnectionSyncModel) {
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf<SyncForm?>(null) }
    var confirmingOff by remember { mutableStateOf(false) }
    // Opening Settings reads the account again, so a change from another device shows here.
    LaunchedEffect(sync) { sync.check() }
    val state = sync.state
    val problem = sync.problem
    val status = statusOf(sync.available, state, problem)
    val canTurnOff = state is ConnectionSyncState.On || state is ConnectionSyncState.Locked || problem == ConnectionSyncProblem.Damaged

    fun open(next: SyncForm) {
        form = if (form == next) null else next
        if (form != null) scope.launch { focusById(if (next == SyncForm.Change) "sync-current" else "sync-pass") }
    }

    fun closed(focusId: String) {
        form = null
        scope.launch {
            delay(16)
            focusById(focusId)
        }
    }

    Div({
        classes("conn")
        id("sync-row")
    }) {
        Span({ classes("conn__icon") }) { IconView(Icon.Cloud, 20) }
        Div({ classes("conn__body") }) {
            P({ classes("conn__name") }) { Text("Sync across devices") }
            P({
                classes("conn__st")
                status.tone?.let { classes(it) }
                id("sync-status")
                attr("aria-live", "polite")
            }) {
                IconView(status.icon, 14)
                Text(" ${status.text}")
            }
            P({ classes("pref__hint") }) { Text(status.hint) }
        }
        when {
            !sync.available || state == null && problem == null -> Unit
            problem == ConnectionSyncProblem.Damaged ->
                SyncButton("sync-repair", "Replace saved copy", primary = true, busy = sync.busy) {
                    scope.launch {
                        val failed = sync.replaceSaved()
                        toasts.show(
                            if (failed == null) {
                                "The saved copy now holds this browser's connections."
                            } else {
                                "The saved copy still cannot be replaced. Turn sync off, then turn it on again."
                            },
                        )
                    }
                }
            problem != null -> SyncButton("sync-check", "Check again", primary = false, busy = sync.busy) { scope.launch { sync.check() } }
            state == ConnectionSyncState.Off ->
                SyncButton("sync-turn-on", "Turn on", primary = true, busy = sync.busy, expanded = form == SyncForm.TurnOn) {
                    open(SyncForm.TurnOn)
                }
            state is ConnectionSyncState.Locked ->
                SyncButton("sync-unlock", "Unlock", primary = true, busy = sync.busy, expanded = form == SyncForm.Unlock) {
                    open(SyncForm.Unlock)
                }
            state is ConnectionSyncState.On ->
                SyncButton("sync-change", "Change passphrase", primary = false, busy = sync.busy, expanded = form == SyncForm.Change) {
                    open(SyncForm.Change)
                }
            else -> Unit
        }
        if (sync.available && canTurnOff) {
            Button({
                classes("btn", "btn--text", "btn--danger-text")
                type(ButtonType.Button)
                id("sync-turn-off")
                if (sync.busy) disabled()
                onClick { confirmingOff = true }
            }) { Text("Turn off") }
        }
    }
    // A form stays only while its state does: another device may change it meanwhile.
    val shown =
        form?.takeIf {
            problem == null &&
                when (it) {
                    SyncForm.TurnOn -> state == ConnectionSyncState.Off
                    SyncForm.Unlock -> state is ConnectionSyncState.Locked
                    SyncForm.Change -> state is ConnectionSyncState.On
                }
        }
    when (shown) {
        SyncForm.TurnOn -> TurnOnForm(sync) { closed("sync-change") }
        SyncForm.Unlock -> UnlockForm(sync) { closed("sync-change") }
        SyncForm.Change -> ChangeForm(sync) { closed("sync-change") }
        null -> Unit
    }
    if (confirmingOff) {
        ConfirmDialog(
            title = "Turn off sync?",
            body =
                "This deletes the encrypted copy from your account. Every browser keeps the connections it has now, " +
                    "but they stop syncing.",
            confirmLabel = "Turn off",
            danger = true,
        ) { ok ->
            confirmingOff = false
            if (!ok) return@ConfirmDialog
            scope.launch {
                val failed = sync.turnOff()
                form = null
                toasts.show(if (failed == null) "Sync is off. The encrypted copy is deleted from your account." else problemText(failed))
            }
        }
    }
}

@Composable
private fun SyncButton(
    id: String,
    label: String,
    primary: Boolean,
    busy: Boolean,
    expanded: Boolean? = null,
    onClick: () -> Unit,
) {
    Button({
        classes("btn", if (primary) "btn--line" else "btn--text")
        type(ButtonType.Button)
        id(id)
        if (expanded != null) {
            attr("aria-expanded", expanded.toString())
            attr("aria-controls", "sync-form")
        }
        if (busy) disabled()
        onClick { onClick() }
    }) { Text(label) }
}

private fun statusOf(
    available: Boolean,
    state: ConnectionSyncState?,
    problem: ConnectionSyncProblem?,
): SyncStatus =
    when {
        !available ->
            SyncStatus(Icon.CloudOff, "Not available in fixture mode", null, "SYNTHETIC connections stay in memory and never sync.")
        problem == ConnectionSyncProblem.Damaged ->
            SyncStatus(
                Icon.Alert,
                "The saved copy cannot be opened",
                "is-warn",
                "Connections in this browser did not change. Replace the saved copy with them, or turn sync off.",
            )
        problem == ConnectionSyncProblem.Refused ->
            SyncStatus(Icon.Alert, "The server refused to read the saved copy", "is-warn", "Sign out and in again, then check again.")
        problem == ConnectionSyncProblem.Device ->
            SyncStatus(Icon.Alert, "This browser's storage failed", "is-warn", "Connections here did not change. Check again.")
        problem == ConnectionSyncProblem.ChangedElsewhere ->
            SyncStatus(Icon.Alert, "Sync changed on another device meanwhile", "is-warn", "Check again to see the new state.")
        problem != null -> SyncStatus(Icon.Alert, "Could not check sync", "is-warn", "Connections here did not change. Check again.")
        state == null -> SyncStatus(Icon.Refresh, "Checking your account…", null, "Encrypted with a passphrase only you know.")
        state == ConnectionSyncState.Off ->
            SyncStatus(
                Icon.CloudOff,
                "Off",
                null,
                "Connections stay in this browser profile. Turn on to use them on your other devices with one passphrase.",
            )
        state is ConnectionSyncState.On && state.waiting ->
            SyncStatus(Icon.CloudUp, "On. A change here waits to be saved", null, "It saves when this browser is back online.")
        state is ConnectionSyncState.On ->
            SyncStatus(
                Icon.CloudCheck,
                "On. Saved to your account, encrypted",
                "is-on",
                "Only your passphrase opens it. A change here replaces the saved copy. " +
                    "Your other devices use it within seconds while Thread is open, or when they open it.",
            )
        state is ConnectionSyncState.Locked && state.passphraseChanged ->
            SyncStatus(Icon.Lock, "The passphrase changed on another device", "is-warn", "Unlock with the new one to keep syncing. $FORGOT")
        state is ConnectionSyncState.Locked ->
            SyncStatus(Icon.Lock, "Saved connections found", null, "Unlock them with your passphrase to use them here. $FORGOT")
        else -> SyncStatus(Icon.CloudOff, "Offline", null, "Whether sync is on shows when this browser is back online.")
    }

/** What went wrong, for a toast. Never names a passphrase or a token. */
private fun problemText(problem: ConnectionSyncProblem): String =
    when (problem) {
        ConnectionSyncProblem.TooShort -> "Use at least ${SyncPassphrase.MIN_LENGTH} characters."
        ConnectionSyncProblem.WrongPassphrase -> "That passphrase does not open the saved connections. Nothing changed."
        ConnectionSyncProblem.Offline -> "You are offline. Nothing changed."
        ConnectionSyncProblem.ChangedElsewhere -> "Sync changed on another device meanwhile. Nothing changed here."
        ConnectionSyncProblem.Damaged -> "The saved copy cannot be opened. Nothing changed here."
        ConnectionSyncProblem.Refused -> "The server refused the request. Nothing changed."
        ConnectionSyncProblem.Failed -> "The server did not finish the request. Nothing changed."
        ConnectionSyncProblem.Device -> "This browser's storage failed. Try again."
    }

@Composable
private fun TurnOnForm(
    sync: ConnectionSyncModel,
    onDone: () -> Unit,
) {
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    SyncFormFrame(
        label = "Turn on sync",
        heading = "Turn on sync",
        intro =
            "Your browser encrypts the Jira and GitHub connections with a key made from this passphrase. " +
                "Your account stores only the encrypted copy.",
        submitLabel = "Encrypt and turn on",
        busy = sync.busy,
        onCancel = {
            passphrase = ""
            again = ""
            onDone()
        },
        onSubmit = {
            val found = newPassphraseErrors("sync-pass", passphrase, "sync-pass-again", again)
            errors = found
            if (found.isNotEmpty()) {
                scope.launch { focusById(found.keys.first()) }
                return@SyncFormFrame
            }
            val secret = SecretText(passphrase)
            passphrase = ""
            again = ""
            scope.launch {
                when (val failed = sync.turnOn(secret)) {
                    null -> {
                        toasts.show("Sync is on. Your other devices can unlock the connections with this passphrase.")
                        onDone()
                    }
                    ConnectionSyncProblem.TooShort -> errors = mapOf("sync-pass" to problemText(failed))
                    else -> {
                        toasts.show(problemText(failed))
                        if (failed == ConnectionSyncProblem.ChangedElsewhere) onDone()
                    }
                }
            }
        },
    ) {
        Div({ classes("fgrid") }) {
            PassphraseField(
                "sync-pass",
                "Passphrase",
                errors["sync-pass"],
                "At least ${SyncPassphrase.MIN_LENGTH} characters. A few unrelated words work well.",
                passphrase,
                "new-password",
            ) {
                passphrase = it
            }
            PassphraseField(
                "sync-pass-again",
                "Type it again",
                errors["sync-pass-again"],
                "Thread cannot recover it. Keep it somewhere safe.",
                again,
                "new-password",
            ) {
                again = it
            }
        }
    }
}

@Composable
private fun UnlockForm(
    sync: ConnectionSyncModel,
    onDone: () -> Unit,
) {
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    SyncFormFrame(
        label = "Unlock saved connections",
        heading = "Unlock saved connections",
        intro = "This replaces the Jira and GitHub connections in this browser with the saved ones.",
        submitLabel = "Unlock and use here",
        busy = sync.busy,
        onCancel = {
            passphrase = ""
            onDone()
        },
        onSubmit = {
            if (passphrase.isEmpty()) {
                error = "Type the passphrase."
                scope.launch { focusById("sync-pass") }
                return@SyncFormFrame
            }
            error = null
            val secret = SecretText(passphrase)
            passphrase = ""
            scope.launch {
                when (val failed = sync.unlock(secret)) {
                    null -> {
                        toasts.show("Unlocked. This browser now uses the saved connections.")
                        onDone()
                    }
                    ConnectionSyncProblem.WrongPassphrase -> {
                        error = problemText(failed)
                        focusById("sync-pass")
                    }
                    else -> {
                        toasts.show(problemText(failed))
                        if (failed == ConnectionSyncProblem.ChangedElsewhere) onDone()
                    }
                }
            }
        },
    ) {
        Div({ classes("fgrid") }) {
            PassphraseField("sync-pass", "Passphrase", error, "The one you set when you turned sync on.", passphrase, "current-password") {
                passphrase = it
            }
        }
    }
}

@Composable
private fun ChangeForm(
    sync: ConnectionSyncModel,
    onDone: () -> Unit,
) {
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    fun clear() {
        current = ""
        passphrase = ""
        again = ""
    }
    SyncFormFrame(
        label = "Change the sync passphrase",
        heading = "Change the passphrase",
        intro = "Your other devices ask for the new passphrase once.",
        submitLabel = "Save new passphrase",
        busy = sync.busy,
        onCancel = {
            clear()
            onDone()
        },
        onSubmit = {
            val found =
                buildMap {
                    if (current.isEmpty()) put("sync-current", "Type the current passphrase.")
                    putAll(newPassphraseErrors("sync-pass", passphrase, "sync-pass-again", again))
                }
            errors = found
            if (found.isNotEmpty()) {
                scope.launch { focusById(found.keys.first()) }
                return@SyncFormFrame
            }
            val oldSecret = SecretText(current)
            val newSecret = SecretText(passphrase)
            clear()
            scope.launch {
                when (val failed = sync.changePassphrase(oldSecret, newSecret)) {
                    null -> {
                        toasts.show("Passphrase changed. Your other devices ask for the new one once.")
                        onDone()
                    }
                    ConnectionSyncProblem.WrongPassphrase -> {
                        errors = mapOf("sync-current" to "That is not the current passphrase. Nothing changed.")
                        focusById("sync-current")
                    }
                    ConnectionSyncProblem.TooShort -> errors = mapOf("sync-pass" to problemText(failed))
                    else -> {
                        toasts.show(problemText(failed))
                        if (failed == ConnectionSyncProblem.ChangedElsewhere) onDone()
                    }
                }
            }
        },
    ) {
        Div({ classes("fgrid") }) {
            PassphraseField(
                "sync-current",
                "Current passphrase",
                errors["sync-current"],
                "The one your devices use now.",
                current,
                "current-password",
            ) {
                current = it
            }
        }
        Div({ classes("fgrid") }) {
            PassphraseField(
                "sync-pass",
                "New passphrase",
                errors["sync-pass"],
                "At least ${SyncPassphrase.MIN_LENGTH} characters.",
                passphrase,
                "new-password",
            ) {
                passphrase = it
            }
            PassphraseField(
                "sync-pass-again",
                "Type the new one again",
                errors["sync-pass-again"],
                "Thread cannot recover it.",
                again,
                "new-password",
            ) {
                again = it
            }
        }
    }
}

private fun newPassphraseErrors(
    id: String,
    passphrase: String,
    againId: String,
    again: String,
): Map<String, String> =
    buildMap {
        if (passphrase.length < SyncPassphrase.MIN_LENGTH) put(id, "Use at least ${SyncPassphrase.MIN_LENGTH} characters.")
        if (again != passphrase) put(againId, "The two do not match.")
    }

@Composable
private fun SyncFormFrame(
    label: String,
    heading: String,
    intro: String,
    submitLabel: String,
    busy: Boolean,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
    fields: @Composable () -> Unit,
) {
    Form(attrs = {
        classes("cform")
        id("sync-form")
        attr("aria-label", label)
        onSubmit { event ->
            event.preventDefault()
            if (!busy) onSubmit()
        }
    }) {
        P({ classes("cform__h") }) { Text(heading) }
        P({ classes("pref__hint", "cform__intro") }) { Text(intro) }
        fields()
        Div({ classes("cform__acts") }) {
            Button({
                classes("btn", "btn--primary")
                type(ButtonType.Submit)
                id("sync-save")
                if (busy) disabled()
            }) { Text(if (busy) "Working…" else submitLabel) }
            Button({
                classes("btn", "btn--text")
                type(ButtonType.Button)
                onClick { onCancel() }
            }) { Text("Cancel") }
        }
    }
}

@Composable
private fun PassphraseField(
    id: String,
    label: String,
    error: String?,
    hint: String,
    value: String,
    autocomplete: String,
    onValue: (String) -> Unit,
) {
    FormField(id, label, error, hint) {
        PasswordInput(value) {
            classes("input")
            id(id)
            attr("autocomplete", autocomplete)
            attr("spellcheck", "false")
            attr("autocapitalize", "off")
            fieldAttrs(id, error)
            onInput { onValue(it.value) }
        }
    }
}
