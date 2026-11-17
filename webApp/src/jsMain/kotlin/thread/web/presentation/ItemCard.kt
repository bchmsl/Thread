package thread.web.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.Element
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import thread.domain.calendar.MeetingId
import thread.domain.external.JiraIssueKey
import thread.domain.external.PullRequestRef
import thread.domain.note.NoteId
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.task.TaskId
import thread.domain.topic.TopicId

/**
 * Opens items as cards on top of the page (D70, owner request #2). An item opened from its own tab opens there; from
 * anywhere else it opens as a card, and the page under it stays as it was. One card shows at a time: opening another
 * item from a card replaces it, and Back returns to it.
 */
internal class CardOpener(
    private val router: Router,
) {
    /** The card that opens for editing: a Task or Note made a moment ago. The card host clears it once it shows. */
    var editOnOpen: CardItem? by mutableStateOf(null)

    /** The card on screen, if any. */
    val shown: CardItem? get() = router.place.card

    fun open(
        item: CardItem,
        edit: Boolean = false,
    ) {
        val place = router.place
        if (place.card == item) return
        // No loops: when the card under this one is the item, go back to it instead of adding it again.
        if (router.previousCard == item) {
            router.back()
            return
        }
        // The page under the card already shows the item: show the page.
        if (cardShownByPage(place.withoutCards(), item)) {
            if (place.hasCard) router.closeCard()
            return
        }
        editOnOpen = item.takeIf { edit }
        router.openCard(place.withCard(item))
    }

    fun openTask(
        id: TaskId,
        edit: Boolean = false,
    ) = open(CardItem(CardType.Task, id.value), edit)

    fun openNote(
        id: NoteId,
        edit: Boolean = false,
    ) = open(CardItem(CardType.Note, id.value), edit)

    fun openJira(key: JiraIssueKey) = open(CardItem(CardType.Jira, key.value))

    fun openPr(ref: PullRequestRef) = open(CardItem(CardType.Pr, ref.id))

    fun openMeeting(id: MeetingId) = open(CardItem(CardType.Meeting, id.value))

    /** A Topic as a card, wherever it is opened from: Cmd+K (D70, the owner's extra answer). */
    fun openTopicCard(id: TopicId) = open(CardItem(CardType.Topic, id.value))

    /** A Topic link: its page in the Topics tab, its own tab; a card anywhere else (D70, owner answer Q1). */
    fun openTopic(id: TopicId) {
        if (topicOpensAsPage(router.place)) router.go(Place.of("topic", id.value)) else openTopicCard(id)
    }

    /** What a reminder is about. */
    fun openTarget(target: ReminderTarget) {
        when (target.kind) {
            TargetKind.Task -> openTask(TaskId(target.id))
            TargetKind.Note -> openNote(NoteId(target.id))
            TargetKind.Topic -> openTopic(TopicId(target.id))
        }
    }

    fun close() = router.closeCard()

    fun back() = router.back()
}

/** The workspace's card opener. Null outside a signed-in workspace, where links stay plain links. */
internal val LocalCards = staticCompositionLocalOf<CardOpener?> { null }

/**
 * A prefix for element ids inside a card. A card can show a Topic over another Topic's page, so without it the card
 * would repeat the page's ids, and labels and focus would find the page's elements instead of the card's.
 */
internal val LocalIdScope = staticCompositionLocalOf { "" }

/** [id] in the current id scope. */
@Composable
internal fun scopedId(id: String): String = LocalIdScope.current + id

/**
 * Runs [open] on a plain left click, instead of following the link. A click with a modifier key, a middle click and a
 * copied link still use the link's address, the item's own page.
 */
internal fun AttrsScope<HTMLAnchorElement>.onPlainClick(open: () -> Unit) {
    onClick { event ->
        if (isPlainClick(event.nativeEvent)) {
            event.preventDefault()
            open()
        }
    }
}

/** A left click with no modifier key: one that a link would follow in this tab. */
internal fun isPlainClick(event: Event): Boolean =
    event is MouseEvent && event.button == 0.toShort() && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey

/**
 * One item as a card (D70): lane 3's band for its [kind] with [label], then [content]. The band holds Back (when
 * [backTo] names a card under this one), Edit or Done, "Open full page" ([fullPage], the item in its own tab) and Close.
 *
 * [editing] is the item's mode (owner request #3): items open for viewing, Edit or E switches to editing, Done or Esc
 * switches back, and a second Esc closes the card. Edits save as they are made, so there is no Cancel. [onEditing] is
 * null for items that are not edited in the card. [classes] sets the width (`dcard--task`, `dcard--topic`) and [attrs]
 * adds a Topic's color.
 */
