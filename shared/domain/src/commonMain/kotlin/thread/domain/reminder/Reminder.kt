package thread.domain.reminder

import thread.domain.task.TaskId
import thread.domain.time.WallTime
import kotlin.jvm.JvmInline

/** A Reminder's id. Made on the device that creates it, so it works offline. */
@JvmInline
value class ReminderId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A Reminder id is not blank" }
    }

    override fun toString(): String = value
}

/**
 * What a Reminder can point at (04-data-and-sync.md: "Reminder has one typed target"). Since D54 new reminders go on a
 * Task or a Note; [Topic] stays readable for reminders an older build made, until they move onto a Task.
 */
enum class TargetKind(
    val id: String,
) {
    Task("task"),
    Note("note"),
    Topic("topic"),
    ;

    companion object {
        fun fromId(id: String?): TargetKind? = entries.firstOrNull { it.id == id }
    }
}

data class ReminderTarget(
    val kind: TargetKind,
    val id: String,
) {
    init {
        require(id.isNotBlank()) { "A Reminder target has an id" }
    }
}

/**
 * An in-app reminder (D27): it shows in Today and as a banner while Thread is open, at [at] on the wall clock of
 * [zone]. Delivery while Thread is closed is not part of v1.
 *
 * [dismissed] is set once the person closes its banner. [deleted] marks a Reminder in Trash.
 */
data class Reminder(
    val id: ReminderId,
    val at: WallTime,
    val zone: String,
    val text: String,
    val target: ReminderTarget,
    val dismissed: Boolean = false,
    val deleted: Boolean = false,
    val createdAtMillis: Long? = null,
    /** When it went to Trash, for the Trash page. */
    val deletedAtMillis: Long? = null,
)

/** Longest reminder text. */
const val REMINDER_TEXT_MAX_LENGTH: Int = 500

/** Text a reminder gets when the person leaves it empty (v3 `addReminderFor`). */
const val DEFAULT_REMINDER_TEXT: String = "Reminder"

/**
 * A new Reminder. Blank text becomes [DEFAULT_REMINDER_TEXT]. Returns null when the text is too long.
 *
 * The pilot has one workspace time zone, and "now" is always taken in it, so [zone] is that zone. It is stored with
 * the reminder so a later version can convert between zones (04-data-and-sync.md: "retain intended timezone for
 * reminders").
 */
fun newReminder(
    id: ReminderId,
    at: WallTime,
    zone: String,
    text: String,
    target: ReminderTarget,
): Reminder? {
    val trimmed = text.trim().ifEmpty { DEFAULT_REMINDER_TEXT }
    if (trimmed.length > REMINDER_TEXT_MAX_LENGTH) return null
    return Reminder(id = id, at = at, zone = zone, text = trimmed, target = target)
}

/**
 * Reminders that are not in Trash and whose target still exists, by time (v3 filters out reminders whose target is
 * gone). [targetExists] answers for one target.
 */
fun liveReminders(
    reminders: List<Reminder>,
    targetExists: (ReminderTarget) -> Boolean,
): List<Reminder> = reminders.filter { !it.deleted && targetExists(it.target) }.sortedWith(TIME_ORDER)

/** The live reminders of one Task, Note or Topic (v3 `remindersFor`). */
fun remindersFor(
    target: ReminderTarget,
    reminders: List<Reminder>,
): List<Reminder> = reminders.filter { !it.deleted && it.target == target }.sortedWith(TIME_ORDER)

/** A reminder in Today's side column. [past] when its time has come. [next] marks the first one still to come. */
data class TodayReminder(
    val reminder: Reminder,
    val past: Boolean,
    val next: Boolean,
)

/** Today's reminders, dismissed ones included, by time (v3 `todayReminders`). [live] are the [liveReminders]. */
fun todayReminders(
    live: List<Reminder>,
    now: WallTime,
): List<TodayReminder> {
    val today = live.filter { it.at.day == now.day }.sortedWith(TIME_ORDER)
    val nextId = today.firstOrNull { it.at > now }?.id
    return today.map { TodayReminder(it, past = it.at <= now, next = it.id == nextId) }
}

/**
 * Reminders whose time has come and whose banner is still open, oldest first. A reminder that came due while Thread
 * was closed shows when it opens, so none is missed silently. [live] are the [liveReminders].
 *
 * A reminder on a completed Task shows no banner (D54): [doneTaskIds] are the ids of the completed Tasks.
 */
fun dueReminders(
    live: List<Reminder>,
    now: WallTime,
    doneTaskIds: Set<String> = emptySet(),
): List<Reminder> =
    live
        .filter { !it.dismissed && it.at <= now }
        .filterNot { it.target.kind == TargetKind.Task && it.target.id in doneTaskIds }
        .sortedWith(TIME_ORDER)

/**
 * The next open reminder of each Task (D54): its earliest reminder that is not dismissed and not in Trash. Today counts
 * its day as a date of the Task, and Task rows show it with a bell.
 */
fun taskReminderTimes(reminders: List<Reminder>): Map<TaskId, WallTime> =
    reminders
        .filter { it.target.kind == TargetKind.Task && !it.dismissed && !it.deleted }
        .groupBy { TaskId(it.target.id) }
        .mapValues { (_, own) -> own.minOf { it.at } }

private val TIME_ORDER: Comparator<Reminder> = compareBy<Reminder> { it.at }.thenBy { it.id.value }
