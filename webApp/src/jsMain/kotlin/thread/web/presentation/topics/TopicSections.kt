package thread.web.presentation.topics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.maxLength
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.required
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput
import org.jetbrains.compose.web.dom.Ul
import thread.domain.note.NoteId
import thread.domain.note.UNTITLED_NOTE
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.reminder.remindersFor
import thread.domain.task.DueGroup
import thread.domain.task.TASK_TITLE_MAX_LENGTH
import thread.domain.task.Task
import thread.domain.task.topicTasks
import thread.domain.topic.Topic
import thread.domain.workspace.linkNote
import thread.domain.workspace.topicTaskParts
import thread.domain.workspace.trashReminder
import thread.domain.workspace.unlinkNote
import thread.web.platform.readDeviceText
import thread.web.platform.writeDeviceText
import thread.web.presentation.CardKind
import thread.web.presentation.Icon
import thread.web.presentation.IconButton
import thread.web.presentation.IconView
import thread.web.presentation.KindMark
import thread.web.presentation.LocalIdScope
import thread.web.presentation.LocalNow
import thread.web.presentation.LocalToasts
import thread.web.presentation.SectionCard
import thread.web.presentation.TaskCreation
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.itemCard
import thread.web.presentation.notes.notePlace
import thread.web.presentation.onPlainClick
import thread.web.presentation.scopedId
import thread.web.presentation.taskProblemText
import thread.web.presentation.tasks.TaskRow
import thread.web.presentation.tasks.dueGroupLook
import thread.web.presentation.tasks.dueGroupTitle
import thread.web.presentation.tasks.focusNow
import thread.web.presentation.whenText

