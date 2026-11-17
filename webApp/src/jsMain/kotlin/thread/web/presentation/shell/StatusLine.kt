package thread.web.presentation.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import thread.domain.workspace.CaptureKind
import thread.web.platform.mediaMatches
import thread.web.platform.observeMedia
import thread.web.platform.observeSize
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.Kbd
import thread.web.presentation.SelectBox
import thread.web.presentation.SyncText
import thread.web.presentation.View

/** What quick capture makes (D35). */
internal enum class CaptureType(
    val id: String,
    val label: String,
    val kind: CaptureKind,
) {
    Task("task", "Task", CaptureKind.Task),
    Note("note", "Note", CaptureKind.Note),
    Topic("topic", "Topic", CaptureKind.Topic),
    ;

    companion object {
        fun of(kind: CaptureKind): CaptureType = entries.first { it.kind == kind }
    }
}

/** A Topic quick capture can file into. */
internal class CaptureDestination(
    val id: String,
    val name: String,
)

/** Quick capture's state, kept by the app so the C shortcut and Settings can change it. */
internal class CaptureState(
    initialType: CaptureType,
) {
    var type by mutableStateOf(initialType)
    var title by mutableStateOf("")

    /** The picked Topic id, or null for Inbox. Null until the person picks one. */
    var destination by mutableStateOf<String?>(null)

    /**
     * The page the destination was picked on, or null when none was picked. A pick holds only on that page: each v3
     * page load starts again from the page's own destination.
     */
    var destinationPage by mutableStateOf<String?>(null)

    /** Counts requests to focus the capture field. */
    var focusRequests by mutableStateOf(0)
        private set

    /** Whether the latest focus request also flashes the line (v3 T.focusCapture), or only returns focus. */
    var flash = true
        private set

    fun requestFocus(
        type: CaptureType? = null,
        flash: Boolean = true,
    ) {
        if (type != null) this.type = type
        this.flash = flash
        focusRequests++
    }
}

/**
 * The bottom status line (D35, v3 shell.js buildLine): views, the narrow views button, quick capture, search,
 * [meetingTool] (the next meeting, D78), [focusTool] (the Focus timer, adr/ADR-019-focus-timer.md), sync status, Trash
 * and Settings.
 */
