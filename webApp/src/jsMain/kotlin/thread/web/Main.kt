package thread.web

import org.jetbrains.compose.web.renderComposable
import thread.data.firebase.FirebaseAccountSession
import thread.data.firebase.FirebaseBackend
import thread.data.firebase.FirestoreNoteSyncRemote
import thread.data.local.IndexedDbDraftStore
import thread.data.local.browserDeviceId
import thread.data.local.randomRevisionId
import thread.domain.note.LocalDraftStore
import thread.web.presentation.App
import thread.web.presentation.m1spike.SpikeServices
import thread.web.presentation.m1spike.SpikeTestHooks

/**
 * Composition root: the only place that picks data implementations. Firebase runs against the local emulators
 * only, with the demo-thread project (adr/ADR-005-auth-and-security-rules.md).
 */
fun main() {
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
    renderComposable(rootElementId = "root") {
        App(services, testHooks)
    }
}
