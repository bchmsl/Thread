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
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import thread.domain.note.NoteId
import thread.domain.note.UNTITLED_NOTE
import thread.domain.task.DueGroup
import thread.domain.task.Task
import thread.domain.task.firstDayOf
import thread.domain.time.WallTime
import thread.domain.topic.TopicId
import thread.domain.workspace.InboxEntry
import thread.domain.workspace.InboxGroup
import thread.domain.workspace.InboxHeading
import thread.domain.workspace.InboxShown
import thread.domain.workspace.inboxGroups
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
import thread.web.presentation.Place
import thread.web.presentation.Router
import thread.web.presentation.SectionCard
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.agoText
import thread.web.presentation.dayText
import thread.web.presentation.itemCard
import thread.web.presentation.shell.CaptureState
import thread.web.presentation.topics.TopicPickerDialog
import thread.web.presentation.topics.topicPlace

/**
 * Inbox (v3 inbox.html): captures without a Topic. Select some, then link them to a Topic or keep them standalone.
 * Tasks join the Topic and Notes add it to their Topics; both clear the Inbox flag. Nothing is copied or deleted.
 *
 * Settings > Inbox (D91) picks what shows (completed Tasks, Notes) and how it is grouped (None, Type, Due date). Select
 * all, the count and the selection cover what shows, across every group (A14). The groups do not fold, so nothing
 * selected is ever out of sight.
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
    val shown = workspace.inboxShown
    val groups = inboxGroups(shown, workspace.prefs.inboxGroupBy, now, workspace.taskReminderTimes)
    // What shows, in page order.
    val items = groups.flatMap { it.entries }
    val selection = remember { mutableStateListOf<String>() }
    var linking by remember { mutableStateOf(false) }
    val chosen = items.filter { it.key in selection }
    val count = chosen.size
    val all = items.isNotEmpty() && count == items.size
    SideEffect {
        // An item filed elsewhere, or hidden in Settings, leaves the selection with it.
        val present = items.map { it.key }.toSet()
        selection.removeAll { it !in present }
    }

    fun triage(
        picked: List<InboxEntry>,
        topicId: TopicId?,
    ) = workspace.apply(
        triageInbox(
            picked.mapNotNull { (it as? InboxEntry.OfTask)?.task },
            picked.mapNotNull { (it as? InboxEntry.OfNote)?.note },
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
        val hidden = hiddenInboxText(shown)
        if (hidden != null) {
            // A14: only hidden items wait. They are still in Inbox; Settings brings them back.
            EmptyState(Icon.Inbox, "Inbox is clear", hidden) {
                A(INBOX_SETTINGS.hash, { classes("btn", "btn--line") }) { Text("Change in Settings") }
            }
            return
        }
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
    val zone = workspace.prefs.timeZone
    val list: @Composable (List<InboxEntry>) -> Unit = { entries -> InboxList(entries, selection, zone, now, onOpenTask, onOpenNote) }
    groups.forEach { group ->
        // Group by None keeps the one list without a heading, as before D91.
        if (group.heading == InboxHeading.All) {
            list(group.entries)
        } else {
            key(inboxGroupKey(group.heading)) { InboxGroupCard(group, workspace, list) }
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

/**
 * One Inbox group with Group by Type or Due date (D91): a section card like Today's groups (D90), whose heading counts
 * what it holds. It does not fold. Upcoming lists its Tasks under day headings, the ones with no date last, as on Today.
 */
@Composable
private fun InboxGroupCard(
    group: InboxGroup,
    workspace: WorkspaceModel,
    list: @Composable (List<InboxEntry>) -> Unit,
) {
    val heading = group.heading
    val key = inboxGroupKey(heading)
    val (mark, hint) =
        when (heading) {
            InboxHeading.All, InboxHeading.Tasks -> Icon.Task to null
            InboxHeading.Notes -> Icon.Notes to null
            is InboxHeading.Due ->
                if (heading.group == DueGroup.Completed) Icon.Check to COMPLETED_HINT else dueGroupLook(heading.group)
        }
    SectionCard(
        kind = if (heading == InboxHeading.Notes) CardKind.Note else CardKind.Task,
        headingId = "ig-$key",
        bodyId = "igb-$key",
        title = inboxGroupTitle(heading),
        count = group.entries.size.toString(),
        hint = hint,
        mark = mark,
        attrs = { classes("ibx-group", "ibx-group--$key") },
    ) {
        if (heading == InboxHeading.Due(DueGroup.Upcoming)) {
            val today = LocalNow.current.day
            val remindAt = workspace.taskReminderTimes
            group.entries
                .groupBy { entry -> (entry as? InboxEntry.OfTask)?.task?.let { firstDayOf(it, remindAt[it.id]) } }
                .forEach { (day, onDay) ->
                    key(day.toString()) {
                        H3({ classes("tgroup__day") }) { Text(if (day == null) "No date" else dayText(day, today)) }
                        list(onDay)
                    }
                }
        } else {
            list(group.entries)
        }
    }
}

