package thread.data.firebase

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import thread.domain.reminder.Reminder
import thread.domain.reminder.ReminderId
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.reminder.newReminder
import thread.domain.time.DEFAULT_WORKSPACE_ZONE
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.TopicId
import thread.domain.topic.newTopic
import thread.domain.workspace.CONVERTED_TASK_PREFIX
import thread.domain.workspace.ConversionBackup
import thread.domain.workspace.ReminderConversion
import thread.domain.workspace.TopicReminderConverter
import thread.domain.workspace.WorkspaceData
import thread.domain.workspace.WorkspaceWrite
import thread.domain.workspace.convertedTaskId
import thread.domain.workspace.createReminderWrite
import thread.domain.workspace.seedWrites
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Topic reminder conversion (D54, adr/ADR-018 part B) through [FirestoreWorkspaceStore] against the emulators and
 * the current rules. Topic reminders are written the way an older build writes them. All content is synthetic.
 */
class FirestoreReminderConversionEmulatorTest {
    private val day = LocalDay(2026, 11, 14)
    private val topic = newTopic(TopicId("t-synthetic-conv"), "Synthetic Topic with reminders", SEED_STATUSES, 0)!!

    private fun onTopic(
        n: Int,
        hour: Int,
    ): Reminder =
        newReminder(
            ReminderId("r-synthetic-$n"),
            WallTime.of(day, hour, 0),
            DEFAULT_WORKSPACE_ZONE,
            "Synthetic topic reminder $n",
            ReminderTarget(TargetKind.Topic, topic.id.value),
        )!!

    /** What an older build stored: the default statuses, a Topic and two reminders on it. */
    private fun olderBuildWrites(): List<WorkspaceWrite> =
        seedWrites() + WorkspaceWrite.PutTopic(topic, isNew = true) + createReminderWrite(onTopic(1, 9)) +
            createReminderWrite(onTopic(2, 10))

    /** The server's data once [ready] holds, with nothing of this device waiting. */
    private suspend fun FirestoreWorkspaceStore.serverData(ready: (WorkspaceData) -> Boolean = { true }): WorkspaceData =
        observe().first { it.fromServer && !it.pendingWrites && ready(it) }

    private fun converted(data: WorkspaceData): Boolean =
        data.reminders.size == 2 && data.reminders.all { it.target.kind == TargetKind.Task }

    private class RecordingBackup : ConversionBackup {
        val saves = mutableListOf<List<ReminderConversion>>()
        var beforeSave: suspend () -> Unit = {}

        override suspend fun save(conversions: List<ReminderConversion>) {
            beforeSave()
            saves += conversions
        }
    }

    @Test
    fun twoDevicesConvertingAtOnceMakeOneTaskPerReminderAndDropTheirRefusals() =
        emulatorTest {
            val subject = syntheticSubject()
            val deviceA = EmulatedDevice.signIn(subject)
            val deviceB = EmulatedDevice.signIn(subject)
            val storeA = FirestoreWorkspaceStore(deviceA.backend, deviceA.accountId)
            val storeB = FirestoreWorkspaceStore(deviceB.backend, deviceB.accountId)
            try {
                storeA.write(olderBuildWrites())
                val original = storeA.serverData { it.reminders.size == 2 }.reminders
                val onA = storeA.serverData { it.reminders.size == 2 }
                val onB = storeB.serverData { it.reminders.size == 2 }
                val converterA = TopicReminderConverter(storeA, RecordingBackup())
                val converterB = TopicReminderConverter(storeB, RecordingBackup())

                val runA = async { converterA.run({ onA }, { true }) }
                val runB = async { converterB.run({ onB }, { true }) }
                assertEquals(2, runA.await().size)
                assertEquals(2, runB.await().size)

                // For each reminder one batch wins; the other is refused whole, because its Task create is now an update.
                combine(storeA.refused, storeB.refused) { a, b -> a.size + b.size }.first { it == 2 }
                val afterA = storeA.serverData(::converted)
                val afterB = storeB.serverData(::converted)
                converterA.dropSettled(afterA)
                converterB.dropSettled(afterB)
                assertTrue(storeA.refused.value.isEmpty() && storeB.refused.value.isEmpty(), "settled refusals are dropped")

                for (data in listOf(afterA, afterB)) {
                    assertEquals(listOf("k-from-r-synthetic-1", "k-from-r-synthetic-2"), data.tasks.map { it.id.value }.sorted())
                    assertEquals(
                        original.map { it.copy(target = ReminderTarget(TargetKind.Task, convertedTaskId(it.id).value)) }.toSet(),
                        data.reminders.toSet(),
                        "only the target changed",
                    )
                    data.tasks.forEach { task ->
                        assertEquals(topic.id, task.topicId)
                        assertTrue(!task.done && !task.inbox && !task.deleted && !task.discarded)
                    }
                }
                assertEquals("Synthetic topic reminder 1", afterA.tasks.first { it.id.value.endsWith("-1") }.title)
            } finally {
                deviceA.backend.close()
                deviceB.backend.close()
            }
        }

