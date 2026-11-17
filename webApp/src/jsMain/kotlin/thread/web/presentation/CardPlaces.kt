package thread.web.presentation

import thread.domain.external.JiraIssueKey
import thread.domain.external.PullRequestRef
import thread.web.presentation.external.JIRA_KEY_PARAM
import thread.web.presentation.external.PR_ID_PARAM
import thread.web.presentation.external.jiraPlace
import thread.web.presentation.external.prPlace
import thread.web.presentation.notes.NOTE_PARAM

/**
 * The kinds of item that open as a card on top of the current page (D70), and the URL parameter that opens each:
 * `?task=<id>`, `?note=<id>`, `?jira=<KEY>`, `?pr=<owner/repo#n>`, `?topic=<id>`, `?meeting=<id>`.
 */
internal enum class CardType(
    val param: String,
) {
    Task(TASK_PARAM),
    Note("note"),
    Jira("jira"),
    Pr("pr"),
    Topic("topic"),

    /** A meeting from the Mac app (D78). It has no page of its own. */
    Meeting("meeting"),
}

/** One card: its kind and the item's id as the URL keeps it. */
internal data class CardItem(
    val type: CardType,
    val id: String,
)

private val CARD_PARAMS = CardType.entries.map { it.param }.toSet()

/** The card this place shows. One shows at a time; a link that names several shows the first kind in [CardType]. */
internal val Place.card: CardItem?
    get() = CardType.entries.firstNotNullOfOrNull { type -> params[type.param]?.takeIf { it.isNotEmpty() }?.let { CardItem(type, it) } }

internal val Place.hasCard: Boolean get() = card != null

/** The page under the card. */
internal fun Place.withoutCards(): Place = copy(params = params - CARD_PARAMS)

/** This page with [item] as its only card. */
internal fun Place.withCard(item: CardItem): Place = withoutCards().with(item.type.param, item.id)

/**
 * Whether a Topic link here opens the Topic's page: only in the Topics tab, its own tab, and not from a card. Anywhere
 * else a Topic opens as a card (D70, owner answer Q1).
 */
internal fun topicOpensAsPage(place: Place): Boolean = !place.hasCard && View.of(place) == View.Topics

/**
 * Whether [page] already shows [item] itself: the Notes tab with that Note open, that Topic's page, the Jira tab with
 * that ticket open, the Pull requests tab with that pull request open. A card would then show it twice, and two
 * editors on one Note could write over each other.
 */
internal fun cardShownByPage(
    page: Place,
    item: CardItem,
): Boolean =
    when (item.type) {
        CardType.Task, CardType.Meeting -> false
        CardType.Note -> page.head == View.Notes.id && page.param(NOTE_PARAM) == item.id
        CardType.Topic -> page.head == "topic" && page.segment(1) == item.id
        CardType.Jira -> page.head == View.Jira.id && page.param(JIRA_KEY_PARAM)?.let(JiraIssueKey::parse)?.value == item.id
        CardType.Pr -> page.head == View.Prs.id && page.param(PR_ID_PARAM) == item.id
    }

/**
 * The item a link shows, as a card: the card it names, else the Topic, Note, Jira ticket or pull request its page
 * shows. Null for a view or a setting.
 */
internal fun cardFor(place: Place): CardItem? {
    place.card?.let { return it }

    fun item(
        type: CardType,
        id: String?,
    ) = id?.takeIf { it.isNotEmpty() }?.let { CardItem(type, it) }
    return when (place.head) {
        "topic" -> item(CardType.Topic, place.segment(1))
        View.Notes.id -> item(CardType.Note, place.param(NOTE_PARAM))
        View.Jira.id -> item(CardType.Jira, place.param(JIRA_KEY_PARAM)?.let(JiraIssueKey::parse)?.value)
        View.Prs.id -> item(CardType.Pr, place.param(PR_ID_PARAM))
        else -> null
    }
}

/**
 * Where an old item page link (`#/item/jira/KEY`, `#/item/pr/owner/repo/n`, before D70) goes now: the item in its own
 * tab, or the tab when the link is broken. Null for any other place.
 */
internal fun legacyItemPlace(
    place: Place,
    inReview: (PullRequestRef) -> Boolean,
): Place? {
    if (place.head != "item") return null
    return when (place.segment(1)) {
        "jira" -> place.segment(2)?.let(JiraIssueKey::parse)?.let(::jiraPlace) ?: View.Jira.place
        "pr" -> {
            val ref =
                PullRequestRef
                    .parse("${place.segment(2)}/${place.segment(3)}#${place.segment(4)}")
                    ?.takeIf { place.path.size == 5 }
            if (ref != null) prPlace(ref, review = inReview(ref)) else View.Prs.place
        }
        else -> View.Today.place
    }
}
