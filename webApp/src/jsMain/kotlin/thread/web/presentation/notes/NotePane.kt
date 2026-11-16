package thread.web.presentation.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import thread.domain.account.AccountId
import thread.domain.note.ConflictChoice
import thread.domain.note.LocalDraft
import thread.domain.note.LocalDraftStore
import thread.domain.note.NoteId
import thread.domain.note.NoteSaveState
import thread.domain.note.NoteSummary
import thread.domain.note.RichTextDocument
import thread.domain.note.UNTITLED_NOTE
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.time.WallTime
import thread.domain.time.clockText
import thread.domain.topic.Topic
import thread.domain.workspace.setNoteTopics
import thread.web.platform.WallClock
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalCards
import thread.web.presentation.LocalNow
import thread.web.presentation.LocalToasts
import thread.web.presentation.Router
import thread.web.presentation.SyncCenter
import thread.web.presentation.SyncProblem
import thread.web.presentation.SyncReport
import thread.web.presentation.View
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.onPlainClick
import thread.web.presentation.scopedId
import thread.web.presentation.shortDate
import thread.web.presentation.tasks.ReminderDialog
import thread.web.presentation.topics.HexSize
import thread.web.presentation.topics.TopicHex
import thread.web.presentation.topics.TopicTag
import thread.web.presentation.topics.TopicsPickerDialog
import thread.web.presentation.topics.topicPlace

/** How a Note shows as a card on another screen (D70) instead of in the Notes tab. */
internal class NoteCardMode(
    val onClose: () -> Unit,
    /** Compare versions is open: the card widens for its two columns. */
    val onComparing: (Boolean) -> Unit,
)

/**
 * The open Note: its filing (Topics, Inbox) from the workspace listing, and its text from this device and the server
 * through [NoteSession]. [listing] is null for a Note made here a moment ago whose listing the store has not shown yet.
 * [card] is set when the Note shows as a card on another screen.
 *
 * [editing] is the Note's mode (D70): it opens for viewing, and Edit (E) or Done (Esc) call [onEditing]. In the Notes
 * tab the status bar holds Edit and Done; a card holds them in its band. [onCanEdit] reports whether the editor could
 * read the Note, so Edit can stay off when it could not.
 */
@Composable
internal fun NotePane(
    workspace: WorkspaceModel,
    router: Router,
    services: NoteServices,
    accountId: AccountId,
    noteId: NoteId,
    listing: NoteSummary?,
    syncCenter: SyncCenter,
    onTitle: (String) -> Unit,
    onState: (NoteSaveState?) -> Unit,
    editing: Boolean,
    onEditing: (Boolean) -> Unit,
    onCanEdit: (Boolean) -> Unit,
    card: NoteCardMode? = null,
) {
    val toasts = LocalToasts.current
    val now = LocalNow.current
    val cards = LocalCards.current
    var comparing by remember { mutableStateOf(false) }
    var reminding by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(listing?.title.orEmpty()) }
    val shownTitle = title.ifBlank { UNTITLED_NOTE }
    val topics = workspace.topicsOf(noteId)
    // A card reports under its own name, so it never clears the Notes tab's report.
    val syncSource = if (card != null) NOTE_CARD_SYNC_SOURCE else NOTE_SYNC_SOURCE
    // From a card, another Note opens as a card too; the Notes tab opens it in the tab.
    val openNote: (NoteId) -> Unit = { id -> if (card != null && cards != null) cards.openNote(id) else router.go(notePlace(id)) }

    DisposableEffect(noteId) {
        onDispose {
            onState(null)
            syncCenter.report(syncSource, null)
        }
    }

    if (!comparing && card == null) BackToNotes()
    NoteLoader(services, accountId, noteId) { store, ready ->
        NoteSession(
            services = services,
            accountId = accountId,
            store = store,
            ready = ready,
            toasts = toasts,
            editing = editing,
            onEditing = if (card == null) onEditing else null,
            onCanEdit = onCanEdit,
            onCompareShown = {
                comparing = it
                card?.onComparing?.invoke(it)
            },
            focusTitle = workspace.isMadeHere(noteId),
            onTitleChange = {
                title = it
                onTitle(it)
            },
            onSaveState = { state ->
                onState(state)
                syncCenter.report(syncSource, SyncReport(listOf(state), listOfNotNull(noteProblem(noteId, shownTitle, state))))
            },
            onReminder = { reminding = true },
            onTrashed = { trashedTitle ->
                if (card != null) card.onClose() else router.replace(View.Notes.place)
                toasts.show(
                    "Moved to Trash: ${trashedTitle.ifBlank { UNTITLED_NOTE }}",
                    undo = {
                        launchNoteWrite(toasts, "Restoring the note was not saved. The server refused the change.") {
                            services.syncRemote(accountId).setInTrash(noteId, false)
                        }
                        openNote(noteId)
                    },
                )
            },
            onResolved = { choice, copyId ->
                workspace.copyLinks(noteId, copyId)
                when (choice) {
                    ConflictChoice.KeepBoth ->
                        toasts.show(
                            "Kept both. The other device's copy is a second note.",
                            open = { openNote(copyId) },
                            openLabel = "Open copy",
                        )
                    ConflictChoice.KeepThisDevice ->
                        toasts.show(
                            "Kept this device's version. The other one is in Trash.",
                            open = { router.go(View.Trash.place) },
                            openLabel = "Open Trash",
                        )
                    ConflictChoice.KeepOther ->
                        toasts.show(
                            "Kept the other device's version. Yours is in Trash.",
                            open = { router.go(View.Trash.place) },
                            openLabel = "Open Trash",
                        )
                }
            },
            filing = { if (listing != null) NoteFiling(listing, topics) { picking = true } },
            syncedAt = listing?.updatedAtMillis?.let { changedAtText(it, workspace.prefs.timeZone, now) },
        )
    }

    if (reminding) {
        ReminderDialog(workspace, ReminderTarget(TargetKind.Note, noteId.value), title.ifBlank { "Note" }) { reminding = false }
    }
    if (picking && listing != null) {
        val before = topics.map { it.id }
        TopicsPickerDialog(
            workspace = workspace,
            title = "Link note to Topics",
            hint = "A note can belong to several Topics. Unlinking never deletes the note.",
            confirmLabel = "Save links",
            selected = before,
            onPick = { picked ->
                picking = false
                val current = workspace.storedNotes.firstOrNull { it.id == noteId } ?: listing
                val edit = workspace.apply(setNoteTopics(current, workspace.noteLinks, picked)) ?: return@TopicsPickerDialog
                val names = picked.mapNotNull { workspace.topic(it.value)?.name }
                toasts.show(
                    if (names.isEmpty()) "Note is now standalone" else "Linked to ${topicsPhrase(names)}",
                    undo = { workspace.undo(edit) },
                )
            },
            onCancel = { picking = false },
        )
    }
}

