package thread.domain.trash

import thread.domain.note.NoteId
import thread.domain.note.NoteSummary
import thread.domain.note.UNTITLED_NOTE
import thread.domain.note.linkedTopicIds
import thread.domain.note.liveNotes
import thread.domain.reminder.Reminder
import thread.domain.reminder.TargetKind
import thread.domain.task.Task
import thread.domain.topic.Topic
import thread.domain.topic.TopicId
import thread.domain.topic.TopicStatus
import thread.domain.topic.visibleStatuses
import thread.domain.workspace.EditResult
import thread.domain.workspace.TrashKind
import thread.domain.workspace.WorkspaceData
import thread.domain.workspace.WorkspaceEdit
import thread.domain.workspace.WorkspaceWrite

/*
 * Trash (D42, D43, adr/ADR-012-trash-and-search.md). A Topic goes to Trash with one write: its own `deleted` flag.
 * Its Tasks, the Reminders on it or on those Tasks, and its Note links stay as stored. They are hidden while the Topic
 * is in Trash and come back exactly when it is restored, also when another device changed them in the meantime.
 * Tasks, Notes and Reminders moved to Trash on their own are separate entries.
 */

/**
 * The workspace as every view shows it: each Topic in Trash is taken out, with its Tasks, the Reminders on it or on
 * those Tasks, and its Note links. Everything else stays as stored, Trash flags included, so the Task, Note and
 * Reminder rules keep working on it.
 */
fun visibleWorkspace(data: WorkspaceData): WorkspaceData {
    val trashed =
        data.topics
            .filter { it.deleted }
            .map { it.id }
            .toSet()
    if (trashed.isEmpty()) return data
    val hiddenTasks =
        data.tasks
            .filter { it.topicId in trashed }
            .map { it.id.value }
            .toSet()
    return data.copy(
        topics = data.topics.filterNot { it.id in trashed },
        tasks = data.tasks.filterNot { it.id.value in hiddenTasks },
        reminders = data.reminders.filterNot { goesWithTopic(it, trashed, hiddenTasks) },
        noteLinks = data.noteLinks.filterNot { it.topicId in trashed },
    )
}

/** Moves [topic] to Trash. Its Tasks, Reminders and Note links go with it, and Undo brings all of them back. */
fun trashTopic(topic: Topic): EditResult {
    if (topic.deleted) return EditResult.Unchanged
    val after = topic.copy(deleted = true)
    return EditResult.Changed(WorkspaceEdit(listOf(topicWrite(topic, after)), listOf(topicWrite(after, topic))))
}

/**
 * Brings [topic] back from Trash, with everything that went with it. When its status was deleted meanwhile, it takes
 * the first status of the same category, else the first status (v3 `restore`). [storedStatuses] includes deleted ones.
 */
fun restoreTopic(
    topic: Topic,
    storedStatuses: List<TopicStatus>,
): EditResult {
    if (!topic.deleted) return EditResult.Unchanged
    val visible = visibleStatuses(storedStatuses)
    val statusId =
        if (visible.any { it.id == topic.statusId }) {
            topic.statusId
        } else {
            val category = storedStatuses.firstOrNull { it.id == topic.statusId }?.category
            (visible.firstOrNull { it.category == category } ?: visible.first()).id
        }
    val after = topic.copy(deleted = false, deletedAtMillis = null, statusId = statusId)
    return EditResult.Changed(WorkspaceEdit(listOf(topicWrite(topic, after)), listOf(topicWrite(after, topic))))
}

/**
 * Brings [task] back from Trash. When its Topic is in Trash or gone, it comes back standalone, so it shows at once
 * (v3 "Restore as standalone"). [data] is the stored workspace, Topics in Trash included.
 */
fun restoreTask(
    task: Task,
    data: WorkspaceData,
): EditResult {
    if (!task.deleted || task.discarded) return EditResult.Unchanged
    val keepsTopic = task.topicId == null || topicState(task.topicId, data) == TopicState.Live
    val after = task.copy(deleted = false, deletedAtMillis = null, topicId = if (keepsTopic) task.topicId else null)
    return EditResult.Changed(
        WorkspaceEdit(
            listOf(WorkspaceWrite.PutTask(after, before = task)),
            listOf(WorkspaceWrite.PutTask(task, before = after)),
        ),
    )
}

