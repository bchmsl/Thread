package thread.web.presentation.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Article
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Small
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import thread.domain.account.AccountId
import thread.domain.note.ConflictChoice
import thread.domain.note.DraftAutosaver
import thread.domain.note.LocalDraft
import thread.domain.note.LocalDraftStore
import thread.domain.note.LocalSaveStatus
import thread.domain.note.NoteId
import thread.domain.note.NoteSaveState
import thread.domain.note.NoteSyncState
import thread.domain.note.NoteSyncer
import thread.domain.note.noteSaveState
import thread.web.platform.copyText
import thread.web.platform.editor.EditorFormatting
import thread.web.platform.editor.NoteEditor
import thread.web.platform.isOnline
import thread.web.platform.onOnlineChange
import thread.web.platform.onPageLeave
import thread.web.platform.warnBeforeUnload
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.ToastState

/**
 * One open Note. Edits go to [DraftAutosaver] (this device) and, when [accountId] is set, to [NoteSyncer] (the
 * server). The status line and banners show only what the device or the server confirmed.
 */
@Composable
internal fun NoteSession(
    services: NoteServices,
    accountId: AccountId?,
    store: LocalDraftStore,
    ready: NoteLoad.Ready,
    toasts: ToastState,
    onCompareShown: (Boolean) -> Unit,
    focusTitle: Boolean = false,
    onTitleChange: (String) -> Unit = {},
    onSaveState: (NoteSaveState) -> Unit = {},
    onReminder: (() -> Unit)? = null,
    onTrashed: (title: String) -> Unit = { toasts.show("Moved to Trash: ${it.ifBlank { UNTITLED }}") },
    onResolved: (ConflictChoice, copyId: NoteId) -> Unit = { choice, _ -> toasts.show(resolvedText(choice)) },
    filing: @Composable () -> Unit = {},
    syncedAt: String? = null,
) {
    // Not tied to the composition: the last write, started when the note is closed, must still finish.
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val autosaver = remember { DraftAutosaver(store, scope, ready.draft, initialIsStored = ready.isStored) }
    var title by remember { mutableStateOf(ready.draft.title) }
    var titleElement by remember { mutableStateOf<HTMLElement?>(null) }
    var editor by remember { mutableStateOf<NoteEditor?>(null) }
    var formatting by remember { mutableStateOf(EditorFormatting()) }
    var editorFailure by remember { mutableStateOf<Throwable?>(null) }
    var comparing by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf(isOnline()) }
    val syncer =
        remember {
            accountId?.let { id ->
                NoteSyncer(
                    drafts = autosaver,
                    remote = services.syncRemote(id),
                    deviceId = services.deviceId,
                    scope = scope,
                    hasLocalWork = ready.isStored,
                    newRevisionId = services.newRevisionId,
                    onContentReplaced = { draft ->
                        title = draft.title
                        editor?.replaceContent(draft.body)
                    },
                )
            }
        }
    val status by autosaver.status.collectAsState()
    val sync: NoteSyncState? = syncer?.state?.collectAsState()?.value
    val saveState = noteSaveState(status, sync, online)
    SideEffect { onSaveState(saveState) }
    LaunchedEffect(title) { onTitleChange(title) }

    DisposableEffect(autosaver, syncer) {
        syncer?.start()
        val removePageLeave =
            onPageLeave {
                autosaver.flush()
                syncer?.flush()
            }
        val removeOnlineListener = onOnlineChange { online = it }
        onDispose {
            removePageLeave()
            removeOnlineListener()
            autosaver.flush()
            syncer?.flush()
            syncer?.stop()
        }
    }
    // Closing the tab would lose an edit this device refused to store, so the browser asks first.
    val needsAttention = status == LocalSaveStatus.NeedsAttention
    DisposableEffect(needsAttention) {
        val removeUnloadWarning = if (needsAttention) warnBeforeUnload() else null
        onDispose { removeUnloadWarning?.invoke() }
    }
    LaunchedEffect(title, titleElement) { titleElement?.let(::fitHeight) }
    // A Note made a moment ago opens with its title ready for typing (v3 openNote(id, focusTitle)).
    var titleFocused by remember { mutableStateOf(false) }
    LaunchedEffect(titleElement) {
        val element = titleElement ?: return@LaunchedEffect
        if (focusTitle && !titleFocused && title.isEmpty()) element.focus()
        titleFocused = true
    }
    val conflict = sync?.conflict
    LaunchedEffect(conflict == null) { if (conflict == null) comparing = false }
    val showCompare = comparing && conflict != null
    LaunchedEffect(showCompare) {
        onCompareShown(showCompare)
        if (showCompare) (document.getElementById("cmp-h") as? HTMLElement)?.focus()
    }
    DisposableEffect(Unit) { onDispose { onCompareShown(false) } }

    fun edited(draft: LocalDraft) {
        if (draft == autosaver.current) return
        autosaver.edit(draft)
        syncer?.localEdited()
    }

    fun copyNoteText() {
        val body = editor?.text.orEmpty()
        val text = if (title.isBlank()) body else "$title\n\n$body"
        copyText(text) { copied -> toasts.show(if (copied) "Note text copied" else "Copy failed. Select the text and press ⌘C or Ctrl+C.") }
    }

    fun keep(choice: ConflictChoice) {
        val copyId = syncer?.resolveConflict(choice)
        if (copyId == null) {
            toasts.show("This device's version is still on its way to the server. Try again in a moment.")
            return
        }
        comparing = false
        (document.getElementById("npane") as? HTMLElement)?.focus()
        onResolved(choice, copyId)
    }

    // Without a stored draft, the content on screen is only a placeholder. The server's version comes first, so
    // that typing into the placeholder never competes with it.
    val waitingForServer = syncer != null && !ready.isStored && sync?.loaded != true && sync?.rejected != true
    if (waitingForServer) {
        P({ classes("pagebar", "muted") }) { Text("Opening the note…") }
        return
    }
    if (showCompare && conflict != null) {
        ConflictCompare(autosaver.current, conflict, onBack = { comparing = false }, onKeep = ::keep)
        return
    }

    Div({ id("nbanners") }) {
        when {
            editorFailure != null -> EditorFailedBanner(onCopy = ::copyNoteText)
            saveState == NoteSaveState.LocalFailed ->
                SaveFailedBanner(
                    onRetry = {
                        autosaver.retry()
                        toasts.show("Trying again")
                    },
                    onCopy = ::copyNoteText,
                )
            saveState == NoteSaveState.Conflict -> ConflictBanner(onCompare = { comparing = true })
            saveState == NoteSaveState.SyncFailed && sync?.unreadable == true -> UnreadableBanner()
            saveState == NoteSaveState.SyncFailed ->
                SyncFailedBanner(
                    onRetry = {
                        syncer?.retry()
                        toasts.show("Trying again")
                    },
                )
            saveState == NoteSaveState.Offline -> OfflineBanner()
            saveState == NoteSaveState.LocalOnly -> LocalOnlyBanner()
        }
        if (sync?.inTrash == true) {
            TrashBanner(
                onRestore = {
                    syncer?.setInTrash(false)
                    toasts.show("Restored: ${title.ifBlank { UNTITLED }}")
                },
            )
        }
    }
    Div({ classes("pagebar") }) {
        SaveStatus(saveState, editorFailed = editorFailure != null, syncedAt = syncedAt)
        Span({ classes("pagebar__sp") })
        if (onReminder != null && sync?.inTrash != true) {
            Button({
                classes("btn", "btn--text", "btn--sm")
                id("note-rem")
                type(ButtonType.Button)
                onClick { onReminder() }
            }) {
                IconView(Icon.Bell, 15)
                Text("Reminder")
            }
        }
        if (syncer != null && sync?.inTrash != true) {
            Button({
                classes("btn", "btn--text", "btn--sm", "btn--danger-text")
                id("note-trash")
                type(ButtonType.Button)
                onClick {
                    syncer.setInTrash(true)
                    onTrashed(title)
                }
            }) {
                IconView(Icon.Trash, 15)
                Text("Move to Trash")
            }
        }
    }
    Article({
        classes("page")
        attr("aria-label", "Note")
    }) {
        filing()
        H2({ classes("vh") }) { Text("Note: ${title.ifBlank { UNTITLED }}") }
        Label("note-title", { classes("vh") }) { Text("Note title") }
        TextArea(title) {
            classes("page__title")
            id("note-title")
            attr("rows", "1")
            attr("placeholder", UNTITLED)
            if (editorFailure != null) attr("readonly", "")
            onInput { event ->
                title = event.value
                edited(autosaver.current.copy(title = event.value))
            }
            onKeyDown { event ->
                if (event.key == "Enter" && (event.nativeEvent as? KeyboardEvent)?.isComposing != true) {
                    event.preventDefault()
                    editor?.focus()
                }
            }
            ref { element ->
                titleElement = element
                onDispose { titleElement = null }
            }
        }
        NoteToolbar(
            formatting = formatting,
            editor = editor,
            enabled = editorFailure == null,
            onLinkDone = toasts::show,
        )
        Div({
            classes("editor-mount")
            ref { element ->
                val noteEditor =
                    NoteEditor(
                        mount = element,
                        // The newest content: the compare view can replace it while the editor is not shown.
                        initial = autosaver.current.body,
                        onChange = { body -> edited(autosaver.current.copy(body = body)) },
                        onFormatting = { formatting = it },
                        onFailure = { editorFailure = it },
                    )
                editor = noteEditor
                onDispose {
                    editor = null
                    noteEditor.destroy()
                }
            }
        })
    }
}

