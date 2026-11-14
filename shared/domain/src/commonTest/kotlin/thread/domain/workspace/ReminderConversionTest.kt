package thread.domain.workspace

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import thread.domain.reminder.Reminder
import thread.domain.reminder.ReminderId
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.time.DEFAULT_WORKSPACE_ZONE
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.StatusId
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// D54: Topic reminders become Tasks. Every Topic, Task and Reminder here is SYNTHETIC test data.
class ReminderConversionTest {
    private val day = LocalDay(2026, 11, 14)
    private val topic = topic("t-synthetic-a")
    private val trashedTopic = topic("t-synthetic-trash").copy(deleted = true)

    private fun topic(id: String) = Topic(TopicId(id), "Synthetic $id", TopicColor.Mint, StatusId("s-planned"))

    private fun reminder(
        id: String,
        hour: Int,
        target: ReminderTarget = ReminderTarget(TargetKind.Topic, topic.id.value),
        dismissed: Boolean = false,
        deleted: Boolean = false,
    ) = Reminder(ReminderId(id), WallTime.of(day, hour, 0), DEFAULT_WORKSPACE_ZONE, "Synthetic $id", target, dismissed, deleted)

    private fun data(
        reminders: List<Reminder>,
        tasks: List<Task> = emptyList(),
        fromServer: Boolean = true,
        pendingWrites: Boolean = false,
    ) = WorkspaceData(
        topics = listOf(topic, trashedTopic),
        tasks = tasks,
        reminders = reminders,
        fromServer = fromServer,
        pendingWrites = pendingWrites,
    )

    @Test
    fun onlyOpenRemindersOnAStoredTopicMoveAndATopicInTrashCounts() {
        val open = reminder("r-open", 15)
        val onTrashedTopic = reminder("r-trashed-topic", 9, ReminderTarget(TargetKind.Topic, trashedTopic.id.value))
        val skipped =
            listOf(
                reminder("r-dismissed", 8, dismissed = true),
                reminder("r-in-trash", 8, deleted = true),
                reminder("r-task", 8, ReminderTarget(TargetKind.Task, "k-synthetic")),
                reminder("r-note", 8, ReminderTarget(TargetKind.Note, "n-synthetic")),
                reminder("r-gone-topic", 8, ReminderTarget(TargetKind.Topic, "t-synthetic-gone")),
            )

        val conversions = topicReminderConversions(data(skipped + open + onTrashedTopic))

        assertEquals(listOf("r-trashed-topic", "r-open"), conversions.map { it.reminder.id.value }, "By reminder time")
        assertTrue(conversions.all { it.createsTask })
    }

    @Test
    fun theNewTaskCarriesTheReminderAndTheWritesGoTogether() {
        val open = reminder("r-1", 15)
        val conversion = topicReminderConversions(data(listOf(open))).single()

        val task = conversion.task
        assertEquals(TaskId("k-from-r-1"), task.id)
        assertEquals("Synthetic r-1", task.title)
        assertEquals(topic.id, task.topicId)
        assertFalse(task.done)
        assertFalse(task.inbox, "It has a Topic, so it never waits in Inbox")
        assertEquals(null, task.plannedDay)
        assertEquals(null, task.deadline)
        val moved = open.copy(target = ReminderTarget(TargetKind.Task, "k-from-r-1"))
        assertEquals(
            listOf(WorkspaceWrite.PutTask(task, isNew = true), WorkspaceWrite.PutReminder(moved, before = open)),
            conversion.writes,
        )
        assertEquals(open.at, moved.at, "The time, zone and text stay")
    }

    @Test
    fun aSecondRunFindsNothingToDo() {
        val first = data(listOf(reminder("r-1", 15), reminder("r-2", 16)))
        val after = applied(first, topicReminderConversions(first).flatMap { it.writes })

        assertEquals(emptyList(), topicReminderConversions(after))
        assertEquals(2, after.tasks.size)
    }

    @Test
    fun aRunCutAfterTheTaskOnlyMovesTheReminder() {
        val open = reminder("r-1", 15)
        val stored = Task(TaskId("k-from-r-1"), "Synthetic r-1", topic.id, createdAtMillis = 1)

        val conversion = topicReminderConversions(data(listOf(open), listOf(stored))).single()

        assertFalse(conversion.createsTask)
        assertEquals(listOf(WorkspaceWrite.PutReminder(conversion.moved, before = open)), conversion.writes)
    }