/** A Topic page section that opens and closes: a section card of its [kind] (D68). */
@Composable
internal fun TopicSection(
    key: String,
    kind: CardKind,
    title: String,
    count: String,
    open: Boolean,
    onToggle: () -> Unit,
    actions: (@Composable () -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    SectionCard(
        kind = kind,
        headingId = "sh-$key",
        bodyId = "sb-$key",
        title = title,
        count = count,
        open = open,
        onToggle = onToggle,
        actions = actions,
        body = body,
    )
}

/**
 * The Topic's own Tasks: an add line, then the list by Settings > Topic Details (D92). Grouping off: the open ones, then
 * the completed ones behind a toggle. "Group tasks by due date" on: the shared rule's groups, Completed being that
 * toggle (A8). "Show completed tasks" off leaves Completed out. The rows look the same in both modes.
 */
@Composable
internal fun TopicTasksSection(
    workspace: WorkspaceModel,
    topic: Topic,
    open: Boolean,
    onToggle: () -> Unit,
    onOpenTask: (Task) -> Unit,
) {
    val toasts = LocalToasts.current
    val now = LocalNow.current
    val newTaskId = scopedId("td-new-task")
    val prefs = workspace.prefs
    val tasks = topicTasks(topic.id, workspace.storedTasks)
    val openCount = tasks.count { !it.done }
    val parts = topicTaskParts(tasks, prefs.topicShowCompleted, prefs.topicGroupByDue, now, workspace.taskReminderTimes)
    var title by remember(topic.id) { mutableStateOf("") }
    TopicSection("tasks", CardKind.Task, "Tasks", "$openCount open", open, onToggle) {
        Form(attrs = {
            classes("addtask")
            attr("autocomplete", "off")
            onSubmit { event ->
                event.preventDefault()
                when (val created = workspace.createTask(title, topic.id)) {
                    is TaskCreation.Refused -> toasts.show(taskProblemText(created.problem))
                    is TaskCreation.Created -> {
                        title = ""
                        focusNow(newTaskId)
                        toasts.show("Task added", undo = { workspace.discard(created.task) })
                    }
                }
            }
        }) {
            Label(newTaskId, { classes("vh") }) { Text("New task in ${topic.name}") }
            TextInput(title) {
                id(newTaskId)
                classes("input")
                placeholder("Add a task to this Topic")
                required()
                maxLength(TASK_TITLE_MAX_LENGTH)
                onInput { title = it.value }
            }
            Button({
                type(ButtonType.Submit)
                classes("btn", "btn--line")
            }) { Text("Add") }
        }
        if (openCount == 0) {
            P({ classes("sect__empty") }) { Text(if (tasks.isNotEmpty()) "All tasks are done." else "No tasks yet.") }
        }
        parts.forEach { part ->
            val due = part.due
            key(due?.name ?: "open") {
                when (due) {
                    null -> TopicTaskList(part.tasks, workspace, onOpenTask)
                    DueGroup.Completed -> CompletedTasks(part.tasks, workspace, onOpenTask, grouped = prefs.topicGroupByDue)
                    else -> TopicDueGroup(due, part.tasks, workspace, onOpenTask)
                }
            }
        }
    }
}

/**
 * A list of the Topic's Task rows. Open rows say when a Task is planned, today too; completed rows, as before D92, do
 * not say "Planned today".
 */
@Composable
private fun TopicTaskList(
    tasks: List<Task>,
    workspace: WorkspaceModel,
    onOpenTask: (Task) -> Unit,
    showPlanned: Boolean = true,
) {
    val toasts = LocalToasts.current
    Ul({ classes("tlist") }) {
        tasks.forEach { task ->
            key(task.id.value) { TaskRow(task, workspace, toasts, onOpenTask, hideTopic = true, showPlanned = showPlanned) }
        }
    }
}

/**
 * One due-date group of a Topic's Tasks (D92): a small heading with the group's mark, name and count, then its rows. The
 * Tasks section opens and closes as a whole, so these groups do not.
 */
@Composable
private fun TopicDueGroup(
    group: DueGroup,
    tasks: List<Task>,
    workspace: WorkspaceModel,
    onOpenTask: (Task) -> Unit,
) {
    val key = group.name.lowercase()
    val headingId = scopedId("tdg-$key")
    Div({
        classes("tdue", "tdue--$key")
        attr("role", "group")
        attr("aria-labelledby", headingId)
    }) {
        H3({
            classes("sect__sub", "tdue__h")
            id(headingId)
        }) {
            IconView(dueGroupLook(group).first, 14)
            Text(" ${dueGroupTitle(group)} ")
            Span({ classes("n") }) { Text(tasks.size.toString()) }
        }
        TopicTaskList(tasks, workspace, onOpenTask)
    }
}

/**
 * The Topic's completed Tasks behind a toggle, closed at first. This device remembers whether it is open, for every
 * Topic (A8). Grouping off it follows the open Tasks, as before D92; [grouped], it is the due-date groups' Completed
 * group, first as in the shared rule, with a heading like theirs.
 */
@Composable
private fun CompletedTasks(
    tasks: List<Task>,
    workspace: WorkspaceModel,
    onOpenTask: (Task) -> Unit,
    grouped: Boolean,
) {
    var showDone by remember { mutableStateOf(readDeviceText(COMPLETED_OPEN_NAME) == "1") }
    val listId = scopedId("td-done-list")
    val toggle: @Composable () -> Unit = {
        Button({
            type(ButtonType.Button)
            if (grouped) classes("sugg__toggle", "tdue__toggle") else classes("btn", "btn--text", "btn--sm")
            attr("aria-expanded", showDone.toString())
            attr("aria-controls", listId)
            onClick {
                showDone = !showDone
                writeDeviceText(COMPLETED_OPEN_NAME, if (showDone) "1" else "0")
            }
        }) {
            if (grouped) {
                Span({ classes("chev") }) { IconView(Icon.ChevDown, 16) }
            } else {
                IconView(if (showDone) Icon.ChevDown else Icon.ChevRight, 15)
            }
            Text(" Completed ")
            Span({ classes("n") }) { Text(tasks.size.toString()) }
        }
    }
    Div({
        classes("tdone")
        if (grouped) classes("tdue", "tdue--completed")
    }) {
        if (grouped) H3({ classes("sect__sub", "tdue__h") }) { toggle() } else toggle()
        Div({
            id(listId)
            if (!showDone) attr("hidden", "")
        }) {
            if (showDone) TopicTaskList(tasks, workspace, onOpenTask, showPlanned = false)
        }
    }
}

/** Where this device keeps whether a Topic's Completed tasks are open: `1` or `0`, for every Topic (A8). Not synced. */
private const val COMPLETED_OPEN_NAME = "thread.topic.open.completed"

/**
 * The Notes linked to the Topic (v3 notesSection): "New note" makes one linked here and opens it for writing; "Link
 * existing" links a Note without copying it; unlinking never deletes the Note. [onOpenNote] opens a Note as a card,
 * for writing when `edit` is true (D70).
 */
@Composable
internal fun TopicNotesSection(
    workspace: WorkspaceModel,
    topic: Topic,
    open: Boolean,
    onToggle: () -> Unit,
    onOpenNote: (id: NoteId, edit: Boolean) -> Unit,
) {
    val toasts = LocalToasts.current
    val ids = LocalIdScope.current
    var linking by remember(topic.id) { mutableStateOf(false) }
    val notes = workspace.notesFor(topic.id)
    TopicSection(
        "notes",
        CardKind.Note,
        "Notes",
        notes.size.toString(),
        open,
        onToggle,
        actions = {
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text", "btn--sm")
                id("${ids}td-new-note")
                onClick {
                    val note = workspace.createNote(topic.id)
                    if (note == null) toasts.show("Your workspace is still loading. Try again in a moment.") else onOpenNote(note.id, true)
                }
            }) {
                IconView(Icon.Plus, 15)
                Text(" New note")
            }
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text", "btn--sm")
                id("${ids}td-link-note")
                onClick { linking = true }
            }) {
                IconView(Icon.Link, 15)
                Text(" Link existing")
            }
        },
    ) {
        if (notes.isEmpty()) {
            P({ classes("sect__empty") }) { Text("No notes linked. A note can belong to several Topics.") }
            return@TopicSection
        }
        Ul({ classes("tlist") }) {
            notes.forEach { note ->
                val title = note.title.ifBlank { UNTITLED_NOTE }
                val others = workspace.topicsOf(note.id).filter { it.id != topic.id }
                key(note.id.value) {
                    Li({ itemCard(CardKind.Note) }) {
                        KindMark(CardKind.Note)
                        // The address is the Note's own page, for a new tab or a copied link; a plain click opens it as a card.
                        A(notePlace(note.id).hash, {
                            classes("xrow__main")
                            onPlainClick { onOpenNote(note.id, false) }
                        }) {
                            Span({ classes("xrow__title") }) { Text(title) }
                            Span({ classes("xrow__snip") }) { Text(note.preview.ifBlank { "Empty note" }) }
                            if (others.isNotEmpty()) {
                                Span({ classes("xrow__also") }) {
                                    Text("Also in ")
                                    others.forEach { PlainTopicTag(it) }
                                }
                            }
                        }
                        IconButton(Icon.Unlink, "Unlink note $title from this Topic", small = true) {
                            val current = workspace.storedNotes.firstOrNull { it.id == note.id } ?: note
                            val edit = workspace.apply(unlinkNote(current, workspace.noteLinks, topic.id))
                            if (edit != null) toasts.show("Note unlinked. It still exists in Notes.", undo = { workspace.undo(edit) })
                        }
                    }
                }
            }
        }
    }
    if (linking) {
        val items =
            workspace.notes
                .filter { note -> workspace.topicsOf(note.id).none { it.id == topic.id } }
                .map { note ->
                    val topics = workspace.topicsOf(note.id)
                    val sub =
                        when {
                            topics.isNotEmpty() -> topics.joinToString(", ") { it.name }
                            note.inbox -> "In Inbox"
                            else -> "Standalone"
                        }
                    PickItem(note.id.value, Icon.Notes, note.title.ifBlank { UNTITLED_NOTE }, sub)
                }
        ItemPickerDialog(
            title = "Link a note",
            items = items,
            empty = "Every note is already linked here.",
            onPick = { picked ->
                linking = false
                val note = workspace.storedNotes.firstOrNull { it.id.value == picked } ?: return@ItemPickerDialog
                val edit = workspace.apply(linkNote(note, workspace.noteLinks, topic.id)) ?: return@ItemPickerDialog
                toasts.show("Note linked", undo = { workspace.undo(edit) })
            },
            onCancel = { linking = false },
        )
    }
}

