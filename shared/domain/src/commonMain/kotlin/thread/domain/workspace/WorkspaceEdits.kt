package thread.domain.workspace

import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.STATUS_NAME_MAX_LENGTH
import thread.domain.topic.StatusCategory
import thread.domain.topic.StatusId
import thread.domain.topic.TOPIC_INFO_MAX_LENGTH
import thread.domain.topic.TOPIC_NAME_MAX_LENGTH
import thread.domain.topic.TOPIC_TEXT_MAX_LENGTH
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicStatus
import thread.domain.topic.topicInfo
import thread.domain.topic.visibleStatuses

/** A change to write, and the change that takes it back for Undo. */
data class WorkspaceEdit(
    val writes: List<WorkspaceWrite>,
    val undo: List<WorkspaceWrite>,
)

/** Why an edit was not made. */
enum class EditProblem {
    BlankName,
    NameTooLong,
    DuplicateName,
    TextTooLong,
    UnknownStatus,
    CannotMove,
    LastStatus,
    ReplacementNeeded,
    BadReplacement,

    /** No status is stored yet: the workspace is still loading or not seeded. Status edits wait for it. */
    NotReady,
}

sealed interface EditResult {
    data class Changed(
        val edit: WorkspaceEdit,
    ) : EditResult

    /** The edit would change nothing, so nothing is written. */
    data object Unchanged : EditResult

    data class Refused(
        val problem: EditProblem,
    ) : EditResult
}

/** The writes that seed a new workspace with Planned, Active, Paused and Done. */
fun seedWrites(): List<WorkspaceWrite> = SEED_STATUSES.map { WorkspaceWrite.PutStatus(it) }

/*
 * Status edits (01-product-spec.md, "Editable workspace-wide statuses"). Each takes `stored`, every stored status
 * with the deleted ones, and works on the visible list. While nothing is stored, they are refused with
 * [EditProblem.NotReady]: an empty device cache may only mean the server has not answered yet, and writing the seed
 * then could overwrite the account's real statuses. Only the server's answer starts the seed (WorkspaceData.needsSeed).
 */

/** Adds a status at the end of the workspace order. The name is trimmed, at most 24 characters and unique. */
fun addStatus(
    stored: List<TopicStatus>,
    id: StatusId,
    name: String,
    category: StatusCategory,
): EditResult {
    val visible = visibleStatuses(stored)
    val trimmed = name.trim()
    statusNameProblem(trimmed, visible, except = null)?.let { return EditResult.Refused(it) }
    require(visible.none { it.id == id } && stored.none { it.id == id }) { "A new status needs a new id" }
    val order = (stored.ifEmpty { visible }.maxOfOrNull { it.sortOrder } ?: -1) + 1
    val status = TopicStatus(id, trimmed, category, order)
    return changed(
        stored,
        writes = listOf(WorkspaceWrite.PutStatus(status)),
        undo = listOf(statusChange(status, status.copy(deleted = true))),
    )
}

/** Renames a status. Topics keep it, so nothing about their behavior changes (acceptance example 5). */
fun renameStatus(
    stored: List<TopicStatus>,
    id: StatusId,
    name: String,
): EditResult {
    val visible = visibleStatuses(stored)
    val status = visible.firstOrNull { it.id == id } ?: return EditResult.Refused(EditProblem.UnknownStatus)
    val trimmed = name.trim()
    if (trimmed == status.name) return EditResult.Unchanged
    statusNameProblem(trimmed, visible, except = id)?.let { return EditResult.Refused(it) }
    return changed(
        stored,
        writes = listOf(statusChange(status, status.copy(name = trimmed))),
        undo = listOf(statusChange(status.copy(name = trimmed), status)),
    )
}

/** Gives a status another category. Its Topics move with it: they now behave as the new category says. */
fun changeStatusCategory(
    stored: List<TopicStatus>,
    id: StatusId,
    category: StatusCategory,
): EditResult {
    val status = visibleStatuses(stored).firstOrNull { it.id == id } ?: return EditResult.Refused(EditProblem.UnknownStatus)
    if (status.category == category) return EditResult.Unchanged
    return changed(
        stored,
        writes = listOf(statusChange(status, status.copy(category = category))),
        undo = listOf(statusChange(status.copy(category = category), status)),
    )
}

/** Moves a status [offset] places in the workspace order (v3 up and down buttons). */
fun moveStatus(
    stored: List<TopicStatus>,
    id: StatusId,
    offset: Int,
): EditResult {
    val visible = visibleStatuses(stored)
    val from = visible.indexOfFirst { it.id == id }
    if (from < 0) return EditResult.Refused(EditProblem.UnknownStatus)
    val to = from + offset
    if (offset == 0 || to !in visible.indices) return EditResult.Refused(EditProblem.CannotMove)
    val reordered = visible.toMutableList().apply { add(to, removeAt(from)) }
    // Renumber from 0, writing only the statuses whose place number changes.
    val moved = reordered.withIndex().filter { (index, status) -> status.sortOrder != index }
    return changed(
        stored,
        writes = moved.map { (index, status) -> statusChange(status, status.copy(sortOrder = index)) },
        undo = moved.map { (index, status) -> statusChange(status.copy(sortOrder = index), status) },
    )
}

/**
 * Deletes a status (03-architecture.md, ReplaceAndDeleteTopicStatus). The last status cannot be deleted. When Topics
 * use it, [replacement] is required, and they move there in the same write. No Topic is deleted.
 */
