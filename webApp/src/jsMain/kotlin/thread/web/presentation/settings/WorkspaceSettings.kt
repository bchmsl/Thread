package thread.web.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.maxLength
import org.jetbrains.compose.web.attributes.name
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.required
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Fieldset
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Legend
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import thread.domain.topic.STATUS_NAME_MAX_LENGTH
import thread.domain.topic.StatusCategory
import thread.domain.topic.StatusId
import thread.domain.topic.TopicStatus
import thread.domain.topic.useCount
import thread.domain.workspace.CaptureKind
import thread.domain.workspace.EditProblem
import thread.domain.workspace.EditResult
import thread.domain.workspace.TopicsView
import thread.domain.workspace.addStatus
import thread.domain.workspace.changeStatusCategory
import thread.domain.workspace.deleteStatus
import thread.domain.workspace.moveStatus
import thread.domain.workspace.renameStatus
import thread.web.presentation.CheckBox
import thread.web.presentation.ConfirmDialog
import thread.web.presentation.Icon
import thread.web.presentation.IconButton
import thread.web.presentation.LocalToasts
import thread.web.presentation.ModalDialog
import thread.web.presentation.SelectBox
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.shell.CaptureState
import thread.web.presentation.shell.CaptureType
import thread.web.presentation.topics.StatusLabel
import thread.web.presentation.topics.Swatch
import thread.web.presentation.topics.categoryHint
import thread.web.presentation.topics.categoryLabel

/** Settings > Topic statuses (v3 settings.js `statuses`): rename, recategorize, reorder, add and delete. */
@Composable
internal fun StatusesBlock(workspace: WorkspaceModel) {
    SettingsBlock(
        "statuses",
        "Topic statuses",
        "Name statuses your way. Each maps to one of four categories, which decide where a Topic shows up.",
    ) {
        val toasts = LocalToasts.current
        val statuses = workspace.statuses
        val topics = workspace.data?.topics.orEmpty()
        var deleting by remember { mutableStateOf<TopicStatus?>(null) }

        P({ classes("ser-legend") }) {
            StatusCategory.entries.forEach { category ->
                Span({ classes("st", "st--${category.id}") }) {
                    Swatch(category)
                    Span({ classes("st__name") }) { Text(categoryLabel(category)) }
                }
                Span { Text(categoryHint(category)) }
            }
        }
        Ul({ classes("ser-list") }) {
            statuses.forEachIndexed { index, status ->
                key(status.id) {
                    Li({ classes("ser") }) {
                        Span({ classes("ser__order") }) {
                            IconButton(Icon.ArrowUp, "Move ${status.name} up", small = true, disabled = index == 0) {
                                workspace.apply(moveStatus(workspace.storedStatuses, status.id, -1))
                            }
                            IconButton(Icon.ArrowDown, "Move ${status.name} down", small = true, disabled = index == statuses.lastIndex) {
                                workspace.apply(moveStatus(workspace.storedStatuses, status.id, 1))
                            }
                        }
                        Span {
                            Label("stn-${status.id}", { classes("vh") }) { Text("Status name") }
                            StatusNameInput(status) { input ->
                                when (val result = renameStatus(workspace.storedStatuses, status.id, input.value)) {
                                    is EditResult.Changed -> {
                                        val edit = workspace.apply(result)
                                        val renamed = input.value.trim()
                                        toasts.show(
                                            "Renamed to $renamed. Topics, Board and Today use the new name.",
                                            undo = edit?.let { { workspace.undo(it) } },
                                        )
                                    }
                                    EditResult.Unchanged -> input.value = status.name
                                    is EditResult.Refused -> {
                                        input.value = status.name
                                        toasts.show(statusProblemText(result.problem))
                                    }
                                }
                            }
                        }
                        Span({ classes("ser__cat") }) {
                            Swatch(status.category)
                            Label("stc-${status.id}", { classes("vh") }) { Text("Category for ${status.name}") }
                            SelectBox(
                                value = status.category.id,
                                options = StatusCategory.entries.map { it.id to categoryLabel(it) },
                                id = "stc-${status.id}",
                                classes = listOf("input", "input--sm"),
                            ) { picked ->
                                val category = StatusCategory.fromId(picked) ?: return@SelectBox
                                val result = changeStatusCategory(workspace.storedStatuses, status.id, category)
                                if (result is EditResult.Refused) {
                                    toasts.show(statusProblemText(result.problem))
                                    return@SelectBox
                                }
                                val edit = workspace.apply(result) ?: return@SelectBox
                                val used = useCount(status.id, topics)
                                val moved = if (used > 0) ". $used ${if (used == 1) "Topic" else "Topics"} moved with it." else "."
                                toasts.show(
                                    "${status.name} now counts as ${categoryLabel(category)}$moved",
                                    undo = { workspace.undo(edit) },
                                )
                            }
                        }
                        val used = useCount(status.id, topics)
                        Span({ classes("ser__use") }) { Text(if (used > 0) "Used by $used" else "Not used") }
                        IconButton(Icon.Trash, "Delete ${status.name}", small = true, disabled = statuses.size <= 1) {
                            deleting = status
                        }
                    }
                }
            }
        }
        AddStatusForm(workspace)

        deleting?.let { status ->
            val used = useCount(status.id, topics)
            val finish = { replacement: StatusId? ->
                deleting = null
                when (val result = deleteStatus(workspace.storedStatuses, workspace.data?.topics.orEmpty(), status.id, replacement)) {
                    is EditResult.Changed -> {
                        val edit = workspace.apply(result)
                        val target = statuses.firstOrNull { it.id == replacement }
                        val text =
                            if (used > 0 && target != null) {
                                "Deleted ${status.name}. $used ${if (used == 1) "Topic" else "Topics"} moved to ${target.name}."
                            } else {
                                "Deleted ${status.name}"
                            }
                        toasts.show(text, undo = edit?.let { { workspace.undo(it) } })
                    }
                    EditResult.Unchanged -> Unit
                    is EditResult.Refused -> toasts.show(statusProblemText(result.problem))
                }
            }
            if (used == 0) {
                ConfirmDialog("Delete ${status.name}?", "No Topic uses it.", "Delete status", danger = true) { ok ->
                    if (ok) finish(null) else deleting = null
                }
            } else {
                ReplaceStatusDialog(status, used, statuses, onCancel = { deleting = null }, onConfirm = { finish(it) })
            }
        }
    }
}