/** Brings [reminder] back from Trash. While what it is for is in Trash, it stays hidden with it. */
fun restoreReminder(reminder: Reminder): EditResult {
    if (!reminder.deleted) return EditResult.Unchanged
    val after = reminder.copy(deleted = false, deletedAtMillis = null)
    return EditResult.Changed(
        WorkspaceEdit(
            listOf(WorkspaceWrite.PutReminder(after, before = reminder)),
            listOf(WorkspaceWrite.PutReminder(reminder, before = after)),
        ),
    )
}

/** Where a Topic link of an item in Trash stands. */
enum class TopicState {
    Live,

    /** The Topic is in Trash. Restoring it brings the link back. */
    InTrash,

    /** The Topic was deleted forever, or never reached this device. */
    Gone,
}

/** One Topic an item in Trash was linked to. [topic] is null when it is [TopicState.Gone]. */
data class TopicLink(
    val id: TopicId,
    val topic: Topic?,
    val state: TopicState,
)

/** What a Reminder in Trash is for. [label] is null when that item is gone. */
data class ReminderFor(
    val kind: TargetKind,
    val label: String?,
    val inTrash: Boolean,
)

/** One row of the Trash page. */
sealed interface TrashEntry {
    val kind: TrashKind
    val id: String
    val title: String
    val deletedAtMillis: Long?

    /** A Topic and what went to Trash with it. [notes] are the live Notes whose link comes back on restore. */
    data class TopicEntry(
        val topic: Topic,
        val tasks: List<Task>,
        val reminders: List<Reminder>,
        val notes: List<NoteSummary>,
    ) : TrashEntry {
        override val kind = TrashKind.Topic
        override val id = topic.id.value
        override val title = topic.name
        override val deletedAtMillis = topic.deletedAtMillis
    }

    data class TaskEntry(
        val task: Task,
        val topic: TopicLink?,
    ) : TrashEntry {
        override val kind = TrashKind.Task
        override val id = task.id.value
        override val title = task.title
        override val deletedAtMillis = task.deletedAtMillis
    }

    /** [topics] are the Topics the Note is linked to; Restore keeps every link. */
    data class NoteEntry(
        val note: NoteSummary,
        val topics: List<TopicLink>,
    ) : TrashEntry {
        override val kind = TrashKind.Note
        override val id = note.id.value
        override val title = note.title.ifBlank { UNTITLED_NOTE }
        override val deletedAtMillis = note.deletedAtMillis
    }

    data class ReminderEntry(
        val reminder: Reminder,
        val target: ReminderFor,
    ) : TrashEntry {
        override val kind = TrashKind.Reminder
        override val id = reminder.id.value
        override val title = reminder.text
        override val deletedAtMillis = reminder.deletedAtMillis
    }
}

/**
 * Everything in Trash, newest deletion first (v3 trash list). [data] is the stored workspace, Topics in Trash
 * included. Items taken back by the Undo of their capture toast are not in Trash.
 */
fun trashEntries(data: WorkspaceData): List<TrashEntry> {
    val topicEntries =
        data.topics.filter { it.deleted }.map { topic ->
            val tasks = bundleTasks(topic.id, data)
            TrashEntry.TopicEntry(
                topic = topic,
                tasks = tasks,
                reminders = bundleReminders(topic.id, tasks, data),
                notes = liveNotes(data.notes).filter { note -> topic.id in linkedTopicIds(note.id, data.noteLinks) },
            )
        }
    val taskEntries =
        data.tasks.filter { it.deleted && !it.discarded }.map { task ->
            TrashEntry.TaskEntry(task, task.topicId?.let { topicLink(it, data) })
        }
    val noteEntries =
        data.notes.filter { it.inTrash && !it.discarded }.map { note ->
            TrashEntry.NoteEntry(note, linkedTopicIds(note.id, data.noteLinks).map { topicLink(it, data) })
        }
    val reminderEntries =
        data.reminders.filter { it.deleted }.map { reminder -> TrashEntry.ReminderEntry(reminder, reminderFor(reminder, data)) }
    return (topicEntries + taskEntries + noteEntries + reminderEntries).sortedWith(
        compareByDescending<TrashEntry> { it.deletedAtMillis ?: Long.MAX_VALUE }.thenBy { it.id },
    )
}

