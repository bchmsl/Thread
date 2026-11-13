package thread.web.presentation.topics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.maxLength
import org.jetbrains.compose.web.attributes.name
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.required
import org.jetbrains.compose.web.attributes.rows
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Aside
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Fieldset
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Legend
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.jetbrains.compose.web.dom.TextInput
import org.w3c.dom.HTMLTextAreaElement
import thread.domain.note.NoteId
import thread.domain.reminder.ReminderTarget
import thread.domain.task.Task
import thread.domain.topic.StatusId
import thread.domain.topic.TOPIC_NAME_MAX_LENGTH
import thread.domain.topic.TOPIC_TEXT_MAX_LENGTH
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.statusOf
import thread.domain.workspace.EditProblem
import thread.domain.workspace.EditResult
import thread.domain.workspace.editPickUp
import thread.domain.workspace.editTopic
import thread.domain.workspace.moveTopic
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalToasts
import thread.web.presentation.ModalDialog
import thread.web.presentation.SelectBox
import thread.web.presentation.View
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.tasks.focusById

/**
 * Topic details (v3 topic.html): identity and status on top, "Pick up here", then Tasks, Notes and Reminders. Jira,
 * pull requests and Move to Trash join in later phases.
 */
@Composable
internal fun TopicScreen(
    workspace: WorkspaceModel,
    topicId: String?,
    onOpenTask: (Task) -> Unit,
    onOpenTarget: (ReminderTarget) -> Unit,
    onOpenNote: (NoteId) -> Unit,
) {
    if (!workspace.loaded) {
        if (workspace.readFailed) EmptyState(Icon.Alert, "Topics could not load", "Reload the page to try again.")
        return
    }
    val topic = topicId?.let(workspace::topic)
    if (topic == null) {
        EmptyState(Icon.Topics, "Topic not found", "It may have been removed in another tab.") {
            A(View.Topics.place.hash, { classes("btn", "btn--line") }) { Text("Open Topics") }
        }
        return
    }
    val toasts = LocalToasts.current
    var editing by remember { mutableStateOf(false) }
    // v3 opens Tasks and Notes and closes Reminders by default; the choice lasts while the page is open.
    var tasksOpen by remember(topic.id) { mutableStateOf(true) }
    var notesOpen by remember(topic.id) { mutableStateOf(true) }
    var remindersOpen by remember(topic.id) { mutableStateOf(false) }
    var focusAddTask by remember { mutableStateOf(0) }
    LaunchedEffect(focusAddTask) { if (focusAddTask > 0) focusById("td-new-task") }
    val statuses = workspace.statuses
    val status = statusOf(topic, statuses)

    A(View.Topics.place.hash, { classes("back") }) {
        IconView(Icon.ChevLeft, 16)
        Text(" Topics")
    }
    Header({
        classes("td-head")
        attr("style", topicStyle(topic.color))
    }) {
        TopicHex(topic.name, topic.color, HexSize.Lg)
        H1({
            classes("td-name")
            tabIndex(-1)
        }) { Text(topic.name) }
        Div({ classes("td-meta") }) {
            Span({ classes("st", "st--${status.category.id}") }) { Swatch(status.category) }
            Label("td-status", { classes("vh") }) { Text("Status") }
            SelectBox(
                value = status.id.value,
                options =
                    statuses.map {
                        val category = categoryLabel(it.category)
                        it.id.value to if (it.name == category) it.name else "${it.name} ($category)"
                    },
                id = "td-status",
                classes = listOf("input", "input--sm", "input--auto"),
            ) { picked ->
                val to = statuses.firstOrNull { it.id == StatusId(picked) }
                val edit = workspace.apply(moveTopic(topic, StatusId(picked), statuses))
                if (edit != null && to != null) toasts.show("Status changed to ${to.name}", undo = { workspace.undo(edit) })
            }
            TopicProgress(topic, workspace.progress(topic.id))
        }
        P({ classes("td-goal") }) {
            if (topic.goal.isNotEmpty()) {
                Text(topic.goal)
            } else {
                Span({ classes("muted") }) { Text("No goal yet. Use Edit to add one.") }
            }
        }
        Div({ classes("td-acts") }) {
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--line")
                onClick { editing = true }
            }) {
                IconView(Icon.Edit, 16)
                Text(" Edit")
            }
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--primary")
                onClick {
                    tasksOpen = true
                    focusAddTask++
                }
            }) { Text("Add task") }
        }
    }
    Section({
        classes("pickup")
        attr("style", topicStyle(topic.color))
        attr("aria-labelledby", "pu-h")
    }) {
        H2({
            classes("pickup__h")
            id("pu-h")
        }) { Text("Pick up here") }
        PickUpField("Where I left off", topic.leftOff, "What were you in the middle of?") { text ->
            workspace.topic(topic.id.value)?.let { workspace.apply(editPickUp(it, leftOff = text)) }
        }
        PickUpField("Next step", topic.nextStep, "The first thing to do when you come back") { text ->
            workspace.topic(topic.id.value)?.let { workspace.apply(editPickUp(it, nextStep = text)) }
        }
    }
    Div({ classes("td-grid") }) {
        Div({ classes("td-main") }) {
            TopicTasksSection(workspace, topic, tasksOpen, { tasksOpen = !tasksOpen }, onOpenTask)
            TopicNotesSection(workspace, topic, notesOpen, { notesOpen = !notesOpen }, onOpenNote)
        }
        Aside({
            classes("td-side")
            attr("aria-label", "Linked work and reminders")
        }) {
            TopicRemindersSection(workspace, topic, remindersOpen, { remindersOpen = !remindersOpen }, onOpenTarget)
        }
    }
    if (editing) {
        EditTopicDialog(topic, workspace) { editing = false }
    }
}

