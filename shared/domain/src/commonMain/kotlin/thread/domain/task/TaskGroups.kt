package thread.domain.task

import thread.domain.time.WallTime
import thread.domain.topic.Topic

// The shared task-group rule (D90): how a list of Tasks is ordered and grouped. Today, Inbox (D91,
// `workspace/InboxGroups`) and Topic Details (D92, `workspace/TopicDetailsView`) use it. A screen picks which Tasks it
// lists; this file orders and groups them, the same way everywhere. Days are the workspace's (A12), as every [WallTime] is.

/** The due-date groups (D90, owner answer Q1), in the order a screen shows them. Each Task is in exactly one. */
enum class DueGroup {
    /** Completed. Which completed Tasks a screen lists is its own choice: Today lists the ones completed today. */
    Completed,

    /**
     * Open, with a date today or before: overdue, planned, due or reminded today (D54), or planned on an earlier day and
     * still open. Before D90 these were three groups: Overdue, Today and Earlier planned.
     */
    Today,

    /** Open, and its first date is tomorrow. */
    Tomorrow,

    /** Open, and its first date is after tomorrow, or it has no date. Before D90: Upcoming and No date. */
    Upcoming,
}

/**
 * The [DueGroup] of [task]. Its first date is the earliest of its planned day, deadline and [remindAt], its next open
 * reminder (D54). A reminder that is due and not dismissed yet has a day before tomorrow, so it counts as today.
 */
fun dueGroupOf(
    task: Task,
    now: WallTime,
    remindAt: WallTime? = null,
): DueGroup {
    if (task.done) return DueGroup.Completed
    val first = firstDayOf(task, remindAt) ?: return DueGroup.Upcoming
    return when {
        first <= now.day -> DueGroup.Today
        first == now.day.plusDays(1) -> DueGroup.Tomorrow
        else -> DueGroup.Upcoming
    }
}

/**
 * [tasks] in the one order every grouping keeps (D90). It is also the whole list with Group by None.
 *
 * 1. Open Tasks of the [DueGroup.Today] group, in D37's order: overdue ones by deadline, then the ones planned, due or
 *    reminded today by deadline (none last), then the ones planned on an earlier day by planned day.
 * 2. Completed Tasks, oldest first. So the day's own part stays together: what is left to do, then what is done.
 * 3. [DueGroup.Tomorrow], then the dated [DueGroup.Upcoming] Tasks: by first day, then the time of a timed deadline or
 *    reminder on that day, then creation (D53).
 * 4. Open Tasks with no date, newest first (D53).
 *
 * [remindAt] holds each Task's next open reminder (D54).
 */
fun dueOrder(
    tasks: List<Task>,
    now: WallTime,
    remindAt: Map<TaskId, WallTime> = emptyMap(),
): List<Task> {
    val byGroup = tasks.sortedWith(CREATION_ORDER).groupBy { dueGroupOf(it, now, remindAt[it.id]) }

    fun inGroup(group: DueGroup) = byGroup[group].orEmpty()
    val (dated, noDate) = inGroup(DueGroup.Upcoming).partition { firstDayOf(it, remindAt[it.id]) != null }
    // The lists are already in creation order, and sortedWith is stable.
    val byFirstDay =
        compareBy<Task> { firstDayOf(it, remindAt[it.id]) }.thenBy(nullsLast()) { timeOnFirstDay(it, remindAt[it.id]) }
    return todayOrder(inGroup(DueGroup.Today), now, remindAt) +
        inGroup(DueGroup.Completed) +
        (inGroup(DueGroup.Tomorrow) + dated).sortedWith(byFirstDay) +
        noDate.reversed()
}

/** The open Tasks of the Today group in D37's order: overdue, then today's, then the ones planned on an earlier day. */
private fun todayOrder(
    tasks: List<Task>,
    now: WallTime,
    remindAt: Map<TaskId, WallTime>,
): List<Task> {
    val (overdue, rest) = tasks.partition { it.deadline?.isPassed(now) == true }
    val (todays, earlier) =
        rest.partition { task ->
            task.plannedDay == now.day || task.deadline?.day == now.day || remindAt[task.id]?.let { it.day <= now.day } == true
        }
    return overdue.sortedBy { it.deadline } + todays.sortedWith(compareBy(nullsLast()) { it.deadline }) + earlier.sortedBy { it.plannedDay }
}

/** One [DueGroup] and its Tasks, in [dueOrder]. */
data class DueTaskGroup(
    val group: DueGroup,
    val tasks: List<Task>,
)

/** [tasks] by due date (D90): the groups that have a Task, in [DueGroup] order. An empty group is left out (Q2). */
fun groupByDue(
    tasks: List<Task>,
    now: WallTime,
    remindAt: Map<TaskId, WallTime> = emptyMap(),
): List<DueTaskGroup> {
    val byGroup = dueOrder(tasks, now, remindAt).groupBy { dueGroupOf(it, now, remindAt[it.id]) }
    return DueGroup.entries.mapNotNull { group -> byGroup[group]?.let { DueTaskGroup(group, it) } }
}

/** One Topic's Tasks, in [dueOrder]. [topic] is null for the Tasks with no Topic. */
data class TopicTaskGroup(
    val topic: Topic?,
    val tasks: List<Task>,
)

/**
 * [tasks] by Topic (D90, A4): the Topic with the most urgent open Task first, by [dueOrder]; then the Topics with only
 * completed Tasks, in the order of their first one; the Tasks with no Topic last. A Task whose Topic is not in [topics]
 * (in Trash, or not loaded yet) counts as having none. Only Topics with a Task have a group.
 */
fun groupByTopic(
    tasks: List<Task>,
    topics: List<Topic>,
    now: WallTime,
    remindAt: Map<TaskId, WallTime> = emptyMap(),
): List<TopicTaskGroup> {
    val ordered = dueOrder(tasks, now, remindAt)
    val position = ordered.withIndex().associate { (index, task) -> task.id to index }
    val known = topics.associateBy { it.id }
    // groupBy keeps the order in which each Topic first appears, and sortedWith is stable.
    return ordered
        .groupBy { task -> task.topicId?.let { known[it] } }
        .entries
        .sortedWith(
            compareBy(
                { it.key == null },
                { entry -> entry.value.firstOrNull { !it.done }?.let { position.getValue(it.id) } ?: Int.MAX_VALUE },
            ),
        ).map { TopicTaskGroup(it.key, it.value) }
}
