package thread.web.presentation.tasks

import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.TopicId
import thread.web.presentation.conversionText
import kotlin.test.Test
import kotlin.test.assertEquals

// D54: what the Task sheet and the toast say about reminders on Tasks. Every Task here is SYNTHETIC.
class ReminderTextTest {
    private val today = LocalDay(2026, 11, 14)
    private val noon = WallTime.of(today, 12, 0)
    private val inTopic = Task(TaskId("k-synthetic"), "Synthetic task", TopicId("t-synthetic"))

    @Test
    fun theTaskSheetNamesAReminderAsTheReasonForToday() {
        assertEquals(
            "Shows in Today under Today because it has a reminder today.",
            todayEffect(inTopic, noon, keepCompleted = true, remindAt = WallTime.of(today, 18, 0)),
        )
        assertEquals(
            "Shows in Today under Upcoming. The day before its first date it moves to Tomorrow.",
            todayEffect(inTopic, noon, keepCompleted = true, remindAt = WallTime.of(today.plusDays(2), 9, 0)),
        )
        assertEquals(
            "Shows in Today under Upcoming. It has no date: plan a day, set a deadline or add a reminder to give it one.",
            todayEffect(inTopic, noon, keepCompleted = true),
        )
        val inInbox = inTopic.copy(topicId = null, inbox = true)
        assertEquals(
            "Shows in Today under Tomorrow. Tomorrow it moves to Today.",
            todayEffect(inInbox, noon, keepCompleted = true, remindAt = WallTime.of(today.plusDays(1), 9, 0)),
            "an Inbox Task with a reminder shows in Today",
        )
    }

    @Test
    fun theToastCountsTheMovedReminders() {
        assertEquals("Moved 1 Topic reminder into a Task", conversionText(1))
        assertEquals("Moved 3 Topic reminders into Tasks", conversionText(3))
    }
}
