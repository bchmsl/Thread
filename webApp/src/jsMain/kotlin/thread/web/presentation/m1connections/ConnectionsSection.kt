package thread.web.presentation.m1connections

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.EmailInput
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.PasswordInput
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput
import thread.domain.external.ConnectionStoreException
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalResult
import thread.domain.external.GitHubConnection
import thread.domain.external.JiraCloudId
import thread.domain.external.JiraConnection
import thread.domain.external.JiraSite
import thread.domain.external.SecretText
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.ToastState

/** The id the "Go to Connections" buttons move focus to. */
internal const val CONNECTIONS_HEADING_ID = "connections-h"

/**
 * Connections (adr/ADR-008, P3-G1 decision 2): a spike-only token form. It deviates from v3 Settings, which plans
 * the provider's own sign-in (O9 stays open until Phase 4).
 *
 * Tokens go only into this browser profile's IndexedDB and only to the provider's API. The fields are password
 * inputs with autocomplete off; a saved token is never shown again or read back into a field.
 */
@Composable
internal fun ConnectionsSection(
    services: ConnectionsServices,
    connectionVersion: Int,
    toasts: ToastState,
    onChanged: () -> Unit,
) {
    var jira by remember(services) { mutableStateOf<JiraConnection?>(null) }
    var gitHub by remember(services) { mutableStateOf<GitHubConnection?>(null) }
    var loaded by remember(services) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(services, connectionVersion) {
        jira = orFallback(null) { services.connections.jira() }
        gitHub = orFallback(null) { services.connections.gitHub() }
        loaded = true
    }

    fun persist(
        done: String,
        action: suspend () -> Unit,
    ) {
        scope.launch {
            try {
                action()
                toasts.show(done)
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ConnectionStoreException) {
                toasts.show("Could not save on this device. Nothing changed.")
            } catch (e: Throwable) {
                toasts.show("Something went wrong on this device. Nothing changed.")
            }
        }
    }

    Section({
        classes("set", "xsec")
        id("connections")
        attr("aria-labelledby", CONNECTIONS_HEADING_ID)
    }) {
        Div {
            H2({
                classes("set__h")
                id(CONNECTIONS_HEADING_ID)
                tabIndex(-1)
            }) { Text("Connections") }
            P({ classes("set__p") }) { Text("Jira and GitHub are read-only in v1.") }
        }
        Div({ classes("set__body") }) {
            if (!loaded) {
                P({ classes("muted") }) { Text("Reading this device…") }
                return@Div
            }
            ConnectionRow(
                icon = Icon.Jira,
                name = services.jiraName,
                status = jira?.let { "Connected to ${it.site.host}, read-only" },
                what = "Tickets you open by key. Descriptions and comments are kept on this device only.",
                onDisconnect = {
                    persist("${services.jiraName} disconnected. Saved tickets hide until you connect again.") {
                        services.connections.clearJira()
                    }
                },
            )
            if (!services.isSynthetic) {
                JiraForm(jira, services, persist = ::persist)
            }
            ConnectionRow(
                icon = Icon.Pr,
                name = services.gitHubName,
                status = gitHub?.let { "Connected, read-only" },
                what = "Your open pull requests and review requests.",
                onDisconnect = {
                    persist("${services.gitHubName} disconnected. Saved pull requests hide until you connect again.") {
                        services.connections.clearGitHub()
                    }
                },
            )
            if (!services.isSynthetic) {
                GitHubForm(gitHub != null, persist = ::persist) { services.connections.saveGitHub(it) }
            }
            if (services.isSynthetic) {
                P({ classes("pref__hint") }) {
                    Text("Fixture mode: these SYNTHETIC connections live in memory only. Reloading the page restores them.")
                }
            }
            Div({ classes("conn") }) {
                Span({ classes("conn__icon") }) { IconView(Icon.Trash, 20) }
                Div({ classes("conn__body") }) {
                    P({ classes("conn__name") }) { Text("Saved copies") }
                    P({ classes("pref__hint") }) {
                        Text("Tickets and pull requests saved on this device for offline reading. Connections stay.")
                    }
                }
                Button({
                    classes("btn", "btn--text", "btn--danger-text")
                    type(ButtonType.Button)
                    id("delete-saved")
                    onClick { persist("Saved copies deleted from this device.") { services.deleteSavedCopies() } }
                }) { Text("Delete saved copies") }
            }
            P({ classes("callout") }) {
                IconView(Icon.Lock, 15)
                Text(
                    " Tokens stay in this browser profile (IndexedDB) and go only to Jira or GitHub. They are never synced, " +
                        "logged or sent to Thread's server. This token form is for the M1 probe only; how the real app " +
                        "connects is still open (O9).",
                )
            }
        }
    }
}

