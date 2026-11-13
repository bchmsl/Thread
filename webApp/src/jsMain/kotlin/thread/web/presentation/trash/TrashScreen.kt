package thread.web.presentation.trash

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Article
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.DDescription
import org.jetbrains.compose.web.dom.DList
import org.jetbrains.compose.web.dom.DTerm
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import thread.domain.account.AccountId
import thread.domain.note.NoteId
import thread.domain.note.RemoteNote
import thread.domain.note.UNTITLED_NOTE
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.task.Task
import thread.domain.time.WallTime
import thread.domain.topic.Topic
import thread.domain.trash.TopicLink
import thread.domain.trash.TopicState
import thread.domain.trash.TrashEntry
import thread.domain.workspace.ExternalReference
import thread.domain.workspace.TrashKind
import thread.web.platform.WallClock
import thread.web.platform.mediaMatches
import thread.web.platform.observeMedia
import thread.web.presentation.ConfirmDialog
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalNow
import thread.web.presentation.LocalToasts
import thread.web.presentation.PageHeader
import thread.web.presentation.Place
import thread.web.presentation.Router
import thread.web.presentation.View
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.dayText
import thread.web.presentation.deadlineText
import thread.web.presentation.notes.NoteServices
import thread.web.presentation.notes.RichTextView
import thread.web.presentation.notes.launchNoteWrite
import thread.web.presentation.notes.notePlace
import thread.web.presentation.tasks.focusById
import thread.web.presentation.topics.HexSize
import thread.web.presentation.topics.PlainTopicTag
import thread.web.presentation.topics.TopicHex
import thread.web.presentation.topics.topicPlace
import thread.web.presentation.whenText

/** The Trash view keeps the shown entry in `#/trash?id=`, like v3 `trash.html?id=`. */
internal const val TRASH_PARAM = "id"

internal fun trashPlace(id: String): Place = View.Trash.place.with(TRASH_PARAM, id)

/**
 * Trash (v3 trash.html, D42, D43, adr/ADR-012-trash-and-search.md): deleted Topics, Notes, Tasks and Reminders, newest
 * first, with what they were linked to. Restore puts an item back with its links; Delete forever asks first. On wide
 * screens the first entry shows when none is picked; on narrow screens the list and the entry show one at a time.
 */
