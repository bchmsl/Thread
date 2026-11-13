package thread.web.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
 * and the browser returns focus to where it was. [onClose] runs when the dialog closes by itself; removing it from
 * the composition closes it without calling [onClose].
 *
 * Give the title the id from [titleId] so the dialog is named by it.
 */
@Composable
internal fun ModalDialog(
    classes: List<String> = emptyList(),
    label: String? = null,
    onClose: () -> Unit,
    content: @Composable (titleId: String) -> Unit,
) {
    val stack = LocalDialogStack.current
    val toasts = LocalToasts.current
    val id = remember { stack.push() }
    val titleId = "dlg-title-$id"
    val latestOnClose by rememberUpdatedState(onClose)
    DisposableEffect(id) { onDispose { stack.remove(id) } }
    TagElement<HTMLElement>(
        tagName = "dialog",
        applyAttrs = {
            classes("dlg")
            if (classes.isNotEmpty()) classes(*classes.toTypedArray())
            if (label != null) attr("aria-label", label) else attr("aria-labelledby", titleId)
            ref { element ->
                val dialog = element.asDynamic()
                var removing = false
                val closeListener: (Event) -> Unit = { if (!removing) latestOnClose() }
                val clickListener: (Event) -> Unit = { event -> if (event.target == element) dialog.close() }
                element.addEventListener("close", closeListener)
                element.addEventListener("click", clickListener)
                dialog.showModal()
                onDispose {
                    removing = true
                    element.removeEventListener("close", closeListener)
                    element.removeEventListener("click", clickListener)
                    if (dialog.open == true) dialog.close()
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
