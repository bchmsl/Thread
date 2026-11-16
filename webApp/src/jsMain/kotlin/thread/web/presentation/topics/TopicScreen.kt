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
import org.jetbrains.compose.web.attributes.AttrsScope
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
import org.w3c.dom.HTMLHeadingElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.KeyboardEvent
import thread.domain.topic.StatusId
import thread.domain.topic.TOPIC_INFO_MAX_LENGTH
import thread.domain.topic.TOPIC_NAME_MAX_LENGTH
import thread.domain.topic.TOPIC_TEXT_MAX_LENGTH
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.statusOf
import thread.domain.topic.topicInfo
import thread.domain.trash.TrashEntry
import thread.domain.workspace.EditProblem
import thread.domain.workspace.EditResult
import thread.domain.workspace.editInfo
import thread.domain.workspace.editTopic
import thread.domain.workspace.moveTopic
import thread.web.presentation.CardKind
import thread.web.presentation.CardOpener
import thread.web.presentation.CardType
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalIdScope
import thread.web.presentation.LocalToasts
import thread.web.presentation.ModalDialog
import thread.web.presentation.SectionCard
import thread.web.presentation.SelectBox
import thread.web.presentation.View
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.external.GitHubModel
import thread.web.presentation.external.JiraModel
import thread.web.presentation.scopedId
import thread.web.presentation.tasks.focusById
import thread.web.presentation.trash.trashPlace

/**
 * A Topic's page in the Topics tab (v3 topic.html): [TopicDetails] under a link back to Topics. A Topic in Trash keeps
 * its page: it says what went with it and offers Restore.
 */
@Composable
internal fun TopicScreen(
    workspace: WorkspaceModel,
    jira: JiraModel,
    gitHub: GitHubModel,
    topicId: String?,
    cards: CardOpener,
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
    A(View.Topics.place.hash, { classes("back") }) {
        IconView(Icon.ChevLeft, 16)
        Text(" Topics")
    }
    TopicDetails(workspace, jira, gitHub, topic, cards, inCard = false) {
        // Move to Trash is instant with Undo (v3, owner decision 2026-11-13). The page then shows what went with it.
        val edit = workspace.trashTopic(topic) ?: return@TopicDetails
        focusAfterSwitch = GONE_HEADING_ID
        toasts.show("Moved to Trash: ${topic.name}", undo = {
            focusAfterSwitch = TOPIC_NAME_ID
            workspace.undo(edit)
        })
    }
}

/**
 * Topic Details (v3 topic.html): identity and status on top, "Additional information" (D52), then Tasks and Notes,
 * with Jira, pull requests and Reminders beside them. Every linked item opens as a card (D70).
 *
 * The Topic's page shows it, and so does the Topic card ([inCard]). In the card the name is the card's heading, and
 * Move to Trash sits in the card's footer: [onTrash] is null there.
 */
