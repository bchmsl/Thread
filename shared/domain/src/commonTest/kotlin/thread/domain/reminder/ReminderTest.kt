package thread.domain.reminder

import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.time.DEFAULT_WORKSPACE_ZONE
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.TopicId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Every Reminder, Task and Topic here is SYNTHETIC test data.
class ReminderTest {
    private val today = LocalDay(2026, 11, 13)
    private val topic = TopicId("t-synthetic")

    private fun at(
        hour: Int,
        minute: Int = 0,
        day: LocalDay = today,
    ) = WallTime.of(day, hour, minute)

    private fun reminder(
        id: String,
        at: WallTime,
        target: ReminderTarget = ReminderTarget(TargetKind.Topic, topic.value),
        dismissed: Boolean = false,
        deleted: Boolean = false,
    ) = Reminder(ReminderId(id), at, DEFAULT_WORKSPACE_ZONE, "Reminder $id", target, dismissed, deleted)

    @Test
    fun aNewReminderGetsDefaultTextAndKeepsItsZone() {
        val target = ReminderTarget(TargetKind.Task, "k1")
        val made = newReminder(ReminderId("r1"), at(15), DEFAULT_WORKSPACE_ZONE, "   ", target)!!
        assertEquals(DEFAULT_REMINDER_TEXT, made.text)
        assertEquals("Asia/Tbilisi", made.zone)
        assertEquals("Check", newReminder(ReminderId("r2"), at(15), DEFAULT_WORKSPACE_ZONE, " Check ", target)!!.text)
        assertNull(newReminder(ReminderId("r3"), at(15), DEFAULT_WORKSPACE_ZONE, "x".repeat(REMINDER_TEXT_MAX_LENGTH + 1), target))
    }

    @Test
    fun todaysRemindersMarkPastAndNext() {
        val early = reminder("a", at(9))
        val now = at(13, 5)
        val atNow = reminder("b", now)
        val later = reminder("c", at(15))
        val last = reminder("d", at(17))
        val tomorrow = reminder("e", at(9, day = today.plusDays(1)))
        val list = todayReminders(listOf(last, tomorrow, later, atNow, early), now)
        assertEquals(listOf("a", "b", "c", "d"), list.map { it.reminder.id.value })
        assertEquals(listOf(true, true, false, false), list.map { it.past })
        assertEquals(listOf(false, false, true, false), list.map { it.next })
    }

    @Test
    fun aReminderIsDueAtItsMinuteUntilDismissed() {
        val due = reminder("a", at(15))
        assertEquals(emptyList(), dueReminders(listOf(due), at(14, 59)))
        assertEquals(listOf(due), dueReminders(listOf(due), at(15)))
        assertEquals(listOf(due), dueReminders(listOf(due), at(9, day = today.plusDays(2))), "missed while closed still shows")
        assertEquals(emptyList(), dueReminders(listOf(due.copy(dismissed = true)), at(16)))
    }

    @Test
    fun d54ADoneTasksReminderShowsNoBanner() {
        val onOpenTask = reminder("a", at(9), target = ReminderTarget(TargetKind.Task, "k-open"))
        val onDoneTask = reminder("b", at(9), target = ReminderTarget(TargetKind.Task, "k-done"))
        val onNoteSameId = reminder("c", at(9), target = ReminderTarget(TargetKind.Note, "k-done"))
        val due = dueReminders(listOf(onOpenTask, onDoneTask, onNoteSameId), at(10), doneTaskIds = setOf("k-done"))
        assertEquals(listOf(onOpenTask, onNoteSameId), due)
    }

    @Test
    fun d54EachTaskGetsItsNextOpenReminder() {
        fun onTask(
            id: String,
            task: String,
            at: WallTime,
            dismissed: Boolean = false,
            deleted: Boolean = false,
        ) = reminder(id, at, ReminderTarget(TargetKind.Task, task), dismissed, deleted)
        val reminders =
            listOf(
                onTask("a", "k1", at(15)),
                onTask("b", "k1", at(9)),
                onTask("c", "k1", at(7), dismissed = true),
                onTask("d", "k2", at(8), deleted = true),
                onTask("e", "k3", at(11, day = today.plusDays(2))),
                reminder("f", at(6)),
                reminder("g", at(6), target = ReminderTarget(TargetKind.Note, "k4")),
            )
        assertEquals(
            mapOf(TaskId("k1") to at(9), TaskId("k3") to at(11, day = today.plusDays(2))),
            taskReminderTimes(reminders),
            "dismissed and trashed reminders, and those on a Topic or Note, are left out",
        )
    }

    @Test
    fun liveRemindersDropTrashAndMissingTargets() {
        val kept = reminder("a", at(10))
        val trashed = reminder("b", at(9), deleted = true)
        val orphan = reminder("c", at(8), target = ReminderTarget(TargetKind.Task, "gone"))
        val live = liveReminders(listOf(kept, trashed, orphan)) { it.kind == TargetKind.Topic }
        assertEquals(listOf(kept), live)
    }

    @Test
    fun aTargetListsItsOwnRemindersByTime() {
        val onTopic = reminder("a", at(12))
        val onTask = reminder("b", at(11), target = ReminderTarget(TargetKind.Task, "k1"))
        val laterOnTask = reminder("c", at(13), target = ReminderTarget(TargetKind.Task, "k1"))
        val trashedOnTask = reminder("d", at(9), target = ReminderTarget(TargetKind.Task, "k1"), deleted = true)
        val all = listOf(laterOnTask, onTopic, onTask, trashedOnTask)
        assertEquals(listOf(onTask, laterOnTask), remindersFor(ReminderTarget(TargetKind.Task, "k1"), all))
        assertEquals(listOf(onTopic), remindersFor(ReminderTarget(TargetKind.Topic, topic.value), all))
    }
}