/**
 * The writes that delete [entry] forever (D43). A Topic takes the Tasks and Reminders that went to Trash with it; a
 * Task or a Note takes the live Reminders on it. Items moved to Trash on their own stay separate entries. Notes, Jira
 * tickets and pull requests linked to a Topic are not touched. [data] is the stored workspace.
 */
fun deleteForeverWrites(
    entry: TrashEntry,
    data: WorkspaceData,
): List<WorkspaceWrite> {
    val reminders =
        when (entry) {
            is TrashEntry.TopicEntry -> entry.reminders
            is TrashEntry.TaskEntry -> remindersOn(TargetKind.Task, entry.id, data)
            is TrashEntry.NoteEntry -> remindersOn(TargetKind.Note, entry.id, data)
            is TrashEntry.ReminderEntry -> emptyList()
        }
    val tasks = (entry as? TrashEntry.TopicEntry)?.tasks.orEmpty()
    return listOf(WorkspaceWrite.DeleteForever(entry.kind, entry.id, entry.title)) +
        tasks.map { WorkspaceWrite.DeleteForever(TrashKind.Task, it.id.value, it.title) } +
        reminders.map { WorkspaceWrite.DeleteForever(TrashKind.Reminder, it.id.value, it.text) }
}

private fun remindersOn(
    kind: TargetKind,
    id: String,
    data: WorkspaceData,
): List<Reminder> = data.reminders.filter { !it.deleted && it.target.kind == kind && it.target.id == id }

/** Where the link to [topicId] stands in the stored [data]. */
fun topicState(
    topicId: TopicId,
    data: WorkspaceData,
): TopicState {
    val topic = data.topics.firstOrNull { it.id == topicId } ?: return TopicState.Gone
    return if (topic.deleted) TopicState.InTrash else TopicState.Live
}

private fun topicLink(
    topicId: TopicId,
    data: WorkspaceData,
): TopicLink = TopicLink(topicId, data.topics.firstOrNull { it.id == topicId }, topicState(topicId, data))

/** The Tasks that went to Trash with a Topic: those not moved to Trash on their own. */
private fun bundleTasks(
    topicId: TopicId,
    data: WorkspaceData,
): List<Task> = data.tasks.filter { it.topicId == topicId && !it.deleted && !it.discarded }

/** The Reminders that went to Trash with a Topic: on the Topic or on its [tasks], not moved to Trash on their own. */
private fun bundleReminders(
    topicId: TopicId,
    tasks: List<Task>,
    data: WorkspaceData,
): List<Reminder> {
    val taskIds = tasks.map { it.id.value }.toSet()
    return data.reminders.filter { !it.deleted && goesWithTopic(it, setOf(topicId), taskIds) }.sortedBy { it.at }
}

private fun goesWithTopic(
    reminder: Reminder,
    topicIds: Set<TopicId>,
    taskIds: Set<String>,
): Boolean =
    when (reminder.target.kind) {
        TargetKind.Topic -> TopicId(reminder.target.id) in topicIds
        TargetKind.Task -> reminder.target.id in taskIds
        TargetKind.Note -> false
    }

private fun reminderFor(
    reminder: Reminder,
    data: WorkspaceData,
): ReminderFor {
    val id = reminder.target.id
    return when (reminder.target.kind) {
        TargetKind.Topic -> {
            val topic = data.topics.firstOrNull { it.id.value == id }
            ReminderFor(TargetKind.Topic, topic?.name, inTrash = topic?.deleted == true)
        }
        TargetKind.Task -> {
            val task = data.tasks.firstOrNull { it.id.value == id }?.takeUnless { it.discarded }
            val topicGone = task?.topicId?.let { topicState(it, data) == TopicState.InTrash } == true
            ReminderFor(TargetKind.Task, task?.title, inTrash = task?.deleted == true || topicGone)
        }
        TargetKind.Note -> {
            val note = data.notes.firstOrNull { it.id == NoteId(id) }?.takeUnless { it.discarded }
            ReminderFor(TargetKind.Note, note?.let { it.title.ifBlank { UNTITLED_NOTE } }, inTrash = note?.inTrash == true)
        }
    }
}

private fun topicWrite(
    before: Topic,
    after: Topic,
) = WorkspaceWrite.PutTopic(after, before = before)
