package thread.web.presentation.tasks

import thread.domain.topic.StatusCategory
import thread.web.presentation.topics.categoryLabel

/*
 * The words Today's Topics card uses for the categories Settings > Today "Topics shown" picks (D89). The categories are
 * always named in their fixed order: Planned, Active, Paused, Done.
 */

/** The card's title: "Active and paused Topics" by default, "Planned Topics" for one, "All Topics" for all four. */
internal fun todayTopicsTitle(categories: Set<StatusCategory>): String =
    when (categories.size) {
        0 -> "Topics"
        StatusCategory.entries.size -> "All Topics"
        else -> listed(names(categories).map { it.lowercase() }, "and").replaceFirstChar(Char::uppercaseChar) + " Topics"
    }

/** The picked categories as one choice, for "in the … category": "Active or Paused" by default. */
internal fun todayCategoryChoice(categories: Set<StatusCategory>): String = listed(names(categories), "or")

/** The empty card's line: "No active or paused Topics" by default, "No Topics" for all four. */
internal fun todayNoTopicsText(categories: Set<StatusCategory>): String =
    if (categories.size == StatusCategory.entries.size) {
        "No Topics"
    } else {
        "No " + listed(names(categories).map { it.lowercase() }, "or") + " Topics"
    }

/** Why the card is empty, and what fills it. */
internal fun todayNoTopicsWhy(categories: Set<StatusCategory>): String =
    if (categories.size == StatusCategory.entries.size) {
        "Every Topic shows here, whatever its status."
    } else {
        "Topics show here when their status is in the ${todayCategoryChoice(categories)} category."
    }

private fun names(categories: Set<StatusCategory>): List<String> = StatusCategory.entries.filter { it in categories }.map(::categoryLabel)

/** "A", "A and B", "A, B and C": no comma before the last word, as the rest of the app writes lists. */
private fun listed(
    names: List<String>,
    word: String,
): String =
    when (names.size) {
        0 -> ""
        1 -> names.single()
        else -> names.dropLast(1).joinToString(", ") + " $word " + names.last()
    }
