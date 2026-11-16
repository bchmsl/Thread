package thread.web.presentation.topics

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Footer
import org.jetbrains.compose.web.dom.Text
import thread.domain.topic.Topic
import thread.web.presentation.CardKind
import thread.web.presentation.CardOpener
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.ItemCard
import thread.web.presentation.LocalToasts
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.external.GitHubModel
import thread.web.presentation.external.JiraModel
import thread.web.presentation.scopedId

/**
 * A Topic opened from outside the Topics tab (`?topic=`, D70, owner answer Q1): [TopicDetails] as a big card. Its band
 * has no Edit: Topic Details keeps its own Edit for the name, goal and color, and one for Additional information.
 * "Open full page" goes to the Topic's page in the Topics tab. Move to Trash closes the card and offers Undo.
 */
@Composable
internal fun TopicCard(
    workspace: WorkspaceModel,
    jira: JiraModel,
    gitHub: GitHubModel,
    topic: Topic,
    cards: CardOpener,
    backTo: String?,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val toasts = LocalToasts.current
    val hintId = scopedId("td-trash-hint")
    ItemCard(
        kind = CardKind.Topic,
        label = "Topic",
        classes = listOf("dcard--topic"),
        attrs = { topicColor(topic.color) },
        backTo = backTo,
        onBack = onBack,
        fullPage = topicPlace(topic),
        onClose = onClose,
    ) {
        Div({ classes("dcard__body", "dcard__body--topic") }) {
            TopicDetails(workspace, jira, gitHub, topic, cards, inCard = true)
        }
        Footer({ classes("dcard__foot") }) {
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text", "btn--danger-text", "btn--sm")
                attr("aria-describedby", hintId)
                onClick {
                    val current = workspace.topic(topic.id.value) ?: return@onClick
                    val edit = workspace.trashTopic(current) ?: return@onClick
                    onClose()
                    toasts.show("Moved to Trash: ${current.name}", undo = { workspace.undo(edit) })
                }
            }) {
                IconView(Icon.Trash, 15)
                Text(" Move to Trash")
            }
            TopicTrashHint()
        }
    }
}
