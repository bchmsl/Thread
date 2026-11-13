package thread.web.presentation.m1spike

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Article
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.CheckboxInput
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Small
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.KeyboardEvent
import thread.domain.note.DraftAutosaver
import thread.domain.note.LocalDraft
import thread.domain.note.LocalDraftStore
import thread.domain.note.LocalSaveStatus
import thread.web.platform.copyText
import thread.web.platform.editor.EditorFormatting
import thread.web.platform.editor.NoteEditor
import thread.web.platform.onPageLeave
import thread.web.platform.warnBeforeUnload
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.ToastState

private sealed interface SpikeLoad {
    data object Loading : SpikeLoad

    data class Ready(
        val draft: LocalDraft,
        val isStored: Boolean,
    ) : SpikeLoad

    data class Failed(
        val reason: String,
    ) : SpikeLoad
}

/**
 * The M1 spike route: one Note on the v3 dot-grid page, edited with [NoteEditor] and saved to [store] with
 * [DraftAutosaver]. Not a real screen: no Notes index, Topics or sync (adr/ADR-003-editor-and-richtext.md).
 */
@Composable
internal fun M1SpikeScreen(
    store: LocalDraftStore,
    testHooks: SpikeTestHooks,
    toasts: ToastState,
) {
    var attempt by remember { mutableStateOf(0) }
    var load by remember { mutableStateOf<SpikeLoad>(SpikeLoad.Loading) }
    LaunchedEffect(attempt) {
        load = SpikeLoad.Loading
        load =
            try {
                val stored = store.read(SPIKE_NOTE_ID)
                if (stored != null) SpikeLoad.Ready(stored, isStored = true) else SpikeLoad.Ready(syntheticSpikeNote(), isStored = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SpikeLoad.Failed(e.message ?: "unknown error")
            }
    }

    Section({ classes("npane", "spike") }) {
        Div({ classes("spike__head") }) {
            A("#/", { classes("back") }) {
                IconView(Icon.ChevLeft, 16)
                Text("Home")
            }
            H1({ classes("ph__title") }) { Text("M1 spike") }
            P({ classes("ph__sub") }) { Text("Editor and local drafts. One synthetic note, stored only in this browser.") }
        }
        when (val current = load) {
            SpikeLoad.Loading -> P({ classes("pagebar", "muted") }) { Text("Opening the note…") }
            is SpikeLoad.Failed -> LoadFailedBanner(current.reason) { attempt++ }
            is SpikeLoad.Ready -> key(attempt) { NoteSession(store, current, toasts) }
        }
        TestHookBar(testHooks)
    }
}

@Composable
private fun NoteSession(
    store: LocalDraftStore,
    ready: SpikeLoad.Ready,
    toasts: ToastState,
) {
    // Not tied to the composition: the last write, started when the route is left, must still finish.
    val autosaver =
        remember {
            DraftAutosaver(store, CoroutineScope(SupervisorJob() + Dispatchers.Main), ready.draft, initialIsStored = ready.isStored)
        }
    val status by autosaver.status.collectAsState()
    var title by remember { mutableStateOf(ready.draft.title) }
    var formatting by remember { mutableStateOf(EditorFormatting()) }
    var editor by remember { mutableStateOf<NoteEditor?>(null) }
    var editorFailure by remember { mutableStateOf<Throwable?>(null) }

    DisposableEffect(autosaver) {
        val removePageLeave = onPageLeave { autosaver.flush() }
        onDispose {
            removePageLeave()
            autosaver.flush()
        }
    }
    // Closing the tab would lose an edit this device refused to store, so the browser asks first.
    val needsAttention = status == LocalSaveStatus.NeedsAttention
    DisposableEffect(needsAttention) {
        val removeUnloadWarning = if (needsAttention) warnBeforeUnload() else null
        onDispose { removeUnloadWarning?.invoke() }
    }

    fun copyNoteText() {
        val body = editor?.text.orEmpty()
        val text = if (title.isBlank()) body else "$title\n\n$body"
        copyText(text) { copied -> toasts.show(if (copied) "Note text copied" else "Copy failed. Select the text and press ⌘C or Ctrl+C.") }
    }

    Div({ id("nbanners") }) {
        when {
            editorFailure != null -> EditorFailedBanner(onCopy = ::copyNoteText)
            status == LocalSaveStatus.NeedsAttention ->
                SaveFailedBanner(
                    onRetry = {
                        autosaver.retry()
                        toasts.show("Trying again")
                    },
                    onCopy = ::copyNoteText,
                )
        }
    }
    Div({ classes("pagebar") }) { SaveStatus(status, editorFailed = editorFailure != null) }
    Article({
        classes("page")
        attr("aria-label", "Note")
    }) {
        H2({ classes("vh") }) { Text("Note: ${title.ifBlank { "Untitled note" }}") }
        Label("note-title", { classes("vh") }) { Text("Note title") }
        TextArea(title) {
            classes("page__title")
            id("note-title")
            attr("rows", "1")
            attr("placeholder", "Untitled note")
            if (editorFailure != null) attr("readonly", "")
            onInput { event ->
                title = event.value
                autosaver.edit(autosaver.current.copy(title = event.value))
                fitHeight(event.target)
            }
            onKeyDown { event ->
                if (event.key == "Enter" && (event.nativeEvent as? KeyboardEvent)?.isComposing != true) {
                    event.preventDefault()
                    editor?.focus()
                }
            }
            ref { element ->
                fitHeight(element)
                onDispose { }
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
                        initial = ready.draft.body,
                        onChange = { body -> autosaver.edit(autosaver.current.copy(body = body)) },
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
    status: LocalSaveStatus,
    editorFailed: Boolean,
) {
    val (icon, text, small, tone) =
        when {
            editorFailed -> StatusView(Icon.Alert, "Needs attention", "Editing is off", "tone-bad")
            status == LocalSaveStatus.NeedsAttention -> StatusView(Icon.Alert, "Needs attention", "Not saved on this device", "tone-bad")
            status == LocalSaveStatus.Saving -> StatusView(Icon.Device, "Saving locally", null, "tone-busy")
            else -> StatusView(Icon.Device, "Saved locally", null, "tone-quiet")
        }
    P({
        classes("savest", tone)
        id("nsave")
        attr("role", "status")
        attr("aria-live", "polite")
        attr("data-status", if (editorFailed) "EditorFailed" else status.name)
    }) {
        IconView(icon, 16)
        Span { Text(text) }
        small?.let { Small { Text(it) } }
    }
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
        title = "This note cannot be edited here",
        text = "The editor could not read this note's content. Editing is off, so nothing is saved over it.",
    ) {
        BannerButton(Icon.Copy, "Copy text", onCopy)
    }
}

@Composable
private fun LoadFailedBanner(
    reason: String,
    onRetry: () -> Unit,
) {
    Banner(
        title = "Could not open this note on this device",
        text = "Local storage did not answer ($reason). Nothing was changed.",
    ) {
        BannerButton(Icon.Refresh, "Try again", onRetry)
    }
}

@Composable
private fun Banner(
    title: String,
    text: String,
    actions: @Composable () -> Unit,
) {
    Div({
        classes("nbanner", "nbanner--bad")
        attr("role", "alert")
    }) {
        IconView(Icon.Alert, 18)
        Div({ classes("nbanner__body") }) {
            P({ classes("nbanner__title") }) { Text(title) }
            P({ classes("nbanner__text") }) { Text(text) }
        }
        Div({ classes("nbanner__acts") }) { actions() }
    }
}

@Composable
private fun BannerButton(
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

/** Prototype-only control under the page, styled like the v3 demo bar. */
@Composable
private fun TestHookBar(testHooks: SpikeTestHooks) {
    Div({ classes("pagedemo") }) {
        Div({
            classes("demo")
            attr("role", "group")
            attr("aria-label", "Test hook: local saves")
        }) {
            Span({ classes("demo__tag") }) {
                IconView(Icon.Flask, 14)
                Text("Test hook")
            }
            Label(attrs = { classes("demo__switch") }) {
                CheckboxInput(testHooks.failLocalSaves) {
                    classes("switch")
                    id("fail-local-saves")
                    onInput { testHooks.failLocalSaves = it.value }
                }
                Text("Make local saves fail")
            }
        }
    }
}

private fun fitHeight(element: EventTarget?) {
    val textArea = element as? HTMLElement ?: return
    textArea.style.height = "auto"
    textArea.style.height = "${textArea.scrollHeight}px"
}
