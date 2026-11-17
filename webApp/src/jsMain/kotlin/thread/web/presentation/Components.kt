package thread.web.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.TagElement
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

/**
 * The v3 page header. The title takes focus (tabindex -1) when the app moves to a new view, so screen readers
 * announce it; v3 app.css draws no ring for that.
 */
@Composable
internal fun PageHeader(
    title: String,
    sub: String? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    Header({ classes("ph") }) {
        Div {
            H1({
                classes("ph__title")
                tabIndex(-1)
            }) { Text(title) }
            if (sub != null) P({ classes("ph__sub") }) { Text(sub) }
        }
        if (actions != null) Div({ classes("ph__acts") }) { actions() }
    }
}

/** The v3 empty state (T.empty). */
@Composable
internal fun EmptyState(
    icon: Icon,
    title: String,
    text: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Div({ classes("empty") }) {
        Span({ classes("empty__icon") }) { IconView(icon, 26) }
        H3({ classes("empty__title") }) { Text(title) }
        if (text != null) P({ classes("empty__text") }) { Text(text) }
        action?.invoke()
    }
}

@Composable
internal fun Kbd(text: String) {
    TagElement<HTMLElement>("kbd", { classes("kbd") }) { Text(text) }
}

/** An icon-only button. [label] is its accessible name and tooltip. */
@Composable
internal fun IconButton(
    icon: Icon,
    label: String,
    small: Boolean = false,
    disabled: Boolean = false,
    onClick: () -> Unit,
) {
    Button({
        type(ButtonType.Button)
        classes("iconbtn")
        if (small) classes("iconbtn--sm")
        attr("aria-label", label)
        attr("title", label)
        if (disabled) attr("disabled", "")
        onClick { onClick() }
    }) { IconView(icon, if (small) 16 else 20) }
}

/**
 * A `<select>` that shows [value]. Compose HTML sets `selected` only as an attribute, which stops working after the
 * person picks an option, so the element's value is set directly after each composition.
 */
@Composable
internal fun SelectBox(
    value: String,
    options: List<Pair<String, String>>,
    id: String? = null,
    classes: List<String> = emptyList(),
    hidden: Boolean = false,
    onChange: (String) -> Unit,
) {
    var element by remember { mutableStateOf<HTMLSelectElement?>(null) }
    Select({
        if (id != null) id(id)
        if (classes.isNotEmpty()) classes(*classes.toTypedArray())
        if (hidden) attr("hidden", "")
        ref {
            element = it
            onDispose { element = null }
        }
        onChange { event -> event.value?.let(onChange) }
    }) {
        options.forEach { (optionValue, label) -> Option(optionValue) { Text(label) } }
    }
    SideEffect {
        element?.let { if (it.value != value) it.value = value }
    }
}

/**
 * A checkbox that shows [checked]. Like [SelectBox], the element's state is set directly after each composition, so
 * it always follows what the store says, also after the person clicks it.
 */
@Composable
internal fun CheckBox(
    checked: Boolean,
    label: String?,
    classes: List<String> = listOf("check"),
    id: String? = null,
    indeterminate: Boolean = false,
    switch: Boolean = false,
    enabled: Boolean = true,
    describedBy: String? = null,
    onChange: (Boolean) -> Unit,
) {
    var element by remember { mutableStateOf<HTMLInputElement?>(null) }
    Input(InputType.Checkbox) {
        classes(*classes.toTypedArray())
        if (id != null) id(id)
        if (switch) attr("role", "switch")
        if (label != null) attr("aria-label", label)
        if (describedBy != null) attr("aria-describedby", describedBy)
        if (!enabled) disabled()
        ref {
            element = it
            onDispose { element = null }
        }
        onChange { event -> onChange(event.value) }
    }
    SideEffect {
        element?.let {
            if (it.checked != checked) it.checked = checked
            if (it.indeterminate != indeterminate) it.indeterminate = indeterminate
        }
    }
}

/**
 * A date or time input that shows [value] and reports a finished change. While it has focus it keeps what the
 * person is typing; when focus leaves, it shows the stored value again.
 */
@Composable
internal fun StoredValueInput(
    type: InputType<String>,
    value: String,
    classes: List<String>,
    id: String? = null,
    label: String? = null,
    onChange: (String) -> Unit,
) {
    var element by remember { mutableStateOf<HTMLInputElement?>(null) }
    var focused by remember { mutableStateOf(false) }
    Input(type) {
        classes(*classes.toTypedArray())
        if (id != null) id(id)
        if (label != null) attr("aria-label", label)
        ref {
            element = it
            onDispose { element = null }
        }
        onFocus { focused = true }
        onBlur { focused = false }
        onChange { event -> onChange(event.value) }
    }
    SideEffect {
        element?.let { if (!focused && document.activeElement != it && it.value != value) it.value = value }
    }
}