/**
 * Reminders an older build put on the Topic itself, while any is not dismissed (D54). Since D54 a reminder belongs to a
 * Task or a Note, and Task rows show theirs, so this only lists Topic reminders that were not moved yet or whose move
 * was undone. It has no Add, and it is not drawn when there is nothing to list.
 */
@Composable
internal fun TopicRemindersSection(
    workspace: WorkspaceModel,
    topic: Topic,
    open: Boolean,
    onToggle: () -> Unit,
    onOpenTarget: (ReminderTarget) -> Unit,
) {
    val toasts = LocalToasts.current
    val today = LocalNow.current.day
    val reminders = remindersFor(ReminderTarget(TargetKind.Topic, topic.id.value), workspace.reminders).filter { !it.dismissed }
    if (reminders.isEmpty()) return
    TopicSection("reminders", CardKind.Reminder, "Topic reminders", reminders.size.toString(), open, onToggle) {
        Ul({ classes("tlist") }) {
            reminders.forEach { reminder ->
                val time = whenText(reminder.at, today)
                key(reminder.id.value) {
                    Li({ itemCard(CardKind.Reminder) }) {
                        KindMark(CardKind.Reminder)
                        Button({
                            type(ButtonType.Button)
                            classes("xrow__main")
                            onClick { onOpenTarget(reminder.target) }
                        }) {
                            Span({ classes("xrow__key") }) { B { Text(time) } }
                            Span({ classes("xrow__title") }) { Text(reminder.text) }
                        }
                        IconButton(Icon.Trash, "Move reminder $time to Trash", small = true) {
                            val edit = workspace.apply(trashReminder(reminder))
                            if (edit != null) toasts.show("Reminder moved to Trash", undo = { workspace.undo(edit) })
                        }
                    }
                }
            }
        }
    }
}
