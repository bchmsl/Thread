package thread.domain.workspace

import thread.domain.reminder.Reminder
import thread.domain.task.Deadline
import thread.domain.task.TASK_TITLE_MAX_LENGTH
import thread.domain.task.Task
import thread.domain.time.LocalDay
import thread.domain.topic.TopicId

/*
 * Task and Reminder edits (adr/ADR-010-tasks-reminders-and-dates.md). Each returns the writes and the writes that
 * take them back, for Undo. Like Topic edits, a write carries the value it was made from, so the store writes only
 * the fields that changed.
 */

/** The write that stores a new Task. */
fun createTaskWrite(task: Task): WorkspaceWrite.PutTask = WorkspaceWrite.PutTask(task, isNew = true)

/**
 * Completes or reopens [task] (01-product-spec.md: a checkbox, no status workflow). Completion records [today], the
 * workspace day, which keeps it in Today until the day ends.
 */
fun toggleTask(
    task: Task,
    today: LocalDay,
): EditResult =
    taskChange(task, if (task.done) task.copy(done = false, completedOn = null) else task.copy(done = true, completedOn = today))

/**
 * Edits a Task's title, Topic, planned day or deadline. A blank title is refused. Picking a Topic files the Task out
 * of Inbox (v3 `task-topic`); taking the Topic away does not put it back in Inbox.
 */
fun editTask(
    task: Task,
    title: String = task.title,
    topicId: TopicId? = task.topicId,
    plannedDay: LocalDay? = task.plannedDay,
    deadline: Deadline? = task.deadline,
): EditResult {
    val trimmed = title.trim()
    when {
        trimmed.isEmpty() -> return EditResult.Refused(EditProblem.BlankName)
        trimmed.length > TASK_TITLE_MAX_LENGTH -> return EditResult.Refused(EditProblem.NameTooLong)
    }
    val inbox = if (topicId != null && topicId != task.topicId) false else task.inbox
    return taskChange(
        task,
        task.copy(title = trimmed, topicId = topicId, plannedDay = plannedDay, deadline = deadline, inbox = inbox),
    )
}

/** Plans [task] for [day], or clears its planned day (03-architecture.md, PlanTaskForDay). */
fun planTask(
    task: Task,
    day: LocalDay?,
): EditResult = editTask(task, plannedDay = day)

/**
 * Files Tasks out of Inbox (03-architecture.md, TriageInboxItem). With [topicId] each Task joins that Topic; without
 * one it is kept standalone. Nothing is copied or deleted (D22). Undo puts each Task back as it was.
 */
fun triageTasks(
    tasks: List<Task>,
    topicId: TopicId?,
): EditResult {
    val changes =
        tasks.mapNotNull { task ->
            val after = task.copy(topicId = topicId ?: task.topicId, inbox = false)
            if (after == task) null else task to after
        }
    if (changes.isEmpty()) return EditResult.Unchanged
    return EditResult.Changed(
        WorkspaceEdit(
            writes = changes.map { (before, after) -> taskWrite(before, after) },
            undo = changes.map { (before, after) -> taskWrite(after, before) },
        ),
    )
}

/** Moves [task] to Trash (v3 "Move to Trash"). Undo brings it back. */
fun trashTask(task: Task): EditResult = taskChange(task, task.copy(deleted = true))

/**
 * Takes back a Task made a moment ago (the Undo of its "added" toast, v3 `discardTask`). It leaves every view,
 * Trash included. The document stays, because nothing is hard-deleted.
 */
fun discardTask(task: Task): List<WorkspaceWrite> = listOf(taskWrite(task, task.copy(deleted = true, discarded = true)))

/** The write that stores a new Reminder. */
fun createReminderWrite(reminder: Reminder): WorkspaceWrite.PutReminder = WorkspaceWrite.PutReminder(reminder, isNew = true)

/** Closes a Reminder's banner. It stays in Today's list. */
fun dismissReminder(reminder: Reminder): List<WorkspaceWrite> =
    if (reminder.dismissed) emptyList() else listOf(reminderWrite(reminder, reminder.copy(dismissed = true)))

/** Moves a Reminder to Trash (v3 "Reminder moved to Trash"). Undo brings it back. */
fun trashReminder(reminder: Reminder): EditResult {
    if (reminder.deleted) return EditResult.Unchanged
    val after = reminder.copy(deleted = true)
    return EditResult.Changed(WorkspaceEdit(listOf(reminderWrite(reminder, after)), listOf(reminderWrite(after, reminder))))
}

private fun taskChange(
    before: Task,
    after: Task,
): EditResult =
    if (after == before) {
        EditResult.Unchanged
    } else {
        EditResult.Changed(WorkspaceEdit(listOf(taskWrite(before, after)), listOf(taskWrite(after, before))))
    }

private fun taskWrite(
    before: Task,
    after: Task,
) = WorkspaceWrite.PutTask(after, before = before)

private fun reminderWrite(
    before: Reminder,
    after: Reminder,
) = WorkspaceWrite.PutReminder(after, before = before)