@Composable
private fun SaveStatus(
    state: NoteSaveState,
    editorFailed: Boolean,
    syncedAt: String?,
) {
    val view = if (editorFailed) StatusView(Icon.Alert, "Needs attention", "Editing is off", "tone-bad") else statusView(state, syncedAt)
    P({
        classes("savest", view.tone)
        id("nsave")
        attr("role", "status")
        attr("aria-live", "polite")
        attr("data-status", if (editorFailed) "EditorFailed" else state.name)
    }) {
        IconView(view.icon, 16)
        Span { Text(view.text) }
        view.small?.let { Small { Text(it) } }
    }
}

/** The phase plan's five labels, with the v3 notes page's detail line. [syncedAt] is when the server copy last changed. */
private fun statusView(
    state: NoteSaveState,
    syncedAt: String?,
): StatusView =
    when (state) {
        NoteSaveState.Saving -> StatusView(Icon.Device, "Saving locally", null, "tone-busy")
        NoteSaveState.LocalFailed -> StatusView(Icon.Alert, "Needs attention", "Not saved on this device", "tone-bad")
        NoteSaveState.LocalOnly -> StatusView(Icon.Device, "Saved locally", "Local only, no sync", "tone-warn")
        NoteSaveState.Offline -> StatusView(Icon.Device, "Saved locally", "Syncs when online", "tone-warn")
        NoteSaveState.Syncing -> StatusView(Icon.CloudUp, "Syncing", "Saved locally", "tone-busy")
        NoteSaveState.Synced ->
            StatusView(Icon.CloudCheck, "Synced", "On this device and the server" + syncedAt?.let { ", $it" }.orEmpty(), "tone-ok")
        NoteSaveState.SyncFailed -> StatusView(Icon.Alert, "Needs attention", "Saved locally, sync failed", "tone-bad")
        NoteSaveState.Conflict -> StatusView(Icon.Compare, "Needs attention", "Conflict with another device", "tone-bad")
    }

