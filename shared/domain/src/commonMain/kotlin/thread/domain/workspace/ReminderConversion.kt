package thread.domain.workspace

import thread.domain.reminder.Reminder
import thread.domain.reminder.ReminderId
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.task.TASK_TITLE_MAX_LENGTH
import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.topic.TopicId
import kotlin.coroutines.cancellation.CancellationException

/*
 * Topic reminders become Tasks (D54, adr/ADR-018, part B). Since D54 a reminder belongs to a Task or a Note. Each
 * reminder an older build put on a Topic, still open, moves onto a Task of its own in that Topic. The Task id comes from
 * the reminder id, so two devices, a second run and a run cut short all end with one Task per reminder.
 */

/** Ids of Tasks made from a Topic reminder start with this, followed by the reminder id. */
const val CONVERTED_TASK_PREFIX: String = "k-from-"

/** The Task a Topic reminder [id] moves onto. Always the same for one reminder, on every device. */
fun convertedTaskId(id: ReminderId): TaskId = TaskId(CONVERTED_TASK_PREFIX + id.value)

/**
 * One open Topic reminder moving onto [task]. [createsTask] is false only when the Task is already stored, for
 * example from a run that another device started.
 */
data class ReminderConversion(
    /** The reminder as stored, on its Topic. */
    val reminder: Reminder,
    val task: Task,
    val createsTask: Boolean,
) {
    /** The reminder on its Task. Everything else about it stays the same. */
    val moved: Reminder get() = reminder.copy(target = ReminderTarget(TargetKind.Task, task.id.value))

    /** The writes, applied together: the Task, when it is new, and the reminder's new target. */
    val writes: List<WorkspaceWrite>
        get() =
            buildList {
                if (createsTask) add(WorkspaceWrite.PutTask(task, isNew = true))
                add(WorkspaceWrite.PutReminder(moved, before = reminder))
            }

    /**
     * Undo: the reminder goes back to its Topic, and the Task is discarded, which hides it everywhere, Trash included.
     * A discarded Task also tells later runs, on any device, to leave that reminder on its Topic.
     */
    val undo: List<WorkspaceWrite>
        get() =
            listOf(
                WorkspaceWrite.PutReminder(reminder, before = moved),
                WorkspaceWrite.PutTask(task.copy(deleted = true, discarded = true), before = task),
            )
}

/**
 * What still needs converting in [data], by reminder time. [data] is everything stored, Topics in Trash included.
 *
 * - A reminder counts while it points at a stored Topic (also one in Trash), is not dismissed and is not in Trash.
 *   Dismissed reminders stay as they are. A reminder in Trash is converted once it is restored.
 * - Its Task is `k-from-<reminder id>`: in the same Topic, titled with the reminder text, open, with no dates and not
 *   in Inbox.
 * - When that Task is already stored, only the reminder moves. When that Task is discarded, the conversion was undone:
 *   the reminder stays on its Topic.
 */
fun topicReminderConversions(data: WorkspaceData): List<ReminderConversion> {
    val topicIds = data.topics.map { it.id.value }.toSet()
    val tasks = data.tasks.associateBy { it.id }
    return data.reminders
        .filter { it.target.kind == TargetKind.Topic && !it.dismissed && !it.deleted && it.target.id in topicIds }
        .sortedWith(compareBy<Reminder> { it.at }.thenBy { it.id.value })
        .mapNotNull { reminder ->
            val stored = tasks[convertedTaskId(reminder.id)]
            when {
                stored == null -> ReminderConversion(reminder, taskFor(reminder), createsTask = true)
                stored.discarded -> null
                else -> ReminderConversion(reminder, stored, createsTask = false)
            }
        }
}

private fun taskFor(reminder: Reminder): Task =
    Task(
        id = convertedTaskId(reminder.id),
        // Reminder text is never blank and at most 500 characters, the same as a Task title.
        title = reminder.text.trim().take(TASK_TITLE_MAX_LENGTH),
        topicId = TopicId(reminder.target.id),
        inbox = false,
    )