@Composable
internal fun ItemCard(
    kind: CardKind,
    label: String,
    classes: List<String> = emptyList(),
    attrs: (AttrsScope<HTMLDivElement>.() -> Unit)? = null,
    editing: Boolean = false,
    onEditing: ((Boolean) -> Unit)? = null,
    backTo: String?,
    onBack: () -> Unit,
    fullPage: Place?,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    var card by remember { mutableStateOf<HTMLElement?>(null) }
    var toggle by remember { mutableStateOf<HTMLElement?>(null) }
    var close by remember { mutableStateOf<HTMLElement?>(null) }
    var wasEditing by remember { mutableStateOf(editing) }
    // Back to viewing: focus goes to the Edit button, where Done was, so E works again at once.
    LaunchedEffect(editing) {
        if (wasEditing && !editing) toggle?.focus()
        wasEditing = editing
    }
    // Viewing opens with focus on Close, so neither Enter nor a stray key changes anything. Editing focuses its field.
    LaunchedEffect(Unit) { if (!editing) close?.focus() }
    Div({
        classes("dcard")
        classes.forEach { classes(it) }
        cardKind(kind)
        if (editing) classes("is-editing")
        attrs?.invoke(this)
        ref {
            card = it
            onDispose { card = null }
        }
        onKeyDown { event ->
            val key = event.nativeEvent as? KeyboardEvent ?: return@onKeyDown
            val target = key.target as? Element
            // Keys in a dialog opened from this card, such as the Topics picker, belong to that dialog.
            if (onEditing == null || key.isComposing || target == null || target.closest("dialog") != card?.parentElement) {
                return@onKeyDown
            }
            // ProseMirror marks every Escape in the note text as handled, though it does nothing with it.
            val handled = key.defaultPrevented && !(key.key == "Escape" && (target as? HTMLElement)?.isContentEditable == true)
            if (handled) return@onKeyDown
            when {
                // Preventing the key also stops the dialog from closing: the second Esc closes it.
                editing && key.key == "Escape" -> {
                    key.preventDefault()
                    onEditing(false)
                }
                !editing && isEditKey(key) && !isTextEntry(target) -> {
                    key.preventDefault()
                    onEditing(true)
                }
            }
        }
    }) {
        CardBand(
            kind = kind,
            label = label,
            lead =
                backTo?.let { name ->
                    {
                        Button({
                            type(ButtonType.Button)
                            classes("btn", "btn--text", "btn--sm", "dcard__back")
                            attr("aria-label", "Back to $name")
                            attr("title", "Back to $name")
                            onClick { onBack() }
                        }) {
                            IconView(Icon.ChevLeft, 18)
                            Span({ classes("dcard__lbl") }) { Text(name) }
                        }
                    }
                },
            mode = if (editing) "Editing" else null,
        ) {
            if (onEditing != null) {
                Button({
                    type(ButtonType.Button)
                    classes("btn", "btn--sm", if (editing) "btn--primary" else "btn--line", "dcard__edit")
                    attr("aria-keyshortcuts", if (editing) "Escape" else "E")
                    attr("title", if (editing) "Done editing (Esc)" else "Edit (E)")
                    ref {
                        toggle = it
                        onDispose { toggle = null }
                    }
                    onClick { onEditing(!editing) }
                }) {
                    IconView(if (editing) Icon.Check else Icon.Edit, 15)
                    Span { Text(if (editing) "Done" else "Edit") }
                }
            }
            if (fullPage != null) {
                A(fullPage.hash, {
                    classes("btn", "btn--text", "btn--sm", "dcard__open")
                    attr("aria-label", "Open full page")
                    attr("title", "Open full page")
                }) {
                    IconView(Icon.External, 15)
                    Span({ classes("dcard__lbl") }) { Text("Open full page") }
                }
            }
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text", "btn--sm", "dcard__x")
                attr("aria-label", "Close")
                attr("title", "Close (Esc)")
                ref {
                    close = it
                    onDispose { close = null }
                }
                onClick { onClose() }
            }) { IconView(Icon.X, 18) }
        }
        content()
    }
}

internal fun isEditKey(event: KeyboardEvent): Boolean =
    (event.key == "e" || event.key == "E") && !event.metaKey && !event.ctrlKey && !event.altKey

/** A field that takes typing. A checkbox or a button does not, so E still means Edit there. */
internal fun isTextEntry(target: Element): Boolean =
    when (target) {
        is HTMLTextAreaElement, is HTMLSelectElement -> true
        is HTMLInputElement -> target.type !in setOf("checkbox", "radio", "button", "submit", "reset")
        is HTMLElement -> target.isContentEditable
        else -> false
    }
