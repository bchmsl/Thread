package thread.domain.task

import thread.domain.time.LocalDay
import thread.domain.time.MINUTES_PER_DAY
import thread.domain.time.WallTime
import thread.domain.time.clockText
import thread.domain.time.parseClock
import thread.domain.topic.TopicId
import kotlin.jvm.JvmInline

/** A personal Task's id. Made on the device that creates the Task, so it works offline. */
@JvmInline
value class TaskId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A Task id is not blank" }
    }

    override fun toString(): String = value
}

/**
 * When a Task must be done: a day, and an optional time on that day in the workspace time zone. A date-only deadline
 * means "by the end of that day". Stored as `YYYY-MM-DD` or `YYYY-MM-DDTHH:MM`, never as an instant.
 */
data class Deadline(
    val day: LocalDay,
    /** Minute of the day, or null for a date-only deadline. */
    val minuteOfDay: Int? = null,
) : Comparable<Deadline> {
    init {
        require(minuteOfDay == null || minuteOfDay in 0 until MINUTES_PER_DAY) { "Minute out of range: $minuteOfDay" }
    }

    /** True once the deadline is behind [now]. A date-only deadline passes when its day is over. */
    fun isPassed(now: WallTime): Boolean = if (minuteOfDay == null) day < now.day else WallTime(day, minuteOfDay) < now

    /** Earlier deadlines first. A date-only deadline counts as the end of its day. */
    override fun compareTo(other: Deadline): Int = compareValuesBy(this, other, { it.day }, { it.minuteOfDay ?: MINUTES_PER_DAY })

    override fun toString(): String = if (minuteOfDay == null) "$day" else "$day" + "T" + clockText(minuteOfDay)

    companion object {
        fun parse(text: String): Deadline? {
            if ('T' !in text) return LocalDay.parse(text)?.let { Deadline(it) }
            val day = LocalDay.parse(text.substringBefore('T')) ?: return null
            val minute = parseClock(text.substringAfter('T')) ?: return null
            return Deadline(day, minute)
        }
    }
}

/**
 * A personal Task (01-product-spec.md, "Tasks"). It belongs to one Topic or none. Completion is a checkbox: [done],
 * with [completedOn], the workspace day it was completed. [plannedDay] is the day the person means to work on it; it
 * is not a deadline. [inbox] is true while it waits in Inbox untriaged, which is separate from having a Topic.
 *
 * [deleted] marks a Task in Trash. [discarded] marks one taken back by Undo right after it was made: it is gone from
 * every view, Trash included, but the document stays, because nothing is ever hard-deleted.
 */
data class Task(
    val id: TaskId,
    val title: String,
    val topicId: TopicId? = null,
    val done: Boolean = false,
    val completedOn: LocalDay? = null,
    val plannedDay: LocalDay? = null,
    val deadline: Deadline? = null,
    val inbox: Boolean = false,
    val createdAtMillis: Long? = null,
    val deleted: Boolean = false,
    val discarded: Boolean = false,
    /** When it went to Trash, for the Trash page. Null while not in Trash, or before the time is known. */
    val deletedAtMillis: Long? = null,
)
