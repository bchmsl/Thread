package thread.web.presentation.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Aside
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.HTMLElement
import thread.domain.account.AccountId
import thread.domain.note.NoteFilter
import thread.domain.note.NoteId
import thread.domain.note.NoteSaveState
import thread.domain.note.NoteSummary
import thread.domain.note.UNTITLED_NOTE
import thread.domain.note.filterNotes
import thread.web.platform.WallClock
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalNow
import thread.web.presentation.LocalToasts
import thread.web.presentation.Place
import thread.web.presentation.Router
import thread.web.presentation.SyncCenter
import thread.web.presentation.ToastState
import thread.web.presentation.View
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.agoText
import thread.web.presentation.topics.HexSize
import thread.web.presentation.topics.TopicHex
import thread.web.presentation.topics.TopicHexEmpty

/** The Notes view keeps the open Note in `#/notes?id=`, like v3 `notes.html?id=`. */
internal const val NOTE_PARAM = "id"

internal fun notePlace(id: NoteId): Place = View.Notes.place.with(NOTE_PARAM, id.value)

/**
 * Writes that must finish even when the screen that started them is gone, such as the Undo of "Moved to Trash".
 * Firestore queues each write on this device first, so it is never lost; this scope only waits for the answer.
 */
private val noteWrites = MainScope()

/** Runs [write] and shows [failText] when the server refuses it or the device cannot store it. */
internal fun launchNoteWrite(
    toasts: ToastState,
    failText: String,
    write: suspend () -> Unit,
) {
    noteWrites.launch {
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            toasts.show(failText)
        }
    }
}

/**
 * Notes (v3 notes.html): the index on the left, the open Note as a page on the right. On narrow screens only one of
 * the two shows. The listing comes from the workspace; the open Note's text syncs through its own NoteSyncer.
 */
