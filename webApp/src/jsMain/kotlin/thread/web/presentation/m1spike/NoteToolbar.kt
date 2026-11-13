package thread.web.presentation.m1spike

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import thread.domain.note.LinkHref
import thread.web.platform.editor.EditorFormatting
import thread.web.platform.editor.NoteEditor
import thread.web.presentation.Icon
import thread.web.presentation.IconView

private class ToolbarButton(
    val icon: Icon,
    val label: String,
    val pressed: (EditorFormatting) -> Boolean,
    val run: (NoteEditor) -> Unit,
)

// Same buttons, order and labels as the v3 Notes toolbar. The link button is separate: it opens the link form.
private val FORMAT_GROUPS =
    listOf(
        listOf(
            ToolbarButton(Icon.Heading, "Heading", { it.heading }, NoteEditor::toggleHeading),
            ToolbarButton(Icon.Bold, "Bold (⌘B)", { it.bold }, NoteEditor::toggleBold),
            ToolbarButton(Icon.Italic, "Italic (⌘I)", { it.italic }, NoteEditor::toggleItalic),
        ),
        listOf(
            ToolbarButton(Icon.List, "Bulleted list", { it.bulletList }, NoteEditor::toggleBulletList),
            ToolbarButton(Icon.ListOrdered, "Numbered list", { it.orderedList }, NoteEditor::toggleOrderedList),
            ToolbarButton(Icon.Checklist, "Checklist. ⌘Enter ticks the current item", { it.taskList }, NoteEditor::toggleTaskList),
        ),
    )

private const val LINK_ERROR = "Use a full address that starts with https://, http:// or mailto:"

/**
 * The v3 formatting toolbar with its link form. Arrow keys, Home and End move between buttons (one tab stop).
 * Adding, changing and removing a link all go through the form: with the cursor in a link it opens with the address
 * filled in and offers Remove link.
 */
@Composable
internal fun NoteToolbar(
    formatting: EditorFormatting,
    editor: NoteEditor?,
    enabled: Boolean,
    onLinkDone: (String) -> Unit,
) {
    var focusIndex by remember { mutableStateOf(0) }
    var linkFormFor by remember { mutableStateOf<LinkFormState?>(null) }
    val buttonCount = FORMAT_GROUPS.sumOf { it.size } + 1

    Div({
        classes("tb")
        attr("role", "toolbar")
        attr("aria-label", "Formatting")
        attr("aria-controls", "editor")
        onKeyDown { event ->
            val target = event.target as? HTMLElement ?: return@onKeyDown
            if (!target.classList.contains("tb__btn")) return@onKeyDown
            val next =
                when (event.key) {
                    "ArrowRight" -> (focusIndex + 1) % buttonCount
                    "ArrowLeft" -> (focusIndex - 1 + buttonCount) % buttonCount
                    "Home" -> 0
                    "End" -> buttonCount - 1
                    else -> return@onKeyDown
                }
            event.preventDefault()
            focusIndex = next
            val buttons =
                target
                    .closest(".tb")
                    ?.querySelectorAll(".tb__btn")
                    ?.asList()
                    .orEmpty()
            (buttons.getOrNull(next) as? HTMLElement)?.focus()
        }
    }) {
        var index = 0
        FORMAT_GROUPS.forEachIndexed { groupIndex, group ->
            if (groupIndex > 0) {
                Span({
                    classes("tb__sep")
                    attr("aria-hidden", "true")
                })
            }
            group.forEach { button ->
                val buttonIndex = index++
                Button({
                    classes("tb__btn")
                    type(ButtonType.Button)
                    attr("tabindex", if (buttonIndex == focusIndex) "0" else "-1")
                    attr("aria-label", button.label)
                    attr("title", button.label)
                    attr("aria-pressed", button.pressed(formatting).toString())
                    if (!enabled) disabled()
                    // Keep the editor focus and selection when a button is clicked.
                    onMouseDown { it.preventDefault() }
                    onClick {
                        focusIndex = buttonIndex
                        editor?.let(button.run)
                    }
                }) { IconView(button.icon, 18) }
            }
        }
        Span({
            classes("tb__sep")
            attr("aria-hidden", "true")
        })
        val linkIndex = index
        Button({
            classes("tb__btn")
            type(ButtonType.Button)
            attr("tabindex", if (linkIndex == focusIndex) "0" else "-1")
            attr("aria-label", "Link")
            attr("title", "Link")
            attr("aria-expanded", (linkFormFor != null).toString())
            attr("aria-controls", "tb-link")
            if (!enabled) disabled()
            onMouseDown { it.preventDefault() }
            onClick {
                focusIndex = linkIndex
                linkFormFor = if (linkFormFor == null) LinkFormState(existing = formatting.link) else null
                if (linkFormFor == null) editor?.focus()
            }
        }) { IconView(Icon.Link, 18) }

        linkFormFor?.let { state ->
            LinkForm(
                state = state,
                onApply = { href ->
                    linkFormFor = null
                    editor?.applyLink(href)
                    onLinkDone(if (state.existing == null) "Link added" else "Link updated")
                },
                onRemove = {
                    linkFormFor = null
                    editor?.removeLink()
                    onLinkDone("Link removed")
                },
                onCancel = {
                    linkFormFor = null
                    editor?.focus()
                },
            )
        }
    }
}

/** One opening of the link form. [existing] is the address of the link being edited, or null for a new link. */
private class LinkFormState(
    val existing: String?,
)

@Composable
private fun LinkForm(
    state: LinkFormState,
    onApply: (LinkHref) -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    var address by remember(state) { mutableStateOf(state.existing.orEmpty()) }
    var error by remember(state) { mutableStateOf(false) }

    Form(attrs = {
        classes("tb__link")
        id("tb-link")
        attr("novalidate", "")
        attr("autocomplete", "off")
        onSubmit { event ->
            event.preventDefault()
            val href = LinkHref.parse(address)
            if (href == null) {
                error = true
                (document.getElementById("tb-url") as? HTMLElement)?.focus()
            } else {
                onApply(href)
            }
        }
    }) {
        Label("tb-url", { classes("vh") }) { Text("Link address") }
        Input(InputType.Url) {
            classes("input")
            id("tb-url")
            value(address)
            placeholder("https://")
            attr("aria-describedby", "tb-err")
            if (error) attr("aria-invalid", "true")
            onInput {
                address = it.value
                error = false
            }
            onKeyDown {
                if (it.key == "Escape") {
                    it.preventDefault()
                    onCancel()
                }
            }
            ref { element ->
                element.focus()
                element.select()
                onDispose { }
            }
        }
        Button({
            classes("btn", "btn--line", "btn--sm")
            type(ButtonType.Submit)
        }) { Text(if (state.existing == null) "Add link" else "Save link") }
        if (state.existing != null) {
            Button({
                classes("btn", "btn--text", "btn--sm")
                type(ButtonType.Button)
                onClick { onRemove() }
            }) { Text("Remove link") }
        }
        Button({
            classes("btn", "btn--text", "btn--sm")
            type(ButtonType.Button)
            onClick { onCancel() }
        }) { Text("Cancel") }
        P({
            classes("tb__err")
            id("tb-err")
            if (!error) attr("hidden", "")
        }) { if (error) Text(LINK_ERROR) }
    }
}
