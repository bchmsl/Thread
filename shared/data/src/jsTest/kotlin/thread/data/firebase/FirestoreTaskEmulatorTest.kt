package thread.data.firebase

import kotlinx.coroutines.flow.first
import thread.domain.reminder.ReminderId
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.reminder.newReminder
import thread.domain.task.Deadline
import thread.domain.task.TaskId
import thread.domain.task.newTask
import thread.domain.time.DEFAULT_WORKSPACE_ZONE
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.TopicId
import thread.domain.workspace.EditResult
import thread.domain.workspace.MeetingsLayout
import thread.domain.workspace.createReminderWrite
import thread.domain.workspace.createTaskWrite
import thread.domain.workspace.discardTask
import thread.domain.workspace.dismissReminder
import thread.domain.workspace.editTask
import thread.domain.workspace.savePrefs
import thread.domain.workspace.seedWrites
import thread.domain.workspace.toggleTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Tasks, Reminders and the Phase 7 preferences through [FirestoreWorkspaceStore] against the emulators. All content is synthetic. */
class FirestoreTaskEmulatorTest {
    private val today = LocalDay(2026, 11, 13)

    @Test
    fun anOfflineTaskKeepsItsCalendarFieldsThroughSync() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            val store = FirestoreWorkspaceStore(device.backend, device.accountId)
            try {
                device.backend.goOfflineForTesting()
                val task =
                    newTask(TaskId("k-synthetic-1"), "Synthetic offline task", topicId = null, plannedDay = today)!!
                        .copy(deadline = Deadline(today.plusDays(1), 18 * 60))
                store.write(seedWrites() + createTaskWrite(task))

                val pending = store.observe().first { it.tasks.isNotEmpty() }
                assertTrue(pending.pendingWrites, "Offline, the Task is only on the device")
                assertNotNull(pending.tasks.single().createdAtMillis)

                device.backend.goOnlineForTesting()
                val synced = store.observe().first { !it.pendingWrites && it.tasks.isNotEmpty() }
                val stored = synced.tasks.single()
                assertEquals(task.copy(createdAtMillis = stored.createdAtMillis), stored)
                assertEquals("2026-11-13", stored.plannedDay.toString(), "The planned day never shifts")
                assertEquals("2026-11-14T18:00", stored.deadline.toString())
                assertTrue(store.refused.value.isEmpty())
            } finally {
                device.backend.close()
            }
        }

    @Test
    fun completingOnOneDeviceAndRenamingOnAnotherBothSurvive() =
        emulatorTest {
            val subject = syntheticSubject()
            val deviceA = EmulatedDevice.signIn(subject)
            val deviceB = EmulatedDevice.signIn(subject)
            val storeA = FirestoreWorkspaceStore(deviceA.backend, deviceA.accountId)
            val storeB = FirestoreWorkspaceStore(deviceB.backend, deviceB.accountId)
            try {
                val task = newTask(TaskId("k-synthetic-2"), "Synthetic shared task", TopicId("t-synthetic"))!!
                storeA.write(seedWrites() + createTaskWrite(task))
                storeA.observe().first { it.tasks.isNotEmpty() && !it.pendingWrites }
                val onB =
                    storeB
                        .observe()
                        .first { it.tasks.isNotEmpty() }
                        .tasks
                        .single()

                deviceA.backend.goOfflineForTesting()
                val onA =
                    storeA
                        .observe()
                        .first { it.tasks.isNotEmpty() }
                        .tasks
                        .single()
                storeA.write(assertIs<EditResult.Changed>(editTask(onA, title = "Synthetic renamed task")).edit.writes)
                storeB.write(assertIs<EditResult.Changed>(toggleTask(onB, today)).edit.writes)
                storeB.observe().first { !it.pendingWrites && it.tasks.single().done }

                deviceA.backend.goOnlineForTesting()
                val merged =
                    storeB
                        .observe()
                        .first { it.tasks.single().title == "Synthetic renamed task" }
                        .tasks
                        .single()
                assertTrue(merged.done, "A's late rename does not reopen the Task")
                assertEquals(today, merged.completedOn)
            } finally {
                deviceA.backend.close()
                deviceB.backend.close()
            }
        }

    @Test
    fun undoingACreateKeepsTheDocumentButHidesIt() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            val store = FirestoreWorkspaceStore(device.backend, device.accountId)
            try {
                val task = newTask(TaskId("k-synthetic-3"), "Synthetic undone task", topicId = null)!!
                store.write(seedWrites() + createTaskWrite(task))
                val created =
                    store
                        .observe()
                        .first { it.tasks.isNotEmpty() && !it.pendingWrites }
                        .tasks
                        .single()

                store.write(discardTask(created))
                val after =
                    store
                        .observe()
                        .first { !it.pendingWrites && it.tasks.single().discarded }
                        .tasks
                        .single()
                assertTrue(after.deleted)
                assertTrue(store.refused.value.isEmpty(), "Undo is an accepted update, not a delete")
            } finally {
                device.backend.close()
            }
        }

    @Test
    fun aReminderIsStoredAsAWallTimeAndCanBeDismissed() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            val store = FirestoreWorkspaceStore(device.backend, device.accountId)
            try {
                val target = ReminderTarget(TargetKind.Task, "k-synthetic-4")
                val reminder =
                    newReminder(ReminderId("r-synthetic-1"), WallTime.of(today, 15, 0), DEFAULT_WORKSPACE_ZONE, "Synthetic nudge", target)!!
                store.write(seedWrites() + createReminderWrite(reminder))
                val stored =
                    store
                        .observe()
                        .first { it.reminders.isNotEmpty() && !it.pendingWrites }
                        .reminders
                        .single()
                assertEquals(reminder.copy(createdAtMillis = stored.createdAtMillis), stored)

                store.write(dismissReminder(stored))
                val dismissed =
                    store
                        .observe()
                        .first { !it.pendingWrites && it.reminders.single().dismissed }
                        .reminders
                        .single()
                assertEquals("2026-11-13T15:00", dismissed.at.toString())
                assertTrue(store.refused.value.isEmpty())
            } finally {
                device.backend.close()
            }
        }

    @Test
    fun theKeepCompletedSettingSyncs() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            val store = FirestoreWorkspaceStore(device.backend, device.accountId)
            try {
                store.write(seedWrites())
                val seeded = store.observe().first { it.statuses.size == 4 && !it.pendingWrites }
                assertTrue(seeded.prefs.keepCompleted, "On by default")

                store.write(listOfNotNull(savePrefs(seeded, seeded.prefs.copy(keepCompleted = false))))
                val saved = store.observe().first { !it.pendingWrites && it.prefsStored }
                assertEquals(false, saved.prefs.keepCompleted)
                assertEquals(DEFAULT_WORKSPACE_ZONE, saved.prefs.timeZone)
                assertTrue(store.refused.value.isEmpty())
            } finally {
                device.backend.close()
            }
        }

    @Test
    fun theHideEmptySectionsSettingSyncsAndStartsOff() =
        emulatorTest {
            val device = EmulatedDevice.signIn(syntheticSubject())
            val store = FirestoreWorkspaceStore(device.backend, device.accountId)
            try {
                store.write(seedWrites())
                val seeded = store.observe().first { it.statuses.size == 4 && !it.pendingWrites }
                assertEquals(false, seeded.prefs.hideEmptySections, "Off by default")

                store.write(listOfNotNull(savePrefs(seeded, seeded.prefs.copy(hideEmptySections = true))))
                val on = store.observe().first { !it.pendingWrites && it.prefsStored && it.prefs.hideEmptySections }
                assertEquals(seeded.prefs.copy(hideEmptySections = true), on.prefs, "Only the switch changed")

                store.write(listOfNotNull(savePrefs(on, on.prefs.copy(hideEmptySections = false))))
                store.observe().first { !it.pendingWrites && !it.prefs.hideEmptySections }
                assertTrue(store.refused.value.isEmpty())
            } finally {
                device.backend.close()
            }
        }

    @Test
    fun theMeetingsLayoutSyncsAndStartsVertical() =
        emulatorTest {
            val subject = syntheticSubject()
            val deviceA = EmulatedDevice.signIn(subject)
            val deviceB = EmulatedDevice.signIn(subject)
            val storeA = FirestoreWorkspaceStore(deviceA.backend, deviceA.accountId)
            val storeB = FirestoreWorkspaceStore(deviceB.backend, deviceB.accountId)
            try {
                storeA.write(seedWrites())
                val seeded = storeA.observe().first { it.statuses.size == 4 && !it.pendingWrites }
                assertEquals(MeetingsLayout.Vertical, seeded.prefs.meetingsLayout, "Vertical by default")

                val horizontal = seeded.prefs.copy(meetingsLayout = MeetingsLayout.Horizontal)
                storeA.write(listOfNotNull(savePrefs(seeded, horizontal)))
                val saved = storeA.observe().first { !it.pendingWrites && it.prefs.meetingsLayout == MeetingsLayout.Horizontal }
                assertEquals(horizontal, saved.prefs, "Only the layout changed")
                // The other device follows.
                assertEquals(horizontal, storeB.observe().first { it.prefs.meetingsLayout == MeetingsLayout.Horizontal }.prefs)

                storeA.write(listOfNotNull(savePrefs(saved, saved.prefs.copy(meetingsLayout = MeetingsLayout.Vertical))))
                storeB.observe().first { it.prefs.meetingsLayout == MeetingsLayout.Vertical }
                assertTrue(storeA.refused.value.isEmpty())
            } finally {
                deviceA.backend.close()
                deviceB.backend.close()
            }
        }
}
