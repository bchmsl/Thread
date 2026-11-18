package thread.web.presentation.tasks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import thread.domain.topic.StatusCategory
import thread.domain.workspace.TodayCard
import thread.domain.workspace.TodayHideableSection
import thread.domain.workspace.TodayLayoutEdit
import thread.domain.workspace.TodayLayoutKind
import thread.domain.workspace.TodayZone
import thread.domain.workspace.zoneOf
import thread.web.platform.mediaMatches
import thread.web.platform.observeMedia
import thread.web.presentation.CardKind
import thread.web.presentation.CheckBox
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.KindMark
import thread.web.presentation.SelectBox
import thread.web.presentation.cardKind
import thread.web.presentation.topics.categoryLabel

// Today's edit mode (#29 L3, D98): the bar, the places with their compact cards, and the phone order's column. The
// draft and what Done saves are the domain's [TodayLayoutEdit]; this file draws it and says what each change did.
// Drag (L4, D99) is in TodayDrag.kt.

/** Windows this wide or narrower show Today in one column, in the phone order (D-9). */
internal const val PHONE_LAYOUT_QUERY = "(max-width: 759px)"

/** The window's main pointer is a finger: the hint says press and hold (D99). */
private const val TOUCH_POINTER_QUERY = "(hover: none) and (pointer: coarse)"

/** Whether this window uses the phone order now (D-9). It follows the window as it is resized. */
@Composable
internal fun rememberPhoneLayout(): Boolean = rememberMedia(PHONE_LAYOUT_QUERY)

/** Whether the CSS media [query] matches, kept up to date. */
@Composable
private fun rememberMedia(query: String): Boolean {
    var matches by remember(query) { mutableStateOf(mediaMatches(query)) }
    DisposableEffect(query) {
        val stop = observeMedia(query) { matches = it }
        onDispose { stop() }
    }
    return matches
}

/** A change made in edit mode: the new draft, the control to keep focus on, and what to announce. */
internal class TodayEditChange(
    val edit: TodayLayoutEdit,
    val focusId: String,
    val announcement: String,
)

/**
 * The bar over the edited layout: what is being edited, Reset to default or Match desktop, Cancel and Done (D-4). Esc
 * cancels too (see [TodayScreen]).
 */
@Composable
internal fun TodayEditBar(
    edit: TodayLayoutEdit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val phone = edit.kind == TodayLayoutKind.Phone
    val touch = rememberMedia(TOUCH_POINTER_QUERY)
    Div({
        classes("ledit-bar")
        attr("role", "region")
        attr("aria-labelledby", EDIT_TITLE_ID)
    }) {
        Div({ classes("ledit-bar__text") }) {
            P({
                classes("ledit-bar__title")
                id(EDIT_TITLE_ID)
                attr("tabindex", "-1")
            }) { Text(if (phone) "Editing the narrow-window order" else "Editing the layout") }
            P({ classes("ledit-bar__hint") }) { Text(editHint(edit, touch)) }
        }
        Div({ classes("ledit-bar__acts") }) {
            Button({
                type(ButtonType.Button)
                id(EDIT_RESET_ID)
                classes("btn", "btn--text")
                if (!edit.canReset) disabled()
                onClick { onReset() }
            }) { Text(if (phone) "Match desktop" else "Reset to default") }
            Button({
                type(ButtonType.Button)
                id(EDIT_CANCEL_ID)
                classes("btn", "btn--line")
                attr("aria-keyshortcuts", "Escape")
                onClick { onCancel() }
            }) { Text("Cancel") }
            Button({
                type(ButtonType.Button)
                id(EDIT_DONE_ID)
                classes("btn", "btn--primary")
                onClick { onDone() }
            }) { Text("Done") }
        }
    }
}

/** What the bar says: how to move a card ([touch]: press and hold), and which windows use the layout being edited. */
private fun editHint(
    edit: TodayLayoutEdit,
    touch: Boolean,
): String {
    val desktop = edit.kind == TodayLayoutKind.Desktop
    val move =
        when {
            touch && desktop -> "Press and hold a card's name, then drag it to any place, or use its Move menu and arrows."
            touch -> "Press and hold a card's name, then drag it, or use its Move menu and arrows."
            desktop -> "Drag a card by its name to any place, or use its Move menu and arrows."
            else -> "Drag a card by its name, or use its Move menu and arrows."
        }
    val which =
        when {
            desktop -> "Wide windows use this layout; narrow windows and phones keep their own order. Done saves."
            edit.start.saved -> "Phones and narrow windows have their own order, apart from the wide layout. Done saves."
            else -> "Phones and narrow windows follow the wide layout until you change the order here. Done saves."
        }
    return "$move $which"
}

