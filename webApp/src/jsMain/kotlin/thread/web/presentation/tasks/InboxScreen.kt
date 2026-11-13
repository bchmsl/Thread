package thread.web.presentation.tasks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import thread.domain.task.Task
import thread.domain.topic.TopicId
import thread.domain.workspace.triageTasks
import thread.web.platform.WallClock
import thread.web.presentation.CheckBox
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalNow
import thread.web.presentation.LocalToasts
import thread.web.presentation.PageHeader
import thread.web.presentation.Router
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.agoText
import thread.web.presentation.shell.CaptureState
import thread.web.presentation.topics.TopicPickerDialog
import thread.web.presentation.topics.topicPlace

/**
 * Inbox (v3 inbox.html): captures without a Topic. Select some, then link them to a Topic or keep them standalone.
 * Both only clear the Inbox flag; nothing is copied or deleted. Notes join in Phase 8.
 */
@Composable
internal fun InboxScreen(
    workspace: WorkspaceModel,
    router: Router,
    capture: CaptureState,
    onOpenTask: (Task) -> Unit,
) {
    if (!workspace.loaded) {
        if (workspace.readFailed) EmptyState(Icon.Alert, "Inbox could not load", "Reload the page to try again.")
        return
    }
    val toasts = LocalToasts.current
    val now = LocalNow.current
    val items = workspace.inbox
    val selection = remember { mutableStateListOf<String>() }
    var linking by remember { mutableStateOf(false) }
    val chosen = items.filter { it.id.value in selection }
    val count = chosen.size
    val all = items.isNotEmpty() && count == items.size
    SideEffect {
        // A Task filed elsewhere leaves the selection with it.
        val present = items.map { it.id.value }.toSet()
        selection.removeAll { it !in present }
    }
    DisposableEffect(count > 0) {
        document.body?.classList?.toggle("has-selbar", count > 0)
        onDispose { document.body?.classList?.remove("has-selbar") }
    }

    val intro =
        when {
            items.isNotEmpty() -> "Select captures, then link them to a Topic or keep them standalone."
            else -> "Captures without a Topic land here."
        }
    PageHeader("Inbox", intro)
    if (items.isEmpty()) {
        EmptyState(Icon.Inbox, "Inbox is clear", "Everything is filed. New tasks and notes captured without a Topic will wait here.") {
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--line")
                onClick { capture.requestFocus() }
            }) {
                IconView(Icon.Plus, 16)
                Text(" Capture something")
            }
        }
        return
    }
    Div({ classes("ibx-tools") }) {
        Label {
            CheckBox(all, label = null, id = "ibx-all", indeterminate = count > 0 && !all) { checked ->
                selection.clear()
                if (checked) selection.addAll(items.map { it.id.value })
            }
            Text(if (all) " Clear selection " else " Select all ")
            Span({ classes("n") }) { Text(items.size.toString()) }
        }
    }
    Ul({ classes("ibx") }) {
        items.forEach { task ->
            val id = task.id.value
            val picked = id in selection
            val captured = task.createdAtMillis?.let { WallClock.wallTimeAt(it.toDouble(), workspace.prefs.timeZone) } ?: now
            key(id) {
                Li({
                    classes("irow")
                    if (picked) classes("is-sel")
                }) {
                    CheckBox(picked, label = "Select task: ${task.title}") { checked ->
                        if (checked) selection.add(id) else selection.remove(id)
                    }
                    Span({ classes("irow__icon") }) { IconView(Icon.Task, 18) }
                    Div({ classes("irow__body") }) {
                        Button({
                            type(ButtonType.Button)
                            classes("irow__title")
                            onClick { onOpenTask(task) }
                        }) { Text(task.title) }
                        Span({ classes("irow__kind") }) { Text("Task, captured ${agoText(captured, now)}") }
                    }
                }
            }
        }
    }
    if (count > 0) {
        Div({
            classes("selbar")
            attr("role", "region")
            attr("aria-label", "Selection")
        }) {
            P({
                classes("selbar__count")
                attr("aria-live", "polite")
            }) { Text("$count selected") }
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--primary")
                onClick { linking = true }
            }) { Text("Link to Topic") }
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--line")
                onClick {
                    val edit = workspace.apply(triageTasks(chosen, null)) ?: return@onClick
                    selection.clear()
                    toasts.show("Kept ${itemCount(chosen.size)} standalone", undo = { workspace.undo(edit) })
                }
            }) { Text("Keep standalone") }
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text")
                onClick {
                    selection.clear()
                    focusNow("ibx-all")
                }
            }) { Text("Clear") }
        }
    }
    if (linking) {
        TopicPickerDialog(
            workspace = workspace,
            title = "Link ${itemCount(count)} to a Topic",
            hint = "Tasks join the Topic. Notes add it to their Topics. They leave Inbox; nothing is copied or deleted.",
            confirmLabel = "Link to Topic",
            onPick = { picked: TopicId? ->
                linking = false
                val topic = picked?.let { workspace.topic(it.value) } ?: return@TopicPickerDialog
                val linked = items.filter { it.id.value in selection }
                val edit = workspace.apply(triageTasks(linked, topic.id)) ?: return@TopicPickerDialog
                selection.clear()
                toasts.show(
                    "Linked ${itemCount(linked.size)} to ${topic.name}",
                    undo = { workspace.undo(edit) },
                    open = { router.go(topicPlace(topic)) },
                    openLabel = "Open Topic",
                )
            },
            onCancel = { linking = false },
        )
    }
}

private fun itemCount(count: Int): String = if (count == 1) "1 item" else "$count items"