    @Test
    fun undoRestoresTheExactReminderAndLaterRunsLeaveItOnItsTopic() {
        val open = reminder("r-1", 15)
        val first = data(listOf(open))
        val conversion = topicReminderConversions(first).single()
        val converted = applied(first, conversion.writes)

        val undone = applied(converted, conversion.undo)

        assertEquals(open, undone.reminders.single(), "The reminder is back on its Topic exactly as it was")
        val task = undone.tasks.single()
        assertTrue(task.discarded && task.deleted, "The Task is hidden everywhere, Trash included")
        assertEquals(emptyList(), topicReminderConversions(undone), "Undo sticks on every device")
    }

    @Test
    fun aRefusalIsSettledOnlyWhenTheTaskIsStoredOrTheReminderMovedOn() {
        val open = reminder("r-1", 15)
        val change = RefusedChange("c-1", topicReminderConversions(data(listOf(open))).single().writes)
        val byOtherDevice = Task(TaskId("k-from-r-1"), "Synthetic r-1", topic.id, createdAtMillis = 2)
        val movedThere = open.copy(target = ReminderTarget(TargetKind.Task, "k-from-r-1"))

        assertTrue(isSettledConversion(change, data(listOf(movedThere), listOf(byOtherDevice))))
        assertTrue(isSettledConversion(change, data(listOf(open), listOf(byOtherDevice))), "The next run moves the reminder")
        assertTrue(isSettledConversion(change, data(listOf(open.copy(dismissed = true)))))
        assertTrue(isSettledConversion(change, data(emptyList())), "The reminder was deleted forever")
        assertFalse(isSettledConversion(change, data(listOf(open))), "Still needed: the refusal stays listed")
        val other = RefusedChange("c-2", change.writes + WorkspaceWrite.PutTopic(topic.copy(name = "Synthetic renamed"), before = topic))
        assertFalse(isSettledConversion(other, data(listOf(movedThere), listOf(byOtherDevice))), "Not only a conversion")
        assertFalse(isSettledConversion(RefusedChange("c-3", listOf(WorkspaceWrite.PutTopic(topic))), data(emptyList())))
    }

    @Test
    fun theConverterWaitsForServerDataOnlineWithNothingPending() =
        runTest {
            val pending = listOf(reminder("r-1", 15))
            val store = RecordingStore()
            val log = mutableListOf<String>()
            val converter = TopicReminderConverter(store) { log += "backup" }

            assertEquals(emptyList(), converter.run({ data(pending, fromServer = false) }, { true }), "Cache only")
            assertEquals(emptyList(), converter.run({ data(pending, pendingWrites = true) }, { true }), "Writes waiting")
            assertEquals(emptyList(), converter.run({ data(pending) }, { false }), "Offline")
            assertEquals(emptyList(), converter.run({ null }, { true }), "Not loaded")
            assertEquals(emptyList(), log)
            assertEquals(emptyList(), store.batches)
        }

    @Test
    fun theBackupIsSavedBeforeAnyWriteAndEachReminderIsItsOwnBatch() =
        runTest {
            val log = mutableListOf<String>()
            val store = RecordingStore(log)
            var saved: List<ReminderConversion> = emptyList()
            val converter =
                TopicReminderConverter(store) {
                    log += "backup"
                    saved = it
                }
            val current = data(listOf(reminder("r-1", 15), reminder("r-2", 16)))

            val written = converter.run({ current }, { true })

            assertEquals(listOf("backup", "write", "write"), log)
            assertEquals(written, saved, "The backup holds exactly what was written")
            assertEquals(written.map { it.writes }, store.batches)
            assertEquals(emptyList(), converter.run({ current }, { true }), "A stale snapshot never repeats a write")
            assertEquals(2, store.batches.size)
        }

