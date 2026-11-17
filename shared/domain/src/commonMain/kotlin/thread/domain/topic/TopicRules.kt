package thread.domain.topic

/** Longest status name (v3 settings, `maxlength="24"`). */
const val STATUS_NAME_MAX_LENGTH: Int = 24

/** Longest Topic name. v3 sets no limit; this one only keeps documents small. */
const val TOPIC_NAME_MAX_LENGTH: Int = 200

/** Longest goal, "Where I left off" or "Next step" text. */
const val TOPIC_TEXT_MAX_LENGTH: Int = 10_000

/** Longest "Additional information" text (D52): room for both old fields at their limit, with their labels. */
const val TOPIC_INFO_MAX_LENGTH: Int = 20_100

/**
 * The "Additional information" text of [topic] (D52, adr/ADR-018-topic-info-and-task-reminders.md): [Topic.info] once
 * it was saved, even empty. Before that, the old "Pick up here" text, each non-empty part with its label, so nothing
 * written there is hidden. Nothing is stored until the text is edited.
 */
fun topicInfo(topic: Topic): String =
    topic.info ?: listOfNotNull(
        topic.leftOff.takeIf { it.isNotBlank() }?.let { "Where I left off: $it" },
        topic.nextStep.takeIf { it.isNotBlank() }?.let { "Next step: $it" },
    ).joinToString("\n\n")

/**
 * The statuses of a new workspace: Planned, Active, Paused, Done (01-product-spec.md). The ids are fixed, so two
 * devices that seed the same new workspace write the same documents.
 */
val SEED_STATUSES: List<TopicStatus> =
    listOf(
        TopicStatus(StatusId("s-planned"), "Planned", StatusCategory.Planned, 0),
        TopicStatus(StatusId("s-active"), "Active", StatusCategory.Active, 1),
        TopicStatus(StatusId("s-paused"), "Paused", StatusCategory.Paused, 2),
        TopicStatus(StatusId("s-done"), "Done", StatusCategory.Done, 3),
    )

/**
 * The statuses to show and offer, in workspace order. Deleted ones are left out. Before the workspace has any status
 * stored (a new account whose seed is still on its way), the seed statuses stand in, so the pages work at once.
 */
fun visibleStatuses(stored: List<TopicStatus>): List<TopicStatus> {
    if (stored.isEmpty()) return SEED_STATUSES
    return stored.filterNot { it.deleted }.sortedWith(compareBy<TopicStatus> { it.sortOrder }.thenBy { it.id.value })
}

/**
 * The status [topic] shows. A Topic whose status is gone (deleted on another device, or not synced yet) shows the
 * first status, as in v3 (`statusOf`). [statuses] is the visible list and is never empty.
 */
fun statusOf(
    topic: Topic,
    statuses: List<TopicStatus>,
): TopicStatus = statuses.firstOrNull { it.id == topic.statusId } ?: statuses.first()

/** What [topic]'s status means. Pages and rules ask this, never the status name (01-product-spec.md). */
fun categoryOf(
    topic: Topic,
    statuses: List<TopicStatus>,
): StatusCategory = statusOf(topic, statuses).category

/** Topics in workspace status order, then oldest first inside a status (v3 `topicsInOrder`). */
fun topicsInOrder(
    topics: List<Topic>,
    statuses: List<TopicStatus>,
): List<Topic> {
    val index = statuses.withIndex().associate { (i, status) -> status.id to i }
    return topics.sortedWith(
        compareBy<Topic> { index[statusOf(it, statuses).id] ?: 0 }
            .thenBy { it.createdAtMillis ?: Long.MAX_VALUE }
            .thenBy { it.id.value },
    )
}

/** The Topics that show under one status. */
data class TopicGroup(
    val status: TopicStatus,
    val topics: List<Topic>,
)

/** The statuses Today groups its Topics by (owner change #1): those in the Active or Paused category, in workspace order. */
fun todayStatuses(statuses: List<TopicStatus>): List<TopicStatus> =
    statuses.filter { it.category == StatusCategory.Active || it.category == StatusCategory.Paused }