/**
 * The layout being edited: the four places at their real widths, each named and outlined even when empty (desktop), or
 * the one column in the phone order. Every card shows, also one Today does not draw now, tagged with why ([sections]
 * follows the draft's hide choices). Cards can be dragged to any place (D99, [installTodayDrag]); a drop is one draft
 * change, announced like the Move select's.
 */
@Composable
internal fun TodayEditView(
    edit: TodayLayoutEdit,
    sections: TodaySections,
    onChange: (TodayEditChange) -> Unit,
) {
    var drag by remember { mutableStateOf<TodayDrag?>(null) }
    val latestEdit by rememberUpdatedState(edit)
    val latestOnChange by rememberUpdatedState(onChange)
    DisposableEffect(Unit) {
        val stop =
            installTodayDrag(onDrag = { drag = it }) { card, target ->
                val next = latestEdit.dropped(card, target)
                if (next != latestEdit) latestOnChange(TodayEditChange(next, moveId(card), dropAnnouncement(next, card)))
            }
        onDispose { stop() }
    }
    val place = EditPlaceScope(edit, sections, drag, onChange)
    if (edit.kind == TodayLayoutKind.Phone) {
        place.EditPlace(PHONE_PLACE, "Order", "One column, top to bottom", edit.order)
        return
    }
    Div({ classes("ledit") }) {
        place.EditZone(TodayZone.Top)
        // The columns sit as Today's do: Right 340 px wide from 1100 px, under Left below that.
        Div({ classes("today", "ledit__cols") }) {
            place.EditZone(TodayZone.Left)
            place.EditZone(TodayZone.Right)
        }
        place.EditZone(TodayZone.Bottom)
    }
}

/** What every place in edit mode draws from: the draft, why cards are not shown, the drag in progress. */
private class EditPlaceScope(
    val edit: TodayLayoutEdit,
    val sections: TodaySections,
    val drag: TodayDrag?,
    val onChange: (TodayEditChange) -> Unit,
)

@Composable
private fun EditPlaceScope.EditZone(zone: TodayZone) = EditPlace(zone.id, zoneName(zone), zoneWhere(zone), edit.layout[zone])

/** One place: named and outlined, lit while a dragged card is over it, with the insert line where the card would land. */
@Composable
private fun EditPlaceScope.EditPlace(
    id: String,
    name: String,
    where: String,
    cards: List<TodayCard>,
) {
    val target = drag?.target?.takeIf { it.place == id }
    val line = if (drag != null && target != null) insertLine(cards, drag.card, target.index) else null
    Section({
        classes("ledit-zone", "ledit-zone--$id")
        if (target != null) classes("is-over")
        attr("data-zone", id)
        attr("aria-labelledby", "tez-$id")
    }) {
        Div({ classes("ledit-zone__head") }) {
            H2({
                classes("ledit-zone__name")
                id("tez-$id")
            }) { Text(name) }
            Span({ classes("ledit-zone__where") }) { Text(where) }
        }
        if (cards.isEmpty()) {
            P({ classes("ledit-zone__empty") }) { Text("Drop a card here") }
        } else {
            Ul({ classes("ledit-zone__list") }) {
                cards.forEach { card ->
                    key(card) {
                        Li({
                            if (line?.card == card) classes(if (line.after) "is-drop-after" else "is-drop-before")
                        }) { CompactCard(card, edit, sections, dragging = drag?.card == card, onChange) }
                    }
                }
            }
        }
    }
}

/**
 * One card in edit mode (D-5): grip, mark and name, why it is not shown now, where it goes, and its hide choice from
 * Settings > Today, if it has one (D-6). The head (grip, mark and name) is the drag handle (D99), dimmed while
 * [dragging]; the select and the buttons carry the same moves for the keyboard.
 */
