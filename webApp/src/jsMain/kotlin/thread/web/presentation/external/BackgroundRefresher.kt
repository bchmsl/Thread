package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.events.Event
import thread.domain.external.BackgroundRefresh
import thread.domain.external.ExternalProblem
import thread.domain.external.PullRequestList
import thread.web.platform.isOnline
import thread.web.platform.nowMillis

/**
 * For emulator checks only (`window.threadTest`): lets the background refresh run with the SYNTHETIC fixtures at
 * [intervalMillis], and records each refresh it starts as `provider:list:reason`. Main makes one only on the
 * emulators.
 */
class BackgroundRefreshProbe(
    val withFixtures: Boolean,
    val intervalMillis: Long,
) {
    val runs: MutableList<String> = mutableListOf()
}

/**
 * Keeps "Assigned to me" in Jira and both pull request lists current while Thread is open (D51), through the same
 * shared lists the pages show, so a page and the background never ask twice. It lives inside the signed-in workspace
 * and stops with it. When to ask is [BackgroundRefresh]; this only watches the clock, visibility and the network.
 */
@Composable
internal fun BackgroundRefresher(
    jira: JiraModel,
    gitHub: GitHubModel,
    probe: BackgroundRefreshProbe?,
) {
    val scope = rememberCoroutineScope()
    val refresher = remember(jira, gitHub) { Refresher(jira, gitHub, probe) }
    DisposableEffect(refresher) {
        val onVisibility: (Event) -> Unit = { if (isVisible()) scope.launch { refresher.check("visible") } }
        val onOnline: (Event) -> Unit = { scope.launch { refresher.check("online") } }
        document.addEventListener("visibilitychange", onVisibility)
        window.addEventListener("online", onOnline)
        onDispose {
            document.removeEventListener("visibilitychange", onVisibility)
            window.removeEventListener("online", onOnline)
        }
    }
    // Once the connections are read, and when the Jira list changes (Current sprint only), so a new list loads at once.
    LaunchedEffect(refresher, jira.checked, jira.connected, gitHub.checked, gitHub.connected, jira.assignedList) {
        refresher.check("start")
    }
    LaunchedEffect(refresher) {
        while (true) {
            delay(refresher.tickMillis())
            refresher.check("interval")
        }
    }
}

private fun isVisible(): Boolean = document.asDynamic().visibilityState == "visible"

private class Refresher(
    private val jira: JiraModel,
    private val gitHub: GitHubModel,
    private val probe: BackgroundRefreshProbe?,
) {
    private var checking = false

    private val intervalMillis: Long get() = probe?.intervalMillis ?: BackgroundRefresh.INTERVAL_MILLIS

    /** How often the clock is checked: often enough that a due list waits at most a quarter of the interval. */
    fun tickMillis(): Long = minOf(MAX_TICK_MILLIS, intervalMillis / 4).coerceAtLeast(MIN_TICK_MILLIS)

    suspend fun check(trigger: String) {
        if (checking) return
        checking = true
        try {
            checkJira(trigger)
            checkGitHub(trigger)
        } finally {
            checking = false
        }
    }

    private suspend fun checkJira(trigger: String) {
        if (!jira.checked) return
        val now = nowMillis()
        val conditions =
            conditions(jira.isSynthetic, jira.connected, jira.problem, jira.services.rateLimit.blockedUntil(now))
        if (!BackgroundRefresh.allowed(conditions, now)) return
        val before = jira.assignedList
        if (!due(conditions, jira.lists.lastAskedMillis(before), jira.lists.failures(before), now)) return
        // At most one `/field` request a day: whether the site has sprints decides which list is shown.
        jira.checkSprints()
        val key = jira.assignedList
        if (key != before && !due(conditions, jira.lists.lastAskedMillis(key), jira.lists.failures(key), now)) return
        record("jira", key.name, jira.lists.lastAskedMillis(key), trigger)
        jira.lists.load(key)
    }

    private fun checkGitHub(trigger: String) {
        if (!gitHub.checked) return
        val now = nowMillis()
        val conditions =
            conditions(gitHub.isSynthetic, gitHub.connected, gitHub.problem, gitHub.services.searchRateLimit.blockedUntil(now))
        PullRequestList.entries.forEach { list ->
            if (due(conditions, gitHub.lists.lastAskedMillis(list), gitHub.lists.failures(list), now)) {
                record("github", list.name, gitHub.lists.lastAskedMillis(list), trigger)
                gitHub.lists.load(list)
            }
        }
    }

    private fun conditions(
        synthetic: Boolean,
        connected: Boolean,
        problem: ExternalProblem?,
        blockedUntilMillis: Long?,
    ): BackgroundRefresh.Conditions =
        BackgroundRefresh.Conditions(
            // This runs only inside the signed-in workspace.
            signedIn = true,
            fixtures = synthetic && probe?.withFixtures != true,
            connected = connected,
            online = isOnline(),
            visible = isVisible(),
            accessExpired = problem == ExternalProblem.Unauthorized,
            blockedUntilMillis = blockedUntilMillis,
        )

    private fun due(
        conditions: BackgroundRefresh.Conditions,
        lastAskedMillis: Long?,
        failures: Int,
        now: Long,
    ): Boolean = BackgroundRefresh.isDue(conditions, lastAskedMillis, failures, now, intervalMillis)

    /** A list never asked in this page load starts with reason `start`, whatever woke the check. */
    private fun record(
        provider: String,
        list: String,
        lastAskedMillis: Long?,
        trigger: String,
    ) {
        probe?.runs?.add("$provider:$list:${if (lastAskedMillis == null) "start" else trigger}")
    }

    private companion object {
        const val MAX_TICK_MILLIS = 15_000L
        const val MIN_TICK_MILLIS = 250L
    }
}
