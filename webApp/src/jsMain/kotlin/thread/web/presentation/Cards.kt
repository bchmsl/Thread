package thread.web.presentation

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/*
 * The card language (owner changes #12 and #13, D68): every section and every item is a card, and each kind of thing
 * has its own accent, mark and shape, so a Jira ticket never reads like a pull request, nor a Note like a Task.
 * css/cards.css draws it; these helpers only say what kind a card is and give sections and dialogs one structure.
 */

/** A kind of card. [id] is the `data-k` value css/cards.css styles; [mark] is the kind's glyph. */
internal enum class CardKind(
    val id: String,
    val mark: Icon,
) {
    Task("task", Icon.Task),
    Note("note", Icon.Notes),
    Jira("jira", Icon.Jira),
    PullRequest("pr", Icon.Pr),
    Topic("topic", Icon.Hex),
    Reminder("time", Icon.Bell),

    /** A part of one item, like a pull request's Description: the item's own head carries the kind. */
    Part("part", Icon.Doc),

    /** A Topic's Additional information, in the Topic's color. */
    Info("info", Icon.Doc),
}

/** Says which kind of card this element is, for its accent and shape. A Topic or Info card also needs `topicColor`. */
internal fun AttrsScope<*>.cardKind(kind: CardKind) {
    attr("data-k", kind.id)
}

/** An item card: one Task, Note, ticket, pull request or Topic in a list. */
internal fun AttrsScope<*>.itemCard(kind: CardKind) {
    classes("icard")
    cardKind(kind)
}

/** The kind's mark: the same glyph on its section, its item cards and its dialog card. Decorative. */
@Composable
internal fun KindMark(
    kind: CardKind,
    icon: Icon = kind.mark,
    size: Int = 16,
) {
    Span({
        classes("kmark")
        attr("aria-hidden", "true")
    }) { IconView(icon, size) }
}

/**
 * A section card (D68): a head in its kind's band (mark, title, count, [hint], [actions] on the right), then the body.
 * With [onToggle] the title is a button that folds the body away; the count stays in the head when it is folded. The
 * heading has [headingId] and the body [bodyId], so a page can move focus to either. [titleContent] replaces the plain
 * [title] text, for a status label; [attrs] adds classes or a Topic color to the card.
 */
@Composable
internal fun SectionCard(
    kind: CardKind,
    headingId: String,
    bodyId: String,
    title: String,
    count: String? = null,
    open: Boolean = true,
    onToggle: (() -> Unit)? = null,
    hint: String? = null,
    level: Int = 2,
    mark: Icon = kind.mark,
    attrs: AttrsScope<*>.() -> Unit = {},
    titleContent: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    Section({
        classes("scard")
        if (!open) classes("is-closed")
        cardKind(kind)
        attr("aria-labelledby", headingId)
        attrs()
    }) {
        Div({ classes("scard__head") }) {
            val heading: AttrsScope<*>.() -> Unit = {
                classes("scard__h")
                id(headingId)
            }
            val head: @Composable () -> Unit = {
                if (onToggle != null) {
                    Button({
                        type(ButtonType.Button)
                        classes("scard__toggle")
                        attr("aria-expanded", open.toString())
                        attr("aria-controls", bodyId)
                        onClick { onToggle() }
                    }) { HeadText(kind, mark, title, count, hint, foldable = true, titleContent) }
                } else {
                    Span({ classes("scard__toggle", "scard__toggle--static") }) {
                        HeadText(kind, mark, title, count, hint, foldable = false, titleContent)
                    }
                }
            }
            if (level == 3) H3(heading) { head() } else H2(heading) { head() }
            if (actions != null) Div({ classes("scard__acts") }) { actions() }
        }
        Div({
            classes("scard__body")
            id(bodyId)
            if (!open) attr("hidden", "")
        }) { if (open) body() }
    }
}

@Composable
private fun HeadText(
    kind: CardKind,
    mark: Icon,
    title: String,
    count: String?,
    hint: String?,
    foldable: Boolean,
    titleContent: (@Composable () -> Unit)?,
) {
    KindMark(kind, mark)
    Span({ classes("scard__title") }) { if (titleContent != null) titleContent() else Text(title) }
    if (count != null) {
        Text(" ")
        Span({ classes("n") }) { Text(count) }
    }
    if (foldable) Span({ classes("chev") }) { IconView(Icon.ChevDown, 18) }
    if (hint != null) Span({ classes("scard__hint") }) { Text(hint) }
}

/**
 * The head of a card opened large (owner changes #2 and #3): its kind's band, so a dialog card reads like its list card.
 * [lead] comes first (a Back button), [label] names the kind ("Topic", "Task"), [actions] sit on the right (Edit, Open
 * full page, Close). Put it first in an element with the `dcard` class and [cardKind] (and `topicColor` for a Topic);
 * css/cards.css draws the band, and fades its pattern away from the buttons. Under 600 px, `dcard__lbl` text hides and
 * the buttons keep their accessible names.
 */
@Composable
internal fun CardBand(
    kind: CardKind,
    label: String,
    lead: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    Header({ classes("scard__head", "dcard__head") }) {
        lead?.invoke()
        P({ classes("dcard__kind") }) {
            KindMark(kind)
            Span { Text(label) }
        }
        Div({ classes("dcard__acts") }) { actions() }
    }
}
