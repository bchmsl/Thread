package thread.domain.workspace

import thread.domain.topic.StatusCategory

// Today's edit mode (#29 L3, D98). It moves cards inside the layout this window uses, the desktop layout or the phone
// order (D-9), and shows the hide choices Settings > Today has for each card, on the same fields (D-6). Nothing is saved
// until Done (D-4): [TodayLayoutEdit] is the draft, and [TodayLayoutEdit.done] makes the one prefs change Done writes.

/** The place [card] is in, or null when this layout does not hold it. */
fun TodayLayout.zoneOf(card: TodayCard): TodayZone? = TodayZone.entries.firstOrNull { card in this[it] }

/** This layout with [zone] holding [cards]. */
fun TodayLayout.with(
    zone: TodayZone,
    cards: List<TodayCard>,
): TodayLayout =
    when (zone) {
        TodayZone.Top -> copy(top = cards)
        TodayZone.Left -> copy(left = cards)
        TodayZone.Right -> copy(right = cards)
        TodayZone.Bottom -> copy(bottom = cards)
    }

/** [card] moved to the end of [zone]. A card already there, or not in this layout, stays where it is. */
fun TodayLayout.movedTo(
    card: TodayCard,
    zone: TodayZone,
): TodayLayout {
    val from = zoneOf(card) ?: return this
    if (from == zone) return this
    val without = with(from, this[from] - card)
    return without.with(zone, without[zone] + card)
}

/** [card] one up inside its place. At the place's first position it stays (Move up is disabled there). */
fun TodayLayout.movedUp(card: TodayCard): TodayLayout {
    val zone = zoneOf(card) ?: return this
    return with(zone, this[zone].movedUp(card))
}

/** [card] one down inside its place. At the place's last position it stays (Move down is disabled there). */
fun TodayLayout.movedDown(card: TodayCard): TodayLayout {
    val zone = zoneOf(card) ?: return this
    return with(zone, this[zone].movedDown(card))
}

/** [card] one up in this order; the first stays. */
fun List<TodayCard>.movedUp(card: TodayCard): List<TodayCard> {
    val index = indexOf(card)
    return if (index <= 0) this else swapped(index, index - 1)
}

/** [card] one down in this order; the last stays. */
fun List<TodayCard>.movedDown(card: TodayCard): List<TodayCard> {
    val index = indexOf(card)
    return if (index < 0 || index == lastIndex) this else swapped(index, index + 1)
}

/** [card] first in this order. */
fun List<TodayCard>.movedFirst(card: TodayCard): List<TodayCard> = if (card in this) listOf(card) + (this - card) else this

/** [card] last in this order. */
fun List<TodayCard>.movedLast(card: TodayCard): List<TodayCard> = if (card in this) (this - card) + card else this

private fun List<TodayCard>.swapped(
    a: Int,
    b: Int,
): List<TodayCard> {
    val result = toMutableList()
    result[a] = this[b]
    result[b] = this[a]
    return result
}

/** Which of Today's layouts edit mode changes: the one the window used when it opened (D-9, A25). */
enum class TodayLayoutKind {
    /** The desktop layout, for windows 760 px wide or wider. */
    Desktop,

    /** The phone order, for windows 759 px wide or narrower. */
    Phone,
}

/**
 * Today's edit mode draft (D98): the layout being edited and the hide choices, each next to its value when edit mode
 * opened, so [done] writes only what changed here.
 *
 * [layout] is the whole desktop draft, [order] the whole phone draft; only the one [kind] names changes. [reset] says
 * Reset to default (desktop) or Match desktop (phone) was pressed and no card has moved since: Done then writes the
 * layout as not saved. [hidden] are the sections that hide while empty, [categories] the Topics shown.
 */
