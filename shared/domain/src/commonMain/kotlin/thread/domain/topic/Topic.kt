package thread.domain.topic

import kotlin.jvm.JvmInline

/** A Topic's id. Made on the device that creates the Topic, so it works offline. */
@JvmInline
value class TopicId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A Topic id is not blank" }
    }

    override fun toString(): String = value
}

/** A status's id. The seed statuses have fixed ids (see [SEED_STATUSES]). */
@JvmInline
value class StatusId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "A status id is not blank" }
    }

    override fun toString(): String = value
}

/**
 * The four fixed meanings a status can have (01-product-spec.md, "Editable workspace-wide statuses"). Behavior comes
 * from the category, never from a status's name or color.
 */
enum class StatusCategory(
    val id: String,
) {
    Planned("planned"),
    Active("active"),
    Paused("paused"),
    Done("done"),
    ;

    companion object {
        fun fromId(id: String): StatusCategory? = entries.firstOrNull { it.id == id }
    }
}

/** A Topic's sticker color (D36). Pastels only; the color never carries meaning. */
enum class TopicColor(
    val id: String,
) {
    Lavender("lavender"),
    Mint("mint"),
    Sky("sky"),
    Peach("peach"),
    Butter("butter"),
    ;

    companion object {
        fun fromId(id: String): TopicColor? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A workspace-wide status. [sortOrder] places it in the workspace order, which the List groups and Board lanes
 * follow. A [deleted] status stays stored, so Undo can bring it back, but it is not shown or offered.
 */
data class TopicStatus(
    val id: StatusId,
    val name: String,
    val category: StatusCategory,
    val sortOrder: Int,
    val deleted: Boolean = false,
)

/**
 * One piece of work (01-product-spec.md, "Topic"). Only [name] is required. [leftOff] and [nextStep] are the
 * "Pick up here" fields. [createdAtMillis] orders Topics inside a status; it is null until the device has the
 * server's time or its own estimate of it.
 *
 * [deleted] marks a Topic in Trash (D42). Its Tasks, Reminders and Note links stay as stored and are hidden with it,
 * so Restore brings all of them back (adr/ADR-012-trash-and-search.md).
 */
data class Topic(
    val id: TopicId,
    val name: String,
    val color: TopicColor,
    val statusId: StatusId,
    val goal: String = "",
    val leftOff: String = "",
    val nextStep: String = "",
    val createdAtMillis: Long? = null,
    val deleted: Boolean = false,
    val deletedAtMillis: Long? = null,
)