@Composable
private fun CompactCard(
    card: TodayCard,
    edit: TodayLayoutEdit,
    sections: TodaySections,
    dragging: Boolean,
    onChange: (TodayEditChange) -> Unit,
) {
    val name = todayCardName(card)
    val reason = sections.notShown(card)
    val list = placeOf(card, edit)
    val index = list.indexOf(card)
    Div({
        classes("ledit-card")
        if (reason != null) classes("is-off")
        if (dragging) classes("is-dragging")
        cardKind(todayCardKind(card))
        attr("data-card", card.id)
    }) {
        Div({ classes("ledit-card__main") }) {
            Span({
                classes("ledit-card__grip")
                attr("aria-hidden", "true")
            }) { IconView(Icon.Grip, 18) }
            KindMark(todayCardKind(card))
            Span({ classes("ledit-card__name") }) { Text(name) }
            if (reason != null) Span({ classes("ledit-card__tag") }) { Text(reason.text) }
        }
        Div({ classes("ledit-card__ctrls") }) {
            MoveSelect(card, name, edit, index, list.size, onChange)
            MoveButton(card, name, up = true, enabled = index > 0) {
                val next = edit.movedUp(card)
                onChange(moved(next, card, up = true))
            }
            MoveButton(card, name, up = false, enabled = index < list.lastIndex) {
                val next = edit.movedDown(card)
                onChange(moved(next, card, up = false))
            }
        }
        HideControl(card, name, edit, onChange)
    }
}

/** "Move [card] to": the four places on the desktop, First and Last in the phone order. */
@Composable
private fun MoveSelect(
    card: TodayCard,
    name: String,
    edit: TodayLayoutEdit,
    index: Int,
    count: Int,
    onChange: (TodayEditChange) -> Unit,
) {
    val selectId = moveId(card)
    Label(selectId, { classes("vh") }) { Text("Move $name to") }
    if (edit.kind == TodayLayoutKind.Desktop) {
        val zone = edit.layout.zoneOf(card) ?: return
        SelectBox(
            value = zone.id,
            options = TodayZone.entries.map { it.id to zoneName(it) },
            id = selectId,
            classes = listOf("input", "input--sm", "ledit-card__move"),
        ) { picked ->
            val to = TodayZone.fromId(picked) ?: return@SelectBox
            val next = edit.movedTo(card, to)
            val cards = next.layout[to]
            onChange(TodayEditChange(next, selectId, "Moved $name to ${zoneName(to)}, ${cards.indexOf(card) + 1} of ${cards.size}"))
        }
    } else {
        SelectBox(
            value = "",
            options =
                buildList {
                    add("" to "Place ${index + 1} of $count")
                    if (index > 0) add(FIRST to "First")
                    if (index < count - 1) add(LAST to "Last")
                },
            id = selectId,
            classes = listOf("input", "input--sm", "ledit-card__move"),
        ) { picked ->
            val first = picked == FIRST
            if (!first && picked != LAST) return@SelectBox
            val next = if (first) edit.movedFirst(card) else edit.movedLast(card)
            val position = next.order.indexOf(card) + 1
            onChange(TodayEditChange(next, selectId, "Moved $name ${if (first) "first" else "last"}, $position of ${next.order.size}"))
        }
    }
}

@Composable
private fun MoveButton(
    card: TodayCard,
    name: String,
    up: Boolean,
    enabled: Boolean,
    onMove: () -> Unit,
) {
    Button({
        type(ButtonType.Button)
        id(if (up) upId(card) else downId(card))
        classes("iconbtn", "iconbtn--sm")
        attr("aria-label", "Move $name ${if (up) "up" else "down"}")
        attr("title", if (up) "Move up" else "Move down")
        if (!enabled) disabled()
        onClick { onMove() }
    }) { IconView(if (up) Icon.ArrowUp else Icon.ArrowDown, 18) }
}

/**
 * The change Move up or Move down made, read where the card is now. Focus stays on the button used, or goes to the
 * select when that button is now disabled (the card reached its place's end).
 */
private fun moved(
    next: TodayLayoutEdit,
    card: TodayCard,
    up: Boolean,
): TodayEditChange {
    val list = placeOf(card, next)
    val index = list.indexOf(card)
    val atEnd = if (up) index == 0 else index == list.lastIndex
    return TodayEditChange(
        next,
        when {
            atEnd -> moveId(card)
            up -> upId(card)
            else -> downId(card)
        },
        "Moved ${todayCardName(card)} ${if (up) "up" else "down"}, ${index + 1} of ${list.size}",
    )
}

/** The cards [card] moves among in the draft: its place's (desktop) or the whole order (phone). */
private fun placeOf(
    card: TodayCard,
    edit: TodayLayoutEdit,
): List<TodayCard> {
    if (edit.kind == TodayLayoutKind.Phone) return edit.order
    val zone = edit.layout.zoneOf(card) ?: return emptyList()
    return edit.layout[zone]
}

/**
 * The card's hide choice from Settings > Today (D-6): "Hide when empty" for Meetings, Reminders and Review requested,
 * the "Topics shown" checkboxes for Topics. Tasks has none.
 */