private data class StatusView(
    val icon: Icon,
    val text: String,
    val small: String?,
    val tone: String,
)

@Composable
private fun SaveFailedBanner(
    onRetry: () -> Unit,
    onCopy: () -> Unit,
) {
    Banner(
        tone = BannerTone.Bad,
        icon = Icon.Alert,
        title = "Not saved on this device",
        text = "This device refused to store the change. Your text is still on screen. Copy it, then retry.",
    ) {
        BannerButton(Icon.Refresh, "Retry save", onRetry)
        BannerButton(Icon.Copy, "Copy text", onCopy)
    }
}

@Composable
private fun EditorFailedBanner(onCopy: () -> Unit) {
    Banner(
        tone = BannerTone.Bad,
        icon = Icon.Alert,
        title = "This note cannot be edited here",
        text = "The editor could not read this note's content. Editing is off, so nothing is saved over it.",
    ) {
        BannerButton(Icon.Copy, "Copy text", onCopy)
    }
}

@Composable
private fun ConflictBanner(onCompare: () -> Unit) {
    Banner(
        tone = BannerTone.Bad,
        icon = Icon.Compare,
        title = "Changed on two devices",
        text = "This device and another device both edited this note. Both versions are kept until you choose.",
    ) {
        BannerButton(Icon.Compare, "Compare versions", onCompare)
    }
}