@Composable
private fun ConnectionRow(
    icon: Icon,
    name: String,
    status: String?,
    what: String,
    onDisconnect: () -> Unit,
) {
    Div({ classes("conn") }) {
        Span({ classes("conn__icon") }) { IconView(icon, 20) }
        Div({ classes("conn__body") }) {
            P({ classes("conn__name") }) { Text(name) }
            P({ classes("conn__st", *(if (status != null) arrayOf("is-on") else emptyArray())) }) {
                IconView(if (status != null) Icon.Check else Icon.Unlink, 14)
                Text(" " + (status ?: "Not connected"))
            }
            P({ classes("pref__hint") }) { Text(what) }
        }
        if (status != null) {
            Button({
                classes("btn", "btn--text", "btn--danger-text")
                type(ButtonType.Button)
                onClick { onDisconnect() }
            }) { Text("Disconnect") }
        }
    }
}

@Composable
private fun JiraForm(
    current: JiraConnection?,
    services: ConnectionsServices,
    persist: (String, suspend () -> Unit) -> Unit,
) {
    var site by remember(current) { mutableStateOf(current?.site?.host ?: "") }
    var cloudId by remember(current) { mutableStateOf(current?.cloudId?.value ?: "") }
    var email by remember(current) { mutableStateOf(current?.email ?: "") }
    var token by remember(current) { mutableStateOf("") }
    var errors by remember(current) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var lookUp by remember(current) { mutableStateOf<String?>(null) }
    var lookUpLink by remember(current) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Div({
        classes("cform")
        attr("role", "group")
        attr("aria-label", "Jira connection")
    }) {
        P({ classes("cform__h") }) { Text(if (current == null) "Connect Jira" else "Replace the Jira token") }
        Div({ classes("fgrid") }) {
            FormField("jira-site", "Site", errors["site"], "Your Jira Cloud host, such as example.atlassian.net.") {
                TextInput(site) {
                    classes("input")
                    id("jira-site")
                    attr("autocomplete", "off")
                    attr("spellcheck", "false")
                    fieldAttrs("jira-site", errors["site"])
                    onInput {
                        site = it.value
                        lookUp = null
                        lookUpLink = null
                    }
                }
            }
            FormField("jira-cloud", "Cloud ID", errors["cloud"], lookUp ?: "Scoped tokens call Jira through this id.") {
                Div({ classes("cform__row") }) {
                    TextInput(cloudId) {
                        classes("input")
                        id("jira-cloud")
                        attr("autocomplete", "off")
                        attr("spellcheck", "false")
                        fieldAttrs("jira-cloud", errors["cloud"])
                        onInput { cloudId = it.value }
                    }
                    Button({
                        classes("btn", "btn--line", "btn--sm")
                        type(ButtonType.Button)
                        id("jira-lookup")
                        if (busy) disabled()
                        onClick {
                            val parsed = JiraSite.parse(site)
                            if (parsed == null) {
                                errors = mapOf("site" to "Type a host that ends with .atlassian.net.")
                                return@onClick
                            }
                            busy = true
                            scope.launch {
                                try {
                                    when (val result = services.lookUpCloudId(parsed)) {
                                        is ExternalResult.Ok -> {
                                            cloudId = result.value.value
                                            lookUp = "Found the cloud ID of ${parsed.host}."
                                            lookUpLink = null
                                        }
                                        is ExternalResult.Problem -> {
                                            lookUp = lookUpProblem(result.problem)
                                            lookUpLink = "https://${parsed.host}/_edge/tenant_info"
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    lookUp = "The look-up failed in this browser. Type the cloud ID instead."
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    }) { Text("Look up") }
                }
                lookUpLink?.let { href ->
                    A(href, {
                        classes("lnk", "field__hint")
                        target(ATarget.Blank)
                        attr("rel", "noopener noreferrer")
                    }) {
                        IconView(Icon.External, 14)
                        Text(" Open the site's tenant info in a new tab and copy cloudId")
                    }
                }
            }
            FormField("jira-email", "Account e-mail", errors["email"], "The Atlassian account the token belongs to.") {
                EmailInput(email) {
                    classes("input")
                    id("jira-email")
                    attr("autocomplete", "off")
                    attr("spellcheck", "false")
                    fieldAttrs("jira-email", errors["email"])
                    onInput { email = it.value }
                }
            }
            FormField("jira-token", "API token", errors["token"], "A scoped, read-only token. It is never shown again after saving.") {
                PasswordInput(token) {
                    classes("input")
                    id("jira-token")
                    attr("autocomplete", "off")
                    attr("spellcheck", "false")
                    fieldAttrs("jira-token", errors["token"])
                    onInput { token = it.value }
                }
            }
        }
        Button({
            classes("btn", "btn--primary")
            type(ButtonType.Button)
            id("jira-save")
            onClick {
                val parsedSite = JiraSite.parse(site)
                val parsedCloud = JiraCloudId.parse(cloudId)
                val found = mutableMapOf<String, String>()
                if (parsedSite == null) found["site"] = "Type a host that ends with .atlassian.net."
                if (parsedCloud == null) found["cloud"] = "Type the cloud ID, a UUID, or use Look up."
                if (email.isBlank() || '@' !in email) found["email"] = "Type the account e-mail."
                if (token.isBlank()) found["token"] = "Paste the API token."
                errors = found
                if (found.isNotEmpty() || parsedSite == null || parsedCloud == null) return@onClick
                val connection = JiraConnection(parsedSite, parsedCloud, email.trim(), SecretText(token.trim()))
                token = ""
                persist("Jira connected, read-only. The token is saved in this browser profile only.") {
                    services.connections.saveJira(connection)
                }
            }
        }) { Text(if (current == null) "Save and connect" else "Save the new token") }
    }
}

private fun lookUpProblem(problem: ExternalProblem): String =
    when (problem) {
        ExternalProblem.Unreachable -> "The browser could not read the site's tenant info, probably blocked (CORS)."
        ExternalProblem.Offline -> "You are offline. Look up works when you are back online."
        ExternalProblem.NotFound -> "The site did not answer with a cloud ID. Check the host."
        else -> "The look-up failed. Type the cloud ID instead."
    }

@Composable
private fun GitHubForm(
    connected: Boolean,
    persist: (String, suspend () -> Unit) -> Unit,
    save: suspend (GitHubConnection) -> Unit,
) {
    var token by remember(connected) { mutableStateOf("") }
    var error by remember(connected) { mutableStateOf<String?>(null) }
    Div({
        classes("cform")
        attr("role", "group")
        attr("aria-label", "GitHub connection")
    }) {
        P({ classes("cform__h") }) { Text(if (connected) "Replace the GitHub token" else "Connect GitHub") }
        Div({ classes("fgrid") }) {
            FormField(
                "gh-token",
                "Fine-grained token",
                error,
                "Read-only: Metadata, Pull requests and Commit statuses, plus Checks if offered. Never shown again after saving.",
            ) {
                PasswordInput(token) {
                    classes("input")
                    id("gh-token")
                    attr("autocomplete", "off")
                    attr("spellcheck", "false")
                    fieldAttrs("gh-token", error)
                    onInput { token = it.value }
                }
            }
        }
        Button({
            classes("btn", "btn--primary")
            type(ButtonType.Button)
            id("gh-save")
            onClick {
                if (token.isBlank()) {
                    error = "Paste the token."
                    return@onClick
                }
                error = null
                val connection = GitHubConnection(SecretText(token.trim()))
                token = ""
                persist("GitHub connected, read-only. The token is saved in this browser profile only.") { save(connection) }
            }
        }) { Text(if (connected) "Save the new token" else "Save and connect") }
    }
}

@Composable
private fun FormField(
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
            classes("field__hint", *(if (error != null) arrayOf("field__err") else emptyArray()))
            id("$id-hint")
            attr("aria-live", "polite")
        }) { Text(error ?: hint) }
    }
}

private fun org.jetbrains.compose.web.attributes.AttrsScope<*>.fieldAttrs(
    id: String,
    error: String?,
) {
    attr("aria-describedby", "$id-hint")
    if (error != null) attr("aria-invalid", "true")
}
