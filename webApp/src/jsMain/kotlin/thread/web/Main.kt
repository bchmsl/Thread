package thread.web

import org.jetbrains.compose.web.renderComposable
import thread.data.external.KeyValueConnectionStore
import thread.data.firebase.FirebaseAccountSession
import thread.data.firebase.FirebaseBackend
import thread.data.firebase.FirestoreNoteSyncRemote
import thread.data.github.GitHubPullRequestDetails
import thread.data.github.GitHubPullRequestLists
import thread.data.http.FetchHttpTransport
import thread.data.http.HttpTransport
import thread.data.http.InMemoryRequestLog
import thread.data.http.RecordingTransport
import thread.data.jira.JiraIssueRepository
import thread.data.local.IndexedDbDraftStore
import thread.data.local.IndexedDbKeyValueDatabase
import thread.data.local.browserDeviceId
import thread.data.local.randomRevisionId
import thread.data.synthetic.SyntheticConnectionStore
import thread.data.synthetic.SyntheticHttpTransport
import thread.data.synthetic.SyntheticScenario
import thread.domain.note.LocalDraftStore
import thread.web.platform.isOnline
import thread.web.platform.nowMillis
import thread.web.platform.registerOfflineShell
import thread.web.presentation.App
import thread.web.presentation.m1connections.ConnectionsServices
import thread.web.presentation.m1connections.ConnectionsSpike
import thread.web.presentation.m1connections.ConnectionsTestHooks
import thread.web.presentation.m1spike.SpikeServices
import thread.web.presentation.m1spike.SpikeTestHooks

/**
 * Composition root: the only place that picks data implementations. Firebase runs against the local emulators
 * only, with the demo-thread project (adr/ADR-005-auth-and-security-rules.md).
 */
fun main() {
    registerOfflineShell()
    val backend = FirebaseBackend()
    val account = FirebaseAccountSession(backend)
    val testHooks =
        SpikeTestHooks(
            signInAsSyntheticUser = { subject -> account.signInToEmulatorForTesting(subject, "$subject@example.test") },
        )
    // One store per database, so an account that signs in again reuses its open connection.
    val draftStores = mutableMapOf<String, LocalDraftStore>()
    val services =
        SpikeServices(
            account = account,
            draftStore = { accountId ->
                val name = accountId?.let { IndexedDbDraftStore.databaseNameFor(it) } ?: IndexedDbDraftStore.DEFAULT_DATABASE_NAME
                draftStores.getOrPut(name) { IndexedDbDraftStore(name, failWritesForTesting = { testHooks.failLocalSaves }) }
            },
            syncRemote = { accountId ->
                FirestoreNoteSyncRemote(backend, accountId, rejectWritesForTesting = { testHooks.failServerWrites })
            },
            deviceId = browserDeviceId(),
            newRevisionId = ::randomRevisionId,
        )
    val connections = connectionsSpike()
    renderComposable(rootElementId = "root") {
        App(services, testHooks, connections)
    }
}

/**
 * The M1-C probe (adr/ADR-006, ADR-007, ADR-008). Live: the browser's fetch, connections in their own IndexedDB
 * database and saved copies in another. Fixtures: SYNTHETIC answers, in-memory connections and a separate cache
 * database, so fixture data never mixes with real saved copies. Both log request metadata to the same log.
 */
private fun connectionsSpike(): ConnectionsSpike {
    val requestLog = InMemoryRequestLog()
    val hooks = ConnectionsTestHooks(SyntheticScenario.entries.map { it.label })
    val built = mutableMapOf<Boolean, ConnectionsServices>()

    fun build(synthetic: Boolean): ConnectionsServices {
        val inner: HttpTransport =
            if (synthetic) {
                SyntheticHttpTransport({ SyntheticScenario.entries[hooks.scenario] }, ::isOnline, ::nowMillis, latencyMillis = 400)
            } else {
                FetchHttpTransport()
            }
        val jiraName = if (synthetic) "Jira (synthetic)" else "Jira"
        val gitHubName = if (synthetic) "GitHub (synthetic)" else "GitHub"
        val jiraTransport = RecordingTransport(jiraName, inner, requestLog, ::nowMillis, ::isOnline)
        val gitHubTransport = RecordingTransport(gitHubName, inner, requestLog, ::nowMillis, ::isOnline)
        val connectionStore =
            if (synthetic) {
                SyntheticConnectionStore()
            } else {
                val database =
                    IndexedDbKeyValueDatabase(
                        IndexedDbKeyValueDatabase.CONNECTIONS,
                        listOf(IndexedDbKeyValueDatabase.CONNECTIONS_STORE),
                        strictDurability = true,
                    )
                KeyValueConnectionStore(database.store(IndexedDbKeyValueDatabase.CONNECTIONS_STORE))
            }
        val cache =
            IndexedDbKeyValueDatabase(
                if (synthetic) IndexedDbKeyValueDatabase.EXTERNAL_CACHE_SYNTHETIC else IndexedDbKeyValueDatabase.EXTERNAL_CACHE,
                IndexedDbKeyValueDatabase.EXTERNAL_CACHE_STORES,
            )
        val cacheStores = IndexedDbKeyValueDatabase.EXTERNAL_CACHE_STORES.map { cache.store(it) }
        val jira =
            JiraIssueRepository(
                connectionStore,
                jiraTransport,
                cache.store(IndexedDbKeyValueDatabase.JIRA_ISSUES),
                cache.store(IndexedDbKeyValueDatabase.JIRA_META),
            )
        return ConnectionsServices(
            isSynthetic = synthetic,
            jiraName = jiraName,
            gitHubName = gitHubName,
            connections = connectionStore,
            jira = jira,
            pullRequestLists = GitHubPullRequestLists(connectionStore, gitHubTransport, cache.store(IndexedDbKeyValueDatabase.PR_LISTS)),
            pullRequests = GitHubPullRequestDetails(connectionStore, gitHubTransport, cache.store(IndexedDbKeyValueDatabase.PULL_REQUESTS)),
            lookUpCloudId = { site -> jira.lookUpCloudId(site, nowMillis()) },
            deleteSavedCopies = { cacheStores.forEach { it.clear() } },
        )
    }

    return ConnectionsSpike(services = { synthetic -> built.getOrPut(synthetic) { build(synthetic) } }, requestLog, hooks)
}