/** The status name box. It keeps what the person typed until they leave it, then [onCommit] decides. */
@Composable
private fun StatusNameInput(
    status: TopicStatus,
    onCommit: (HTMLInputElement) -> Unit,
) {
    var element by remember { mutableStateOf<HTMLInputElement?>(null) }
    Input(InputType.Text) {
        classes("input", "input--sm")
        id("stn-${status.id}")
        maxLength(STATUS_NAME_MAX_LENGTH)
        ref { input ->
            input.value = status.name
            element = input
            onDispose { element = null }
        }
        onChange { event -> (event.target as? HTMLInputElement)?.let(onCommit) }
    }
    SideEffect {
        element?.let { if (document.activeElement != it && it.value != status.name) it.value = status.name }
    }
}

@Composable
private fun AddStatusForm(workspace: WorkspaceModel) {
    val toasts = LocalToasts.current
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(StatusCategory.Active) }
    Form(attrs = {
        classes("ser-add")
        attr("autocomplete", "off")
        onSubmit { event ->
            event.preventDefault()
            when (val result = addStatus(workspace.storedStatuses, workspace.newStatusId(), name, category)) {
                is EditResult.Changed -> {
                    val edit = workspace.apply(result)
                    toasts.show("Added ${name.trim()}. It shows as a lane on the Board.", undo = edit?.let { { workspace.undo(it) } })
                    name = ""
                    (document.getElementById("st-new") as? HTMLElement)?.focus()
                }
                EditResult.Unchanged -> Unit
                is EditResult.Refused -> toasts.show(statusProblemText(result.problem))
            }
        }
    }) {
        Label("st-new", { classes("vh") }) { Text("New status name") }
        TextInput(name) {
            classes("input")
            id("st-new")
            name("name")
            placeholder("New status, for example Waiting on QA")
            maxLength(STATUS_NAME_MAX_LENGTH)
            required()
            onInput { name = it.value }
        }
        Label("st-new-cat", { classes("vh") }) { Text("Category") }
        SelectBox(
            value = category.id,
            options = StatusCategory.entries.map { it.id to categoryLabel(it) },
            id = "st-new-cat",
            classes = listOf("input"),
        ) { picked -> StatusCategory.fromId(picked)?.let { category = it } }
        Button({
            type(ButtonType.Submit)
            classes("btn", "btn--primary")
        }) { Text("Add status") }
    }
}