@Composable
internal fun TrashScreen(
    workspace: WorkspaceModel,
    router: Router,
    services: NoteServices,
    accountId: AccountId,
    openId: String?,
    onOpenTask: (Task) -> Unit,
    onOpenTarget: (ReminderTarget) -> Unit,
    externalLabel: (ExternalReference) -> String,
) {
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    var wide by remember { mutableStateOf(mediaMatches(WIDE_QUERY)) }
    DisposableEffect(Unit) {
        val stop = observeMedia(WIDE_QUERY) { wide = it }
        onDispose { stop() }
    }
    var previousOpen by remember { mutableStateOf(openId) }
    LaunchedEffect(openId) {
        val was = previousOpen
        previousOpen = openId
        if (was == openId) return@LaunchedEffect
        if (openId != null) {
            if (!wide) window.scrollTo(0.0, 0.0)
            focusById(TITLE_ID)
        } else if (was != null) {
            focusById(rowId(was))
        }
    }
    var purging by remember { mutableStateOf<TrashEntry?>(null) }

    PageHeader("Trash", "Deleted Topics, notes, tasks and reminders. Restore puts them back with their links.")
    if (!workspace.loaded) {
        if (workspace.readFailed) EmptyState(Icon.Alert, "Trash could not load", "Reload the page to try again.")
        return
    }
    val entries = workspace.trash
    if (entries.isEmpty()) {
        EmptyState(
            Icon.Trash,
            "Trash is empty",
            "Deleted Topics, notes, tasks and reminders wait here until you restore them or delete them forever.",
        )
        return
    }
    val picked = entries.firstOrNull { it.id == openId }
    val shown = picked ?: if (wide) entries.first() else null

    /** Focuses the row that takes the place of [gone], or the page when Trash is now empty (v3). */
    fun focusAfterRemoving(gone: TrashEntry) {
        val index = entries.indexOf(gone)
        val rest = entries - gone
        val next = rest.getOrNull(minOf(index, rest.lastIndex))
        scope.launch { focusById(next?.let { rowId(it.id) } ?: "main") }
    }

    fun restore(entry: TrashEntry) {
        val open: () -> Unit
        when (entry) {
            is TrashEntry.NoteEntry -> {
                val noteId = entry.note.id
                launchNoteWrite(toasts, "“${entry.title}” could not be restored. Try again.") {
                    services.syncRemote(accountId).setInTrash(noteId, false)
                }
                open = { router.go(notePlace(noteId)) }
            }
            else -> {
                workspace.restore(entry) ?: return
                open =
                    when (entry) {
                        is TrashEntry.TopicEntry -> ({ router.go(topicPlace(entry.topic)) })
                        is TrashEntry.TaskEntry -> ({ workspace.task(entry.id)?.let(onOpenTask) })
                        is TrashEntry.ReminderEntry -> ({ onOpenTarget(entry.reminder.target) })
                        is TrashEntry.NoteEntry -> ({})
                    }
            }
        }
        if (openId != null) router.replace(View.Trash.place)
        toasts.show("Restored: ${entry.title}", open = open)
        focusAfterRemoving(entry)
    }

    Div({
        classes("md")
        if (picked != null) classes("has-open")
    }) {
        Div({ classes("md__list") }) {
            Ul({ classes("md__scroll") }) {
                entries.forEach { entry -> key(entry.id) { TrashRow(entry, entry.id == shown?.id, workspace.prefs.timeZone) } }
            }
            P({ classes("callout") }) {
                IconView(Icon.Lock, 15)
                Text(" Jira tickets and pull requests never go to Trash. Unlinking them only removes the link.")
            }
        }
        Div({ classes("md__detail") }) {
            if (shown != null) {
                key(shown.id) {
                    TrashPreview(
                        entry = shown,
                        zone = workspace.prefs.timeZone,
                        noteBody = { noteId -> rememberRemoteNote(services, accountId, noteId) },
                        deviceIsThis = { it == services.deviceId.value },
                        externalLabel = externalLabel,
                        onBack = { router.go(View.Trash.place) },
                        onRestore = { restore(shown) },
                        onDeleteForever = { purging = shown },
                    )
                }
            }
        }
    }

    purging?.let { entry ->
        ConfirmDialog(
            title = "Delete forever?",
            body = purgeText(entry, workspace),
            confirmLabel = "Delete forever",
            danger = true,
        ) { ok ->
            purging = null
            if (!ok) return@ConfirmDialog
            workspace.deleteForever(entry)
            if (entry is TrashEntry.NoteEntry) {
                // Its saved versions are deleted by the purged-notes sweep (App); this device's draft goes now.
                launchNoteWrite(toasts, "This device's copy of “${entry.title}” could not be removed.") {
                    services.draftStore(accountId).delete(entry.note.id)
                }
            }
            if (openId != null) router.replace(View.Trash.place)
            toasts.show("Deleted forever")
            scope.launch { focusById("main") }
        }
    }
}

