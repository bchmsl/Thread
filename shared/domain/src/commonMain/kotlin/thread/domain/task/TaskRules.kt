package thread.domain.task

import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.TopicId

/** Longest Task title. v3 sets no limit; this one only keeps documents small. */
const val TASK_TITLE_MAX_LENGTH: Int = 500

/**
 * A new open Task (v3 `createTask`). Without a Topic it waits in Inbox. Returns null when the trimmed title is blank
 * or too long.
 *
 * D38: a Task added from Today is planned for today, so Today passes [plannedDay]; without a Topic it also waits in
 * Inbox until it is filed.
 */
fun newTask(
    id: TaskId,
    title: String,
    topicId: TopicId?,
    plannedDay: LocalDay? = null,
): Task? {
    val trimmed = title.trim()
    if (trimmed.isEmpty() || trimmed.length > TASK_TITLE_MAX_LENGTH) return null
    return Task(id = id, title = trimmed, topicId = topicId, plannedDay = plannedDay, inbox = topicId == null)
}

/** Tasks that are not in Trash and not discarded: the ones every view works with. */
fun liveTasks(tasks: List<Task>): List<Task> = tasks.filterNot { it.deleted }

/** A Topic's live Tasks, oldest first. */
fun topicTasks(
    topicId: TopicId,
    tasks: List<Task>,
): List<Task> = liveTasks(tasks).filter { it.topicId == topicId }.sortedWith(CREATION_ORDER)

/**
 * The first open Task of each Topic, in the order Topic Details lists them (oldest first): what Topic rows and cards
 * show as "Next" (D52). Topics without an open Task are not in the map.
 */
fun firstOpenTasks(tasks: List<Task>): Map<TopicId, Task> =
    liveTasks(tasks)
        .filter { !it.done && it.topicId != null }
        .sortedWith(CREATION_ORDER)
        .groupBy { it.topicId!! }
        .mapValues { it.value.first() }

/** Personal Task progress of one Topic (v3 `progress`). Jira tickets and pull requests never count. */
data class TaskProgress(
    val done: Int,
    val total: Int,
)

fun progressOf(
    topicId: TopicId,
    tasks: List<Task>,
): TaskProgress {
    val own = topicTasks(topicId, tasks)
    return TaskProgress(done = own.count { it.done }, total = own.size)
}

/** True when [task] is open and its deadline is behind [now]. */
fun isOverdue(
    task: Task,
    now: WallTime,
): Boolean = !task.done && task.deadline?.isPassed(now) == true

/**
 * The earliest of [task]'s planned day, deadline day and the day of [remindAt], its next reminder (D54), or null when
 * it has none of them.
 */
fun firstDayOf(
    task: Task,
    remindAt: WallTime? = null,
): LocalDay? = listOfNotNull(task.plannedDay, task.deadline?.day, remindAt?.day).minOrNull()

/**
 * Whether [task] shows in Today (D37, D53, D90): every open Task, and one completed today while [keepCompleted] is on
 * (Settings > Today, "Show completed tasks"). A Task waiting in Inbox with no planned day, no deadline and no open
 * reminder is left to Inbox (D38), done or not. [remindAt] is its next open reminder (D54).
 */
fun showsInToday(
    task: Task,
    now: WallTime,
    keepCompleted: Boolean,
    remindAt: WallTime? = null,
): Boolean {
    if (task.deleted) return false
    if (task.inbox && task.plannedDay == null && task.deadline == null && remindAt == null) return false
    return !task.done || (keepCompleted && task.completedOn == now.day)
}

/**
 * The Tasks Today shows ([showsInToday]), in [dueOrder]. [remindAt] holds each Task's next open reminder (D54), from
 * [taskReminderTimes][thread.domain.reminder.taskReminderTimes].
 */
fun todayTasks(
    tasks: List<Task>,
    now: WallTime,
    keepCompleted: Boolean,
    remindAt: Map<TaskId, WallTime> = emptyMap(),
): List<Task> = dueOrder(tasks.filter { showsInToday(it, now, keepCompleted, remindAt[it.id]) }, now, remindAt)

/** The earliest time on [task]'s first day: of a timed deadline or of its next reminder on that day. Null without one. */
internal fun timeOnFirstDay(
    task: Task,
    remindAt: WallTime?,
): Int? {
    val first = firstDayOf(task, remindAt)
    val deadline = task.deadline?.takeIf { it.day == first }?.minuteOfDay
    val reminder = remindAt?.takeIf { it.day == first }?.minuteOfDay
    return listOfNotNull(deadline, reminder).minOrNull()
}

/** Tasks waiting in Inbox, newest first (v3 `inboxItems`). Notes in Inbox: `inboxNotes`. */
fun inboxTasks(tasks: List<Task>): List<Task> = liveTasks(tasks).filter { it.inbox }.sortedWith(CREATION_ORDER.reversed())

/** Oldest first. A Task whose creation time is not known yet is the newest. */
internal val CREATION_ORDER: Comparator<Task> =
    compareBy<Task> { it.createdAtMillis ?: Long.MAX_VALUE }.thenBy { it.id.value }