/** v3 "Delete X?" with Topics: pick where they move. No Topic is deleted. */
@Composable
private fun ReplaceStatusDialog(
    status: TopicStatus,
    used: Int,
    statuses: List<TopicStatus>,
    onCancel: () -> Unit,
    onConfirm: (StatusId) -> Unit,
) {
    val others = statuses.filter { it.id != status.id }
    var picked by remember { mutableStateOf((others.firstOrNull { it.category == status.category } ?: others.first()).id) }
    ModalDialog(onClose = onCancel) { titleId ->
        Form(attrs = {
            classes("dlg__in")
            onSubmit { event ->
                event.preventDefault()
                onConfirm(picked)
            }
        }) {
            H2({
                classes("dlg__title")
                id(titleId)
            }) { Text("Delete ${status.name}?") }
            P({ classes("dlg__text") }) {
                Text("$used ${if (used == 1) "Topic uses" else "Topics use"} it. Pick the status they move to. No Topic is deleted.")
            }
            Fieldset({ classes("picklist") }) {
                Legend({ classes("vh") }) { Text("Move Topics to") }
                others.forEach { other ->
                    Label(attrs = { classes("pick") }) {
                        Input(InputType.Radio) {
                            name("rep")
                            value(other.id.value)
                            checked(other.id == picked)
                            if (other.id == picked) attr("autofocus", "")
                            onChange { if (it.value) picked = other.id }
                        }
                        StatusLabel(other)
                        if (other.name != categoryLabel(other.category)) {
                            Span({
                                classes("pick__sub")
                                attr("aria-hidden", "true")
                            }) { Text(categoryLabel(other.category)) }
                        }
                    }
                }
            }
            Div({ classes("dlg__acts") }) {
                Button({
                    type(ButtonType.Button)
                    classes("btn", "btn--line")
                    onClick { onCancel() }
                }) { Text("Cancel") }
                Button({
                    type(ButtonType.Submit)
                    classes("btn", "btn--danger")
                }) { Text("Delete and move Topics") }
            }
        }
    }
}

private fun statusProblemText(problem: EditProblem): String =
    when (problem) {
        EditProblem.BlankName -> "A status needs a name"
        EditProblem.NameTooLong -> "A status name can have at most $STATUS_NAME_MAX_LENGTH characters"
        EditProblem.DuplicateName -> "A status with that name already exists"
        EditProblem.LastStatus -> "Keep at least one status"
        EditProblem.NotReady -> "Statuses are still loading. Try again in a moment."
        else -> "That change could not be made"
    }

/** Settings > Preferences (v3 settings.js `prefs`). "Keep completed tasks in Today" comes with Tasks. */
@Composable
internal fun PreferencesBlock(
    workspace: WorkspaceModel,
    capture: CaptureState,
) {
    SettingsBlock("prefs", "Preferences", null) {
        val prefs = workspace.prefs
        Div({ classes("pref") }) {
            Div {
                P({
                    classes("pref__label")
                    id("pv-l")
                }) { Text("Topics opens as") }
                P({ classes("pref__hint") }) { Text("You can switch any time on the Topics page.") }
            }
            Div({
                classes("seg")
                attr("role", "radiogroup")
                attr("aria-labelledby", "pv-l")
            }) {
                TopicsView.entries.forEach { option ->
                    Button({
                        type(ButtonType.Button)
                        attr("role", "radio")
                        attr("aria-checked", (prefs.topicsView == option).toString())
                        onClick { workspace.setPrefs(workspace.prefs.copy(topicsView = option)) }
                    }) { Text(if (option == TopicsView.List) "List" else "Board") }
                }
            }
        }
        Div({ classes("pref") }) {
            Div {
                Label("pref-keep", { classes("pref__label") }) { Text("Keep completed tasks in Today") }
                P({ classes("pref__hint") }) { Text("They stay, struck through, until the end of the day.") }
            }
            CheckBox(prefs.keepCompleted, label = null, classes = listOf("switch"), id = "pref-keep", switch = true) { on ->
                workspace.setPrefs(workspace.prefs.copy(keepCompleted = on))
            }
        }
        Div({ classes("pref") }) {
            Div {
                Label("pref-hide", { classes("pref__label") }) { Text("Hide empty sections in Today") }
                P({ classes("pref__hint") }) { Text("Today, Reminders and Review requested show only when they have something.") }
            }
            CheckBox(prefs.hideEmptySections, label = null, classes = listOf("switch"), id = "pref-hide", switch = true) { on ->
                workspace.setPrefs(workspace.prefs.copy(hideEmptySections = on))
            }
        }
        Div({ classes("pref") }) {
            Div {
                Label("pref-cap", { classes("pref__label") }) { Text("Capture starts as") }
                P({ classes("pref__hint") }) { Text("The type picked in the bottom bar when a page opens.") }
            }
            SelectBox(
                value = prefs.captureType.id,
                options = CaptureKind.entries.map { it.id to it.id.replaceFirstChar(Char::uppercaseChar) },
                id = "pref-cap",
                classes = listOf("input"),
            ) { picked ->
                val kind = CaptureKind.fromId(picked) ?: return@SelectBox
                workspace.setPrefs(workspace.prefs.copy(captureType = kind))
                capture.type = CaptureType.of(kind)
            }
        }
    }
}