/**
 * True when [change] is a refused conversion that no longer needs to happen: another device stored the same Task first
 * (its create then fails here, because each create sets its own creation time), or the reminder moved on. Such a
 * refusal is dropped without showing, because nothing was lost. Any other refusal stays listed.
 */
fun isSettledConversion(
    change: RefusedChange,
    data: WorkspaceData,
): Boolean {
    val moves = change.writes.filterIsInstance<WorkspaceWrite.PutReminder>()
    if (moves.isEmpty() || !change.writes.all(::isConversionWrite)) return false
    val stillNeeded = topicReminderConversions(data).filter { it.createsTask }.map { it.reminder.id }.toSet()
    return moves.none { it.reminder.id in stillNeeded }
}

private fun isConversionWrite(write: WorkspaceWrite): Boolean =
    when (write) {
        is WorkspaceWrite.PutTask ->
            write.isNew &&
                write.before == null &&
                write.task.id.value
                    .startsWith(CONVERTED_TASK_PREFIX)
        is WorkspaceWrite.PutReminder ->
            write.before?.target?.kind == TargetKind.Topic &&
                write.reminder.target == ReminderTarget(TargetKind.Task, convertedTaskId(write.reminder.id).value)
        else -> false
    }

/** Keeps a copy of what a conversion changes on this device, before anything is written (D54). */
fun interface ConversionBackup {
    /** Throws when the copy could not be stored. Then nothing is converted. */
    suspend fun save(conversions: List<ReminderConversion>)
}

/**
 * Runs the Topic reminder conversion for one signed-in account (D54). [run] is called on every new snapshot; it acts
 * only when the data is the server's, nothing of this device is waiting to sync and the device is online. It saves
 * the [backup] first, and writes nothing when that fails. Each reminder's writes go in their own batch, so a refusal
 * affects that reminder only.
 */
class TopicReminderConverter(
    private val store: WorkspaceStore,
    private val backup: ConversionBackup,
) {
    /** Reminders this converter already wrote. A snapshot that does not show those writes yet never repeats them. */
    private val written = mutableSetOf<ReminderId>()
    private var running = false

    /**
     * Converts what [current] still needs and returns what it wrote, for the toast and its Undo. Empty when it is not
     * ready, has nothing to do, another run is going on, or the backup failed. [current] is read again after the
     * backup, and only conversions that are in the backup and still needed are written.
     */
    suspend fun run(
        current: () -> WorkspaceData?,
        online: () -> Boolean,
    ): List<ReminderConversion> {
        if (running) return emptyList()
        running = true
        try {
            val planned = due(current(), online()).ifEmpty { return emptyList() }
            try {
                backup.save(planned)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // No copy on this device, so no change: the next snapshot tries again.
                return emptyList()
            }
            val backedUp = planned.toSet()
            val now = due(current(), online()).filter { it in backedUp }
            now.forEach { conversion ->
                written += conversion.reminder.id
                store.write(conversion.writes)
            }
            return now
        } finally {
            running = false
        }
    }

    /** Takes [conversions] back: one batch, so the reminders and their Tasks change together. */
    fun undo(conversions: List<ReminderConversion>) {
        if (conversions.isNotEmpty()) store.write(conversions.flatMap { it.undo })
    }

    /** Drops refused conversions that [data], the server's, shows are no longer needed ([isSettledConversion]). */
    fun dropSettled(data: WorkspaceData) {
        if (!data.fromServer) return
        store.refused.value
            .filter { isSettledConversion(it, data) }
            .forEach(store::discard)
    }

    private fun due(
        data: WorkspaceData?,
        online: Boolean,
    ): List<ReminderConversion> {
        if (data == null || !online || !data.fromServer || data.pendingWrites) return emptyList()
        // A reminder whose earlier conversion was refused for another reason stays listed in the sync panel, once.
        val refused =
            store.refused.value
                .flatMap { it.writes }
                .filterIsInstance<WorkspaceWrite.PutReminder>()
                .map { it.reminder.id }
                .toSet()
        return topicReminderConversions(data).filter { it.reminder.id !in written && it.reminder.id !in refused }
    }
}