    @Test
    fun aFailedBackupWritesNothingAndTheNextSnapshotTriesAgain() =
        runTest {
            val store = RecordingStore()
            var fail = true
            val converter = TopicReminderConverter(store) { if (fail) error("Synthetic storage failure") }
            val current = data(listOf(reminder("r-1", 15)))

            assertEquals(emptyList(), converter.run({ current }, { true }))
            assertEquals(emptyList(), store.batches)

            fail = false
            assertEquals(1, converter.run({ current }, { true }).size)
            assertEquals(1, store.batches.size)
        }

    @Test
    fun onlyWhatIsStillNeededAfterTheBackupIsWritten() =
        runTest {
            val store = RecordingStore()
            val first = reminder("r-1", 15)
            val second = reminder("r-2", 16)
            var current = data(listOf(first, second))
            // While the backup is saved, another device converts r-1 and a new Topic reminder r-3 arrives.
            val converter =
                TopicReminderConverter(store) {
                    val elsewhere = topicReminderConversions(current).first { it.reminder.id == first.id }
                    current = applied(current, elsewhere.writes).let { it.copy(reminders = it.reminders + reminder("r-3", 17)) }
                }

            val written = converter.run({ current }, { true })

            assertEquals(listOf("r-2"), written.map { it.reminder.id.value }, "r-3 was not in the backup; it waits for the next run")
        }

    @Test
    fun aReminderWithAnUnsettledRefusalIsNotTriedAgain() =
        runTest {
            val open = reminder("r-1", 15)
            val store = RecordingStore()
            store.refused.value = listOf(RefusedChange("c-1", topicReminderConversions(data(listOf(open))).single().writes))
            val converter = TopicReminderConverter(store) {}

            assertEquals(emptyList(), converter.run({ data(listOf(open)) }, { true }))
            assertEquals(emptyList(), store.batches)
        }

    @Test
    fun settledRefusalsAreDroppedAndOthersStay() {
        val open = reminder("r-1", 15)
        val store = RecordingStore()
        val settled = RefusedChange("c-1", topicReminderConversions(data(listOf(open))).single().writes)
        val unrelated = RefusedChange("c-2", listOf(WorkspaceWrite.PutTopic(topic.copy(name = "Synthetic renamed"), before = topic)))
        store.refused.value = listOf(settled, unrelated)
        val byOtherDevice = Task(TaskId("k-from-r-1"), "Synthetic r-1", topic.id, createdAtMillis = 2)
        val converter = TopicReminderConverter(store) {}

        converter.dropSettled(data(listOf(open), listOf(byOtherDevice), fromServer = false))
        assertEquals(2, store.refused.value.size, "Cache data decides nothing")

        converter.dropSettled(data(listOf(open), listOf(byOtherDevice)))
        assertEquals(listOf(unrelated), store.refused.value)
    }

    @Test
    fun undoIsOneBatchForAllConversions() {
        val store = RecordingStore()
        val conversions = topicReminderConversions(data(listOf(reminder("r-1", 15), reminder("r-2", 16))))

        TopicReminderConverter(store) {}.undo(conversions)

        assertEquals(listOf(conversions.flatMap { it.undo }), store.batches)
    }

    /** [data] after [writes], as the store would show them. Only Task and Reminder writes are needed here. */
    private fun applied(
        data: WorkspaceData,
        writes: List<WorkspaceWrite>,
    ): WorkspaceData =
        writes.fold(data) { current, write ->
            when (write) {
                is WorkspaceWrite.PutTask ->
                    current.copy(tasks = current.tasks.filterNot { it.id == write.task.id } + write.task.copy(createdAtMillis = 1))
                is WorkspaceWrite.PutReminder ->
                    current.copy(reminders = current.reminders.filterNot { it.id == write.reminder.id } + write.reminder)
                else -> error("Not used here")
            }
        }

    private class RecordingStore(
        private val log: MutableList<String> = mutableListOf(),
    ) : WorkspaceStore {
        val batches = mutableListOf<List<WorkspaceWrite>>()
        override val refused = MutableStateFlow<List<RefusedChange>>(emptyList())

        override fun observe(): Flow<WorkspaceData> = emptyFlow()

        override fun write(writes: List<WorkspaceWrite>) {
            log += "write"
            batches += writes
        }

        override fun retry(change: RefusedChange) = Unit

        override fun discard(change: RefusedChange) {
            refused.value = refused.value - change
        }

        override fun keep(change: RefusedChange) = Unit
    }
}