    @Test
    fun aSecondRunAndAnotherDeviceFindNothingToDo() =
        emulatorTest {
            val subject = syntheticSubject()
            val deviceA = EmulatedDevice.signIn(subject)
            val deviceB = EmulatedDevice.signIn(subject)
            val storeA = FirestoreWorkspaceStore(deviceA.backend, deviceA.accountId)
            val storeB = FirestoreWorkspaceStore(deviceB.backend, deviceB.accountId)
            try {
                storeA.write(olderBuildWrites())
                val before = storeA.serverData { it.reminders.size == 2 }
                assertEquals(2, TopicReminderConverter(storeA, RecordingBackup()).run({ before }, { true }).size)
                val after = storeA.serverData(::converted)

                // A fresh converter is what the next page load has: it remembers nothing it wrote.
                val again = RecordingBackup()
                assertEquals(emptyList(), TopicReminderConverter(storeA, again).run({ after }, { true }))
                val onB = RecordingBackup()
                val dataOnB = storeB.serverData(::converted)
                assertEquals(emptyList(), TopicReminderConverter(storeB, onB).run({ dataOnB }, { true }))
                assertTrue(again.saves.isEmpty() && onB.saves.isEmpty(), "no backup when there is nothing to do")
                assertEquals(2, dataOnB.tasks.size)
            } finally {
                deviceA.backend.close()
                deviceB.backend.close()
            }
        }