/** The v3 page stickers (up to three hexes) and the "Linked to" line with its Change Topics button. */
@Composable
private fun NoteFiling(
    listing: NoteSummary,
    topics: List<Topic>,
    onChange: () -> Unit,
) {
    val cards = LocalCards.current
    if (topics.isNotEmpty()) {
        Div({
            classes("page__stickers")
            attr("aria-hidden", "true")
        }) {
            topics.take(MAX_STICKERS).forEach { topic ->
                A(topicPlace(topic).hash, {
                    tabIndex(-1)
                    attr("title", topic.name)
                    if (cards != null) onPlainClick { cards.openTopic(topic.id) }
                }) { TopicHex(topic.name, topic.color, HexSize.Md) }
            }
        }
    }
    val linkId = scopedId("note-link")
    Div({ classes("page__links") }) {
        if (topics.isNotEmpty()) {
            Span { Text("Linked to") }
            topics.forEach { TopicTag(it) }
        } else {
            TopicTag(null, noneLabel = if (listing.inbox) "In Inbox, no Topic" else "Standalone")
        }
        Button({
            classes("btn", "btn--text", "btn--sm")
            type(ButtonType.Button)
            id(linkId)
            onClick { onChange() }
        }) {
            IconView(Icon.Link, 15)
            Text(if (topics.isNotEmpty()) " Change Topics" else " Link to Topic")
        }
    }
}

/** Loads the Note's draft from this device, then opens [content]. A Note new to this device starts empty. */
@Composable
private fun NoteLoader(
    services: NoteServices,
    accountId: AccountId,
    noteId: NoteId,
    content: @Composable (store: LocalDraftStore, ready: NoteLoad.Ready) -> Unit,
) {
    val store = remember(accountId) { services.draftStore(accountId) }
    var attempt by remember { mutableStateOf(0) }
    var load by remember { mutableStateOf<NoteLoad>(NoteLoad.Loading) }
    LaunchedEffect(attempt) {
        load = NoteLoad.Loading
        load =
            try {
                val stored = store.read(noteId)
                if (stored != null) {
                    NoteLoad.Ready(stored, isStored = true)
                } else {
                    NoteLoad.Ready(LocalDraft(noteId, "", RichTextDocument.EMPTY), isStored = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                NoteLoad.Failed(e.message ?: "unknown error")
            }
    }
    when (val current = load) {
        NoteLoad.Loading -> P({ classes("pagebar", "muted") }) { Text("Opening the note…") }
        is NoteLoad.Failed ->
            Banner(
                tone = BannerTone.Bad,
                icon = Icon.Alert,
                title = "Could not open this note on this device",
                text = "Local storage did not answer (${current.reason}). Nothing was changed.",
            ) {
                BannerButton(Icon.Refresh, "Try again") { attempt++ }
            }
        is NoteLoad.Ready -> key(attempt) { content(store, current) }
    }
}

/** What the sync panel lists under "Needs attention" for the open Note. */
private fun noteProblem(
    noteId: NoteId,
    title: String,
    state: NoteSaveState,
): SyncProblem? {
    val text =
        when (state) {
            NoteSaveState.LocalFailed -> "Note “$title” is not saved on this device. Copy its text, then retry."
            NoteSaveState.SyncFailed -> "Note “$title” did not sync. It is saved on this device."
            NoteSaveState.Conflict -> "Note “$title” was changed on two devices. Choose the version to keep."
            else -> return null
        }
    return SyncProblem(text, state, notePlace(noteId))
}

/** "11:40" today, else "Wed 11 Nov 16:20" (v3 D.time on the note's last change). */
private fun changedAtText(
    millis: Long,
    zone: String,
    now: WallTime,
): String {
    val at = WallClock.wallTimeAt(millis.toDouble(), zone)
    val time = clockText(at.minuteOfDay)
    return if (at.day == now.day) time else "${shortDate(at.day)} $time"
}

/** "Alpha", "Alpha and Beta", or "3 Topics" (v3 T.topicsPhrase). */
internal fun topicsPhrase(names: List<String>): String =
    when (names.size) {
        1 -> names[0]
        2 -> "${names[0]} and ${names[1]}"
        else -> "${names.size} Topics"
    }

private const val MAX_STICKERS = 3
