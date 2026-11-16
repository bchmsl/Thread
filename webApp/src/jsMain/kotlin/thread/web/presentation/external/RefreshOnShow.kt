package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.events.Event
import thread.domain.external.BackgroundRefresh
import thread.domain.external.ExternalProblem
import thread.domain.external.JiraIssueKey
import thread.domain.external.PullRequestRef
import thread.domain.external.ShowRefresh
import thread.web.platform.isOnline
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.rememberOnline

/**
 * For emulator checks only (`window.threadTest`): lets refresh on show (D67) run with the SYNTHETIC fixtures, and
 * records each request it starts as `provider:ITEM+ITEM`. Main makes one only on the emulators.
 */
class ShowRefreshProbe(
    val withFixtures: Boolean,
) {
    val runs: MutableList<String> = mutableListOf()
}

/**
 * What [BackgroundRefresh] needs to know about one provider now. Used inside the signed-in workspace only. The
 * SYNTHETIC fixtures count as fixtures unless [fixturesAllowed] (an emulator check turned them on).
 */
internal fun providerConditions(
    synthetic: Boolean,
    fixturesAllowed: Boolean,
    connected: Boolean,
    problem: ExternalProblem?,
    blockedUntilMillis: Long?,
): BackgroundRefresh.Conditions =
    BackgroundRefresh.Conditions(
        signedIn = true,
        fixtures = synthetic && !fixturesAllowed,
        connected = connected,
        online = isOnline(),
        visible = isPageVisible(),
        accessExpired = problem == ExternalProblem.Unauthorized,
        blockedUntilMillis = blockedUntilMillis,
    )

internal fun isPageVisible(): Boolean = document.asDynamic().visibilityState == "visible"

/** Whether the page is visible now, updated when it is hidden or shown again. */
@Composable
private fun rememberPageVisible(): State<Boolean> {
    val visible = remember { mutableStateOf(isPageVisible()) }
    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = { visible.value = isPageVisible() }
        document.addEventListener("visibilitychange", listener)
        onDispose { document.removeEventListener("visibilitychange", listener) }
    }
    return visible
}

/**
 * Refresh on show (D67): while a screen shows [items] with their status, asks the provider about them through
 * [refresh]: when they first show, when the page is visible or online again, after a reconnect ([version]), and every
 * [JIRA_REFRESH_MILLIS] while they stay on screen. The copies on this device show meanwhile; answers update every
 * screen that shows the same item. [accessExpired] stops the five-minute tick after a 401, like detail views.
 */
@Composable
internal fun <K> RefreshOnShow(
    refresh: ShowRefresh<K>,
    items: List<K>,
    version: Int,
    accessExpired: () -> Boolean,
) {
    val online by rememberOnline()
    val visible by rememberPageVisible()
    val tick = rememberAutoRefresh(refresh, accessExpired)
    LaunchedEffect(refresh, items, version, online, visible, tick) {
        if (items.isNotEmpty()) refresh.keepShowing(items)
    }
}

/**
 * A row the provider did not show now: deleted, or not shared with this account (a Jira search cannot tell which).
 * The Topic link stays (D67).
 */
@Composable
internal fun GoneBadge() {
    Span({ classes("dl-badge") }) {
        IconView(Icon.Alert, 13)
        Text("Not found or no access")
    }
}

/** Keeps the rows of Jira tickets [keys] current while they show (D67). */
@Composable
internal fun RefreshJiraOnShow(
    jira: JiraModel,
    keys: List<JiraIssueKey>,
) {
    RefreshOnShow(jira.rowRefresh, keys, jira.version) { jira.problem == ExternalProblem.Unauthorized }
}

/** Keeps pull requests [refs], with their CI and reviews, current while they show (D67). */
@Composable
internal fun RefreshPullsOnShow(
    gitHub: GitHubModel,
    refs: List<PullRequestRef>,
) {
    RefreshOnShow(gitHub.pullRefresh, refs, gitHub.version) { gitHub.problem == ExternalProblem.Unauthorized }
}
