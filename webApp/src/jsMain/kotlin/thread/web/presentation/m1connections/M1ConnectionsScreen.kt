package thread.web.presentation.m1connections

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.selected
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.CheckboxInput
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Th
import org.jetbrains.compose.web.dom.Thead
import org.jetbrains.compose.web.dom.Tr
import thread.domain.external.RequestLog
import thread.domain.external.RequestLogEntry
import thread.web.platform.copyText
import thread.web.platform.readSessionFlag
import thread.web.platform.writeSessionFlag
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.ToastState
import thread.web.presentation.m1spike.Banner
import thread.web.presentation.m1spike.BannerTone

/** sessionStorage flag of the fixture mode. Live is the default, so a new tab never shows fixtures by surprise. */
private const val FIXTURES_FLAG = "thread.m1c.fixtures"

/**
 * The M1-C probe page (Phase 3): Jira and GitHub, read-only, with saved copies on this device and a request log.
 * Not a v3 screen; it reuses the v3 external parts (Jira detail, pull requests, Connections rows) so the probe looks
 * like the product. In the fixture mode every answer is SYNTHETIC and nothing reaches Jira or GitHub.
 */
@Composable
internal fun M1ConnectionsScreen(
    spike: ConnectionsSpike,
    toasts: ToastState,
) {
    var synthetic by remember { mutableStateOf(readSessionFlag(FIXTURES_FLAG)) }
    var connectionVersion by remember { mutableStateOf(0) }
    val services = remember(synthetic) { spike.services(synthetic) }
    val goToConnections = { focusElement(CONNECTIONS_HEADING_ID) }

    Div({ classes("xpage") }) {
        Div({ classes("spike__head") }) {
            A("#/", { classes("back") }) {
                IconView(Icon.ChevLeft, 16)
                Text("Home")
            }
            H1({ classes("ph__title") }) { Text("M1 connections") }
            P({ classes("ph__sub") }) {
                Text("Jira and GitHub, read-only. Tokens and saved copies stay in this browser profile.")
            }
        }
        if (synthetic) {
            Banner(
                BannerTone.Info,
                Icon.Flask,
                "Synthetic fixtures",
                "Every answer on this page is SYNTHETIC test data. Nothing comes from Jira or GitHub, and outbound links are hidden.",
            )
        }
        key(synthetic) {
            ConnectionsSection(services, connectionVersion, toasts) { connectionVersion++ }
            JiraSection(services, connectionVersion, goToConnections)
            PullRequestSection(services, connectionVersion, goToConnections)
        }
        RequestLogSection(spike.requestLog, synthetic, toasts)
        HookBar(spike.hooks, synthetic) {
            writeSessionFlag(FIXTURES_FLAG, it)
            synthetic = it
        }
    }
}

/** Metadata of every request this page sent (J1, G3). No URL, token or provider content is ever in it. */
@Composable
private fun RequestLogSection(
    log: RequestLog,
    synthetic: Boolean,
    toasts: ToastState,
) {
    val entries by log.entries.collectAsState()
    Section({
        classes("xsec")
        id("log")
        attr("aria-labelledby", "log-h")
    }) {
        Div({ classes("xsec__head") }) {
            H2({
                classes("xsec__h")
                id("log-h")
            }) { Text("Request log") }
            P({ classes("ph__sub") }) {
                Text("Status, time and readable headers of each request in this page load. No URLs, tokens or content.")
            }
        }
        Div({ classes("xlog__acts") }) {
            Button({
                classes("btn", "btn--line", "btn--sm")
                type(ButtonType.Button)
                id("copy-log")
                onClick {
                    copyText(logMarkdown(entries, synthetic)) { ok ->
                        toasts.show(if (ok) "Results copied." else "The browser did not allow copying.")
                    }
                }
            }) {
                IconView(Icon.Copy, 15)
                Text("Copy results")
            }
            Button({
                classes("btn", "btn--text", "btn--sm")
                type(ButtonType.Button)
                onClick { log.clear() }
            }) { Text("Clear log") }
        }
        if (entries.isEmpty()) {
            P({ classes("muted") }) { Text("No requests yet.") }
            return@Section
        }
        Div({
            classes("xlog")
            attr("tabindex", "0")
            attr("role", "region")
            attr("aria-label", "Request log table")
        }) {
            Table {
                Thead {
                    Tr { COLUMNS.forEach { Th { Text(it) } } }
                }
                Tbody {
                    for (entry in entries.asReversed()) {
                        Tr {
                            cells(entry).forEachIndexed { index, value ->
                                Td({ if (index == STATUS_COLUMN && entry.status == null) classes("xlog__none") }) { Text(value) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val COLUMNS = listOf("Time", "Provider", "Request", "Status", "ms", "Online", "Readable headers")
private const val STATUS_COLUMN = 3

private fun cells(entry: RequestLogEntry): List<String> =
    listOf(
        Times.clockWithSeconds(entry.atMillis),
        entry.provider,
        entry.endpoint,
        entry.status?.toString() ?: "No answer",
        entry.durationMillis.toString(),
        if (entry.onlineAtStart) "yes" else "no",
        headers(entry),
    )

private fun headers(entry: RequestLogEntry): String =
    entry.readableHeaders
        .joinToString(", ") { name ->
            entry.headerValues.firstOrNull { it.first == name }?.let { "$name=${it.second}" } ?: name
        }.ifEmpty { "None" }

/** The log as a Markdown table, to paste into adr/M1-RESULTS.md. Oldest first. */
private fun logMarkdown(
    entries: List<RequestLogEntry>,
    synthetic: Boolean,
): String {
    val mode = if (synthetic) "SYNTHETIC fixtures" else "live"
    val head = "Thread M1-C request log. Origin ${window.location.origin}. Mode: $mode. Browser: ${window.navigator.userAgent}"
    val rows = entries.map { entry -> "| " + cells(entry).joinToString(" | ") { it.replace("|", "\\|") } + " |" }
    return (
        listOf(head, "", "| " + COLUMNS.joinToString(" | ") + " |", "|" + COLUMNS.joinToString("|") { " --- " } + "|") +
            rows
    ).joinToString("\n")
}

/** Prototype-only controls under the page, styled like the v3 demo bar. */
@Composable
private fun HookBar(
    hooks: ConnectionsTestHooks,
    synthetic: Boolean,
    onSynthetic: (Boolean) -> Unit,
) {
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
            Label(attrs = { classes("demo__switch") }) {
                CheckboxInput(synthetic) {
                    classes("switch")
                    id("use-fixtures")
                    onInput { onSynthetic(it.value) }
                }
                Text("Use synthetic fixtures")
            }
            if (synthetic) {
                Label("fixture-scenario", { classes("demo__switch") }) { Text("Answers") }
                Select({
                    classes("input", "input--sm", "input--auto")
                    id("fixture-scenario")
                    onChange { event -> event.value?.toIntOrNull()?.let { hooks.scenario = it } }
                }) {
                    hooks.scenarios.forEachIndexed { index, label ->
                        Option(index.toString(), { if (index == hooks.scenario) selected() }) { Text(label) }
                    }
                }
            }
        }
    }
}
