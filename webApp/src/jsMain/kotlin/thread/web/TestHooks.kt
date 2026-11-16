package thread.web

import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.promise
import thread.data.firebase.FirebaseAccountSession
import thread.data.firebase.FirebaseBackend
import thread.data.http.InMemoryRequestLog
import thread.data.synthetic.SyntheticScenario
import thread.domain.external.BackgroundRefresh
import thread.web.platform.WallClock
import thread.web.platform.readSessionFlag
import thread.web.platform.writeSessionFlag
import thread.web.presentation.external.BackgroundRefreshProbe
import thread.web.presentation.external.ShowRefreshProbe

/** The tab flag that makes the app use the SYNTHETIC Jira and GitHub providers (emulators only). */
internal const val SYNTHETIC_PROVIDERS_FLAG = "thread.syntheticProviders"

/** The tab setting that lets the background refresh run with the SYNTHETIC providers: its interval in ms (D51). */
private const val BACKGROUND_PROBE_KEY = "thread.backgroundRefreshProbe"

/**
 * The background refresh probe for this tab (emulators only). It runs with the SYNTHETIC fixtures only after a check
 * turned it on with `threadTest.setBackgroundRefresh`; otherwise it only records runs.
 */
internal fun backgroundRefreshProbe(): BackgroundRefreshProbe {
    val interval =
        try {
            window.sessionStorage.getItem(BACKGROUND_PROBE_KEY)?.toLongOrNull()
        } catch (e: Throwable) {
            null
        }
    return BackgroundRefreshProbe(
        withFixtures = interval != null,
        intervalMillis = interval?.coerceAtLeast(1_000L) ?: BackgroundRefresh.INTERVAL_MILLIS,
    )
}

/** The tab flag that lets refresh on show run with the SYNTHETIC providers (D67). */
private const val SHOW_PROBE_FLAG = "thread.showRefreshProbe"

/**
 * The refresh-on-show probe for this tab (emulators only). It runs with the SYNTHETIC fixtures only after a check
 * turned it on with `threadTest.setShowRefresh`; otherwise it only records runs.
 */
internal fun showRefreshProbe(): ShowRefreshProbe = ShowRefreshProbe(withFixtures = readSessionFlag(SHOW_PROBE_FLAG))

/** Failure switches for automated checks. Only the emulator test hooks change them. */
internal class TestFlags {
    /** The SYNTHETIC fixture revision: from 1, a few tickets and pull requests changed, as between two calls (D67). */
    var fixtureRevision: Int = 0

    /** When true, every local save aborts its IndexedDB transaction. */
    var failLocalSaves: Boolean = false

    /** When true, every Note and workspace write to the server carries a field the security rules refuse. */
    var failServerWrites: Boolean = false

    /** What the SYNTHETIC Jira answers, when the synthetic providers are on. */
    var jiraScenario: SyntheticScenario = SyntheticScenario.Normal

    /** What the SYNTHETIC GitHub answers, when the synthetic providers are on. */
    var gitHubScenario: SyntheticScenario = SyntheticScenario.Normal
}

private val hookScope = MainScope()

/**
 * Test hooks on `window.threadTest`, for automated checks through Chrome DevTools. Installed only when the app runs
 * against the local emulators: the Auth emulator accepts the unsigned sign-in below, a real project would not.
 * Every value is SYNTHETIC; nothing here reads or prints user data.
 */
internal fun installTestHooks(
    backend: FirebaseBackend,
    account: FirebaseAccountSession,
    flags: TestFlags,
    backgroundProbe: BackgroundRefreshProbe?,
    showProbe: ShowRefreshProbe?,
    requestLog: InMemoryRequestLog?,
) {
    val hooks: dynamic = js("({})")
    hooks.signIn = { subject: String ->
        hookScope.promise { account.signInToEmulatorForTesting(subject, "$subject@example.test") }
    }
    hooks.signOut = { hookScope.promise { account.signOut() } }
    hooks.goOffline = { hookScope.promise { backend.goOfflineForTesting() } }
    hooks.goOnline = { hookScope.promise { backend.goOnlineForTesting() } }
    hooks.setFailLocalSaves = { on: Boolean -> flags.failLocalSaves = on }
    hooks.setFailServerWrites = { on: Boolean -> flags.failServerWrites = on }
    // Moves the app's clock, so reminder and day-change behaviour can be checked without waiting.
    hooks.setClockOffset = { millis: Double -> WallClock.shiftForTesting(millis) }
    // Switches this tab to the SYNTHETIC Jira and GitHub fixtures (or back) and reloads. Real tokens are never used then.
    hooks.useSyntheticProviders = { on: Boolean ->
        writeSessionFlag(SYNTHETIC_PROVIDERS_FLAG, on)
        window.location.reload()
    }
    // Turns every SYNTHETIC Jira answer into one failure kind: Normal, Unauthorized, Forbidden, NotFound, RateLimited,
    // ServerError or NoAnswer. Returns false for an unknown name.
    hooks.setJiraScenario = { name: String ->
        val scenario = SyntheticScenario.entries.firstOrNull { it.name == name }
        if (scenario != null) flags.jiraScenario = scenario
        scenario != null
    }
    // The same for every SYNTHETIC GitHub answer.
    hooks.setGitHubScenario = { name: String ->
        val scenario = SyntheticScenario.entries.firstOrNull { it.name == name }
        if (scenario != null) flags.gitHubScenario = scenario
        scenario != null
    }
    // Lets the background refresh run with the SYNTHETIC fixtures every [intervalMillis] (0 turns it off), and reloads.
    hooks.setBackgroundRefresh = { intervalMillis: Double ->
        try {
            if (intervalMillis > 0) {
                window.sessionStorage.setItem(BACKGROUND_PROBE_KEY, intervalMillis.toLong().toString())
            } else {
                window.sessionStorage.removeItem(BACKGROUND_PROBE_KEY)
            }
        } catch (e: Throwable) {
            // Without storage the probe stays off.
        }
        window.location.reload()
    }
    // The background refreshes started in this page load, as `provider:list:reason`.
    hooks.backgroundRuns = { backgroundProbe?.runs?.toTypedArray() ?: emptyArray() }
    // Lets refresh on show run with the SYNTHETIC fixtures (or not), and reloads (D67).
    hooks.setShowRefresh = { on: Boolean ->
        writeSessionFlag(SHOW_PROBE_FLAG, on)
        window.location.reload()
    }
    // The refresh-on-show requests started in this page load, as `provider:ITEM+ITEM`.
    hooks.showRuns = { showProbe?.runs?.toTypedArray() ?: emptyArray() }
    // Moves the SYNTHETIC fixtures to [revision]: from 1, SYN-103 is In Progress and #12's CI and reviews passed.
    hooks.setFixtureRevision = { revision: Int -> flags.fixtureRevision = revision }
    // The SYNTHETIC requests of this page load, as `provider endpoint status`. No URL, header or body is kept.
    hooks.requests = {
        requestLog
            ?.entries
            ?.value
            ?.map { "${it.provider} ${it.endpoint} ${it.status ?: "none"}" }
            ?.toTypedArray() ?: emptyArray()
    }
    hooks.clearRequests = { requestLog?.clear() }
    window.asDynamic().threadTest = hooks
}