    @Test
    fun offlineNothingConvertsAndBackOnlineItDoes() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            val store = FirestoreWorkspaceStore(device.backend, device.accountId)
            try {
                store.write(olderBuildWrites())
                store.serverData { it.reminders.size == 2 }
                device.backend.goOfflineForTesting()
                val cached = store.observe().first { it.reminders.size == 2 && !it.fromServer }
                val backup = RecordingBackup()
                val converter = TopicReminderConverter(store, backup)
                assertEquals(emptyList(), converter.run({ cached }, { true }), "a device copy is not enough")
                assertEquals(emptyList(), converter.run({ cached.copy(fromServer = true) }, { false }), "nor is being offline")
                assertTrue(backup.saves.isEmpty())
                assertTrue(
                    store
                        .observe()
                        .first()
                        .tasks
                        .isEmpty(),
                )

                device.backend.goOnlineForTesting()
                val online = store.serverData { it.reminders.size == 2 }
                assertEquals(2, converter.run({ online }, { true }).size)
                assertEquals(2, store.serverData(::converted).tasks.size)
                assertTrue(store.refused.value.isEmpty())
            } finally {
                device.backend.close()
            }
        }

    @Test
    fun aDeviceThatWasOfflineDuringTheConversionAddsNothingWhenItReturns() =
        emulatorTest {
            val subject = syntheticSubject()
            val deviceA = EmulatedDevice.signIn(subject)
            val deviceB = EmulatedDevice.signIn(subject)
            val storeA = FirestoreWorkspaceStore(deviceA.backend, deviceA.accountId)
            val storeB = FirestoreWorkspaceStore(deviceB.backend, deviceB.accountId)
            try {
                storeA.write(olderBuildWrites())
                storeB.serverData { it.reminders.size == 2 }
                deviceB.backend.goOfflineForTesting()
                val staleOnB = storeB.observe().first { it.reminders.size == 2 && !it.fromServer }
                val converterB = TopicReminderConverter(storeB, RecordingBackup())
                assertEquals(emptyList(), converterB.run({ staleOnB }, { true }))

                val onA = storeA.serverData { it.reminders.size == 2 }
                assertEquals(2, TopicReminderConverter(storeA, RecordingBackup()).run({ onA }, { true }).size)
                storeA.serverData(::converted)

                deviceB.backend.goOnlineForTesting()
                val freshOnB = storeB.serverData(::converted)
                assertEquals(emptyList(), converterB.run({ freshOnB }, { true }))
                assertEquals(2, freshOnB.tasks.size)
                assertTrue(storeB.refused.value.isEmpty())
            } finally {
                deviceA.backend.close()
                deviceB.backend.close()
            }
        }

    @Test
    fun undoPutsEachReminderBackAndNoDeviceConvertsItAgain() =
        emulatorTest {
            val subject = syntheticSubject()
            val deviceA = EmulatedDevice.signIn(subject)
            val deviceB = EmulatedDevice.signIn(subject)
            val storeA = FirestoreWorkspaceStore(deviceA.backend, deviceA.accountId)
            val storeB = FirestoreWorkspaceStore(deviceB.backend, deviceB.accountId)
            try {
                storeA.write(olderBuildWrites())
                val before = storeA.serverData { it.reminders.size == 2 }
                val converter = TopicReminderConverter(storeA, RecordingBackup())
                val moved = converter.run({ before }, { true })
                storeA.serverData(::converted)

                converter.undo(moved)
                val undone =
                    storeA.serverData { data -> data.reminders.size == 2 && data.reminders.all { it.target.kind == TargetKind.Topic } }
                assertEquals(before.reminders.toSet(), undone.reminders.toSet(), "each reminder exactly as it was")
                assertEquals(2, undone.tasks.size)
                assertTrue(undone.tasks.all { it.deleted && it.discarded }, "the Tasks are hidden everywhere, Trash included")

                val onB = storeB.serverData { data -> data.tasks.size == 2 && data.tasks.all { it.discarded } }
                assertEquals(emptyList(), TopicReminderConverter(storeB, RecordingBackup()).run({ onB }, { true }))
                assertEquals(emptyList(), TopicReminderConverter(storeA, RecordingBackup()).run({ undone }, { true }))
                assertTrue(storeA.refused.value.isEmpty() && storeB.refused.value.isEmpty())
            } finally {
                deviceA.backend.close()
                deviceB.backend.close()
            }
        }

    @Test
    fun aTopicReminderAnOlderBuildAddsLaterIsConvertedOnTheNextRun() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            val store = FirestoreWorkspaceStore(device.backend, device.accountId)
            try {
                store.write(olderBuildWrites())
                val converter = TopicReminderConverter(store, RecordingBackup())
                val before = store.serverData { it.reminders.size == 2 }
                converter.run({ before }, { true })
                store.serverData(::converted)

                store.write(listOf(createReminderWrite(onTopic(3, 11))))
                val withNew = store.serverData { it.reminders.size == 3 }
                val moved = converter.run({ withNew }, { true })
                assertEquals(listOf("r-synthetic-3"), moved.map { it.reminder.id.value })
                val after =
                    store.serverData { data ->
                        data.reminders.size == 3 && data.reminders.all { it.target.kind == TargetKind.Task }
                    }
                assertEquals(3, after.tasks.count { it.id.value.startsWith(CONVERTED_TASK_PREFIX) })
            } finally {
                device.backend.close()
            }
        }

    @Test
    fun theBackupIsStoredBeforeAnyWriteAndAFailedOneWritesNothing() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            val store = FirestoreWorkspaceStore(device.backend, device.accountId)
            try {
                store.write(olderBuildWrites())
                val before = store.serverData { it.reminders.size == 2 }

                val failing = ConversionBackup { throw IllegalStateException("Synthetic storage failure") }
                assertEquals(emptyList(), TopicReminderConverter(store, failing).run({ before }, { true }))
                val untouched = store.observe().first()
                assertTrue(!untouched.pendingWrites && untouched.tasks.isEmpty(), "nothing was written")

                val backup = RecordingBackup()
                backup.beforeSave = {
                    val atSave = store.observe().first()
                    assertTrue(!atSave.pendingWrites && atSave.tasks.isEmpty(), "the copy is stored before the first write")
                }
                assertEquals(2, TopicReminderConverter(store, backup).run({ before }, { true }).size)
                assertEquals(
                    listOf(listOf("r-synthetic-1", "r-synthetic-2")),
                    backup.saves.map { list ->
                        list.map { it.reminder.id.value }
                    },
                )
                store.serverData(::converted)
            } finally {
                device.backend.close()
            }
        }
}
