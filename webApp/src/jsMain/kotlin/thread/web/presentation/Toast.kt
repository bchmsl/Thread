package thread.web.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import kotlin.time.Duration.Companion.seconds

/** A short confirmation at the bottom of the screen, as the v3 toast. */
internal class ToastState {
    var message by mutableStateOf("")
        private set
    var visible by mutableStateOf(false)
        private set
    var shown by mutableStateOf(0)
        private set

    fun show(text: String) {
        message = text
        visible = true
        shown++
    }

    fun hide() {
        visible = false
    }
}

@Composable
internal fun ToastHost(state: ToastState) {
    LaunchedEffect(state.shown) {
        if (state.visible) {
            delay(TOAST_TIME)
            state.hide()
        }
    }
    Div({
        classes("toast")
        if (state.visible) classes("is-on")
        attr("role", "status")
        attr("aria-live", "polite")
    }) {
        Span({ classes("toast__msg") }) { Text(state.message) }
    }
}

private val TOAST_TIME = 4.seconds
