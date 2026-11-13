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
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import thread.domain.external.JiraIssueKey
import thread.domain.external.PullRequestRef
import thread.domain.note.NoteId
import thread.domain.reminder.ReminderTarget
import thread.domain.task.Task
import thread.domain.topic.StatusId
import thread.domain.topic.TOPIC_NAME_MAX_LENGTH
import thread.domain.topic.TOPIC_TEXT_MAX_LENGTH
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.statusOf
import thread.domain.trash.TrashEntry
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
import thread.web.presentation.Place
import thread.web.presentation.SelectBox
import thread.web.presentation.View
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.external.GitHubModel
import thread.web.presentation.external.JiraModel
import thread.web.presentation.external.rememberWide
import thread.web.presentation.tasks.focusById
import thread.web.presentation.trash.trashPlace

/**
 * Topic details (v3 topic.html): identity and status on top, "Pick up here", then Tasks and Notes, with Jira, pull
 * requests and Reminders beside them. A linked ticket or pull request opens in a side panel at 1000px and wider.
 * A Topic in Trash keeps its page: it says what went with it and offers Restore.
 */
@Composable
internal fun TopicScreen(
    workspace: WorkspaceModel,
    jira: JiraModel,
    gitHub: GitHubModel,
    topicId: String?,
    onOpenTask: (Task) -> Unit,
    onOpenTarget: (ReminderTarget) -> Unit,
    onOpenNote: (NoteId) -> Unit,
    onGo: (Place) -> Unit,
) {
    if (!workspace.loaded) {
        if (workspace.readFailed) EmptyState(Icon.Alert, "Topics could not load", "Reload the page to try again.")
        return
    }
    val toasts = LocalToasts.current
    // Where focus goes once the page has switched between the Topic and its Trash state (v3 #gone-h, .td-name).
    var focusAfterSwitch by remember { mutableStateOf<String?>(null) }
    val topic = topicId?.let(workspace::topic)
    if (topic == null) {
        val entry = workspace.trash.firstOrNull { it is TrashEntry.TopicEntry && it.id == topicId } as? TrashEntry.TopicEntry
        if (entry != null) {
            TopicInTrash(entry) {
                workspace.restore(entry) ?: return@TopicInTrash
                focusAfterSwitch = TOPIC_NAME_ID
                toasts.show("Restored: ${entry.title}")
            }
        } else {
            EmptyState(Icon.Topics, "Topic not found", "It may have been removed in another tab.") {
                A(View.Topics.place.hash, { classes("btn", "btn--line") }) { Text("Open Topics") }
            }
        }
        FocusAfterSwitch(focusAfterSwitch, GONE_HEADING_ID) { focusAfterSwitch = null }
        return
    }
    FocusAfterSwitch(focusAfterSwitch, TOPIC_NAME_ID) { focusAfterSwitch = null }
    var editing by remember { mutableStateOf(false) }
    // v3 opens Tasks and Notes and closes Reminders by default; the choice lasts while the page is open.
    var tasksOpen by remember(topic.id) { mutableStateOf(true) }
    var notesOpen by remember(topic.id) { mutableStateOf(true) }
    var remindersOpen by remember(topic.id) { mutableStateOf(false) }
    var jiraOpen by remember(topic.id) { mutableStateOf(true) }
    var prsOpen by remember(topic.id) { mutableStateOf(true) }
    var panel by remember(topic.id) { mutableStateOf<LinkedPanel?>(null) }
    val wide = rememberWide()
    val scope = rememberCoroutineScope()
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
        topicColor(topic.color)
    }) {
        TopicHex(topic.name, topic.color, HexSize.Lg)
        H1({
            classes("td-name")
            id(TOPIC_NAME_ID)
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
            // Move to Trash is instant with Undo (v3, owner decision 2026-11-13). The page then shows what went with it.
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text", "btn--danger-text")
                attr("aria-describedby", "td-trash-hint")
                onClick {
                    val edit = workspace.trashTopic(topic) ?: return@onClick
                    focusAfterSwitch = GONE_HEADING_ID
                    toasts.show("Moved to Trash: ${topic.name}", undo = {
                        focusAfterSwitch = TOPIC_NAME_ID
                        workspace.undo(edit)
                    })
                }
            }) {
                IconView(Icon.Trash, 16)
                Text(" Move to Trash")
            }
            Span({
                classes("vh")
                id("td-trash-hint")
            }) { Text("Its tasks and reminders go with it. Notes stay in Notes. You can undo or restore it from Trash.") }
        }
    }
    Section({
        classes("pickup")
        topicColor(topic.color)
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
            val shown = panel
            // Closing the panel puts focus back on the row it was opened from.
            val closePanel: (String) -> Unit = { selector ->
                panel = null
                scope.launch {
                    delay(16)
                    (document.querySelector(selector) as? HTMLElement)?.focus()
                }
            }
            when {
                shown is LinkedPanel.Ticket && wide ->
                    TopicSidePanel(
                        workspace,
                        jira,
                        topic,
                        shown.key,
                        onOpen = { panel = LinkedPanel.Ticket(it) },
                        onClose = { closePanel(".xrow__main[data-key=\"${shown.key.value}\"]") },
                        onGo = onGo,
                    )
                shown is LinkedPanel.Pull && wide ->
                    TopicPrSidePanel(
                        workspace,
                        gitHub,
                        topic,
                        shown.ref,
                        onClose = { closePanel(".xrow__main[data-id=\"${shown.ref.id}\"]") },
                        onGo = onGo,
                    )
                else -> {
                    val openKey = (shown as? LinkedPanel.Ticket)?.key?.value
                    TopicJiraSection(workspace, jira, topic, jiraOpen, { jiraOpen = !jiraOpen }, openKey) { key ->
                        if (wide) panel = LinkedPanel.Ticket(key)
                        wide
                    }
                    val openId = (shown as? LinkedPanel.Pull)?.ref?.id
                    TopicPrSection(workspace, gitHub, topic, prsOpen, { prsOpen = !prsOpen }, openId) { ref ->
                        if (wide) panel = LinkedPanel.Pull(ref)
                        wide
                    }
                    TopicRemindersSection(workspace, topic, remindersOpen, { remindersOpen = !remindersOpen }, onOpenTarget)
                }
            }
        }
    }
    if (editing) {
        EditTopicDialog(topic, workspace) { editing = false }
    }
}

