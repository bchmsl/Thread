package thread.web.presentation.external

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.dom.Article
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.P
import thread.domain.workspace.ExternalReference
import thread.web.presentation.CardKind
import thread.web.presentation.KindMark
import thread.web.presentation.SectionCard
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.cardKind

/**
 * A Jira ticket or a pull request, opened (owner changes #12 and #13, D68, D69): a head card in the kind's band with
 * the [kicker], the title and [head] (people, counts, actions), then its parts as cards, [main] on the left and [side]
 * on the right when there is room. css/cards.css switches the columns on the width the detail has, not the window's, so
 * it also fits the Topic side panel and a dialog card. On one column the side's Reviewers card comes right after the
 * head. [heading1] on the record's own page.
 */
@Composable
internal fun DetailCards(
    kind: CardKind,
    heading1: Boolean,
    provider: String,
    kicker: @Composable () -> Unit,
    title: @Composable () -> Unit,
    head: @Composable () -> Unit,
    main: @Composable () -> Unit,
    side: @Composable () -> Unit,
) {
    Article({
        classes("xd")
        attr("aria-labelledby", XD_TITLE_ID)
    }) {
        Div({ classes("xd__grid") }) {
            Header({
                classes("scard", "xd__head")
                cardKind(kind)
            }) {
                Div({ classes("scard__head", "xd__band") }) {
                    KindMark(kind)
                    P({ classes("xd__kicker") }) { kicker() }
                }
                Div({ classes("scard__body") }) {
                    val titleAttrs: AttrsScope<*>.() -> Unit = {
                        classes("xd__title")
                        id(XD_TITLE_ID)
                        tabIndex(-1)
                    }
                    if (heading1) H1(titleAttrs) { title() } else H2(titleAttrs) { title() }
                    head()
                }
            }
            Div({ classes("xd__main") }) { main() }
            Div({ classes("xd__side") }) { side() }
            ReadOnlyNote(provider)
        }
    }
}

/** The heading level of a detail's part cards: under the title, which is a level 1 heading on the record's own page. */
internal fun partLevel(heading1: Boolean): Int = if (heading1) 2 else 3

/** The Topics a record belongs to, as a card on the detail's side, each with Unlink. Unlinking never changes [what]. */
@Composable
internal fun TopicsCard(
    reference: ExternalReference?,
    workspace: WorkspaceModel,
    what: String,
    heading1: Boolean,
) {
    if (reference == null) return
    SectionCard(
        kind = CardKind.Topic,
        headingId = "xd-topics-h",
        bodyId = "xd-topics-b",
        title = "Topics",
        count = workspace.topicsLinkedTo(reference.id).size.toString(),
        level = partLevel(heading1),
        attrs = { classes("scard--topics") },
    ) { TopicLinks(reference, workspace, what) }
}