@Composable
internal fun NotesScreen(
    workspace: WorkspaceModel,
    router: Router,
    services: NoteServices,
    accountId: AccountId,
    openId: String?,
    syncCenter: SyncCenter,
) {
    val toasts = LocalToasts.current
    var filter by remember { mutableStateOf(NoteFilter.All) }
    var query by remember { mutableStateOf("") }
    // What the open Note shows before its listing catches up: the title as typed, and its save state.
    var openTitle by remember { mutableStateOf<Pair<String, String>?>(null) }
    var openState by remember { mutableStateOf<Pair<String, NoteSaveState>?>(null) }
    var previousOpen by remember { mutableStateOf(openId) }
    LaunchedEffect(openId) {
        val was = previousOpen
        previousOpen = openId
        if (was == openId) return@LaunchedEffect
        if (openId != null) {
            (document.getElementById("npane") as? HTMLElement)?.focus()
            if (window.innerWidth < NARROW_WIDTH) window.scrollTo(0.0, 0.0)
        } else if (was != null) {
            (document.getElementById(rowId(was)) as? HTMLElement)?.focus()
        }
    }

    fun newNote() {
        val note = workspace.createNote(null)
        if (note == null) {
            toasts.show("Your workspace is still loading. Try again in a moment.")
            return
        }
        filter = NoteFilter.All
        query = ""
        router.go(notePlace(note.id))
    }

    Div({
        classes("notes")
        if (openId != null) classes("has-open")
    }) {
        Aside({
            classes("nindex")
            attr("aria-label", "All notes")
        }) {
            Div({ classes("nindex__top") }) {
                H1({
                    classes("nindex__title")
                    tabIndex(-1)
                }) { Text("Notes") }
                Button({
                    classes("btn", "btn--primary")
                    type(ButtonType.Button)
                    id("note-new")
                    onClick { newNote() }
                }) { Text("New note") }
            }
            Label("nfilter", { classes("vh") }) { Text("Filter notes") }
            Input(InputType.Search) {
                classes("input")
                id("nfilter")
                placeholder("Filter by title or text")
                attr("autocomplete", "off")
                value(query)
                onInput { query = it.value }
            }
            Div({
                classes("nindex__filters")
                attr("role", "group")
                attr("aria-label", "Show")
            }) {
                FilterChip("All", filter == NoteFilter.All) { filter = NoteFilter.All }
                FilterChip("In a Topic", filter == NoteFilter.InTopic) { filter = NoteFilter.InTopic }
                FilterChip("Standalone", filter == NoteFilter.Standalone) { filter = NoteFilter.Standalone }
            }
            Ul({
                classes("nindex__list")
                id("nlist")
            }) {
                if (workspace.loaded) {
                    NoteRows(workspace, filter, query, openId, openTitle, openState)
                }
            }
        }
        Section({
            classes("npane")
            id("npane")
            tabIndex(-1)
            attr("aria-label", "Note editor")
        }) {
            val noteId = openId?.let(::NoteId)
            val listing = noteId?.let { id -> workspace.storedNotes.firstOrNull { it.id == id } }?.takeUnless { it.discarded }
            when {
                !workspace.loaded ->
                    if (workspace.readFailed) EmptyState(Icon.Alert, "Notes could not load", "Reload the page to try again.")
                noteId == null ->
                    if (workspace.notes.isEmpty()) {
                        EmptyState(Icon.Notes, "No notes yet", "Notes are rich text pages. Link them to Topics or keep them standalone.")
                    } else {
                        EmptyState(Icon.Notes, "Pick a note", "Choose one from the list, or start a new one.")
                    }
                listing == null && !workspace.isMadeHere(noteId) -> {
                    BackToNotes()
                    EmptyState(Icon.Notes, "Note not found", "It may have been moved to Trash.") {
                        A(View.Trash.place.hash, { classes("btn", "btn--line") }) { Text("Open Trash") }
                    }
                }
                else ->
                    key(noteId.value) {
                        NotePane(
                            workspace = workspace,
                            router = router,
                            services = services,
                            accountId = accountId,
                            noteId = noteId,
                            listing = listing,
                            syncCenter = syncCenter,
                            onTitle = { openTitle = noteId.value to it },
                            onState = { state -> openState = state?.let { noteId.value to it } },
                        )
                    }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { syncCenter.report(NOTE_SYNC_SOURCE, null) } }
}

@Composable
private fun FilterChip(
    label: String,
    pressed: Boolean,
    onClick: () -> Unit,
) {
    Button({
        classes("chip")
        type(ButtonType.Button)
        attr("aria-pressed", pressed.toString())
        onClick { onClick() }
    }) { Text(label) }
}

@Composable
private fun NoteRows(
    workspace: WorkspaceModel,
    filter: NoteFilter,
    query: String,
    openId: String?,
    openTitle: Pair<String, String>?,
    openState: Pair<String, NoteSaveState>?,
) {
    val now = LocalNow.current
    val zone = workspace.prefs.timeZone
    val rows = filterNotes(workspace.notes, filter, query) { id -> workspace.topicsOf(id).map { it.id } }
    if (rows.isEmpty()) {
        Li({
            classes("muted")
            style { padding(12.px) }
        }) { Text(if (workspace.notes.isEmpty()) "No notes yet. Start one with New note." else "No notes match.") }
        return
    }
    rows.forEach { note ->
        val id = note.id.value
        val title = (openTitle?.takeIf { it.first == id }?.second ?: note.title).ifBlank { UNTITLED_NOTE }
        val flag = rowFlag(note, openState?.takeIf { it.first == id }?.second)
        val topics = workspace.topicsOf(note.id)
        val updated = note.updatedAtMillis?.let { WallClock.wallTimeAt(it.toDouble(), zone) } ?: now
        key(id) {
            Li {
                A(notePlace(note.id).hash, {
                    classes("nrow")
                    id(rowId(id))
                    if (id == openId) attr("aria-current", "page")
                }) {
                    Span({ classes("nrow__title") }) {
                        Span { Text(title) }
                        if (flag != null) {
                            Span({
                                classes("nrow__flag")
                                if (flag.warn) classes("tone-warn")
                                attr("title", flag.text)
                            }) {
                                IconView(if (flag.warn) Icon.CloudOff else Icon.Alert, 15)
                                Span({ classes("vh") }) { Text(flag.text) }
                            }
                        }
                    }
                    Span({ classes("nrow__snip") }) { Text(note.preview.ifBlank { "Empty note" }) }
                    Span({ classes("nrow__meta") }) {
                        if (topics.isNotEmpty()) {
                            topics.forEach { TopicHex(it.name, it.color, HexSize.Xs) }
                            Span({ classes("nrow__tps") }) { Text(topics.joinToString(", ") { it.name }) }
                        } else {
                            TopicHexEmpty(HexSize.Xs)
                            Span({ classes("nrow__tps") }) { Text(if (note.inbox) "In Inbox" else "Standalone") }
                        }
                        Span({ attr("aria-hidden", "true") }) { Text("·") }
                        Span({ classes("nrow__when") }) { Text(agoText(updated, now)) }
                    }
                }
            }
        }
    }
}

private class RowFlag(
    val text: String,
    val warn: Boolean,
)

/**
 * The open Note shows its own save state. Other Notes are flagged only when this device holds listing changes the
 * server has not acknowledged (their text state is known only while they are open).
 */
private fun rowFlag(
    note: NoteSummary,
    openState: NoteSaveState?,
): RowFlag? =
    when (openState) {
        NoteSaveState.LocalFailed -> RowFlag("Not saved on this device", warn = false)
        NoteSaveState.SyncFailed -> RowFlag("Sync failed", warn = false)
        NoteSaveState.Conflict -> RowFlag("Conflict with another device", warn = false)
        NoteSaveState.Offline -> RowFlag("Saved on this device, syncs when online", warn = true)
        null ->
            if (note.pendingWrites &&
                !window.navigator.onLine
            ) {
                RowFlag("Saved on this device, syncs when online", warn = true)
            } else {
                null
            }
        else -> null
    }

@Composable
internal fun BackToNotes() {
    A(View.Notes.place.hash, { classes("back", "nback") }) {
        IconView(Icon.ChevLeft, 16)
        Text(" All notes")
    }
}

private fun rowId(noteId: String): String = "nrow-$noteId"

/** The SyncCenter key of the open Note. */
internal const val NOTE_SYNC_SOURCE = "note"

/** Below this width the index and the page do not fit side by side (v3 app.css). */
private const val NARROW_WIDTH = 1000
