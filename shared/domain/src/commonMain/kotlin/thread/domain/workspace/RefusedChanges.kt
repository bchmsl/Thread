package thread.domain.workspace

import thread.domain.focus.FOCUS_HISTORY_LIMIT
import thread.domain.focus.FocusSession
import thread.domain.note.NoteSummary
import thread.domain.reminder.Reminder
import thread.domain.task.Task
import thread.domain.topic.Topic
import thread.domain.topic.TopicStatus

/*
 * Changes sent before a reload (owner-review-queue Q14, adr/ADR-009-workspace-entities-and-sync.md). The device copy
 * keeps unsent writes across a reload and sends them later, but their answer then reaches no one, so a refusal would
 * be silent. Once the server has answered every write from before the reload, the stored data shows whether each
 * change got through.
 */

/**
 * The changes in [leftovers] (sent before a reload, oldest first) that [data] does not show, marked unconfirmed.
 *
 * A field is checked only when no later change writes it, neither a later leftover nor one of [later] (the writes
 * made since the reload): the later value hides the earlier one. A change whose every field was written again later
 * counts as shown. Call it once the server has answered every write sent before the reload.
 */
fun unconfirmedChanges(
    leftovers: List<RefusedChange>,
    later: List<WorkspaceWrite>,
    data: WorkspaceData,
): List<RefusedChange> =
    leftovers.mapIndexedNotNull { index, change ->
        val after = leftovers.drop(index + 1).flatMap { it.writes } + later
        if (change.writes.all { isShown(it, data, after) }) null else change.copy(unconfirmed = true)
    }

private fun isShown(
    write: WorkspaceWrite,
    data: WorkspaceData,
    after: List<WorkspaceWrite>,
): Boolean {
    val stored = storedFields(write, data)
    // Delete forever leaves a tombstone, which the device copy leaves out.
    if (write is WorkspaceWrite.DeleteForever) return stored == null
    val document = documentOf(write)
    val laterWrites = after.filter { documentOf(it) == document }
    if (laterWrites.any { it is WorkspaceWrite.DeleteForever }) return true
    val rewritten = laterWrites.flatMap { writtenFields(it).keys }.toSet()
    val checked = writtenFields(write).filterKeys { it !in rewritten }
    if (checked.isEmpty()) return true
    if (stored == null) return false
    return checked.all { (field, value) -> stored[field] == value }
}

/** The document [write] changes, as `collection/id`. */
private fun documentOf(write: WorkspaceWrite): String =
    when (write) {
        is WorkspaceWrite.PutTopic -> "topics/${write.topic.id.value}"
        is WorkspaceWrite.PutStatus -> "statuses/${write.status.id.value}"
        is WorkspaceWrite.PutPrefs -> "prefs/main"
        is WorkspaceWrite.PutTask -> "tasks/${write.task.id.value}"
        is WorkspaceWrite.PutReminder -> "reminders/${write.reminder.id.value}"
        is WorkspaceWrite.PutNote -> "notes/${write.note.id.value}"
        is WorkspaceWrite.PutNoteLink -> "noteLinks/${write.link.id}"
        is WorkspaceWrite.PutExternalRef -> "externalRefs/${write.reference.id}"
        is WorkspaceWrite.PutExternalLink -> "externalLinks/${write.link.id}"
        is WorkspaceWrite.PutMeetingLink -> "meetingLinks/${write.link.id}"
        is WorkspaceWrite.PutFocusSession -> "focusSessions/${write.session.id}"
        is WorkspaceWrite.DeleteForever ->
            when (write.kind) {
                TrashKind.Topic -> "topics/${write.id}"
                TrashKind.Task -> "tasks/${write.id}"
                TrashKind.Reminder -> "reminders/${write.id}"
                TrashKind.Note -> "notes/${write.id}"
            }
    }

/** The fields [write] sets, by name: those that differ from its `before` value, or all of them without one. */
private fun writtenFields(write: WorkspaceWrite): Map<String, Any?> =
    when (write) {
        is WorkspaceWrite.PutTopic -> changed(write.topic.fields(), write.before?.takeUnless { write.isNew }?.fields())
        is WorkspaceWrite.PutStatus -> changed(write.status.fields(), write.before?.fields())
        is WorkspaceWrite.PutPrefs -> changed(write.prefs.fields(), write.before?.fields())
        is WorkspaceWrite.PutTask -> changed(write.task.fields(), write.before?.takeUnless { write.isNew }?.fields())
        is WorkspaceWrite.PutReminder -> changed(write.reminder.fields(), write.before?.takeUnless { write.isNew }?.fields())
        is WorkspaceWrite.PutNote -> changed(write.note.filing(), write.before?.takeUnless { write.isNew }?.filing())
        is WorkspaceWrite.PutNoteLink -> mapOf(LINKED to write.link.linked)
        is WorkspaceWrite.PutExternalRef -> mapOf(URL to write.reference.url)
        is WorkspaceWrite.PutExternalLink ->
            buildMap {
                put(LINKED, write.link.linked)
                write.link.dismissed?.let { put(DISMISSED, it) }
            }
        is WorkspaceWrite.PutMeetingLink ->
            buildMap {
                put(LINKED, write.link.linked)
                write.link.title?.let { put(TITLE, it) }
            }
        is WorkspaceWrite.PutFocusSession -> write.session.fields()
        is WorkspaceWrite.DeleteForever -> emptyMap()
    }

