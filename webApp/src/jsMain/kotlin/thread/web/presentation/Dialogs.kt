package thread.web.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.TagElement
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/** The open modal dialogs, newest last. The newest one holds the toast. */
internal class DialogStack {
    private var nextId = 0
    private val open = mutableStateListOf<Int>()

    val top: Int? get() = open.lastOrNull()

    val isEmpty: Boolean get() = open.isEmpty()

    fun push(): Int = (++nextId).also { open.add(it) }

    fun remove(id: Int) {
        open.remove(id)
    }
}

internal val LocalDialogStack = compositionLocalOf<DialogStack> { error("No DialogStack provided") }

/**
 * A native modal `<dialog>` with the v3 classes (`dlg` plus [classes]). Escape and a click on the backdrop close it,
 * and focus returns to where it was, also when the dialog is removed. [onClose] runs when the dialog closes by itself;
 * removing it from the composition closes it without calling [onClose].
 *
 * With [onDismiss], Escape and a click on the backdrop call it instead, and the dialog stays open until the caller
 * removes it, so the caller can ask first. The browser can still close it on a repeated Escape, and then [onClose] runs.
 *
 * Give the title the id from [titleId] so the dialog is named by it.
 */
@Composable
internal fun ModalDialog(
    classes: List<String> = emptyList(),
    label: String? = null,
    onDismiss: (() -> Unit)? = null,
    onClose: () -> Unit,
    content: @Composable (titleId: String) -> Unit,
) {
    val stack = LocalDialogStack.current
    val toasts = LocalToasts.current
    val id = remember { stack.push() }
    val titleId = "dlg-title-$id"
    val latestOnClose by rememberUpdatedState(onClose)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val latestLabel by rememberUpdatedState(label)
    val dialogElement = remember { arrayOfNulls<HTMLElement>(1) }
    DisposableEffect(id) { onDispose { stack.remove(id) } }
    // Compose HTML sets every attribute again when one of them changes, and that drops the `open` that showModal()
    // added, which hides the dialog without a close event. A label can change while the dialog is open (a card follows
    // its item's title), so it is set here, outside the attributes Compose manages.
    SideEffect { label?.let { dialogElement[0]?.setAttribute("aria-label", it) } }
    TagElement<HTMLElement>(
        tagName = "dialog",
        applyAttrs = {
            classes("dlg")
            if (classes.isNotEmpty()) classes(*classes.toTypedArray())
            if (label == null) attr("aria-labelledby", titleId)
            ref { element ->
                val dialog = element.asDynamic()
                // What had focus before the dialog opened, such as the row that opened a card.
                val opener = document.activeElement as? HTMLElement
                dialogElement[0] = element
                latestLabel?.let { element.setAttribute("aria-label", it) }
                var removing = false
                // A press that starts inside, as when selecting text, and ends on the backdrop is not a click outside.
                var pressedOutside = false
                val dismiss = {
                    val handler = latestOnDismiss
                    if (handler != null) handler() else dialog.close()
                }
                val closeListener: (Event) -> Unit = { if (!removing) latestOnClose() }
                val downListener: (Event) -> Unit = { event -> pressedOutside = event.target == element }
                val clickListener: (Event) -> Unit = { event ->
                    if (event.target == element && pressedOutside) dismiss()
                    pressedOutside = false
                }
                val cancelListener: (Event) -> Unit = { event ->
                    if (latestOnDismiss != null) {
                        event.preventDefault()
                        dismiss()
                    }
                }
                element.addEventListener("close", closeListener)
                element.addEventListener("pointerdown", downListener)
                element.addEventListener("click", clickListener)
                element.addEventListener("cancel", cancelListener)
                dialog.showModal()
                onDispose {
                    dialogElement[0] = null
                    removing = true
                    element.removeEventListener("close", closeListener)
                    element.removeEventListener("pointerdown", downListener)
                    element.removeEventListener("click", clickListener)
                    element.removeEventListener("cancel", cancelListener)
                    if (dialog.open == true) dialog.close()
                    // Compose takes the dialog out of the page before this runs, and a dialog that is no longer in the
                    // page does not give focus back. When focus fell to the page, it goes back to where it was.
                    val focused = document.activeElement
                    if ((focused == null || focused == document.body) && opener?.isConnected == true) opener.focus()
                }
            }
        },
    ) {
        content(titleId)
        if (stack.top == id) ToastHost(toasts)
    }
}

/** The v3 confirm dialog. Cancel comes first and gets the focus, so Enter never confirms by accident. */
@Composable
internal fun ConfirmDialog(
    title: String,
    body: String?,
    confirmLabel: String,
    danger: Boolean = false,
    onResult: (Boolean) -> Unit,
) {
    ModalDialog(onClose = { onResult(false) }) { titleId ->
        Div({ classes("dlg__in") }) {
            H2({
                classes("dlg__title")
                id(titleId)
            }) { Text(title) }
            if (body != null) P({ classes("dlg__text") }) { Text(body) }
            Div({ classes("dlg__acts") }) {
                Button({
                    type(ButtonType.Button)
                    classes("btn", "btn--line")
                    onClick { onResult(false) }
                }) { Text("Cancel") }
                Button({
                    type(ButtonType.Button)
                    classes("btn", if (danger) "btn--danger" else "btn--primary")
                    onClick { onResult(true) }
                }) { Text(confirmLabel) }
            }
        }
    }
}
