package thread.web.presentation.tasks

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import thread.domain.workspace.TodayCard
import thread.domain.workspace.TodayLayoutEdit
import thread.domain.workspace.TodayLayoutKind
import thread.domain.workspace.TodayZone
import thread.domain.workspace.zoneOf
import thread.web.platform.DragSession
import thread.web.platform.installPointerDrag
import kotlin.math.max
import kotlin.math.min

// Drag in Today's edit mode (#29 L4, D99). A card's head (grip, mark and name) is the handle: mouse and pen drag it,
// touch presses and holds it first (D-7). The drop is one draft change, like the Move select. The engine is
// platform/PointerDrag.kt; this file finds the place and position under the pointer and draws the drag.

/** Where a dragged card would land: the place's `data-zone` and the position among its cards, the dragged one left out. */
internal data class TodayDropTarget(
    val place: String,
    val index: Int,
)

/**
 * A drag in progress: the card, and where it would land now.
 * [target] is null outside every place: a release there does nothing.
 */
internal data class TodayDrag(
    val card: TodayCard,
    val target: TodayDropTarget?,
)

/** The insert line: just before [card], or just after it when [after]. */
internal data class InsertLine(
    val card: TodayCard,
    val after: Boolean,
)

/** The position a card dropped at [y] takes among cards whose vertical middles are [middles], top to bottom. */
internal fun dropIndex(
    middles: List<Double>,
    y: Double,
): Int = middles.count { it < y }

/**
 * Where the insert line shows for a drop at [index] in a place holding [cards]: before the card that will follow the
 * dropped one, or after the last. [index] counts the cards without [dragged]. None when no other card is there.
 */
internal fun insertLine(
    cards: List<TodayCard>,
    dragged: TodayCard,
    index: Int,
): InsertLine? {
    val others = cards - dragged
    return when {
        others.isEmpty() -> null
        index < others.size -> InsertLine(others[index.coerceAtLeast(0)], after = false)
        else -> InsertLine(others.last(), after = true)
    }
}

/**
 * This draft after [card] is dropped at [target]. The same draft for a drop where the card already is, or at a place
 * this layout does not have.
 */
internal fun TodayLayoutEdit.dropped(
    card: TodayCard,
    target: TodayDropTarget,
): TodayLayoutEdit =
    when (kind) {
        TodayLayoutKind.Phone -> if (target.place == PHONE_PLACE) droppedAt(card, target.index) else this
        TodayLayoutKind.Desktop -> TodayZone.fromId(target.place)?.let { droppedAt(card, it, target.index) } ?: this
    }

/**
 * What a drop says, read where [card] is in [next]: "Moved Meetings to Top, 1 of 2" on the desktop layout, "Moved
 * Meetings to place 3 of 5" in the phone order.
 */
internal fun dropAnnouncement(
    next: TodayLayoutEdit,
    card: TodayCard,
): String {
    val name = todayCardName(card)
    if (next.kind == TodayLayoutKind.Phone) return "Moved $name to place ${next.order.indexOf(card) + 1} of ${next.order.size}"
    val zone = next.layout.zoneOf(card) ?: return "Moved $name"
    val cards = next.layout[zone]
    return "Moved $name to ${zoneName(zone)}, ${cards.indexOf(card) + 1} of ${cards.size}"
}

/** The phone order's one place, as its `data-zone` says. */
internal const val PHONE_PLACE = "phone"

/**
 * Lets the compact cards be dragged while edit mode is shown. [onDrag] hears the drag as it goes, and null when it
 * ends; [onDrop] hears a release over a place. Returns a function that stops it and calls off a drag in progress.
 */
internal fun installTodayDrag(
    onDrag: (TodayDrag?) -> Unit,
    onDrop: (TodayCard, TodayDropTarget) -> Unit,
): () -> Unit =
    installPointerDrag(
        pick = ::handleCard,
        start = { item, x, y ->
            TodayCard.fromId(item.getAttribute("data-card"))?.let { card -> TodayDragSession(card, item, x, y, onDrag, onDrop) }
        },
        scrollArea = ::scrollArea,
    )

/** The compact card whose head [target] is in, or null: the select, buttons, switch and checkboxes are not handles. */
private fun handleCard(target: Element): Element? {
    if (target.closest(".ledit-card__main") == null) return null
    return target.closest(".ledit-zone .ledit-card[data-card]")
}