/**
 * A "Pick up here" text box. It saves when it loses focus (v3), and also a second after typing stops and when the
 * page leaves it, so a reload or a closed tab never loses what was typed. While it has focus, a change from another
 * device does not replace the text being typed.
 */
@Composable
private fun PickUpField(
    label: String,
    value: String,
    placeholder: String,
    onSave: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val latestValue by rememberUpdatedState(value)
    val latestSave by rememberUpdatedState(onSave)
    var element by remember { mutableStateOf<HTMLTextAreaElement?>(null) }
    var pending by remember { mutableStateOf<Job?>(null) }
    val saveNow = { text: String ->
        pending?.cancel()
        pending = null
        if (text.trim() != latestValue) latestSave(text)
    }
    Label(attrs = { classes("field") }) {
        Span({ classes("field__label") }) { Text(label) }
        TextArea(attrs = {
            classes("input")
            rows(3)
            placeholder(placeholder)
            maxLength(TOPIC_TEXT_MAX_LENGTH)
            ref { textArea ->
                textArea.value = latestValue
                element = textArea
                onDispose {
                    saveNow(textArea.value)
                    element = null
                }
            }
            onInput { event ->
                val text = event.value
                pending?.cancel()
                pending =
                    scope.launch {
                        delay(SAVE_AFTER_TYPING_MS)
                        saveNow(text)
                    }
            }
            onChange { event -> saveNow(event.value) }
        })
    }
    SideEffect {
        element?.let { if (document.activeElement != it && it.value != value) it.value = value }
    }
}

private const val SAVE_AFTER_TYPING_MS = 1000L

/** The v3 Edit Topic dialog: name, goal and sticker color. */
@Composable
private fun EditTopicDialog(
    topic: Topic,
    workspace: WorkspaceModel,
    onClose: () -> Unit,
) {
    val toasts = LocalToasts.current
    var name by remember { mutableStateOf(topic.name) }
    var goal by remember { mutableStateOf(topic.goal) }
    var color by remember { mutableStateOf(topic.color) }
    ModalDialog(onClose = onClose) { titleId ->
        Form(attrs = {
            classes("dlg__in")
            onSubmit { event ->
                event.preventDefault()
                val current = workspace.topic(topic.id.value) ?: topic
                when (val result = editTopic(current, name, goal, color)) {
                    is EditResult.Changed -> {
                        workspace.apply(result)
                        onClose()
                        toasts.show("Topic saved")
                    }
                    EditResult.Unchanged -> onClose()
                    is EditResult.Refused -> toasts.show(topicProblemText(result.problem))
                }
            }
        }) {
            H2({
                classes("dlg__title")
                id(titleId)
            }) { Text("Edit Topic") }
            Label(attrs = { classes("field") }) {
                Span({ classes("field__label") }) { Text("Name") }
                TextInput(name) {
                    classes("input")
                    name("name")
                    required()
                    maxLength(TOPIC_NAME_MAX_LENGTH)
                    attr("autofocus", "")
                    onInput { name = it.value }
                }
            }
            Label(attrs = { classes("field") }) {
                Span({ classes("field__label") }) { Text("Goal") }
                TextArea(goal) {
                    classes("input")
                    name("goal")
                    rows(3)
                    maxLength(TOPIC_TEXT_MAX_LENGTH)
                    onInput { goal = it.value }
                }
            }
            Fieldset({ classes("field", "colors") }) {
                Legend({ classes("field__label") }) { Text("Sticker color") }
                TopicColor.entries.forEach { option ->
                    Label(attrs = { attr("title", option.id) }) {
                        Input(InputType.Radio) {
                            name("color")
                            value(option.id)
                            checked(option == color)
                            attr("aria-label", option.id)
                            onChange { if (it.value) color = option }
                        }
                        TopicHex(topic.name, option, HexSize.Md)
                    }
                }
            }
            Div({ classes("dlg__acts") }) {
                Button({
                    type(ButtonType.Button)
                    classes("btn", "btn--line")
                    onClick { onClose() }
                }) { Text("Cancel") }
                Button({
                    type(ButtonType.Submit)
                    classes("btn", "btn--primary")
                }) { Text("Save changes") }
            }
        }
    }
}

internal fun topicProblemText(problem: EditProblem): String =
    when (problem) {
        EditProblem.BlankName -> "A Topic needs a name"
        EditProblem.NameTooLong -> "A Topic name can have at most $TOPIC_NAME_MAX_LENGTH characters"
        EditProblem.TextTooLong -> "That text is too long to save"
        EditProblem.NotReady -> "Topics are still loading. Try again in a moment."
        else -> "That change could not be made"
    }