fun deleteStatus(
    stored: List<TopicStatus>,
    topics: List<Topic>,
    id: StatusId,
    replacement: StatusId?,
): EditResult {
    val visible = visibleStatuses(stored)
    val status = visible.firstOrNull { it.id == id } ?: return EditResult.Refused(EditProblem.UnknownStatus)
    if (visible.size <= 1) return EditResult.Refused(EditProblem.LastStatus)
    val users = topics.filter { it.statusId == id }
    val moved =
        if (users.isEmpty()) {
            emptyList()
        } else {
            if (replacement == null) return EditResult.Refused(EditProblem.ReplacementNeeded)
            if (replacement == id || visible.none { it.id == replacement }) return EditResult.Refused(EditProblem.BadReplacement)
            users.map { it to it.copy(statusId = replacement) }
        }
    val deleted = status.copy(deleted = true)
    return changed(
        stored,
        writes = listOf(statusChange(status, deleted)) + moved.map { (before, after) -> topicWrite(before, after) },
        undo = listOf(statusChange(deleted, status)) + moved.map { (before, after) -> topicWrite(after, before) },
    )
}

/** Moves [topic] to [statusId] (Board drag, "Move to", the Topic Details status picker). */
fun moveTopic(
    topic: Topic,
    statusId: StatusId,
    statuses: List<TopicStatus>,
): EditResult {
    if (topic.statusId == statusId) return EditResult.Unchanged
    if (statuses.none { it.id == statusId }) return EditResult.Refused(EditProblem.UnknownStatus)
    return topicChange(topic, topic.copy(statusId = statusId))
}

/**
 * The Edit Topic dialog: name (required), goal and sticker color. Text is trimmed, as in v3. [info] is its Additional
 * information field, there only while that section is hidden (D92, A9): null leaves the text as it is.
 */
fun editTopic(
    topic: Topic,
    name: String,
    goal: String,
    color: TopicColor,
    info: String? = null,
): EditResult {
    val trimmedName = name.trim()
    val trimmedGoal = goal.trim()
    val trimmedInfo = info?.trim()
    when {
        trimmedName.isEmpty() -> return EditResult.Refused(EditProblem.BlankName)
        trimmedName.length > TOPIC_NAME_MAX_LENGTH -> return EditResult.Refused(EditProblem.NameTooLong)
        trimmedGoal.length > TOPIC_TEXT_MAX_LENGTH -> return EditResult.Refused(EditProblem.TextTooLong)
        trimmedInfo != null && trimmedInfo.length > TOPIC_INFO_MAX_LENGTH -> return EditResult.Refused(EditProblem.TextTooLong)
    }
    // As in editInfo: text equal to what the section shows keeps the stored value, so the old text is not frozen.
    val nextInfo = if (trimmedInfo == null || trimmedInfo == topicInfo(topic)) topic.info else trimmedInfo
    return topicChange(topic, topic.copy(name = trimmedName, goal = trimmedGoal, color = color, info = nextInfo))
}

/**
 * The "Additional information" box (D52). Text is trimmed. Text equal to what the box shows writes nothing, so the old
 * "Pick up here" text is stored as [Topic.info] only once it is edited. Undo puts back the [Topic.info] before, null
 * included, so the old text shows again. The old fields are never written.
 */
fun editInfo(
    topic: Topic,
    text: String,
): EditResult {
    val trimmed = text.trim()
    if (trimmed.length > TOPIC_INFO_MAX_LENGTH) return EditResult.Refused(EditProblem.TextTooLong)
    if (trimmed == topicInfo(topic)) return EditResult.Unchanged
    return topicChange(topic, topic.copy(info = trimmed))
}

/**
 * The write that saves [next] as the preferences, or null when nothing changes. Until the account has a stored
 * preferences document, every field is written: the server accepts the document only whole.
 */
fun savePrefs(
    data: WorkspaceData?,
    next: WorkspacePrefs,
): WorkspaceWrite.PutPrefs? {
    val current = data?.prefs ?: WorkspacePrefs()
    if (next == current) return null
    return WorkspaceWrite.PutPrefs(next, before = current.takeIf { data?.prefsStored == true })
}

private fun topicChange(
    before: Topic,
    after: Topic,
): EditResult =
    if (after == before) {
        EditResult.Unchanged
    } else {
        EditResult.Changed(WorkspaceEdit(listOf(topicWrite(before, after)), listOf(topicWrite(after, before))))
    }

/** Writes [after], made from [before]: the store writes only the fields that differ. */
private fun topicWrite(
    before: Topic,
    after: Topic,
) = WorkspaceWrite.PutTopic(after, before = before)

private fun statusChange(
    before: TopicStatus,
    after: TopicStatus,
) = WorkspaceWrite.PutStatus(after, before = before)

private fun statusNameProblem(
    name: String,
    visible: List<TopicStatus>,
    except: StatusId?,
): EditProblem? =
    when {
        name.isEmpty() -> EditProblem.BlankName
        name.length > STATUS_NAME_MAX_LENGTH -> EditProblem.NameTooLong
        visible.any { it.id != except && it.name.equals(name, ignoreCase = true) } -> EditProblem.DuplicateName
        else -> null
    }

/** A status edit, refused while nothing is stored (see above). */
private fun changed(
    stored: List<TopicStatus>,
    writes: List<WorkspaceWrite>,
    undo: List<WorkspaceWrite>,
): EditResult = if (stored.isEmpty()) EditResult.Refused(EditProblem.NotReady) else EditResult.Changed(WorkspaceEdit(writes, undo))
