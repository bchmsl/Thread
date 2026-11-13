package thread.web.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** One toast: a short confirmation with optional Undo and Open buttons (v3 T.toast). */
internal class ToastMessage(
    val id: Int,
    val text: String,
    val undo: (() -> Unit)?,
    val open: (() -> Unit)?,
    val openLabel: String,
)

internal class ToastState {
    private var nextId = 0

    var message: ToastMessage? by mutableStateOf(null)
        private set
    var visible: Boolean by mutableStateOf(false)
        private set

    fun show(
        text: String,
        undo: (() -> Unit)? = null,
        open: (() -> Unit)? = null,
        openLabel: String = "Open",
    ) {
        message = ToastMessage(++nextId, text, undo, open, openLabel)
        visible = true
    }

    fun hide() {
        visible = false
    }
}

internal val LocalToasts = compositionLocalOf<ToastState> { error("No ToastState provided") }

/**
 * The toast box. It stays open while the pointer or keyboard focus is on it. Only one host shows at a time: the app's
 * own, or the one inside the topmost open dialog, because a modal dialog makes the rest of the page inert.
 */
@Composable
internal fun ToastHost(state: ToastState) {
    var held by remember { mutableStateOf(false) }
    val message = state.message
    LaunchedEffect(message?.id, state.visible) {
        if (message == null || !state.visible) return@LaunchedEffect
        delay(TOAST_TIME)
        while (held) delay(250.milliseconds)
        state.hide()
    }
    Div({
        id("toast")
        classes("toast")
        if (state.visible && message != null) classes("is-on")
        attr("role", "status")
        attr("aria-live", "polite")
        onMouseEnter { held = true }
        onMouseLeave { held = false }
        addEventListener("focusin") { held = true }
        addEventListener("focusout") { held = false }
    }) {
        if (message != null && state.visible) {
            P({ classes("toast__msg") }) { Text(message.text) }
            message.undo?.let { undo -> ToastButton("Undo") { undo() } }
            message.open?.let { open -> ToastButton(message.openLabel) { open() } }
        }
    }
}

@Composable
private fun ToastButton(
    label: String,
    onClick: () -> Unit,
) {
    val state = LocalToasts.current
    Button({
        type(ButtonType.Button)
        classes("btn", "btn--text")
        onClick {
            state.hide()
            onClick()
        }
    }) { Text(label) }
}

private val TOAST_TIME = 6.seconds