/** Inbox rows: a checkbox to select, the kind, the title that opens it, and when it was captured. */
@Composable
private fun InboxList(
    entries: List<InboxEntry>,
    selection: SnapshotStateList<String>,
    zone: String,
    now: WallTime,
    onOpenTask: (Task) -> Unit,
    onOpenNote: (NoteId) -> Unit,
) {
    Ul({ classes("ibx", "tlist") }) {
        entries.forEach { item ->
            val picked = item.key in selection
            val captured = item.createdAtMillis?.let { WallClock.wallTimeAt(it.toDouble(), zone) } ?: now
            val task = (item as? InboxEntry.OfTask)?.task
            val kind = if (task != null) "Task" else "Note"
            val cardKind = if (task != null) CardKind.Task else CardKind.Note
            key(item.key) {
                Li({
                    itemCard(cardKind)
                    classes("irow")
                    if (picked) classes("is-sel")
                    // I1: a completed Task still waiting in Inbox shows struck through (A7).
                    if (task?.done == true) classes("is-done")
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
                                when (item) {
                                    is InboxEntry.OfTask -> onOpenTask(item.task)
                                    is InboxEntry.OfNote -> onOpenNote(item.note.id)
                                }
                            }
                        }) { Text(item.title) }
                        val state = if (task?.done == true) ", completed" else ""
                        Span({ classes("irow__kind") }) { Text("$kind$state, captured ${agoText(captured, now)}") }
                    }
                }
            }
        }
    }
}

/** The selection key of an Inbox entry: its kind and id, so a Task and a Note never share one. */
private val InboxEntry.key: String
    get() =
        when (this) {
            is InboxEntry.OfTask -> "task:${task.id.value}"
            is InboxEntry.OfNote -> "note:${note.id.value}"
        }

private val InboxEntry.title: String
    get() =
        when (this) {
            is InboxEntry.OfTask -> task.title
            is InboxEntry.OfNote -> note.title.ifBlank { UNTITLED_NOTE }
        }

/** An Inbox group's heading (D91). The due-date groups are named as on Today. */
internal fun inboxGroupTitle(heading: InboxHeading): String =
    when (heading) {
        InboxHeading.All -> "Inbox"
        InboxHeading.Tasks -> "Tasks"
        InboxHeading.Notes -> "Notes"
        is InboxHeading.Due -> dueGroupTitle(heading.group)
    }

private fun inboxGroupKey(heading: InboxHeading): String =
    when (heading) {
        InboxHeading.All -> "all"
        InboxHeading.Tasks -> "tasks"
        InboxHeading.Notes -> "notes"
        is InboxHeading.Due -> heading.group.name.lowercase()
    }

/**
 * What Settings > Inbox hides while nothing else waits (A14): "1 note is hidden by your Inbox settings.", or null when
 * nothing is hidden.
 */
internal fun hiddenInboxText(shown: InboxShown): String? {
    val parts =
        listOfNotNull(
            shown.hiddenNotes.takeIf { it > 0 }?.let { if (it == 1) "1 note" else "$it notes" },
            shown.hiddenCompleted.takeIf { it > 0 }?.let { if (it == 1) "1 completed task" else "$it completed tasks" },
        )
    if (parts.isEmpty()) return null
    val verb = if (shown.hiddenNotes + shown.hiddenCompleted == 1) "is" else "are"
    return "${parts.joinToString(" and ")} $verb hidden by your Inbox settings."
}

private const val COMPLETED_HINT = "Done. They stay in Inbox until you file them"

private val INBOX_SETTINGS = Place.of("settings", "inbox")

private fun itemCount(count: Int): String = if (count == 1) "1 item" else "$count items"