/** Moves focus to [target] once the element with that id is on the page, then calls [onDone]. */
@Composable
private fun FocusAfterSwitch(
    target: String?,
    shownId: String,
    onDone: () -> Unit,
) {
    LaunchedEffect(target, shownId) {
        if (target != shownId) return@LaunchedEffect
        focusById(target)
        onDone()
    }
}

/** The page of a Topic in Trash (v3 topic.js trashedState). */
@Composable
private fun TopicInTrash(
    entry: TrashEntry.TopicEntry,
    onRestore: () -> Unit,
) {
    val topic = entry.topic
    A(View.Topics.place.hash, { classes("back") }) {
        IconView(Icon.ChevLeft, 16)
        Text(" Topics")
    }
    Section({
        classes("gone")
        topicColor(topic.color)
        attr("aria-labelledby", GONE_HEADING_ID)
    }) {
        TopicHex(topic.name, topic.color, HexSize.Lg)
        H1({
            classes("gone__h")
            id(GONE_HEADING_ID)
            tabIndex(-1)
        }) { Text("${topic.name} is in Trash") }
        P({ classes("gone__p") }) { Text(wentWithTopic(entry)) }
        P({ classes("gone__p") }) {
            Text("Restore brings back the Topic, its tasks and reminders, and its links to notes, tickets and pull requests.")
        }
        Div({ classes("gone__acts") }) {
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--primary")
                onClick { onRestore() }
            }) { Text("Restore Topic") }
            A(trashPlace(entry.id).hash, { classes("btn", "btn--line") }) { Text("Open in Trash") }
        }
    }
}

/** "Its 2 tasks and 1 reminder went with it. 3 notes stay in Notes, unlinked from it." (v3 trashedState). */
private fun wentWithTopic(entry: TrashEntry.TopicEntry): String {
    val parts = listOfNotNull(count(entry.tasks.size, "task", "tasks"), count(entry.reminders.size, "reminder", "reminders"))
    val went = if (parts.isEmpty()) "It had no tasks or reminders." else "Its ${parts.joinToString(" and ")} went with it."
    val stay = if (entry.notes.size == 1) "stays" else "stay"
    val kept = count(entry.notes.size, "note", "notes")?.let { " $it $stay in Notes, unlinked from it." }.orEmpty()
    val links =
        count(entry.externals.size, "Jira or pull request link was", "Jira and pull request links were")
            ?.let { " $it removed. The tickets and pull requests are not changed." }
            .orEmpty()
    return went + kept + links
}

private fun count(
    n: Int,
    one: String,
    many: String,
): String? = if (n == 0) null else "$n ${if (n == 1) one else many}"

private const val TOPIC_NAME_ID = "td-name"
private const val GONE_HEADING_ID = "gone-h"

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

/** What the Topic's side panel shows: a linked Jira ticket or a linked pull request. */
private sealed interface LinkedPanel {
    data class Ticket(
        val key: JiraIssueKey,
    ) : LinkedPanel

    data class Pull(
        val ref: PullRequestRef,
    ) : LinkedPanel
}
