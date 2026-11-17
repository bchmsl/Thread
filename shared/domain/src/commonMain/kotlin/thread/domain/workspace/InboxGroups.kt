package thread.domain.workspace

import thread.domain.note.NoteSummary
import thread.domain.task.DueGroup
import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.task.groupByDue
import thread.domain.time.WallTime

// What Inbox shows and how it groups it (owner change #25, D91). Inbox holds the Tasks and Notes made without a Topic
// until they are filed (D38, D39). Its Tasks group by due date with the shared task-group rule (`task/TaskGroups`, D90).

/** A Task or a Note waiting in Inbox. */
sealed interface InboxEntry {
    /** When it was captured. Null until the server stamps it, so it counts as the newest. */
    val createdAtMillis: Long?

    data class OfTask(
        val task: Task,
    ) : InboxEntry {
        override val createdAtMillis: Long? get() = task.createdAtMillis
    }

    data class OfNote(
        val note: NoteSummary,
    ) : InboxEntry {
        override val createdAtMillis: Long? get() = note.createdAtMillis
    }
}

/**
 * What Inbox shows (D91): its [tasks] and [notes], newest first, less the completed Tasks while "Show completed tasks"
 * is off (I1) and the Notes while "Show notes" is off (I3). A hidden one still waits in Inbox, untriaged: [hiddenCompleted]
 * and [hiddenNotes] count them. [count] is the Inbox count (Q8): what shows.
 */
data class InboxShown(
    val tasks: List<Task>,
    val notes: List<NoteSummary>,
    val hiddenCompleted: Int = 0,
    val hiddenNotes: Int = 0,
) {
    val count: Int get() = tasks.size + notes.size

    /** Tasks and Notes together, newest capture first (v3 S.inboxItems). The list with Group by None. */
    val entries: List<InboxEntry>
        get() =
            (tasks.map { InboxEntry.OfTask(it) } + notes.map { InboxEntry.OfNote(it) })
                .sortedByDescending { it.createdAtMillis ?: Long.MAX_VALUE }
}

/**
 * What Inbox shows of [tasks] and [notes], the ones waiting in it, newest first (`inboxTasks`, `inboxNotes`), with
 * Settings > Inbox [showCompleted] and [showNotes].
 */
fun inboxShown(
    tasks: List<Task>,
    notes: List<NoteSummary>,
    showCompleted: Boolean,
    showNotes: Boolean,
): InboxShown {
    val shownTasks = if (showCompleted) tasks else tasks.filter { !it.done }
    return InboxShown(
        tasks = shownTasks,
        notes = if (showNotes) notes else emptyList(),
        hiddenCompleted = tasks.size - shownTasks.size,
        hiddenNotes = if (showNotes) 0 else notes.size,
    )
}

/** A heading of Inbox's list (D91). */
sealed interface InboxHeading {
    /** Group by None: one list, without a heading, as before D91. */
    data object All : InboxHeading

    /** Group by Type: the Tasks. */
    data object Tasks : InboxHeading

    /** The Notes: a group with Group by Type, and the last group with Group by Due date (A6). */
    data object Notes : InboxHeading

    /** Group by Due date: one of the four groups (Q1). [DueGroup.Completed] holds every completed Task in Inbox. */
    data class Due(
        val group: DueGroup,
    ) : InboxHeading
}

/** One group of Inbox's list and its entries, in the order they show. */
data class InboxGroup(
    val heading: InboxHeading,
    val entries: List<InboxEntry>,
)

/**
 * [shown] in groups (D91), by Settings > Inbox [groupBy]. An empty group is left out (Q2).
 *
 * - None: one list, newest first, as before D91.
 * - Type: Tasks, then Notes, each newest first.
 * - Due date: the Tasks in the four groups of the shared rule ([groupByDue]: Completed, Today, Tomorrow, Upcoming, in
 *   its order), then the Notes, newest first: they have no date (A6). [remindAt] holds each Task's next open reminder,
 *   whose day counts as a date (D54).
 */
fun inboxGroups(
    shown: InboxShown,
    groupBy: InboxGroupBy,
    now: WallTime,
    remindAt: Map<TaskId, WallTime> = emptyMap(),
): List<InboxGroup> {
    val notes = InboxGroup(InboxHeading.Notes, shown.notes.map { InboxEntry.OfNote(it) })
    val groups =
        when (groupBy) {
            InboxGroupBy.None -> listOf(InboxGroup(InboxHeading.All, shown.entries))
            InboxGroupBy.Type -> listOf(InboxGroup(InboxHeading.Tasks, shown.tasks.map { InboxEntry.OfTask(it) }), notes)
            InboxGroupBy.DueDate ->
                groupByDue(shown.tasks, now, remindAt).map { group ->
                    InboxGroup(InboxHeading.Due(group.group), group.tasks.map { InboxEntry.OfTask(it) })
                } + notes
        }
    return groups.filter { it.entries.isNotEmpty() }
}
