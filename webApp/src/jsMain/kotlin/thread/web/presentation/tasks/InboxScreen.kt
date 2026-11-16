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
import thread.domain.note.NoteId
import thread.domain.note.NoteSummary
import thread.domain.note.UNTITLED_NOTE
import thread.domain.task.Task
import thread.domain.topic.TopicId
import thread.domain.workspace.triageInbox
import thread.web.platform.WallClock
import thread.web.presentation.CardKind
import thread.web.presentation.CheckBox
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.KindMark
import thread.web.presentation.LocalCards
import thread.web.presentation.LocalNow
import thread.web.presentation.LocalToasts
import thread.web.presentation.PageHeader
import thread.web.presentation.Router
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.agoText
import thread.web.presentation.itemCard
import thread.web.presentation.shell.CaptureState
import thread.web.presentation.topics.TopicPickerDialog
import thread.web.presentation.topics.topicPlace

/**
 * Inbox (v3 inbox.html): captures without a Topic. Select some, then link them to a Topic or keep them standalone.
 * Tasks join the Topic and Notes add it to their Topics; both clear the Inbox flag. Nothing is copied or deleted.
 */
@Composable
internal fun InboxScreen(
    workspace: WorkspaceModel,
    router: Router,
    capture: CaptureState,
    onOpenTask: (Task) -> Unit,
    onOpenNote: (NoteId) -> Unit,
) {
    if (!workspace.loaded) {
        if (workspace.readFailed) EmptyState(Icon.Alert, "Inbox could not load", "Reload the page to try again.")
        return
    }
    val toasts = LocalToasts.current
    val cards = LocalCards.current
    val now = LocalNow.current
    val zone = workspace.prefs.timeZone
    val items = inboxItems(workspace.inbox, workspace.inboxNotes)
    val selection = remember { mutableStateListOf<String>() }
    var linking by remember { mutableStateOf(false) }
    val chosen = items.filter { it.key in selection }
    val count = chosen.size
    val all = items.isNotEmpty() && count == items.size
    SideEffect {
        // An item filed elsewhere leaves the selection with it.
        val present = items.map { it.key }.toSet()
        selection.removeAll { it !in present }
    }

    fun triage(
        picked: List<InboxItem>,
        topicId: TopicId?,
    ) = workspace.apply(
        triageInbox(
            picked.mapNotNull { it.task },
            picked.mapNotNull { it.note },
            workspace.noteLinks,
            topicId,
        ),
    )
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
                if (checked) selection.addAll(items.map { it.key })
            }
            Text(if (all) " Clear selection " else " Select all ")
            Span({ classes("n") }) { Text(items.size.toString()) }
        }
    }
    Ul({ classes("ibx", "tlist") }) {
        items.forEach { item ->
            val picked = item.key in selection
            val captured = item.createdAtMillis?.let { WallClock.wallTimeAt(it.toDouble(), zone) } ?: now
            val kind = if (item.task != null) "Task" else "Note"
            val cardKind = if (item.task != null) CardKind.Task else CardKind.Note
            key(item.key) {
                Li({
                    itemCard(cardKind)
                    classes("irow")
                    if (picked) classes("is-sel")
                }) {
                    CheckBox(picked, label = "Select ${kind.lowercase()}: ${item.title}") { checked ->
                        if (checked) selection.add(item.key) else selection.remove(item.key)
                    }
                    KindMark(cardKind)
                    Div({ classes("irow__body") }) {
                        Button({
                            type(ButtonType.Button)
                            classes("irow__title")
                            onClick {
                                item.task?.let(onOpenTask)
                                item.note?.let { onOpenNote(it.id) }
                            }
                        }) { Text(item.title) }
                        Span({ classes("irow__kind") }) { Text("$kind, captured ${agoText(captured, now)}") }
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
                    val edit = triage(chosen, null) ?: return@onClick
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
                val linked = items.filter { it.key in selection }
                val edit = triage(linked, topic.id) ?: return@TopicPickerDialog
                selection.clear()
                toasts.show(
                    "Linked ${itemCount(linked.size)} to ${topic.name}",
                    undo = { workspace.undo(edit) },
                    open = { if (cards != null) cards.openTopic(topic.id) else router.go(topicPlace(topic)) },
                    openLabel = "Open Topic",
                )
            },
            onCancel = { linking = false },
        )
    }
}

/** A Task or a Note waiting in Inbox. */
private class InboxItem(
    val task: Task?,
    val note: NoteSummary?,
) {
    val key: String = task?.let { "task:${it.id.value}" } ?: "note:${note?.id?.value}"
    val title: String = task?.title ?: note?.title?.ifBlank { null } ?: UNTITLED_NOTE
    val createdAtMillis: Long? = task?.createdAtMillis ?: note?.createdAtMillis
}

/** Tasks and Notes together, newest capture first (v3 S.inboxItems). One not on the server yet is the newest. */
private fun inboxItems(
    tasks: List<Task>,
    notes: List<NoteSummary>,
): List<InboxItem> =
    (tasks.map { InboxItem(it, null) } + notes.map { InboxItem(null, it) })
        .sortedByDescending { it.createdAtMillis ?: Long.MAX_VALUE }

private fun itemCount(count: Int): String = if (count == 1) "1 item" else "$count items"