data class TodayLayoutEdit(
    val kind: TodayLayoutKind,
    val layout: TodayLayout,
    val order: List<TodayCard>,
    val reset: Boolean,
    val hidden: Set<TodayHideableSection>,
    val categories: Set<StatusCategory>,
    val start: TodayLayoutStart,
) {
    /**
     * Reset to default (desktop) or Match desktop (phone) would change something: a layout is saved, or a card moved
     * here. Disabled otherwise.
     */
    val canReset: Boolean
        get() =
            !reset &&
                when (kind) {
                    TodayLayoutKind.Desktop -> start.saved || layout != start.layout
                    TodayLayoutKind.Phone -> start.saved || order != start.order
                }

    fun movedTo(
        card: TodayCard,
        zone: TodayZone,
    ): TodayLayoutEdit = withLayout(layout.movedTo(card, zone))

    fun movedUp(card: TodayCard): TodayLayoutEdit =
        if (kind == TodayLayoutKind.Desktop) withLayout(layout.movedUp(card)) else withOrder(order.movedUp(card))

    fun movedDown(card: TodayCard): TodayLayoutEdit =
        if (kind == TodayLayoutKind.Desktop) withLayout(layout.movedDown(card)) else withOrder(order.movedDown(card))

    fun movedFirst(card: TodayCard): TodayLayoutEdit = withOrder(order.movedFirst(card))

    fun movedLast(card: TodayCard): TodayLayoutEdit = withOrder(order.movedLast(card))

    /** Reset to default (desktop) or Match desktop (phone): the layout as if none were saved, saved so on Done. */
    fun resetLayout(): TodayLayoutEdit =
        when (kind) {
            TodayLayoutKind.Desktop -> copy(layout = start.defaultLayout, reset = true)
            TodayLayoutKind.Phone -> copy(order = start.desktopOrder, reset = true)
        }

    /** [section] hides while empty, or not ("Hide when empty"). */
    fun withHidden(
        section: TodayHideableSection,
        hide: Boolean,
    ): TodayLayoutEdit = copy(hidden = if (hide) hidden + section else hidden - section)

    /** Topics in [category] show on Today, or not ("Topics shown"). */
    fun withCategory(
        category: StatusCategory,
        shown: Boolean,
    ): TodayLayoutEdit = copy(categories = if (shown) categories + category else categories - category)

    /**
     * The prefs Done saves, made from [latest], the prefs as stored now (another device may have changed them since edit
     * mode opened). Only what changed here is written:
     * - the layout, when a card moved (a layout not saved stays not saved when nothing moved), or as not saved after
     *   Reset or Match desktop;
     * - each "Hide when empty" switched here, applied to the latest set, so a section switched elsewhere keeps its value;
     * - "Topics shown", when it changed here.
     * The result equals [latest] when nothing changed.
     */
    fun done(latest: WorkspacePrefs): WorkspacePrefs {
        var next =
            when (kind) {
                TodayLayoutKind.Desktop ->
                    when {
                        reset -> latest.copy(todayLayout = null)
                        layout != start.layout -> latest.copy(todayLayout = layout)
                        else -> latest
                    }
                TodayLayoutKind.Phone ->
                    when {
                        reset -> latest.copy(todayPhoneLayout = null)
                        order != start.order -> latest.copy(todayPhoneLayout = order)
                        else -> latest
                    }
            }
        val switched = TodayHideableSection.entries.filter { (it in hidden) != (it in start.hidden) }
        if (switched.isNotEmpty()) {
            val stored = latest.todayHiddenWhenEmpty
            val merged = switched.fold(stored) { set, section -> if (section in hidden) set + section else set - section }
            next = next.copy(todayHideEmpty = merged)
        }
        if (categories != start.categories) next = next.copy(todayTopicCategories = categories)
        return next
    }

    private fun withLayout(next: TodayLayout): TodayLayoutEdit = if (next == layout) this else copy(layout = next, reset = false)

    private fun withOrder(next: List<TodayCard>): TodayLayoutEdit = if (next == order) this else copy(order = next, reset = false)

    companion object {
        /** Edit mode opening on [prefs], for the layout [kind] names. */
        fun start(
            prefs: WorkspacePrefs,
            kind: TodayLayoutKind,
        ): TodayLayoutEdit {
            val start =
                TodayLayoutStart(
                    saved = if (kind == TodayLayoutKind.Desktop) prefs.todayLayout != null else prefs.todayPhoneLayout != null,
                    layout = prefs.todayDesktopLayout,
                    order = prefs.todayPhoneOrder,
                    defaultLayout = defaultTodayLayout(prefs.meetingsLayout),
                    desktopOrder = prefs.todayDesktopLayout.inOrder,
                    hidden = prefs.todayHiddenWhenEmpty,
                    categories = prefs.todayTopicCategories,
                )
            return TodayLayoutEdit(kind, start.layout, start.order, reset = false, start.hidden, start.categories, start)
        }
    }
}

/**
 * What edit mode opened on: the values [TodayLayoutEdit.done] compares with. [saved]: the layout being edited was saved.
 * [defaultLayout] is what Reset to default draws, [desktopOrder] what Match desktop draws.
 */
data class TodayLayoutStart(
    val saved: Boolean,
    val layout: TodayLayout,
    val order: List<TodayCard>,
    val defaultLayout: TodayLayout,
    val desktopOrder: List<TodayCard>,
    val hidden: Set<TodayHideableSection>,
    val categories: Set<StatusCategory>,
)
