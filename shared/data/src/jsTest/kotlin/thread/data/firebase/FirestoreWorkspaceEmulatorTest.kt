package thread.data.firebase

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import thread.data.external.InMemoryKeyValueStore
import thread.data.workspace.WorkspaceOutbox
import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.StatusCategory
import thread.domain.topic.StatusId
import thread.domain.topic.TopicId
import thread.domain.topic.newTopic
import thread.domain.topic.visibleStatuses
import thread.domain.workspace.CaptureKind
import thread.domain.workspace.EditResult
import thread.domain.workspace.RefusedChange
import thread.domain.workspace.TopicsView
import thread.domain.workspace.WorkspacePrefs
import thread.domain.workspace.WorkspaceWrite
import thread.domain.workspace.deleteStatus
import thread.domain.workspace.editPickUp
import thread.domain.workspace.moveTopic
import thread.domain.workspace.renameStatus
import thread.domain.workspace.savePrefs
import thread.domain.workspace.seedWrites
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [FirestoreWorkspaceStore] against the emulators. All accounts and content are synthetic. */
class FirestoreWorkspaceEmulatorTest {
    @Test
    fun aNewAccountNeedsASeedAndTheSeedSyncs() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            val store = FirestoreWorkspaceStore(device.backend, device.accountId)
            try {
                val fresh = store.observe().first { it.needsSeed }
                assertTrue(fresh.statuses.isEmpty())
                assertEquals(WorkspacePrefs(), fresh.prefs)
                assertFalse(fresh.prefsStored)

                store.write(seedWrites())
                val seeded = store.observe().first { it.statuses.size == 4 && !it.pendingWrites }
                assertFalse(seeded.needsSeed)
                assertEquals(SEED_STATUSES, visibleStatuses(seeded.statuses))
            } finally {
                device.backend.close()
            }
        }

    @Test
    fun anOfflineChangeIsPendingUntilTheServerAcknowledgesIt() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            val store = FirestoreWorkspaceStore(device.backend, device.accountId)
            try {
                device.backend.goOfflineForTesting()
                val topic = newTopic(TopicId("t-synthetic-1"), "Synthetic topic", SEED_STATUSES, 0)!!
                store.write(seedWrites() + WorkspaceWrite.PutTopic(topic, isNew = true))

                val pending = store.observe().first { it.topics.isNotEmpty() }
                assertTrue(pending.pendingWrites, "Offline, the write is only on the device")
                assertEquals("Synthetic topic", pending.topics.single().name)
                assertNotNull(pending.topics.single().createdAtMillis, "The creation time shows as an estimate")

                device.backend.goOnlineForTesting()
                val synced = store.observe().first { !it.pendingWrites && it.topics.isNotEmpty() }
                assertEquals(topic.copy(createdAtMillis = synced.topics.single().createdAtMillis), synced.topics.single())
                assertTrue(store.refused.value.isEmpty())
            } finally {
                device.backend.close()
            }
        }

    @Test
    fun aRefusedWriteIsReportedAndTakenBackOut() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            var reject = false
            val store = FirestoreWorkspaceStore(device.backend, device.accountId) { reject }
            try {
                store.write(seedWrites())
                store.observe().first { it.statuses.size == 4 && !it.pendingWrites }

                reject = true
                val rename = assertIs<EditResult.Changed>(renameStatus(SEED_STATUSES, StatusId("s-active"), "Building")).edit
                store.write(rename.writes)
                val refused = store.refused.first { it.isNotEmpty() }
                assertEquals(rename.writes, refused.single().writes)
                assertFalse(refused.single().unconfirmed)
                val after = store.observe().first { !it.pendingWrites }
                assertEquals("Active", visibleStatuses(after.statuses)[1].name, "The server copy wins again")
            } finally {
                device.backend.close()
            }
        }

    @Test
    fun aRefusedChangeIsStillListedAfterAReloadAndARetryIsAccepted() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            val deviceStorage = InMemoryKeyValueStore()
            val pages = MainScope()
            var reject = false
            val store = FirestoreWorkspaceStore(device.backend, device.accountId, deviceStorage, pages) { reject }
            try {
                store.write(seedWrites())
                store.observe().first { it.statuses.size == 4 && !it.pendingWrites }
                reject = true
                val rename = assertIs<EditResult.Changed>(renameStatus(SEED_STATUSES, StatusId("s-active"), "Building")).edit
                store.write(rename.writes)
                val refused = store.refused.first { it.isNotEmpty() }.single()
                awaitRecords(deviceStorage) { records -> records.singleOrNull()?.contains("\"state\":\"refused\"") == true }

                // The next page on this device: a new store over the same device storage.
                val reloaded = FirestoreWorkspaceStore(device.backend, device.accountId, deviceStorage, pages)
                assertEquals(refused, reloaded.refused.first { it.isNotEmpty() }.single())

                reloaded.retry(refused)
                assertTrue(reloaded.refused.value.isEmpty(), "Retrying takes it off the list")
                val after = reloaded.observe().first { !it.pendingWrites && visibleStatuses(it.statuses)[1].name == "Building" }
                assertEquals("Building", visibleStatuses(after.statuses)[1].name)
                awaitRecords(deviceStorage) { it.isEmpty() }
            } finally {
                pages.cancel()
                device.backend.close()
            }
        }

    @Test
    fun aChangeWhoseAnswerWasLostInAReloadIsListedWhenTheDataDoesNotShowIt() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            val deviceStorage = InMemoryKeyValueStore()
            val pages = MainScope()
            val store = FirestoreWorkspaceStore(device.backend, device.accountId)
            try {
                store.write(seedWrites())
                store.observe().first { it.statuses.size == 4 && !it.pendingWrites }
                val arrived = assertIs<EditResult.Changed>(renameStatus(SEED_STATUSES, StatusId("s-active"), "Building")).edit
                val lost = assertIs<EditResult.Changed>(renameStatus(SEED_STATUSES, StatusId("s-paused"), "Waiting")).edit
                store.write(arrived.writes)
                store.observe().first { !it.pendingWrites && visibleStatuses(it.statuses)[1].name == "Building" }

                // An earlier page recorded both as sent and closed before the server answered.
                val earlierPage = WorkspaceOutbox(deviceStorage, pages, now = { 1L }, session = "earlier-page")
                earlierPage.sent(arrived.writes)
                val lostId = earlierPage.sent(lost.writes)
                earlierPage.load()

                val reloaded = FirestoreWorkspaceStore(device.backend, device.accountId, deviceStorage, pages)
                val listed = reloaded.refused.first { it.isNotEmpty() }
                assertEquals(listOf(RefusedChange(lostId, lost.writes, unconfirmed = true)), listed)
                awaitRecords(deviceStorage) { records -> records.singleOrNull()?.contains("\"state\":\"unconfirmed\"") == true }
            } finally {
                pages.cancel()
                device.backend.close()
            }
        }

    /** Waits until the device storage holds records that pass [check]. Storage writes run after the list changes. */
    private suspend fun awaitRecords(
        storage: InMemoryKeyValueStore,
        check: (List<String>) -> Boolean,
    ) {
        while (!check(storage.all().map { it.second })) delay(20)
    }

    @Test
    fun theFirstPreferencesSaveIsAcceptedAndLaterSavesMerge() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            val store = FirestoreWorkspaceStore(device.backend, device.accountId)
            try {
                store.write(seedWrites())
                val seeded = store.observe().first { it.statuses.size == 4 && !it.pendingWrites }
                assertFalse(seeded.prefsStored)

                store.write(listOfNotNull(savePrefs(seeded, seeded.prefs.copy(topicsView = TopicsView.Board))))
                val first = store.observe().first { !it.pendingWrites && it.prefsStored }
                assertEquals(WorkspacePrefs(TopicsView.Board, CaptureKind.Task), first.prefs)

                store.write(listOfNotNull(savePrefs(first, first.prefs.copy(captureType = CaptureKind.Note))))
                val second = store.observe().first { !it.pendingWrites && it.prefs.captureType == CaptureKind.Note }
                assertEquals(WorkspacePrefs(TopicsView.Board, CaptureKind.Note), second.prefs)
                assertTrue(store.refused.value.isEmpty(), "The server accepts both saves")
            } finally {
                device.backend.close()
            }
        }

    @Test
    fun deletingAStatusMovesItsTopicsInOneWriteAndAnotherDeviceSeesIt() =
        emulatorTest {
            val subject = syntheticSubject()
            val deviceA = EmulatedDevice.signIn(subject)
            val deviceB = EmulatedDevice.signIn(subject)
            val storeA = FirestoreWorkspaceStore(deviceA.backend, deviceA.accountId)
            val storeB = FirestoreWorkspaceStore(deviceB.backend, deviceB.accountId)
            try {
                val paused = SEED_STATUSES[2]
                val topic = newTopic(TopicId("t-synthetic-2"), "Paused work", SEED_STATUSES, 0)!!.copy(statusId = paused.id)
                storeA.write(seedWrites() + WorkspaceWrite.PutTopic(topic, isNew = true))
                val before = storeA.observe().first { it.topics.isNotEmpty() && !it.pendingWrites }

                val delete =
                    assertIs<EditResult.Changed>(
                        deleteStatus(before.statuses, before.topics, paused.id, StatusId("s-active")),
                    ).edit
                storeA.write(delete.writes + WorkspaceWrite.PutPrefs(WorkspacePrefs(TopicsView.Board, CaptureKind.Topic)))

                val seen =
                    storeB.observe().first { data ->
                        data.statuses.any { it.id == paused.id && it.deleted } &&
                            data.topics.singleOrNull()?.statusId == StatusId("s-active") &&
                            data.prefs.topicsView == TopicsView.Board
                    }
                assertEquals(listOf("Planned", "Active", "Done"), visibleStatuses(seen.statuses).map { it.name })
                assertEquals(StatusId("s-active"), seen.topics.single().statusId)
                assertEquals(
                    StatusCategory.Active,
                    visibleStatuses(seen.statuses).first { it.id == seen.topics.single().statusId }.category,
                )
                assertEquals(WorkspacePrefs(TopicsView.Board, CaptureKind.Topic), seen.prefs)
            } finally {
                deviceA.backend.close()
                deviceB.backend.close()
            }
        }

    @Test
    fun editsToDifferentFieldsOnTwoDevicesBothSurvive() =
        emulatorTest {
            val subject = syntheticSubject()
            val deviceA = EmulatedDevice.signIn(subject)
            val deviceB = EmulatedDevice.signIn(subject)
            val storeA = FirestoreWorkspaceStore(deviceA.backend, deviceA.accountId)
            val storeB = FirestoreWorkspaceStore(deviceB.backend, deviceB.accountId)
            try {
                val topic = newTopic(TopicId("t-synthetic-3"), "Shared work", SEED_STATUSES, 0)!!
                storeA.write(seedWrites() + WorkspaceWrite.PutTopic(topic, isNew = true))
                storeA.observe().first { it.topics.isNotEmpty() && !it.pendingWrites }
                val onB =
                    storeB
                        .observe()
                        .first { it.topics.isNotEmpty() && it.statuses.size == 4 }
                        .topics
                        .single()

                // A edits "Where I left off" offline. Meanwhile B moves the Topic to Active.
                deviceA.backend.goOfflineForTesting()
                val onA =
                    storeA
                        .observe()
                        .first { it.topics.isNotEmpty() }
                        .topics
                        .single()
                storeA.write(assertIs<EditResult.Changed>(editPickUp(onA, leftOff = "Synthetic: halfway through")).edit.writes)
                storeB.write(assertIs<EditResult.Changed>(moveTopic(onB, StatusId("s-active"), SEED_STATUSES)).edit.writes)
                storeB.observe().first { !it.pendingWrites && it.topics.single().statusId == StatusId("s-active") }

                deviceA.backend.goOnlineForTesting()
                val merged =
                    storeB
                        .observe()
                        .first { data ->
                            data.topics
                                .single()
                                .leftOff
                                .isNotEmpty()
                        }.topics
                        .single()
                assertEquals(StatusId("s-active"), merged.statusId, "A's late write does not move the Topic back")
                assertEquals("Synthetic: halfway through", merged.leftOff)
            } finally {
                deviceA.backend.close()
                deviceB.backend.close()
            }
        }
}