@Composable
private fun TrashRow(
    entry: TrashEntry,
    current: Boolean,
    zone: String,
) {
    val now = LocalNow.current
    Li {
        A(trashPlace(entry.id).hash, {
            classes("xi")
            id(rowId(entry.id))
            if (current) attr("aria-current", "true")
        }) {
            Span({ classes("xi__top") }) {
                IconView(kindIcon(entry.kind), 14)
                Span { Text(kindLabel(entry.kind)) }
                Span { Text("Deleted ${deletedText(entry, zone, now)}") }
            }
            Span({ classes("xi__title") }) { Text(entry.title) }
            Span({ classes("xi__meta") }) {
                when (entry) {
                    is TrashEntry.TopicEntry -> {
                        TopicHex(entry.topic.name, entry.topic.color, HexSize.Xs)
                        Span { Text(bundlePhrase(entry)) }
                    }
                    is TrashEntry.ReminderEntry -> Span { Text("Was set for ${whenText(entry.reminder.at, now.day, mid = true)}") }
                    is TrashEntry.TaskEntry -> TopicsMeta(listOfNotNull(entry.topic))
                    is TrashEntry.NoteEntry -> {
                        TopicsMeta(entry.topics)
                        if (entry.note.fromConflict) Span { Text("Conflict copy") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicsMeta(links: List<TopicLink>) {
    val live = links.filter { it.state == TopicState.Live }.mapNotNull { it.topic }
    when {
        live.isNotEmpty() -> live.forEach { PlainTopicTag(it) }
        links.any { it.state == TopicState.InTrash } -> Span { Text("Its Topic is in Trash") }
        else -> Span { Text("No Topic") }
    }
}

/** The shown entry (v3 trash.js preview). */
@Composable
private fun TrashPreview(
    entry: TrashEntry,
    zone: String,
    noteBody: @Composable (NoteId) -> RemoteNote?,
    deviceIsThis: (String) -> Boolean,
    externalLabel: (ExternalReference) -> String,
    onBack: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    val now = LocalNow.current
    val links =
        when (entry) {
            is TrashEntry.TaskEntry -> listOfNotNull(entry.topic)
            is TrashEntry.NoteEntry -> entry.topics
            else -> emptyList()
        }
    val live = links.filter { it.state == TopicState.Live }.mapNotNull { it.topic }
    val parked = links.count { it.state == TopicState.InTrash }
    val lost = links.count { it.state == TopicState.Gone }
    Article({
        classes("tprev")
        attr("aria-labelledby", TITLE_ID)
    }) {
        A(View.Trash.place.hash, {
            classes("back", "md-back")
            onClick { event ->
                event.preventDefault()
                onBack()
            }
        }) {
            IconView(Icon.ChevLeft, 16)
            Text(" Trash")
        }
        P({ classes("tprev__kind") }) {
            IconView(kindIcon(entry.kind), 15)
            Text(" ${kindLabel(entry.kind)}, deleted ${deletedText(entry, zone, now)}")
        }
        H2({
            classes("tprev__title")
            id(TITLE_ID)
            tabIndex(-1)
        }) { Text(entry.title) }
        P({ classes("tprev__ctx") }) {
            if (entry is TrashEntry.TopicEntry) {
                TopicHex(entry.topic.name, entry.topic.color, HexSize.Sm)
                Span { Text(bundlePhrase(entry)) }
            } else if (live.isNotEmpty()) {
                Span { Text("Was linked to") }
                live.forEach { PlainTopicTag(it) }
            } else {
                Span { Text("Not linked to a Topic") }
            }
        }
        if (parked > 0) {
            Callout(Icon.Alert, parkedText(entry, parked))
        }
        if (lost > 0) {
            val what = if (lost == 1) "One of its Topics no longer exists" else "$lost of its Topics no longer exist"
            Callout(Icon.Alert, "$what, so that link cannot come back.")
        }
        when (entry) {
            is TrashEntry.TopicEntry -> TopicBody(entry, now, externalLabel)
            is TrashEntry.NoteEntry -> NoteBody(entry, noteBody(entry.note.id), deviceIsThis)
            is TrashEntry.TaskEntry -> TaskBody(entry, now)
            is TrashEntry.ReminderEntry -> ReminderBody(entry, now)
        }
        Div({ classes("tprev__acts") }) {
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--primary")
                onClick { onRestore() }
            }) { Text(restoreLabel(entry, links, live)) }
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text", "btn--danger-text")
                onClick { onDeleteForever() }
            }) {
                IconView(Icon.Trash, 16)
                Text(" Delete forever")
            }
        }
    }
}

@Composable
private fun Callout(
    icon: Icon,
    text: String,
) {
    P({ classes("callout") }) {
        IconView(icon, 15)
        Text(" $text")
    }
}

/** What a deleted Topic took with it, and the links Restore brings back (v3 topicBody). */
@Composable
private fun TopicBody(
    entry: TrashEntry.TopicEntry,
    now: WallTime,
    externalLabel: (ExternalReference) -> String,
) {
    Div({ classes("tprev__body") }) {
        if (entry.topic.goal.isNotEmpty()) P({ classes("tprev__goal") }) { Text(entry.topic.goal) }
        H3({ classes("tprev__h") }) { Text("Went to Trash with it") }
        if (entry.tasks.isEmpty() && entry.reminders.isEmpty()) {
            P({ classes("muted") }) { Text("No tasks or reminders.") }
        } else {
            Ul({ classes("tprev__list") }) {
                entry.tasks.forEach { Li { Text((if (it.done) "Done: " else "Task: ") + it.title) } }
                entry.reminders.forEach { Li { Text("Reminder ${whenText(it.at, now.day, mid = true)}: ${it.text}") } }
            }
        }
        H3({ classes("tprev__h") }) { Text("Links that come back on restore") }
        if (entry.notes.isEmpty() && entry.externals.isEmpty()) {
            P({ classes("muted") }) { Text("No links to notes, tickets or pull requests.") }
        } else {
            Ul({ classes("tprev__list") }) {
                entry.notes.forEach { Li { Text("Note: ${it.title.ifBlank { UNTITLED_NOTE }}") } }
                entry.externals.forEach { Li { Text(externalLabel(it)) } }
            }
        }
        Callout(Icon.Lock, "Notes stay in Notes. Jira tickets and pull requests are not changed.")
    }
}

/** A Note in Trash, read-only: its current version, or its listing preview until that has loaded. */
@Composable
private fun NoteBody(
    entry: TrashEntry.NoteEntry,
    remote: RemoteNote?,
    deviceIsThis: (String) -> Boolean,
) {
    val version = remote?.history?.leaves?.firstOrNull()
    if (entry.note.fromConflict) {
        val from =
            when {
                version == null -> ""
                deviceIsThis(version.deviceId.value) -> " It holds the version from this device."
                else -> " It holds the version from another device."
            }
        Callout(Icon.Compare, "Made when you resolved a conflict.$from")
    }
    Div({ classes("tprev__body") }) {
        Div({ classes("editor", "editor--ro") }) {
            if (version != null) {
                RichTextView(version.body)
            } else {
                P { Text(entry.note.preview.ifBlank { if (remote == null) "Loading…" else "Empty note" }) }
            }
        }
    }
}

@Composable
private fun TaskBody(
    entry: TrashEntry.TaskEntry,
    now: WallTime,
) {
    val task = entry.task
    DList({ classes("fields", "tprev__body") }) {
        Field("Planned day", task.plannedDay?.let { dayText(it, now.day) } ?: "None")
        Field("Deadline", task.deadline?.let { deadlineText(it, now.day) } ?: "None")
        Field("Done", if (task.done) "Yes" else "No")
    }
}

@Composable
private fun ReminderBody(
    entry: TrashEntry.ReminderEntry,
    now: WallTime,
) {
    val target = entry.target
    val label =
        target.label?.let {
            when (target.kind) {
                TargetKind.Topic -> "Topic: $it"
                TargetKind.Task -> "Task: $it"
                TargetKind.Note -> "Note: $it"
            }
        }
    if (label != null && target.inTrash) {
        Callout(Icon.Alert, "What it is for is in Trash. The reminder shows again once that is restored too.")
    }
    DList({ classes("fields", "tprev__body") }) {
        Field("Time", whenText(entry.reminder.at, now.day))
        Field("For", label ?: "An item that no longer exists")
    }
}

@Composable
private fun Field(
    term: String,
    value: String,
) {
    DTerm { Text(term) }
    DDescription { Text(value) }
}

/** A Note's current version from its note sync, read-only. Null until the first answer, or when it cannot load. */
@Composable
private fun rememberRemoteNote(
    services: NoteServices,
    accountId: AccountId,
    noteId: NoteId,
): RemoteNote? {
    val state =
        produceState<RemoteNote?>(null, noteId) {
            try {
                services.syncRemote(accountId).observe(noteId).collect { value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The preview from the listing stays.
            }
        }
    return state.value
}

/** "With 2 tasks and 1 reminder" (v3 bundlePhrase). */
private fun bundlePhrase(entry: TrashEntry.TopicEntry): String {
    val parts = bundleParts(entry.tasks.size, entry.reminders.size)
    return if (parts.isEmpty()) "No tasks or reminders" else "With ${parts.joinToString(" and ")}"
}

private fun bundleParts(
    tasks: Int,
    reminders: Int,
): List<String> = listOfNotNull(count(tasks, "task", "tasks"), count(reminders, "reminder", "reminders"))

private fun count(
    n: Int,
    one: String,
    many: String,
): String? = if (n == 0) null else "$n ${if (n == 1) one else many}"

private fun restoreLabel(
    entry: TrashEntry,
    links: List<TopicLink>,
    live: List<Topic>,
): String =
    when {
        entry is TrashEntry.TopicEntry -> "Restore Topic"
        entry is TrashEntry.ReminderEntry -> "Restore reminder"
        live.isEmpty() -> if (links.isNotEmpty()) "Restore as standalone" else "Restore"
        live.size == 1 -> "Restore to ${live.single().name}"
        else -> "Restore to its ${live.size} Topics"
    }

/**
 * A Task whose Topic is in Trash comes back standalone, as in v3. A Note keeps its links, so the link to a Topic in
 * Trash comes back with that Topic.
 */
private fun parkedText(
    entry: TrashEntry,
    parked: Int,
): String {
    val topics = if (parked == 1) "Its Topic is" else "$parked of its Topics are"
    val them = if (parked == 1) "that Topic" else "them"
    return if (entry is TrashEntry.NoteEntry) {
        "$topics in Trash. The link comes back when you restore $them."
    } else {
        "$topics in Trash. Restore $them first to keep the link."
    }
}

/** The Delete forever question (v3 trash-purge). A Task or Note also takes the reminders on it. */
private fun purgeText(
    entry: TrashEntry,
    workspace: WorkspaceModel,
): String {
    val title = "“${entry.title}”"
    return when (entry) {
        is TrashEntry.TopicEntry -> {
            val parts = bundleParts(entry.tasks.size, entry.reminders.size)
            val with = if (parts.isEmpty()) "" else " and its ${parts.joinToString(" and ")}"
            "$title$with cannot be restored after this. Notes, tickets and pull requests are not affected."
        }
        is TrashEntry.ReminderEntry -> "$title cannot be restored after this."
        else -> {
            val reminders = workspace.remindersGoingWith(entry)
            val with = count(reminders, "reminder", "reminders")?.let { " and its $it" }.orEmpty()
            "$title$with cannot be restored after this."
        }
    }
}

private fun deletedText(
    entry: TrashEntry,
    zone: String,
    now: WallTime,
): String = entry.deletedAtMillis?.let { whenText(WallClock.wallTimeAt(it.toDouble(), zone), now.day, mid = true) } ?: "just now"

private fun kindIcon(kind: TrashKind): Icon =
    when (kind) {
        TrashKind.Topic -> Icon.Topics
        TrashKind.Note -> Icon.Notes
        TrashKind.Task -> Icon.Task
        TrashKind.Reminder -> Icon.Bell
    }

private fun kindLabel(kind: TrashKind): String =
    when (kind) {
        TrashKind.Topic -> "Topic"
        TrashKind.Note -> "Note"
        TrashKind.Task -> "Task"
        TrashKind.Reminder -> "Reminder"
    }

private fun rowId(id: String) = "tr-$id"

private const val TITLE_ID = "tp-title"
private const val WIDE_QUERY = "(min-width: 1000px)"