/** The stored fields of the document [write] changes, or null when [data] has no such document. */
private fun storedFields(
    write: WorkspaceWrite,
    data: WorkspaceData,
): Map<String, Any?>? =
    when (write) {
        is WorkspaceWrite.PutTopic -> data.topics.firstOrNull { it.id == write.topic.id }?.fields()
        is WorkspaceWrite.PutStatus -> data.statuses.firstOrNull { it.id == write.status.id }?.fields()
        is WorkspaceWrite.PutPrefs -> if (data.prefsStored) data.prefs.fields() else null
        is WorkspaceWrite.PutTask -> data.tasks.firstOrNull { it.id == write.task.id }?.fields()
        is WorkspaceWrite.PutReminder -> data.reminders.firstOrNull { it.id == write.reminder.id }?.fields()
        is WorkspaceWrite.PutNote -> data.notes.firstOrNull { it.id == write.note.id }?.filing()
        is WorkspaceWrite.PutNoteLink -> data.noteLinks.firstOrNull { it.id == write.link.id }?.let { mapOf(LINKED to it.linked) }
        is WorkspaceWrite.PutExternalRef -> data.externalRefs.firstOrNull { it.id == write.reference.id }?.let { mapOf(URL to it.url) }
        is WorkspaceWrite.PutExternalLink ->
            data.externalLinks.firstOrNull { it.id == write.link.id }?.let { mapOf(LINKED to it.linked, DISMISSED to it.dismissed) }
        is WorkspaceWrite.PutMeetingLink ->
            data.meetingLinks.firstOrNull { it.id == write.link.id }?.let { mapOf(LINKED to it.linked, TITLE to it.title) }
        is WorkspaceWrite.PutFocusSession ->
            (data.focusSessions.firstOrNull { it.id == write.session.id } ?: write.session.takeIf { beyondHistory(it, data) })?.fields()
        is WorkspaceWrite.DeleteForever ->
            when (write.kind) {
                TrashKind.Topic -> data.topics.firstOrNull { it.id.value == write.id }?.fields()
                TrashKind.Task -> data.tasks.firstOrNull { it.id.value == write.id }?.fields()
                TrashKind.Reminder -> data.reminders.firstOrNull { it.id.value == write.id }?.fields()
                TrashKind.Note -> data.notes.firstOrNull { it.id.value == write.id }?.filing()
            }
    }

/**
 * True when [session] is older than every session [data] holds while it holds the most it can: the bounded history
 * leaves it out, so it may well be stored. It counts as shown rather than as a false refusal.
 */
private fun beyondHistory(
    session: FocusSession,
    data: WorkspaceData,
): Boolean =
    data.focusSessions.size >= FOCUS_HISTORY_LIMIT &&
        session.endedAtMillis <= data.focusSessions.minOf { it.endedAtMillis }

private fun changed(
    after: Map<String, Any?>,
    before: Map<String, Any?>?,
): Map<String, Any?> = if (before == null) after else after.filter { (field, value) -> before[field] != value }

// The fields each store writes. Creation and update times are the server's and are not compared.

private fun Topic.fields(): Map<String, Any?> =
    mapOf(
        "name" to name,
        "color" to color,
        "statusId" to statusId,
        "goal" to goal,
        "leftOff" to leftOff,
        "nextStep" to nextStep,
        "info" to info,
        "deleted" to deleted,
    )

private fun TopicStatus.fields(): Map<String, Any?> =
    mapOf(
        "name" to name,
        "category" to category,
        "sortOrder" to sortOrder,
        "deleted" to deleted,
    )

private fun WorkspacePrefs.fields(): Map<String, Any?> =
    mapOf(
        "topicsView" to topicsView,
        "captureType" to captureType,
        "keepCompleted" to keepCompleted,
        "timeZone" to timeZone,
        "focusMin" to focusWorkMinutes,
        "breakMin" to focusBreakMinutes,
        "hideEmptySections" to hideEmptySections,
    )

private fun Task.fields(): Map<String, Any?> =
    mapOf(
        "title" to title,
        "topicId" to topicId,
        "done" to done,
        "completedOn" to completedOn,
        "plannedDay" to plannedDay,
        "deadline" to deadline,
        "inbox" to inbox,
        "deleted" to deleted,
        "discarded" to discarded,
    )

private fun Reminder.fields(): Map<String, Any?> =
    mapOf("at" to at, "zone" to zone, "text" to text, "target" to target, "dismissed" to dismissed, "deleted" to deleted)

/** Every field: a session never changes once made. */
private fun FocusSession.fields(): Map<String, Any?> =
    mapOf(
        "startedAt" to startedAtMillis,
        "endedAt" to endedAtMillis,
        "focusedMs" to focusedMillis,
        "plannedMin" to plannedMinutes,
        "outcome" to outcome,
        "target" to target,
    )

/** Only the filing fields: the title, preview and Trash state belong to Note sync. */
private fun NoteSummary.filing(): Map<String, Any?> = mapOf("inbox" to inbox, "discarded" to discarded)

private const val LINKED = "linked"
private const val DISMISSED = "dismissed"
private const val TITLE = "title"
private const val URL = "url"
