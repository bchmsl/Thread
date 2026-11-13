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
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput
import org.jetbrains.compose.web.dom.Ul
import thread.domain.note.NoteId
import thread.domain.note.UNTITLED_NOTE
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.reminder.remindersForTopic
import thread.domain.task.TASK_TITLE_MAX_LENGTH
import thread.domain.task.Task
import thread.domain.task.topicTasks
import thread.domain.topic.Topic
import thread.domain.workspace.linkNote
import thread.domain.workspace.trashReminder
import thread.domain.workspace.unlinkNote
import thread.web.presentation.Icon
import thread.web.presentation.IconButton
import thread.web.presentation.IconView
import thread.web.presentation.LocalNow
import thread.web.presentation.LocalToasts
import thread.web.presentation.TaskCreation
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.notes.notePlace
import thread.web.presentation.taskProblemText
import thread.web.presentation.tasks.ReminderDialog
import thread.web.presentation.tasks.TaskRow
import thread.web.presentation.tasks.focusNow
import thread.web.presentation.tasks.targetInfo
import thread.web.presentation.whenText

/** A Topic page section that opens and closes (v3 section()). */
@Composable
private fun TopicSection(
    key: String,
    title: String,
    count: String,
    open: Boolean,
    onToggle: () -> Unit,
    actions: (@Composable () -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    Section({
        classes("sect")
        attr("aria-labelledby", "sh-$key")
    }) {
        Div({ classes("sect__head") }) {
            H2({ id("sh-$key") }) {
                Button({
                    type(ButtonType.Button)
                    classes("sect__toggle")
                    attr("aria-expanded", open.toString())
                    attr("aria-controls", "sb-$key")
                    onClick { onToggle() }
                }) {
                    Span({ classes("chev") }) { IconView(Icon.ChevDown, 18) }
                    Text("$title ")
                    Span({ classes("n") }) { Text(count) }
                }
            }
            if (actions != null) Div({ classes("sect__acts") }) { actions() }
        }
        Div({
            classes("sect__body")
            id("sb-$key")
            if (!open) attr("hidden", "")
        }) { if (open) body() }
    }
}

/** The Topic's own Tasks: an add line, the open ones, and the completed ones behind a toggle. */
@Composable
internal fun TopicTasksSection(
    workspace: WorkspaceModel,
    topic: Topic,
    open: Boolean,
    onToggle: () -> Unit,
    onOpenTask: (Task) -> Unit,
) {
    val toasts = LocalToasts.current
    val tasks = topicTasks(topic.id, workspace.storedTasks)
    val openTasks = tasks.filter { !it.done }
    val doneTasks = tasks.filter { it.done }
    var showDone by remember(topic.id) { mutableStateOf(false) }
    var title by remember(topic.id) { mutableStateOf("") }
    TopicSection("tasks", "Tasks", "${openTasks.size} open", open, onToggle) {
        Form(attrs = {
            classes("addtask")
            attr("autocomplete", "off")
            onSubmit { event ->
                event.preventDefault()
                when (val created = workspace.createTask(title, topic.id)) {
                    is TaskCreation.Refused -> toasts.show(taskProblemText(created.problem))
                    is TaskCreation.Created -> {
                        title = ""
                        focusNow("td-new-task")
                        toasts.show("Task added", undo = { workspace.discard(created.task) })
                    }
                }
            }
        }) {
            Label("td-new-task", { classes("vh") }) { Text("New task in ${topic.name}") }
            TextInput(title) {
                id("td-new-task")
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
        if (openTasks.isNotEmpty()) {
            Ul({ classes("tlist") }) {
                openTasks.forEach { task ->
                    key(task.id.value) { TaskRow(task, workspace, toasts, onOpenTask, hideTopic = true, showPlanned = true) }
                }
            }
        } else {
            P({ classes("sect__empty") }) { Text(if (doneTasks.isNotEmpty()) "All tasks are done." else "No tasks yet.") }
        }
        if (doneTasks.isNotEmpty()) {
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text", "btn--sm")
                attr("aria-expanded", showDone.toString())
                onClick { showDone = !showDone }
            }) {
                IconView(if (showDone) Icon.ChevDown else Icon.ChevRight, 15)
                Text(" Completed ")
                Span({ classes("n") }) { Text(doneTasks.size.toString()) }
            }
            if (showDone) {
                Ul({ classes("tlist") }) {
                    doneTasks.forEach { task -> key(task.id.value) { TaskRow(task, workspace, toasts, onOpenTask, hideTopic = true) } }
                }
            }
        }
    }
}

/**
 * The Notes linked to the Topic (v3 notesSection): "New note" makes one linked here and opens it; "Link existing"
 * links a Note without copying it; unlinking never deletes the Note.
 */
@Composable
internal fun TopicNotesSection(
    workspace: WorkspaceModel,
    topic: Topic,
    open: Boolean,
    onToggle: () -> Unit,
    onOpenNote: (NoteId) -> Unit,
) {
    val toasts = LocalToasts.current
    var linking by remember(topic.id) { mutableStateOf(false) }
    val notes = workspace.notesFor(topic.id)
    TopicSection(
        "notes",
        "Notes",
        notes.size.toString(),
        open,
        onToggle,
        actions = {
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text", "btn--sm")
                id("td-new-note")
                onClick {
                    val note = workspace.createNote(topic.id)
                    if (note == null) toasts.show("Your workspace is still loading. Try again in a moment.") else onOpenNote(note.id)
                }
            }) {
                IconView(Icon.Plus, 15)
                Text(" New note")
            }
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text", "btn--sm")
                id("td-link-note")
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
                    Li({ classes("xrow") }) {
                        Span({ classes("xrow__icon") }) { IconView(Icon.Notes, 18) }
                        A(notePlace(note.id).hash, { classes("xrow__main") }) {
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

/** Reminders for the Topic, its Tasks and its linked Notes (v3 remindersSection). */
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
    var adding by remember(topic.id) { mutableStateOf(false) }
    val noteIds = workspace.notesFor(topic.id).map { it.id.value }.toSet()
    val reminders = remindersForTopic(topic.id, workspace.reminders, workspace.tasks, noteIds)
    TopicSection(
        "reminders",
        "Reminders",
        reminders.size.toString(),
        open,
        onToggle,
        actions = {
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text", "btn--sm")
                onClick { adding = true }
            }) {
                IconView(Icon.Plus, 15)
                Text(" Add")
            }
        },
    ) {
        if (reminders.isEmpty()) {
            P({ classes("sect__empty") }) { Text("No reminders for this Topic or its tasks and notes.") }
            return@TopicSection
        }
        Ul({ classes("tlist") }) {
            reminders.forEach { reminder ->
                val info = targetInfo(workspace, reminder.target)
                val time = whenText(reminder.at, today)
                key(reminder.id.value) {
                    Li({ classes("xrow") }) {
                        Span({ classes("xrow__icon") }) { IconView(Icon.Bell, 18) }
                        Button({
                            type(ButtonType.Button)
                            classes("xrow__main")
                            onClick { onOpenTarget(reminder.target) }
                        }) {
                            Span({ classes("xrow__key") }) {
                                B { Text(time) }
                                when (info?.kind) {
                                    TargetKind.Task -> Span { Text("Task: ${info.label}") }
                                    TargetKind.Note -> Span { Text("Note: ${info.label}") }
                                    else -> Unit
                                }
                            }
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
    if (adding) {
        ReminderDialog(workspace, ReminderTarget(TargetKind.Topic, topic.id.value), "Check ${topic.name}") { adding = false }
    }
}
