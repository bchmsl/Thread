package thread.data.firebase

import kotlinx.coroutines.await
import kotlinx.coroutines.flow.first
import thread.domain.focus.FOCUS_HISTORY_LIMIT
import thread.domain.focus.FocusOutcome
import thread.domain.focus.FocusSession
import thread.domain.focus.FocusSettings
import thread.domain.focus.FocusTarget
import thread.domain.focus.FocusTargetKind
import thread.domain.workspace.WorkspaceWrite
import thread.domain.workspace.savePrefs
import thread.domain.workspace.seedWrites
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Focus timer's account data in [FirestoreWorkspaceStore] against the emulators. All of it is SYNTHETIC. */
class FirestoreFocusEmulatorTest {
    private fun session(
        index: Int,
        target: FocusTarget? = FocusTarget(FocusTargetKind.Task, "k-synthetic-1"),
    ) = FocusSession(
        id = "f-run-$index",
        startedAtMillis = T0 + index * HOUR,
        endedAtMillis = T0 + index * HOUR + 25 * MIN,
        focusedMillis = 25 * MIN,
        plannedMinutes = 25,
        outcome = FocusOutcome.Completed,
        target = target,
    )

    private suspend fun serverCount(
        device: EmulatedDevice,
        collectionName: String,
    ): Int = getDocsFromServer(collection(device.backend.firestore, "users", device.accountId.value, collectionName)).await().docs.size

    @Test
    fun anOfflineSessionShowsAtOnceStaysAcrossAPageAndSyncsOnce() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            val store = FirestoreWorkspaceStore(device.backend, device.accountId)
            try {
                store.write(seedWrites())
                store.observe().first { it.statuses.size == 4 && !it.pendingWrites }
                device.backend.goOfflineForTesting()
                val saved = session(1)
                store.write(listOf(WorkspaceWrite.PutFocusSession(saved)))
                // Two tabs settling the same end send the same session again.
                store.write(listOf(WorkspaceWrite.PutFocusSession(saved)))

                val pending = store.observe().first { it.focusSessions.isNotEmpty() }
                assertTrue(pending.pendingWrites, "Offline, the session is only on the device")
                assertEquals(listOf(saved), pending.focusSessions)
                // The next page on this device reads the same cache.
                val nextPage = FirestoreWorkspaceStore(device.backend, device.accountId)
                assertEquals(listOf(saved), nextPage.observe().first().focusSessions)

                device.backend.goOnlineForTesting()
                val synced = store.observe().first { !it.pendingWrites && it.focusSessions.isNotEmpty() }
                assertEquals(listOf(saved), synced.focusSessions)
                assertEquals(1, serverCount(device, "focusSessions"))
                assertTrue(store.refused.value.isEmpty(), "The second identical write is accepted")
            } finally {
                device.backend.close()
            }
        }

    @Test
    fun aRefusedSessionIsListedAndItsRetryStoresOneDocument() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            var reject = false
            val store = FirestoreWorkspaceStore(device.backend, device.accountId) { reject }
            try {
                store.write(seedWrites())
                store.observe().first { it.statuses.size == 4 && !it.pendingWrites }

                reject = true
                val saved = session(2, target = null)
                store.write(listOf(WorkspaceWrite.PutFocusSession(saved)))
                val refused = store.refused.first { it.isNotEmpty() }.single()
                assertEquals(listOf<WorkspaceWrite>(WorkspaceWrite.PutFocusSession(saved)), refused.writes)
                store.observe().first { !it.pendingWrites && it.focusSessions.isEmpty() }

                reject = false
                store.retry(refused)
                store.retry(refused)
                val synced = store.observe().first { !it.pendingWrites && it.focusSessions.isNotEmpty() }
                assertEquals(listOf(saved), synced.focusSessions)
                assertEquals(1, serverCount(device, "focusSessions"))
                assertTrue(store.refused.value.isEmpty())
            } finally {
                device.backend.close()
            }
        }

    @Test
    fun onlyTheNewestSessionsAreReadNewestFirst() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            val store = FirestoreWorkspaceStore(device.backend, device.accountId)
            try {
                val all = (1..FOCUS_HISTORY_LIMIT + 3).map { session(it) }
                store.write(seedWrites() + all.shuffled().map { WorkspaceWrite.PutFocusSession(it) })

                val synced = store.observe().first { !it.pendingWrites && it.focusSessions.size == FOCUS_HISTORY_LIMIT }
                assertEquals(all.reversed().take(FOCUS_HISTORY_LIMIT), synced.focusSessions)
                assertEquals(FOCUS_HISTORY_LIMIT + 3, serverCount(device, "focusSessions"))
            } finally {
                device.backend.close()
            }
        }

    @Test
    fun theLengthsSavedOnOneDeviceShowOnAnother() =
        emulatorTest {
            val subject = syntheticSubject()
            val deviceA = EmulatedDevice.signIn(subject)
            val deviceB = EmulatedDevice.signIn(subject)
            val storeA = FirestoreWorkspaceStore(deviceA.backend, deviceA.accountId)
            val storeB = FirestoreWorkspaceStore(deviceB.backend, deviceB.accountId)
            try {
                storeA.write(seedWrites())
                val seeded = storeA.observe().first { it.statuses.size == 4 && !it.pendingWrites }
                assertEquals(FocusSettings(), seeded.prefs.focusSettings)
                storeA.write(listOfNotNull(savePrefs(seeded, seeded.prefs.copy(focusWorkMinutes = 50, focusBreakMinutes = 10))))
                val stored = storeA.observe().first { !it.pendingWrites && it.prefsStored }
                storeA.write(listOfNotNull(savePrefs(stored, stored.prefs.copy(focusBreakMinutes = 15))))

                val seen = storeB.observe().first { it.prefs.focusBreakMinutes == 15 }
                assertEquals(FocusSettings(50, 15), seen.prefs.focusSettings)
                assertTrue(storeA.refused.value.isEmpty(), "The server accepts both saves")
            } finally {
                deviceA.backend.close()
                deviceB.backend.close()
            }
        }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 60 * MIN

        // A SYNTHETIC start time.
        const val T0 = 1_794_820_800_000L
    }
}