@Composable
private fun HideControl(
    card: TodayCard,
    name: String,
    edit: TodayLayoutEdit,
    onChange: (TodayEditChange) -> Unit,
) {
    val section = hideableSection(card)
    if (section != null) {
        val id = "te-hide-${card.id}"
        Div({ classes("ledit-card__hide") }) {
            CheckBox(section in edit.hidden, label = "Hide $name when empty", classes = listOf("switch"), id = id, switch = true) { on ->
                val text = if (on) "$name will hide when empty" else "$name will always show"
                onChange(TodayEditChange(edit.withHidden(section, on), id, text))
            }
            Label(id) { Text("Hide when empty") }
        }
        return
    }
    if (card != TodayCard.Topics) return
    Div({
        classes("ledit-card__hide", "ledit-card__cats")
        attr("role", "group")
        attr("aria-labelledby", "te-tc-l")
    }) {
        Span({
            classes("ledit-card__cats-label")
            id("te-tc-l")
        }) { Text("Topics shown") }
        StatusCategory.entries.forEach { category ->
            val id = "te-tc-${category.id}"
            Span({ classes("pref-check") }) {
                CheckBox(category in edit.categories, label = null, id = id) { on ->
                    val next = edit.withCategory(category, on)
                    onChange(TodayEditChange(next, id, topicsShownText(next.categories)))
                }
                Label(id) { Text(categoryLabel(category)) }
            }
        }
    }
}

private fun topicsShownText(categories: Set<StatusCategory>): String {
    if (categories.isEmpty()) return "Topics will be left out"
    val names = StatusCategory.entries.filter { it in categories }.map(::categoryLabel)
    val text = if (names.size == 1) names.single() else names.dropLast(1).joinToString(", ") + " and " + names.last()
    return "Topics shown: $text"
}

/** Today in one column, in the phone order (D-9): only the cards Today draws now. Spacing: css/cards.css. */
@Composable
internal fun PhoneColumn(
    cards: List<TodayCard>,
    drawCard: @Composable (TodayCard) -> Unit,
) {
    if (cards.isEmpty()) return
    Div({
        classes("tzone", "tzone--phone")
        attr("data-zone", "phone")
    }) { cards.forEach { key(it) { drawCard(it) } } }
}

private fun hideableSection(card: TodayCard): TodayHideableSection? =
    when (card) {
        TodayCard.Meetings -> TodayHideableSection.Meetings
        TodayCard.Reminders -> TodayHideableSection.Reminders
        TodayCard.ReviewRequested -> TodayHideableSection.ReviewRequested
        TodayCard.Tasks, TodayCard.Topics -> null
    }

/** A card's name in edit mode and its announcements. */
internal fun todayCardName(card: TodayCard): String =
    when (card) {
        TodayCard.Meetings -> "Meetings"
        TodayCard.Tasks -> "Tasks"
        TodayCard.Reminders -> "Reminders"
        TodayCard.ReviewRequested -> "Review requested"
        TodayCard.Topics -> "Topics"
    }

private fun todayCardKind(card: TodayCard): CardKind =
    when (card) {
        TodayCard.Meetings -> CardKind.Meeting
        TodayCard.Tasks -> CardKind.Task
        TodayCard.Reminders -> CardKind.Reminder
        TodayCard.ReviewRequested -> CardKind.PullRequest
        TodayCard.Topics -> CardKind.Topic
    }

internal fun zoneName(zone: TodayZone): String =
    when (zone) {
        TodayZone.Top -> "Top"
        TodayZone.Left -> "Left"
        TodayZone.Right -> "Right"
        TodayZone.Bottom -> "Bottom"
    }

private fun zoneWhere(zone: TodayZone): String =
    when (zone) {
        TodayZone.Top -> "Across the page, above the columns"
        TodayZone.Left -> "The main column"
        TodayZone.Right -> "The side column"
        TodayZone.Bottom -> "Across the page, below the columns"
    }

private fun moveId(card: TodayCard) = "te-move-${card.id}"

private fun upId(card: TodayCard) = "te-up-${card.id}"

private fun downId(card: TodayCard) = "te-down-${card.id}"

private const val FIRST = "first"
private const val LAST = "last"

/** Today's address parameter that opens edit mode, as Settings > Today's "Edit layout" link does: `#/today?edit=1`. */
internal const val TODAY_EDIT_PARAM = "edit"

/** Edit mode's title: focus goes there when it opens. */
internal const val EDIT_TITLE_ID = "te-title"
internal const val EDIT_RESET_ID = "te-reset"
internal const val EDIT_CANCEL_ID = "te-cancel"
internal const val EDIT_DONE_ID = "te-done"