@Composable
internal fun StatusLine(
    current: View,
    inboxCount: Int,
    sync: SyncText,
    capture: CaptureState,
    destinations: List<CaptureDestination>,
    defaultDestination: String?,
    onCapture: (type: CaptureType, title: String, destination: String?) -> Unit,
    onOpenViews: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSync: () -> Unit,
    meetingTool: @Composable () -> Unit,
    focusTool: @Composable () -> Unit,
) {
    var lineElement by remember { mutableStateOf<HTMLElement?>(null) }
    var inputElement by remember { mutableStateOf<HTMLInputElement?>(null) }
    var showHint by remember { mutableStateOf(mediaMatches(CAPTURE_HINT_MEDIA)) }
    DisposableEffect(Unit) {
        val stop = observeMedia(CAPTURE_HINT_MEDIA) { showHint = it }
        onDispose { stop() }
    }
    LaunchedEffect(capture.focusRequests) {
        if (capture.focusRequests == 0) return@LaunchedEffect
        if (capture.flash) {
            lineElement?.let { line ->
                // Restart the flash animation, as v3 does.
                line.classList.remove("is-flash")
                line.offsetWidth
                line.classList.add("is-flash")
            }
        }
        inputElement?.focus()
    }
    val page = "${current.id}/${defaultDestination.orEmpty()}"
    val destination =
        (if (capture.destinationPage == page) capture.destination else defaultDestination)
            ?.takeIf { id -> destinations.any { it.id == id } }

    Div({
        classes("line")
        id("line")
        tabIndex(-1)
        attr("role", "region")
        attr("aria-label", "Workspace bar")
        ref { element ->
            lineElement = element
            // Toasts sit above the line, which grows while the narrow capture is open.
            val stopObserving =
                observeSize(element) {
                    document.documentElement
                        ?.asDynamic()
                        ?.style
                        ?.setProperty("--line-live", "${element.offsetHeight}px")
                }
            onDispose {
                stopObserving()
                lineElement = null
            }
        }
    }) {
        A(View.Today.place.hash, {
            classes("line__brand")
            attr("title", "Thread: Today")
            attr("aria-label", "Thread, go to Today")
        }) { BrandMark(22, 12) }
        Nav({
            classes("line__views")
            attr("aria-label", "Views")
        }) {
            View.entries.filter { it.inLine }.forEach { view ->
                A(view.place.hash, {
                    classes("lv")
                    attr("title", view.label)
                    if (view == current) attr("aria-current", "page")
                }) {
                    IconView(view.icon, 17)
                    Span({ classes("lv__label") }) { Text(view.label) }
                    if (view == View.Inbox) InboxCount(inboxCount, "inbox-count")
                }
            }
        }
        Button({
            type(ButtonType.Button)
            classes("lv", "lv--current")
            attr("aria-haspopup", "dialog")
            attr("aria-label", current.label + ", all views" + if (inboxCount > 0) ", $inboxCount in Inbox" else "")
            onClick { onOpenViews() }
        }) {
            IconView(current.icon, 17)
            val short = current.short
            if (short != null) {
                Span({ attr("aria-hidden", "true") }) { Text(short) }
                Span({ classes("vh") }) { Text(current.label) }
            } else {
                Span { Text(current.label) }
            }
            IconView(Icon.ChevDown, 15)
            InboxCount(inboxCount, "inbox-count-m")
        }
        Form(attrs = {
            classes("cap")
            attr("autocomplete", "off")
            onSubmit { event ->
                event.preventDefault()
                onCapture(capture.type, capture.title.trim(), destination)
            }
        }) {
            Label("cap-type", { classes("vh") }) { Text("Capture type") }
            SelectBox(
                value = capture.type.id,
                options = CaptureType.entries.map { it.id to it.label },
                id = "cap-type",
                classes = listOf("cap__type"),
            ) { picked -> CaptureType.entries.firstOrNull { it.id == picked }?.let { capture.type = it } }
            Label("cap-title", { classes("vh") }) { Text("Capture title") }
            TextInput(capture.title) {
                classes("cap__input")
                id("cap-title")
                attr("name", "title")
                attr("enterkeyhint", "done")
                placeholder(capturePlaceholder(capture.type, showHint))
                ref {
                    inputElement = it
                    onDispose { inputElement = null }
                }
                onInput { capture.title = it.value }
            }
            Span({ classes("cap__dest") }) {
                Span({
                    classes("cap__arrow")
                    attr("aria-hidden", "true")
                }) { Text("→") }
                Label("cap-dest", { classes("vh") }) { Text("Capture destination") }
                val isTopic = capture.type == CaptureType.Topic
                SelectBox(
                    value = destination ?: INBOX,
                    options = listOf(INBOX to "Inbox") + destinations.map { it.id to it.name },
                    id = "cap-dest",
                    classes = listOf("cap__destsel"),
                    hidden = isTopic,
                ) { picked ->
                    capture.destination = picked.takeIf { it != INBOX }
                    capture.destinationPage = page
                }
                Span({
                    classes("cap__fixed")
                    id("cap-fixed")
                    if (!isTopic) attr("hidden", "")
                }) { Text("Topics") }
            }
            Button({
                type(ButtonType.Submit)
                classes("cap__go")
                attr("aria-label", "Capture")
            }) { IconView(Icon.ArrowRight, 18) }
        }
        Div({ classes("line__tools") }) {
            Button({
                type(ButtonType.Button)
                classes("tool", "tool--search")
                attr("aria-label", "Search (Command K)")
                attr("title", "Search (⌘K)")
                attr("aria-keyshortcuts", "Meta+K Control+K")
                onClick { onOpenSearch() }
            }) {
                IconView(Icon.Search, 18)
                Span({ classes("tool__label") }) { Text("Search") }
                Kbd("⌘K")
            }
            meetingTool()
            focusTool()
            Button({
                type(ButtonType.Button)
                classes("tool", "tool--sync", "tone-${sync.tone}")
                id("sync-chip")
                attr("aria-label", "Save and sync status: ${sync.text}")
                onClick { onOpenSync() }
            }) {
                IconView(sync.icon, 18)
                Span({ classes("tool__label") }) { Text(sync.text) }
            }
            ToolLink(View.Trash, current)
            ToolLink(View.Settings, current)
        }
    }
}

@Composable
private fun ToolLink(
    view: View,
    current: View,
) {
    A(view.place.hash, {
        classes("tool", "tool--icon")
        attr("aria-label", view.label)
        attr("title", view.label)
        if (view == current) attr("aria-current", "page")
    }) { IconView(view.icon, 18) }
}

@Composable
private fun InboxCount(
    count: Int,
    id: String,
) {
    Span({
        classes("lv__count")
        id(id)
        if (count == 0) attr("hidden", "")
    }) { if (count > 0) Text(count.toString()) }
}

/** The C shortcut hint only helps with a keyboard and a wide layout (v3). */
private const val CAPTURE_HINT_MEDIA = "(pointer: fine) and (min-width: 900px)"

private fun capturePlaceholder(
    type: CaptureType,
    showHint: Boolean,
): String = "Capture a ${type.id}" + if (showHint) "  (press C)" else ""

/** The Thread hex mark (v3 brandMark). Decorative. */
@Composable
internal fun BrandMark(
    size: Int,
    strokeWidth: Int,
) {
    Span({
        classes("icon")
        ref { element ->
            element.innerHTML =
                """<svg class="brand" viewBox="-12 -12 124 124" width="$size" height="$size" aria-hidden="true" focusable="false">""" +
                """<polygon points="50,4 89.8,27 89.8,73 50,96 10.2,73 10.2,27" fill="none" stroke="currentColor" """ +
                """stroke-width="$strokeWidth" stroke-linejoin="round"/>""" +
                """<polygon points="50,30 67,40 67,60 50,70 33,60 33,40" fill="currentColor"/></svg>"""
            onDispose { }
        }
    })
}

/** Focuses the status line itself, for the skip link. */
internal fun focusStatusLine() {
    (document.getElementById("line") as? HTMLElement)?.focus()
}

private const val INBOX = "inbox"