@Composable
internal fun TopicDetails(
    workspace: WorkspaceModel,
    jira: JiraModel,
    gitHub: GitHubModel,
    topic: Topic,
    cards: CardOpener,
    inCard: Boolean,
    onTrash: (() -> Unit)? = null,
) {
    val toasts = LocalToasts.current
    val ids = LocalIdScope.current
    var editing by remember { mutableStateOf(false) }
    // v3 opens Tasks and Notes and closes Reminders by default; the choice lasts while the page is open.
    var tasksOpen by remember(topic.id) { mutableStateOf(true) }
    var notesOpen by remember(topic.id) { mutableStateOf(true) }
    var remindersOpen by remember(topic.id) { mutableStateOf(false) }
    var jiraOpen by remember(topic.id) { mutableStateOf(true) }
    var prsOpen by remember(topic.id) { mutableStateOf(true) }
    var focusAddTask by remember { mutableStateOf(0) }
    LaunchedEffect(focusAddTask) { if (focusAddTask > 0) focusById("${ids}td-new-task") }
    val statuses = workspace.statuses
    val status = statusOf(topic, statuses)

    Header({
        classes("td-head")
        if (inCard) classes("dcard__th")
        topicColor(topic.color)
    }) {
        TopicHex(topic.name, topic.color, if (inCard) HexSize.Md else HexSize.Lg)
        val name: AttrsScope<HTMLHeadingElement>.() -> Unit = {
            classes("td-name")
            id(ids + TOPIC_NAME_ID)
            tabIndex(-1)
        }
        if (inCard) H2(name) { Text(topic.name) } else H1(name) { Text(topic.name) }
        Div({ classes("td-meta") }) {
            Span({ classes("st", "st--${status.category.id}") }) { Swatch(status.category) }
            Label("${ids}td-status", { classes("vh") }) { Text("Status") }
            SelectBox(
                value = status.id.value,
                options =
                    statuses.map {
                        val category = categoryLabel(it.category)
                        it.id.value to if (it.name == category) it.name else "${it.name} ($category)"
                    },
                id = "${ids}td-status",
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
            if (onTrash != null) {
                Button({
                    type(ButtonType.Button)
                    classes("btn", "btn--text", "btn--danger-text")
                    attr("aria-describedby", "${ids}td-trash-hint")
                    onClick { onTrash() }
                }) {
                    IconView(Icon.Trash, 16)
                    Text(" Move to Trash")
                }
                TopicTrashHint()
            }
        }
    }
    InfoSection(workspace, topic)
    // Suggestions are computed once here, for both the Jira and the pull request section.
    val suggestions = rememberTopicSuggestions(workspace, jira, gitHub, topic)
    // The card on top, if it shows one of this Topic's tickets or pull requests: that one asks for its own update.
    val shown = cards.shown
    Div({ classes("td-grid") }) {
        Div({ classes("td-main") }) {
            TopicTasksSection(workspace, topic, tasksOpen, { tasksOpen = !tasksOpen }) { cards.openTask(it.id) }
            TopicNotesSection(workspace, topic, notesOpen, { notesOpen = !notesOpen }) { id, edit -> cards.openNote(id, edit) }
        }
        Aside({
            classes("td-side")
            attr("aria-label", "Linked work and reminders")
        }) {
            val openKey = shown?.takeIf { it.type == CardType.Jira }?.id
            TopicJiraSection(workspace, jira, topic, jiraOpen, { jiraOpen = !jiraOpen }, openKey, suggestions, cards::openJira)
            val openId = shown?.takeIf { it.type == CardType.Pr }?.id
            TopicPrSection(workspace, gitHub, topic, prsOpen, { prsOpen = !prsOpen }, openId, suggestions, cards::openPr)
            TopicRemindersSection(workspace, topic, remindersOpen, { remindersOpen = !remindersOpen }, cards::openTarget)
        }
    }
    if (editing) {
        EditTopicDialog(topic, workspace) { editing = false }
    }
}

/** What Move to Trash takes with it, for screen readers: the button's description. */
@Composable
internal fun TopicTrashHint() {
    val hintId = scopedId("td-trash-hint")
    Span({
        classes("vh")
        id(hintId)
    }) { Text("Its tasks and reminders go with it. Notes stay in Notes. You can undo or restore it from Trash.") }
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
 * "Additional information" (D52). It shows as text, with a small Edit in its header that turns it into the text box
 * (D70, owner request #3); Done or Esc turns it back. The text saves as it is typed, so there is no Cancel.
 */
@Composable
private fun InfoSection(
    workspace: WorkspaceModel,
    topic: Topic,
) {
    val ids = LocalIdScope.current
    var editing by remember(topic.id) { mutableStateOf(false) }
    var toggle by remember { mutableStateOf<HTMLElement?>(null) }
    var wasEditing by remember { mutableStateOf(false) }
    // Back to the text: focus returns to the Edit button, where Done was.
    LaunchedEffect(editing) {
        if (wasEditing && !editing) toggle?.focus()
        wasEditing = editing
    }
    val text = topicInfo(topic)
    SectionCard(
        kind = CardKind.Info,
        headingId = "pu-h",
        bodyId = "pu-b",
        title = "Additional information",
        attrs = { topicColor(topic.color) },
        actions = {
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text", "btn--sm")
                attr("aria-label", if (editing) "Done editing additional information" else "Edit additional information")
                ref {
                    toggle = it
                    onDispose { toggle = null }
                }
                onClick { editing = !editing }
            }) {
                IconView(if (editing) Icon.Check else Icon.Edit, 15)
                Text(if (editing) " Done" else " Edit")
            }
        },
    ) {
        when {
            editing ->
                InfoField(
                    labelledBy = "${ids}pu-h",
                    value = text,
                    placeholder = "Context, links, decisions: anything worth keeping with this Topic",
                    onDone = { editing = false },
                ) { typed -> workspace.topic(topic.id.value)?.let { workspace.apply(editInfo(it, typed)) } }
            text.isNotEmpty() -> P({ classes("info-text") }) { Text(text) }
            else -> P({ classes("sect__empty") }) { Text("Nothing here yet. Use Edit to add context, links and decisions.") }
        }
    }
}

/**
 * The "Additional information" text box (D52), labelled by the section heading [labelledBy]. It takes focus when it
 * opens. It saves when it loses focus (v3), a second after typing stops, and when it closes or the page leaves it, so
 * a reload or a closed tab never loses what was typed. Text equal to [value] is not saved, so a blur without typing
 * writes nothing. While it has focus, a change from another device does not replace the text being typed. Esc calls
 * [onDone].
 */
@Composable
private fun InfoField(
    labelledBy: String,
    value: String,
    placeholder: String,
    onDone: () -> Unit,
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
    LaunchedEffect(Unit) {
        element?.let {
            it.focus()
            it.setSelectionRange(it.value.length, it.value.length)
        }
    }
    Div({ classes("field") }) {
        TextArea(attrs = {
            classes("input")
            attr("aria-labelledby", labelledBy)
            attr("aria-keyshortcuts", "Escape")
            rows(4)
            placeholder(placeholder)
            maxLength(TOPIC_INFO_MAX_LENGTH)
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
            onKeyDown { event ->
                val key = event.nativeEvent as? KeyboardEvent ?: return@onKeyDown
                // Preventing it also keeps a Topic card open: the next Esc closes the card.
                if (key.key == "Escape" && !key.isComposing) {
                    key.preventDefault()
                    onDone()
                }
            }
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