/**
 * Today's Topics, grouped by status (owner change #1): one group per status in [todayStatuses], in workspace order,
 * oldest Topic first inside a group. A status that no Topic uses has no group. A Topic whose status is gone counts
 * under the first status, as everywhere else ([statusOf]).
 */
fun todayTopicGroups(
    topics: List<Topic>,
    statuses: List<TopicStatus>,
): List<TopicGroup> {
    val byStatus = topicsInOrder(topics, statuses).groupBy { statusOf(it, statuses).id }
    return todayStatuses(statuses).mapNotNull { status -> byStatus[status.id]?.let { TopicGroup(status, it) } }
}

/** How many Topics use [status]. */
fun useCount(
    status: StatusId,
    topics: List<Topic>,
): Int = topics.count { it.statusId == status }

/** A new Topic starts in the first Planned status, or the first status when no status is Planned (v3 `createTopic`). */
fun statusForNewTopic(statuses: List<TopicStatus>): TopicStatus =
    statuses.firstOrNull { it.category == StatusCategory.Planned } ?: statuses.first()

/**
 * Sticker colors rotate in creation order (v3 `createTopic`), through every color once (D72). The first five are the
 * five colors in their old order, so the first five Topics get the colors they got before. Grey, the quietest, is last.
 */
private val NEW_TOPIC_COLORS: List<TopicColor> =
    listOf(
        TopicColor.Purple,
        TopicColor.Green,
        TopicColor.Blue,
        TopicColor.Orange,
        TopicColor.Yellow,
        TopicColor.Red,
        TopicColor.Pink,
        TopicColor.Cyan,
        TopicColor.Grey,
    )

/** The sticker color of a new Topic, given how many Topics there are (D72). */
fun colorForNewTopic(topicCount: Int): TopicColor = NEW_TOPIC_COLORS[topicCount.mod(NEW_TOPIC_COLORS.size)]

/**
 * A new Topic with only a name (01-product-spec.md: "A Topic can exist with only a name"). Returns null when the
 * trimmed name is blank or too long.
 */
fun newTopic(
    id: TopicId,
    name: String,
    statuses: List<TopicStatus>,
    topicCount: Int,
): Topic? {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || trimmed.length > TOPIC_NAME_MAX_LENGTH) return null
    return Topic(id = id, name = trimmed, color = colorForNewTopic(topicCount), statusId = statusForNewTopic(statuses).id)
}

/**
 * Completes [topic]: it moves to the first Done status (03-architecture.md, CompleteTopic). Returns null when it is
 * already done or no status is Done.
 */
fun completeTopic(
    topic: Topic,
    statuses: List<TopicStatus>,
): Topic? {
    if (statusOf(topic, statuses).category == StatusCategory.Done) return null
    val done = statuses.firstOrNull { it.category == StatusCategory.Done } ?: return null
    return topic.copy(statusId = done.id)
}

/**
 * Reopens a completed [topic] (01-product-spec.md: "A completed Topic can be reopened"): it moves to the first Active
 * status, else the first Planned one, else the first one that is not Done. Returns null when it is not done or no
 * such status exists.
 */
fun reopenTopic(
    topic: Topic,
    statuses: List<TopicStatus>,
): Topic? {
    if (statusOf(topic, statuses).category != StatusCategory.Done) return null
    val target =
        statuses.firstOrNull { it.category == StatusCategory.Active }
            ?: statuses.firstOrNull { it.category == StatusCategory.Planned }
            ?: statuses.firstOrNull { it.category != StatusCategory.Done }
            ?: return null
    return topic.copy(statusId = target.id)
}

/**
 * The two letters on a Topic's sticker (D36, v3 `T.mono`). Small words are skipped. One word gives its first two
 * letters; more words give the first letters of the first and last word. No word gives "?".
 */
fun monogram(name: String): String {
    val words = name.split(WHITESPACE).filter { it.isNotEmpty() && it.lowercase() !in MONOGRAM_STOP_WORDS }
    return when (words.size) {
        0 -> "?"
        1 -> words[0].take(2).uppercase()
        else -> (words.first().take(1) + words.last().take(1)).uppercase()
    }
}

private val WHITESPACE = Regex("\\s+")

private val MONOGRAM_STOP_WORDS = setOf("to", "the", "a", "an", "of", "and", "for", "in", "on", "with")
