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

/** The groups of Today's main column (D37). */
enum class TodayGroup {
    /** The deadline passed. */
    Overdue,

    /** Planned for today, or due later today. */
    Today,

    /** Planned before today and still open. Nothing moves it automatically. */
    EarlierPlanned,
}

/**
 * The one Today group [task] belongs to by its dates, or null when it does not show in Today (v3 `taskGroup`).
 * A Task that qualifies twice, for example planned for today and overdue, is in the first group that matches, so it
 * shows once.
 */
fun todayGroupOf(
    task: Task,
    now: WallTime,
): TodayGroup? {
    val today = now.day
    return when {
        task.deadline?.isPassed(now) == true -> TodayGroup.Overdue
        task.plannedDay == today || task.deadline?.day == today -> TodayGroup.Today
        task.plannedDay != null && task.plannedDay < today -> TodayGroup.EarlierPlanned
        else -> null
    }
}

/** Today's Tasks by group, each Task in one group at most. */
data class TodayTasks(
    val overdue: List<Task>,
    val today: List<Task>,
    val earlier: List<Task>,
) {
    val openCount: Int get() = (overdue + today + earlier).count { !it.done }

    val doneCount: Int get() = (overdue + today + earlier).count { it.done }
}

/**
 * Today's main column (D37, v3 `todayGroups`).
 *
 * - Open Tasks go to the group of [todayGroupOf].
 * - A Task completed today stays while [keepCompleted] is on (Settings, "Keep completed tasks in Today"), in Earlier
 *   planned when it was planned before today, else in Today. It leaves the next day.
 * - A Task in Inbox with no planned day and no deadline does not show, done or not.
 *
 * Overdue is sorted by deadline, Today by open first and then deadline, Earlier planned by planned day.
 */
fun todayTasks(
    tasks: List<Task>,
    now: WallTime,
    keepCompleted: Boolean,
): TodayTasks {
    val overdue = mutableListOf<Task>()
    val today = mutableListOf<Task>()
    val earlier = mutableListOf<Task>()
    liveTasks(tasks).sortedWith(CREATION_ORDER).forEach { task ->
        if (task.inbox && task.plannedDay == null && task.deadline == null) return@forEach
        val group =
            when {
                !task.done -> todayGroupOf(task, now)
                !keepCompleted || task.completedOn != now.day -> null
                // A completed Task is never overdue; one with no Today group of its own stays under Today.
                else -> todayGroupOf(task, now).takeIf { it == TodayGroup.EarlierPlanned } ?: TodayGroup.Today
            }
        when (group) {
            TodayGroup.Overdue -> overdue += task
            TodayGroup.Today -> today += task
            TodayGroup.EarlierPlanned -> earlier += task
            null -> Unit
        }
    }
    return TodayTasks(
        overdue = overdue.sortedWith(compareBy(nullsLast()) { it.deadline }),
        today = today.sortedWith(compareBy<Task> { it.done }.thenBy(nullsLast()) { it.deadline }),
        earlier = earlier.sortedWith(compareBy(nullsLast()) { it.plannedDay }),
    )
}

/** Tasks waiting in Inbox, newest first (v3 `inboxItems`). Notes in Inbox: `inboxNotes`. */
fun inboxTasks(tasks: List<Task>): List<Task> = liveTasks(tasks).filter { it.inbox }.sortedWith(CREATION_ORDER.reversed())

/** Oldest first. A Task whose creation time is not known yet is the newest. */
private val CREATION_ORDER: Comparator<Task> =
    compareBy<Task> { it.createdAtMillis ?: Long.MAX_VALUE }.thenBy { it.id.value }
