package thread.domain.workspace

// Where Today places its cards (#29, D95). A window 760 px wide or wider uses the desktop layout, four places; one
// 759 px wide or narrower, where Today is one column, uses the phone order (D-9). Each [id] is what is stored and synced,
// so it never changes. Every card has a place in both. Whether a card is drawn is decided by the hide choices Today
// already has (Settings > Today), never by the layout.

/** A card on Today. The Tasks card holds every task group and moves as one. */
enum class TodayCard(
    val id: String,
) {
    Meetings("meetings"),
    Tasks("tasks"),
    Reminders("reminders"),
    ReviewRequested("review"),
    Topics("topics"),
    ;

    companion object {
        fun fromId(id: String?): TodayCard? = entries.firstOrNull { it.id == id }
    }
}

/** A place on Today's desktop layout. [Top] and [Bottom] run across the page; [Left] and [Right] are its two columns. */
enum class TodayZone(
    val id: String,
) {
    Top("top"),
    Left("left"),
    Right("right"),
    Bottom("bottom"),
    ;

    companion object {
        fun fromId(id: String?): TodayZone? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Today's desktop layout: the cards in each [TodayZone], in order. As saved it may miss a card or repeat one;
 * [todayDesktopLayout] reads it whole.
 */
data class TodayLayout(
    val top: List<TodayCard> = emptyList(),
    val left: List<TodayCard> = emptyList(),
    val right: List<TodayCard> = emptyList(),
    val bottom: List<TodayCard> = emptyList(),
) {
    operator fun get(zone: TodayZone): List<TodayCard> =
        when (zone) {
            TodayZone.Top -> top
            TodayZone.Left -> left
            TodayZone.Right -> right
            TodayZone.Bottom -> bottom
        }

    /** The cards read Top, Left, Right, Bottom: how one column shows this layout. */
    val inOrder: List<TodayCard>
        get() = top + left + right + bottom

    /** True when exactly one of the two columns has a card, so that one takes the full width (D77). */
    val oneSide: Boolean
        get() = left.isEmpty() != right.isEmpty()

    /** This layout with only [cards], the ones Today draws now. A place left with none takes no space. */
    fun only(cards: Set<TodayCard>): TodayLayout =
        TodayLayout(top.filter { it in cards }, left.filter { it in cards }, right.filter { it in cards }, bottom.filter { it in cards })

    companion object {
        /** A layout built from [zones], in the order given. */
        fun of(zones: Map<TodayZone, List<TodayCard>>): TodayLayout =
            TodayLayout(
                zones[TodayZone.Top].orEmpty(),
                zones[TodayZone.Left].orEmpty(),
                zones[TodayZone.Right].orEmpty(),
                zones[TodayZone.Bottom].orEmpty(),
            )
    }
}

/**
 * Today's layout before #29, and the layout of an account that has not saved one: the meetings lead the right column, or
 * run across the top when [meetings] is Horizontal (D79); the Tasks on the left; Reminders and Review requested on the
 * right; the Topics below.
 */
fun defaultTodayLayout(meetings: MeetingsLayout): TodayLayout =
    when (meetings) {
        MeetingsLayout.Vertical ->
            TodayLayout(
                left = listOf(TodayCard.Tasks),
                right = listOf(TodayCard.Meetings, TodayCard.Reminders, TodayCard.ReviewRequested),
                bottom = listOf(TodayCard.Topics),
            )
        MeetingsLayout.Horizontal ->
            TodayLayout(
                top = listOf(TodayCard.Meetings),
                left = listOf(TodayCard.Tasks),
                right = listOf(TodayCard.Reminders, TodayCard.ReviewRequested),
                bottom = listOf(TodayCard.Topics),
            )
    }

/**
 * The desktop layout Today uses: [WorkspacePrefs.todayLayout] made whole, or [defaultTodayLayout] when none is saved.
 * A card saved twice keeps its first place, read Top, Left, Right, Bottom. A card not saved goes to its default place,
 * at its default position there or at the end when the place holds fewer cards.
 */
val WorkspacePrefs.todayDesktopLayout: TodayLayout
    get() {
        val default = defaultTodayLayout(meetingsLayout)
        val saved = todayLayout ?: return default
        val seen = mutableSetOf<TodayCard>()
        val zones = TodayZone.entries.associateWith { zone -> saved[zone].filter { seen.add(it) } }
        return TodayLayout.of(
            zones.mapValues { (zone, cards) -> withMissing(cards, default[zone].filter { it !in seen }, default[zone]) },
        )
    }

/**
 * The one-column order Today uses at 759 px and below (D-9): [WorkspacePrefs.todayPhoneLayout] made whole, or the
 * desktop layout read Top, Left, Right, Bottom when none is saved, which is how one column looked before #29. A card
 * saved twice keeps its first position; a card not saved goes where the desktop order has it, or at the end.
 */
val WorkspacePrefs.todayPhoneOrder: List<TodayCard>
    get() {
        val desktop = todayDesktopLayout.inOrder
        val saved = todayPhoneLayout?.distinct() ?: return desktop
        return withMissing(saved, desktop.filter { it !in saved }, desktop)
    }

/** [cards] with each of [missing] put back at its index in [order], or at the end when [cards] is shorter. */
private fun withMissing(
    cards: List<TodayCard>,
    missing: List<TodayCard>,
    order: List<TodayCard>,
): List<TodayCard> {
    val result = cards.toMutableList()
    for (card in missing) result.add(order.indexOf(card).coerceAtMost(result.size), card)
    return result
}
