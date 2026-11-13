package thread.web.presentation.topics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.name
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Fieldset
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Legend
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import thread.domain.topic.TopicId
import thread.domain.topic.statusOf
import thread.web.presentation.ModalDialog
import thread.web.presentation.WorkspaceModel

/**
 * The v3 Topic picker for one Topic (T.pickTopics with multiple = false). [onPick] gets the picked Topic, or null for
 * "No Topic" when [allowNone]; closing the dialog any other way calls [onCancel].
 */
@Composable
internal fun TopicPickerDialog(
    workspace: WorkspaceModel,
    title: String,
    hint: String?,
    confirmLabel: String,
    selected: TopicId? = null,
    allowNone: Boolean = false,
    onPick: (TopicId?) -> Unit,
    onCancel: () -> Unit,
) {
    val topics = workspace.topics
    val statuses = workspace.statuses
    var pick by remember { mutableStateOf(selected?.value ?: "") }
    val canConfirm = allowNone || pick.isNotEmpty()
    ModalDialog(onClose = onCancel) { titleId ->
        Form(attrs = {
            classes("dlg__in")
            onSubmit { event ->
                event.preventDefault()
                if (canConfirm) onPick(pick.takeIf(String::isNotEmpty)?.let(::TopicId))
            }
        }) {
            H2({
                classes("dlg__title")
                id(titleId)
            }) { Text(title) }
            if (hint != null) P({ classes("dlg__text") }) { Text(hint) }
            Fieldset({ classes("picklist") }) {
                Legend({ classes("vh") }) { Text(title) }
                if (allowNone) {
                    Label(attrs = { classes("pick") }) {
                        Input(InputType.Radio) {
                            name("pick")
                            value("")
                            checked(pick.isEmpty())
                            attr("autofocus", "")
                            onChange { if (it.value) pick = "" }
                        }
                        TopicHexEmpty(HexSize.Sm)
                        Span({ classes("pick__name") }) { Text("No Topic (standalone)") }
                    }
                }
                topics.forEachIndexed { index, topic ->
                    Label(attrs = { classes("pick") }) {
                        Input(InputType.Radio) {
                            name("pick")
                            value(topic.id.value)
                            checked(pick == topic.id.value)
                            if (index == 0 && !allowNone) attr("autofocus", "")
                            onChange { if (it.value) pick = topic.id.value }
                        }
                        TopicHex(topic.name, topic.color, HexSize.Sm)
                        Span({ classes("pick__name") }) { Text(topic.name) }
                        StatusLabel(statusOf(topic, statuses))
                    }
                }
            }
            if (topics.isEmpty()) P({ classes("dlg__text") }) { Text("No Topics yet. Create one from the capture line.") }
            Div({ classes("dlg__acts") }) {
                Button({
                    type(ButtonType.Button)
                    classes("btn", "btn--line")
                    onClick { onCancel() }
                }) { Text("Cancel") }
                Button({
                    type(ButtonType.Submit)
                    classes("btn", "btn--primary")
                    if (!canConfirm) attr("disabled", "")
                }) { Text(confirmLabel) }
            }
        }
    }
}
