package thread.web.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.EmailInput
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.PasswordInput
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput
import thread.domain.external.ConnectionStoreException
import thread.domain.external.ConnectionSyncState
import thread.domain.external.ExternalProblem
import thread.domain.external.GitHubConnection
import thread.domain.external.JiraCloudId
import thread.domain.external.JiraConnection
import thread.domain.external.JiraSite
import thread.domain.external.SecretText
import thread.web.platform.nowMillis
import thread.web.presentation.ConfirmDialog
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalToasts
import thread.web.presentation.external.ConnectionSyncModel
import thread.web.presentation.external.GitHubModel
import thread.web.presentation.external.JiraModel
import thread.web.presentation.external.orFallback
import thread.web.presentation.rememberOnline
import thread.web.presentation.tasks.focusById
import kotlin.math.ceil

/**
 * Settings "Connections" (v3 settings.js `connections`, adr/ADR-013-jira-in-the-workspace.md). Jira connects with a
 * token form instead of v3's provider sign-in (owner-review-queue Q3, provisional). The token goes only into this
 * browser profile and only to Jira; it is never shown again or read back into a field. GitHub connects the same way with
 * a fine-grained personal access token (adr/ADR-014-github-in-the-workspace.md). With sync on, every change here also
 * replaces the account's encrypted copy (adr/ADR-016-encrypted-connection-sync.md).
 */
@Composable
internal fun ConnectionsBlock(
    jira: JiraModel,
    gitHub: GitHubModel,
    sync: ConnectionSyncModel,
) {
    SettingsBlock("connections", "Connections", "Jira and GitHub are read-only in v1.") {
        if (!jira.checked || !gitHub.checked) {
            P({ classes("muted") }) { Text("Reading this device…") }
            return@SettingsBlock
        }
        JiraRow(jira, sync)
        GitHubRow(gitHub, sync)
        ConnectionSyncRow(sync)
        SavedCopiesRow(jira, gitHub)
        if (jira.isSynthetic || gitHub.isSynthetic) {
            P({ classes("pref__hint") }) {
                Text("Fixture mode: these SYNTHETIC connections live in memory only. Reloading the page restores them.")
            }
        }
        P({ classes("callout") }) {
            IconView(Icon.Lock, 15)
            Text(
                " Tokens go only to Jira or GitHub and never into logs. Without sync they stay in this browser profile. With " +
                    "sync on, your account stores only an encrypted copy that your passphrase opens. Use a read-only token.",
            )
        }
    }
}