@Composable
private fun SyncFailedBanner(onRetry: () -> Unit) {
    Banner(
        tone = BannerTone.Bad,
        icon = Icon.Alert,
        title = "Sync failed",
        text = "Your change is saved on this device. The server did not accept it yet.",
    ) {
        BannerButton(Icon.Refresh, "Retry sync", onRetry)
    }
}

@Composable
private fun UnreadableBanner() {
    Banner(
        tone = BannerTone.Bad,
        icon = Icon.Alert,
        title = "Sync paused for this note",
        text =
            "The server has a version this app cannot read. Your text is saved on this device, and nothing is written " +
                "over that version.",
    )
}

@Composable
private fun OfflineBanner() {
    Banner(
        tone = BannerTone.Warn,
        icon = Icon.CloudOff,
        title = "Offline",
        text = "Edits save on this device and sync when you are back online.",
    )
}

@Composable
private fun LocalOnlyBanner() {
    Banner(
        tone = BannerTone.Warn,
        icon = Icon.Device,
        title = "Local only",
        text = "You are not signed in, so this note stays on this device.",
    )
}

@Composable
private fun TrashBanner(onRestore: () -> Unit) {
    Banner(
        tone = BannerTone.Info,
        icon = Icon.Trash,
        title = "This note is in Trash",
        text = "It keeps its text and versions. Restore it to bring it back.",
    ) {
        BannerButton(Icon.Restore, "Restore", onRestore)
    }
}

internal enum class BannerTone(
    val cssClass: String,
) {
    Bad("nbanner--bad"),
    Warn("nbanner--warn"),
    Info("nbanner--info"),
}

/** The v3 note banner. Problems are alerts; the other banners are polite status messages. */
@Composable
internal fun Banner(
    tone: BannerTone,
    icon: Icon,
    title: String,
    text: String,
    actions: (@Composable () -> Unit)? = null,
) {
    Div({
        classes("nbanner", tone.cssClass)
        attr("role", if (tone == BannerTone.Bad) "alert" else "status")
    }) {
        IconView(icon, 18)
        Div({ classes("nbanner__body") }) {
            P({ classes("nbanner__title") }) { Text(title) }
            P({ classes("nbanner__text") }) { Text(text) }
        }
        actions?.let { Div({ classes("nbanner__acts") }) { it() } }
    }
}

@Composable
internal fun BannerButton(
    icon: Icon,
    label: String,
    onClick: () -> Unit,
) {
    Button({
        classes("btn", "btn--line", "btn--sm")
        type(ButtonType.Button)
        onClick { onClick() }
    }) {
        IconView(icon, 15)
        Text(label)
    }
}

private fun resolvedText(choice: ConflictChoice): String =
    when (choice) {
        ConflictChoice.KeepThisDevice -> "Kept this device's version. The other one is in Trash."
        ConflictChoice.KeepOther -> "Kept the other device's version. Yours is in Trash."
        ConflictChoice.KeepBoth -> "Kept both. The other device's copy is a second note."
    }

private fun fitHeight(textArea: HTMLElement) {
    textArea.style.height = "auto"
    textArea.style.height = "${textArea.scrollHeight}px"
}
