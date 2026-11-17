package thread.web.presentation.notes

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Br
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.CheckboxInput
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.I
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Ol
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import thread.domain.note.ConflictChoice
import thread.domain.note.LocalDraft
import thread.domain.note.NoteRevision
import thread.domain.note.RichTextBlock
import thread.domain.note.RichTextDocument
import thread.domain.note.RichTextInline
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalIdScope

/**
 * The v3 "Choose which version to keep" view (notes page, D41). The version not kept goes to Trash; Keep both makes
 * the other version a second Note. Devices have no names yet, so the other one is "Other device".
 */
@Composable
internal fun ConflictCompare(
    local: LocalDraft,
    other: NoteRevision,
    onBack: () -> Unit,
    onKeep: (ConflictChoice) -> Unit,
) {
    val ids = LocalIdScope.current
    Div({ classes("cmp-wrap") }) {
        Button({
            classes("back")
            type(ButtonType.Button)
            onClick { onBack() }
        }) {
            IconView(Icon.ChevLeft, 16)
            Text("Back to the note")
        }
        Div({ classes("cmp-intro") }) {
            H2({
                classes("h2")
                id("${ids}cmp-h")
                tabIndex(-1)
            }) { Text("Choose which version to keep") }
            P({ classes("muted", "cmp-intro__text") }) {
                Text(
                    "Both devices changed “${local.title.ifBlank { UNTITLED }}”. The version you do not keep goes to Trash, " +
                        "so you can still restore it.",
                )
            }
        }
        Div({ classes("cmp") }) {
            VersionColumn("This device's version", "This device", local.title, local.body)
            VersionColumn("Other device's version", "Other device", other.title, other.body)
        }
        Div({ classes("cmp__acts") }) {
            KeepButton("Keep this device's version") { onKeep(ConflictChoice.KeepThisDevice) }
            KeepButton("Keep other device's version") { onKeep(ConflictChoice.KeepOther) }
            KeepButton("Keep both") { onKeep(ConflictChoice.KeepBoth) }
            P({ classes("cmp__hint") }) { Text("Keep both saves the other device's version as a second note.") }
        }
    }
}

@Composable
private fun VersionColumn(
    label: String,
    device: String,
    title: String,
    body: RichTextDocument,
) {
    Section({
        classes("cmp__col")
        attr("aria-label", label)
    }) {
        Div({ classes("cmp__head") }) {
            Span({ classes("cmp__dev") }) {
                IconView(Icon.Device, 18)
                Text(device)
            }
        }
        P({ classes("cmp__title") }) { Text(title.ifBlank { UNTITLED }) }
        Div({ classes("editor", "editor--ro") }) { RichTextView(body) }
    }
}

@Composable
private fun KeepButton(
    label: String,
    onClick: () -> Unit,
) {
    Button({
        classes("btn", "btn--line")
        type(ButtonType.Button)
        onClick { onClick() }
    }) { Text(label) }
}

/**
 * A read-only view of a Note body. Built from DOM elements, never HTML strings, so content from the server cannot
 * add markup. Links are already limited to http, https and mailto by [thread.domain.note.LinkHref].
 */
@Composable
internal fun RichTextView(document: RichTextDocument) {
    document.blocks.forEach { RichTextBlockView(it) }
}

/** One read-only block. */
@Composable
private fun RichTextBlockView(block: RichTextBlock) {
    when (block) {
        is RichTextBlock.Paragraph -> P { Inlines(block.content) }
        is RichTextBlock.Heading ->
            when {
                block.level == 1 -> H1 { Inlines(block.content) }
                block.level == 2 -> H2 { Inlines(block.content) }
                else -> H3 { Inlines(block.content) }
            }
        is RichTextBlock.BulletList ->
            Ul { block.items.forEach { item -> Li { item.blocks.forEach { RichTextBlockView(it) } } } }
        is RichTextBlock.OrderedList ->
            Ol({ if (block.start != 1) attr("start", block.start.toString()) }) {
                block.items.forEach { item -> Li { item.blocks.forEach { RichTextBlockView(it) } } }
            }
        is RichTextBlock.TaskList ->
            Ul({ attr("data-type", "taskList") }) {
                block.items.forEach { item ->
                    Li({ attr("data-checked", item.checked.toString()) }) {
                        Label {
                            CheckboxInput(item.checked) {
                                disabled()
                                attr("aria-label", if (item.checked) "Done checklist item" else "Open checklist item")
                            }
                        }
                        Div { item.blocks.forEach { RichTextBlockView(it) } }
                    }
                }
            }
    }
}

@Composable
private fun Inlines(content: List<RichTextInline>) {
    content.forEach { inline ->
        when (inline) {
            RichTextInline.HardBreak -> Br()
            is RichTextInline.Text -> StyledText(inline)
        }
    }
}

@Composable
private fun StyledText(text: RichTextInline.Text) {
    val link = text.link
    if (link != null) {
        A(link.value, {
            target(ATarget.Blank)
            attr("rel", "noopener noreferrer")
        }) { Emphasis(text) }
    } else {
        Emphasis(text)
    }
}

@Composable
private fun Emphasis(text: RichTextInline.Text) {
    when {
        text.bold && text.italic -> B { I { Text(text.text) } }
        text.bold -> B { Text(text.text) }
        text.italic -> I { Text(text.text) }
        else -> Text(text.text)
    }
}

internal const val UNTITLED = "Untitled note"