@Composable
private fun JiraRow(
    jira: JiraModel,
    sync: ConnectionSyncModel,
) {
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val online by rememberOnline()
    var formOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    // Kept here, so the toggle cannot close the form while a save runs.
    var saving by remember { mutableStateOf(false) }
    val site = jira.site
    val problem = jira.problem
    val expired = site != null && problem == ExternalProblem.Unauthorized
    val (statusOn, statusIcon, status) =
        when {
            site == null -> Triple(false, Icon.Unlink, "Not connected")
            expired -> Triple(false, Icon.Lock, "Access expired. Replace the token to refresh.")
            !online -> Triple(true, Icon.CloudOff, "Connected, offline. Saved copies stay readable.")
            problem is ExternalProblem.RateLimited && problem.retryAtMillis > nowMillis() -> {
                val minutes = ceil((problem.retryAtMillis - nowMillis()) / 60_000.0).toInt().coerceAtLeast(1)
                val next = if (minutes == 1) "about a minute" else "about $minutes minutes"
                Triple(true, Icon.Clock, "Connected. Jira is limiting requests; next refresh in $next.")
            }
            problem == ExternalProblem.Forbidden ->
                Triple(
                    true,
                    Icon.Lock,
                    "Connected, but Jira refused a request. Check the token's permissions.",
                )
            else -> Triple(true, Icon.Check, "Connected to ${site.site.host}, read-only")
        }
    Div({ classes("conn") }) {
        Span({ classes("conn__icon") }) { IconView(Icon.Jira, 20) }
        Div({ classes("conn__body") }) {
            P({ classes("conn__name") }) { Text(jira.name) }
            P({
                classes("conn__st")
                if (statusOn) classes("is-on")
                id("jira-status")
            }) {
                IconView(statusIcon, 14)
                Text(" $status")
            }
            P({ classes("pref__hint") }) { Text("Assigned tickets and search. Descriptions and comments are kept on this device only.") }
        }
        Button({
            classes("btn", if (site == null || expired) "btn--line" else "btn--text")
            type(ButtonType.Button)
            id("jira-connect")
            attr("aria-expanded", formOpen.toString())
            attr("aria-controls", "jira-form")
            if (saving) disabled()
            onClick {
                formOpen = !formOpen
                if (formOpen) scope.launch { focusById("jira-site") }
            }
        }) {
            Text(
                when {
                    site == null -> "Connect"
                    expired -> "Reconnect"
                    else -> "Replace token"
                },
            )
        }
        if (site != null) {
            Button({
                classes("btn", "btn--text", "btn--danger-text")
                type(ButtonType.Button)
                id("jira-disconnect")
                onClick { confirming = true }
            }) { Text("Disconnect") }
        }
    }
    if (formOpen) {
        JiraForm(jira, sync, replacing = site != null, saving = saving, onSaving = { saving = it }) {
            formOpen = false
            scope.launch {
                delay(16)
                focusById("jira-connect")
            }
        }
    }
    if (confirming) {
        ConfirmDialog(
            title = "Disconnect ${jira.name}?",
            body = "Topic links stay. Jira lists and saved copies hide until you connect again.",
            confirmLabel = "Disconnect",
            danger = true,
        ) { ok ->
            confirming = false
            if (!ok) return@ConfirmDialog
            scope.launch {
                try {
                    val synced =
                        withContext(NonCancellable) {
                            jira.services.connections.clearJira()
                            sync.connectionsChanged().also {
                                formOpen = false
                                jira.connectionChanged()
                            }
                        }
                    toasts.show("${jira.name} disconnected${disconnectedWhere(sync, synced.await())}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    toasts.show("Could not change this device. Jira is still connected.")
                }
            }
        }
    }
}

@Composable
private fun JiraForm(
    jira: JiraModel,
    sync: ConnectionSyncModel,
    replacing: Boolean,
    saving: Boolean,
    onSaving: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    var site by remember { mutableStateOf("") }
    var cloudId by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // A new token for the same site: the site, Cloud ID and e-mail stay as saved. The token is never read back.
    LaunchedEffect(Unit) {
        orFallback(null) { jira.services.connections.jira() }?.let {
            site = it.site.host
            cloudId = it.cloudId.value
            email = it.email
        }
    }
    val tenantInfo = JiraSite.parse(site)?.let { "https://${it.host}/_edge/tenant_info" }
    Form(attrs = {
        classes("cform")
        id("jira-form")
        attr("aria-label", "Jira connection")
        attr("autocomplete", "off")
        onSubmit { event ->
            event.preventDefault()
            if (saving) return@onSubmit
            val parsedSite = JiraSite.parse(site)
            val parsedCloud = JiraCloudId.parse(cloudId)
            val found = mutableMapOf<String, String>()
            if (parsedSite == null) found["jira-site"] = "Type a host that ends with .atlassian.net."
            if (parsedCloud == null) found["jira-cloud"] = "Type the Cloud ID, a UUID."
            if (email.isBlank() || '@' !in email) found["jira-email"] = "Type the account e-mail."
            if (token.isBlank()) found["jira-token"] = "Paste the API token."
            errors = found
            if (parsedSite == null || parsedCloud == null || found.isNotEmpty()) {
                found.keys.firstOrNull()?.let { first -> scope.launch { focusById(first) } }
                return@onSubmit
            }
            val connection = JiraConnection(parsedSite, parsedCloud, email.trim(), SecretText(token.trim()))
            token = ""
            onSaving(true)
            scope.launch {
                try {
                    // Leaving the form or Settings cannot split this: the change is saved here, its sync started (in the
                    // workspace scope) and the Jira views told. Only the toast below needs the form.
                    val synced =
                        withContext(NonCancellable) {
                            jira.services.connections.saveJira(connection)
                            sync.connectionsChanged().also { jira.connectionChanged() }
                        }
                    toasts.show("${jira.name} connected, read-only. ${savedWhere(sync, synced.await())}")
                    onDone()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ConnectionStoreException) {
                    toasts.show("Could not save on this device. Nothing changed.")
                } catch (e: Throwable) {
                    toasts.show("Something went wrong on this device. Nothing changed.")
                } finally {
                    onSaving(false)
                }
            }
        }
    }) {
        P({ classes("cform__h") }) { Text(if (replacing) "Replace the Jira token" else "Connect Jira") }
        Div({ classes("fgrid") }) {
            FormField("jira-site", "Site", errors["jira-site"], "Your Jira Cloud host, such as example.atlassian.net.") {
                TextInput(site) {
                    classes("input")
                    id("jira-site")
                    attr("spellcheck", "false")
                    attr("autocapitalize", "off")
                    fieldAttrs("jira-site", errors["jira-site"])
                    onInput { site = it.value }
                }
            }
            FormField("jira-cloud", "Cloud ID", errors["jira-cloud"], "The cloudId value on the site's tenant info page.") {
                TextInput(cloudId) {
                    classes("input")
                    id("jira-cloud")
                    attr("spellcheck", "false")
                    attr("autocapitalize", "off")
                    fieldAttrs("jira-cloud", errors["jira-cloud"])
                    onInput { cloudId = it.value }
                }
                if (tenantInfo != null) {
                    A(tenantInfo, {
                        classes("lnk", "field__hint")
                        target(ATarget.Blank)
                        attr("rel", "noopener noreferrer")
                    }) {
                        IconView(Icon.External, 14)
                        Text(" Open the tenant info in a new tab")
                    }
                }
            }
            FormField("jira-email", "Account e-mail", errors["jira-email"], "The Atlassian account the token belongs to.") {
                EmailInput(email) {
                    classes("input")
                    id("jira-email")
                    attr("spellcheck", "false")
                    fieldAttrs("jira-email", errors["jira-email"])
                    onInput { email = it.value }
                }
            }
            FormField("jira-token", "API token", errors["jira-token"], "A read-only token. It is never shown again after saving.") {
                PasswordInput(token) {
                    classes("input")
                    id("jira-token")
                    attr("autocomplete", "off")
                    attr("spellcheck", "false")
                    fieldAttrs("jira-token", errors["jira-token"])
                    onInput { token = it.value }
                }
            }
        }
        Div({ classes("cform__acts") }) {
            Button({
                classes("btn", "btn--primary")
                type(ButtonType.Submit)
                id("jira-save")
                if (saving) disabled()
            }) { Text(if (replacing) "Save the new token" else "Save and connect") }
            Button({
                classes("btn", "btn--text")
                type(ButtonType.Button)
                if (saving) disabled()
                onClick {
                    token = ""
                    onDone()
                }
            }) { Text("Cancel") }
        }
    }
}

/** GitHub with a fine-grained personal access token: the same states as Jira (v3 settings.js `row('github')`). */
@Composable
private fun GitHubRow(
    gitHub: GitHubModel,
    sync: ConnectionSyncModel,
) {
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val online by rememberOnline()
    var formOpen by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    // Kept here, so the toggle cannot close the form while a save runs.
    var saving by remember { mutableStateOf(false) }
    val connected = gitHub.connected
    val problem = gitHub.problem
    val expired = connected && problem == ExternalProblem.Unauthorized
    val (statusOn, statusIcon, status) =
        when {
            !connected -> Triple(false, Icon.Unlink, "Not connected")
            expired -> Triple(false, Icon.Lock, "Access expired. Replace the token to refresh.")
            !online -> Triple(true, Icon.CloudOff, "Connected, offline. Saved copies stay readable.")
            problem is ExternalProblem.RateLimited && problem.retryAtMillis > nowMillis() -> {
                val minutes = ceil((problem.retryAtMillis - nowMillis()) / 60_000.0).toInt().coerceAtLeast(1)
                val next = if (minutes == 1) "about a minute" else "about $minutes minutes"
                Triple(true, Icon.Clock, "Connected. GitHub is limiting requests; next refresh in $next.")
            }
            problem == ExternalProblem.Forbidden ->
                Triple(true, Icon.Lock, "Connected, but GitHub refused a request. Check the token's permissions.")
            else -> Triple(true, Icon.Check, "Connected, read-only")
        }
    Div({ classes("conn") }) {
        Span({ classes("conn__icon") }) { IconView(Icon.Pr, 20) }
        Div({ classes("conn__body") }) {
            P({ classes("conn__name") }) { Text(gitHub.name) }
            P({
                classes("conn__st")
                if (statusOn) classes("is-on")
                id("github-status")
            }) {
                IconView(statusIcon, 14)
                Text(" $status")
            }
            P({ classes("pref__hint") }) { Text("Your open pull requests and review requests.") }
        }
        Button({
            classes("btn", if (!connected || expired) "btn--line" else "btn--text")
            type(ButtonType.Button)
            id("github-connect")
            attr("aria-expanded", formOpen.toString())
            attr("aria-controls", "github-form")
            if (saving) disabled()
            onClick {
                formOpen = !formOpen
                if (formOpen) scope.launch { focusById("github-token") }
            }
        }) {
            Text(
                when {
                    !connected -> "Connect"
                    expired -> "Reconnect"
                    else -> "Replace token"
                },
            )
        }
        if (connected) {
            Button({
                classes("btn", "btn--text", "btn--danger-text")
                type(ButtonType.Button)
                id("github-disconnect")
                onClick { confirming = true }
            }) { Text("Disconnect") }
        }
    }
    if (formOpen) {
        GitHubForm(gitHub, sync, replacing = connected, saving = saving, onSaving = { saving = it }) {
            formOpen = false
            scope.launch {
                delay(16)
                focusById("github-connect")
            }
        }
    }
    if (confirming) {
        ConfirmDialog(
            title = "Disconnect ${gitHub.name}?",
            body = "Topic links stay. GitHub lists and saved copies hide until you connect again.",
            confirmLabel = "Disconnect",
            danger = true,
        ) { ok ->
            confirming = false
            if (!ok) return@ConfirmDialog
            scope.launch {
                try {
                    val synced =
                        withContext(NonCancellable) {
                            gitHub.services.connections.clearGitHub()
                            sync.connectionsChanged().also {
                                formOpen = false
                                gitHub.connectionChanged()
                            }
                        }
                    toasts.show("${gitHub.name} disconnected${disconnectedWhere(sync, synced.await())}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    toasts.show("Could not change this device. GitHub is still connected.")
                }
            }
        }
    }
}

@Composable
private fun GitHubForm(
    gitHub: GitHubModel,
    sync: ConnectionSyncModel,
    replacing: Boolean,
    saving: Boolean,
    onSaving: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Form(attrs = {
        classes("cform")
        id("github-form")
        attr("aria-label", "GitHub connection")
        attr("autocomplete", "off")
        onSubmit { event ->
            event.preventDefault()
            if (saving) return@onSubmit
            if (token.isBlank()) {
                error = "Paste the token."
                scope.launch { focusById("github-token") }
                return@onSubmit
            }
            error = null
            val connection = GitHubConnection(SecretText(token.trim()))
            token = ""
            onSaving(true)
            scope.launch {
                try {
                    val synced =
                        withContext(NonCancellable) {
                            // As for Jira: leaving the form or Settings cannot split this.
                            gitHub.services.connections.saveGitHub(connection)
                            sync.connectionsChanged().also { gitHub.connectionChanged() }
                        }
                    toasts.show("${gitHub.name} connected, read-only. ${savedWhere(sync, synced.await())}")
                    onDone()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ConnectionStoreException) {
                    toasts.show("Could not save on this device. Nothing changed.")
                } catch (e: Throwable) {
                    toasts.show("Something went wrong on this device. Nothing changed.")
                } finally {
                    onSaving(false)
                }
            }
        }
    }) {
        P({ classes("cform__h") }) { Text(if (replacing) "Replace the GitHub token" else "Connect GitHub") }
        Div({ classes("fgrid") }) {
            FormField(
                "github-token",
                "Personal access token",
                error,
                "Fine-grained, read-only: Pull requests, Commit statuses and Checks. It is never shown again after saving.",
            ) {
                PasswordInput(token) {
                    classes("input")
                    id("github-token")
                    attr("autocomplete", "off")
                    attr("spellcheck", "false")
                    fieldAttrs("github-token", error)
                    onInput { token = it.value }
                }
            }
        }
        Div({ classes("cform__acts") }) {
            Button({
                classes("btn", "btn--primary")
                type(ButtonType.Submit)
                id("github-save")
                if (saving) disabled()
            }) { Text(if (replacing) "Save the new token" else "Save and connect") }
            Button({
                classes("btn", "btn--text")
                type(ButtonType.Button)
                if (saving) disabled()
                onClick {
                    token = ""
                    onDone()
                }
            }) { Text("Cancel") }
        }
    }
}

@Composable
private fun SavedCopiesRow(
    jira: JiraModel,
    gitHub: GitHubModel,
) {
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    Div({ classes("conn") }) {
        Span({ classes("conn__icon") }) { IconView(Icon.Device, 20) }
        Div({ classes("conn__body") }) {
            P({ classes("conn__name") }) { Text("Saved copies") }
            P({ classes("pref__hint") }) {
                Text(
                    "Tickets, pull requests and lists saved on this device for offline reading: " +
                        "${countOf(jira.saved.size, "ticket")} and ${countOf(gitHub.saved.size, "pull request")} saved. Connections stay.",
                )
            }
        }
        Button({
            classes("btn", "btn--text", "btn--danger-text")
            type(ButtonType.Button)
            id("delete-saved")
            onClick {
                scope.launch {
                    try {
                        jira.services.deleteSavedCopies()
                        gitHub.services.deleteSavedCopies()
                        jira.connectionChanged()
                        gitHub.connectionChanged()
                        toasts.show("Saved copies deleted from this device.")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        toasts.show("Could not delete the saved copies on this device.")
                    }
                }
            }
        }) { Text("Delete saved copies") }
    }
}

@Composable
internal fun FormField(
    id: String,
    label: String,
    error: String?,
    hint: String,
    input: @Composable () -> Unit,
) {
    Div({ classes("field") }) {
        Label(id, { classes("field__label") }) { Text(label) }
        input()
        P({
            classes("field__hint")
            if (error != null) classes("field__err")
            id("$id-hint")
        }) { Text(error ?: hint) }
    }
}

internal fun AttrsScope<*>.fieldAttrs(
    id: String,
    error: String?,
) {
    attr("aria-describedby", "$id-hint")
    if (error != null) attr("aria-invalid", "true")
}

/** Where a new token is saved, for the toast after connecting: [state] is what sync answered after the change. */
private fun savedWhere(
    sync: ConnectionSyncModel,
    state: ConnectionSyncState?,
): String =
    when {
        state == ConnectionSyncState.On(waiting = false) -> "The token is saved in this browser and, encrypted, to your account."
        state ==
            ConnectionSyncState.On(
                waiting = true,
            )
        -> "The token is saved in this browser. The encrypted copy updates when you are back online."
        state == null && sync.problem != null -> "The token is saved in this browser. Sync could not update the encrypted copy."
        else -> "The token is saved in this browser profile only."
    }

/** The end of the toast after a disconnect. */
private fun disconnectedWhere(
    sync: ConnectionSyncModel,
    state: ConnectionSyncState?,
): String =
    when {
        state == ConnectionSyncState.On(waiting = false) -> " here and in the encrypted copy."
        state == ConnectionSyncState.On(waiting = true) -> ". The encrypted copy updates when you are back online."
        state == null && sync.problem != null -> " here. Sync could not update the encrypted copy."
        else -> ""
    }

/** "1 ticket", "3 tickets". */
private fun countOf(
    count: Int,
    noun: String,
): String = if (count == 1) "1 $noun" else "$count ${noun}s"