/**
 * The part of the window the places show in: under the edit bar and a due reminder's banner, which both stay at the
 * top, and over the status line.
 */
private fun scrollArea(): Pair<Double, Double> {
    val height = window.innerHeight.toDouble()
    val bar = document.querySelector(".ledit-bar")?.getBoundingClientRect()
    val banner = document.getElementById("banner")?.getBoundingClientRect()
    val line = document.querySelector(".line")?.getBoundingClientRect()
    val top = maxOf(0.0, bar?.bottom ?: 0.0, banner?.bottom ?: 0.0)
    val bottom = if (line != null && line.height > 0) min(height, line.top) else height
    return top to bottom
}

/** One drag of [card]: the dimmed card stays in place (state), a copy of its head follows the pointer (DOM). */
private class TodayDragSession(
    private val card: TodayCard,
    item: Element,
    x: Double,
    y: Double,
    private val onDrag: (TodayDrag?) -> Unit,
    private val onDrop: (TodayCard, TodayDropTarget) -> Unit,
) : DragSession {
    private val ghost = document.createElement("div") as HTMLElement
    private val offsetX: Double
    private val offsetY: Double
    private var target: TodayDropTarget? = null

    init {
        // A copy of the card's own element keeps its kind (data-k) and look; it holds only the head.
        val shell = item.cloneNode(false) as HTMLElement
        shell.removeAttribute("data-card")
        shell.classList.remove("is-dragging")
        shell.classList.add("ledit-ghost__card")
        shell.setAttribute("aria-hidden", "true")
        item.querySelector(".ledit-card__main")?.let { shell.appendChild(it.cloneNode(true)) }
        ghost.appendChild(shell)
        ghost.classList.add("ledit-ghost")
        document.body?.appendChild(ghost)
        val rect = item.getBoundingClientRect()
        offsetX = (x - rect.left).coerceIn(0.0, max(0.0, shell.offsetWidth - GHOST_GRAB_INSET_PX))
        offsetY = (y - rect.top).coerceIn(0.0, max(0.0, shell.offsetHeight - GHOST_GRAB_INSET_PX))
        document.documentElement?.classList?.add(DRAGGING_CLASS)
        onDrag(TodayDrag(card, null))
    }

    override fun move(
        x: Double,
        y: Double,
    ) {
        ghost.style.transform = "translate(${x - offsetX}px, ${y - offsetY}px)"
        val next = dropTargetAt(x, y)
        if (next != target) {
            target = next
            onDrag(TodayDrag(card, next))
        }
    }

    override fun drop(
        x: Double,
        y: Double,
    ) {
        val at = dropTargetAt(x, y)
        end()
        if (at != null) onDrop(card, at)
    }

    override fun cancel() = end()

    private fun end() {
        ghost.remove()
        document.documentElement?.classList?.remove(DRAGGING_CLASS)
        onDrag(null)
    }

    /** The place under ([x], [y]) and the position there, by the middles of its other cards. Null outside every place. */
    private fun dropTargetAt(
        x: Double,
        y: Double,
    ): TodayDropTarget? {
        val places = document.querySelectorAll(".ledit-zone[data-zone]")
        for (i in 0 until places.length) {
            val place = places.item(i) as? Element ?: continue
            val box = place.getBoundingClientRect()
            if (x < box.left || x > box.right || y < box.top || y > box.bottom) continue
            val id = place.getAttribute("data-zone") ?: continue
            val cards = place.querySelectorAll(".ledit-card[data-card]")
            val middles =
                (0 until cards.length).mapNotNull { j ->
                    val other = cards.item(j) as? Element
                    if (other == null || other.getAttribute("data-card") == card.id) {
                        null
                    } else {
                        other.getBoundingClientRect().let { it.top + it.height / 2 }
                    }
                }
            return TodayDropTarget(id, dropIndex(middles, y))
        }
        return null
    }
}

/** On the page's root while a card is dragged: the grabbing cursor everywhere, no text selection. */
private const val DRAGGING_CLASS = "ledit-dragging"

/** The copy is held at least this far inside its right and bottom edges, so it stays under the pointer. */
private const val GHOST_GRAB_INSET_PX = 16.0
