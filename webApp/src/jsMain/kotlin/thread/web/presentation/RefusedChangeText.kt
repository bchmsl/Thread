package thread.web.presentation

import thread.domain.note.NoteSaveState
import thread.domain.note.UNTITLED_NOTE
import thread.domain.topic.topicInfo
import thread.domain.workspace.RefusedChange
import thread.domain.workspace.WorkspaceWrite
import thread.web.presentation.notes.notePlace
import thread.web.presentation.topics.categoryLabel
import thread.web.presentation.topics.topicPlace

/*
 * How the sync panel names a change the server refused (owner-review-queue Q14), and the text its Copy button puts on
 * the clipboard so nothing typed is lost when the person discards the change.
 */

/** The sync panel entry for [change], with Retry, Copy and Discard. */
internal fun WorkspaceModel.refusedProblem(change: RefusedChange): SyncProblem {
    val write = change.writes.first()
    val (subject, place) = subjectOf(write)
    val done = if (write is WorkspaceWrite.DeleteForever) "deleted forever" else "saved"
    val text =
        if (change.unconfirmed) {
            "Could not confirm that $subject was $done. The server may have refused it after a reload, or another device changed it since."
        } else {
            "${subject.capitalized()} was not $done. The server refused the change."
        }
    val related = change.writes.size - 1
    val label = if (related > 0) "$text It came with ${countOf(related, "related change")}." else text
    return SyncProblem(label, NoteSaveState.SyncFailed, place, RefusedItem(change, subject.capitalized(), copyText(change, label)))
}

/** What [write] changes, as the panel names it, and where to see it. */
private fun WorkspaceModel.subjectOf(write: WorkspaceWrite): Pair<String, Place?> =
    when (write) {
        is WorkspaceWrite.PutTopic -> "Topic “${write.topic.name}”" to topicPlace(write.topic)
        is WorkspaceWrite.PutStatus -> "status “${write.status.name}”" to Place.of("settings", "statuses")
        is WorkspaceWrite.PutPrefs -> "the preferences change" to Place.of("settings", "prefs")
        is WorkspaceWrite.PutTask ->
            "Task “${write.task.title}”" to if (write.isNew) null else taskPlace(View.Today.place, write.task.id)
        is WorkspaceWrite.PutReminder -> "reminder “${write.reminder.text}”" to null
        is WorkspaceWrite.PutNote ->
            "where “${noteTitle(write.note.id.value)}” is filed" to
                if (write.isNew) null else notePlace(write.note.id)
        is WorkspaceWrite.PutNoteLink -> {
            val topic = storedTopic(write.link.topicId.value)?.name?.let { "“$it”" } ?: "a Topic"
            "the link between “${noteTitle(write.link.noteId.value)}” and $topic" to notePlace(write.link.noteId)
        }
        is WorkspaceWrite.PutExternalRef -> "the link to ${write.reference.key}" to null
        is WorkspaceWrite.PutExternalLink -> {
            val topic = storedTopic(write.link.topicId.value)
            val key = write.link.referenceId.substringAfterLast('~')
            val name = topic?.name?.let { "“$it”" } ?: "a Topic"
            val what =
                if (write.link.linked ||
                    write.link.dismissed == null
                ) {
                    "the link between $key and $name"
                } else {
                    "whether $key is suggested in $name"
                }
            what to topic?.let(::topicPlace)
        }
        is WorkspaceWrite.DeleteForever -> "“${write.title}”" to View.Trash.place
    }

/** The panel text, then what each write would have stored, one fact per line. */
private fun WorkspaceModel.copyText(
    change: RefusedChange,
    label: String,
): String =
    buildList {
        add(label)
        change.writes.forEach { write -> addAll(linesOf(write)) }
    }.joinToString("\n")

private fun WorkspaceModel.linesOf(write: WorkspaceWrite): List<String> =
    when (write) {
        is WorkspaceWrite.PutTopic ->
            listOfNotNull(
                "Topic: ${write.topic.name}",
                lineOf("Goal", write.topic.goal),
                lineOf("Additional information", topicInfo(write.topic)),
                "In Trash".takeIf { write.topic.deleted },
            )
        is WorkspaceWrite.PutStatus -> listOf("Status: ${write.status.name} (${categoryLabel(write.status.category)})")
        is WorkspaceWrite.PutPrefs ->
            listOf(
                "Preferences: Topics opens as ${write.prefs.topicsView.id}, capture makes a ${write.prefs.captureType.id}, " +
                    "keep completed tasks ${if (write.prefs.keepCompleted) "on" else "off"}, time zone ${write.prefs.timeZone}",
            )
        is WorkspaceWrite.PutTask ->
            listOfNotNull(
                "Task: ${write.task.title}",
                write.task.topicId
                    ?.let { storedTopic(it.value)?.name }
                    ?.let { "Topic: $it" },
                write.task.plannedDay?.let { "Planned for: $it" },
                write.task.deadline?.let { "Deadline: $it" },
                "Done".takeIf { write.task.done },
                "In Trash".takeIf { write.task.deleted },
            )
        is WorkspaceWrite.PutReminder -> listOf("Reminder: ${write.reminder.text}, at ${write.reminder.at} (${write.reminder.zone})")
        is WorkspaceWrite.PutNote ->
            listOf("Note “${noteTitle(write.note.id.value)}”: ${if (write.note.inbox) "in Inbox" else "filed"}")
        is WorkspaceWrite.PutNoteLink -> {
            val topic = storedTopic(write.link.topicId.value)?.name ?: "a Topic"
            val how = if (write.link.linked) "linked to" else "unlinked from"
            listOf("Note “${noteTitle(write.link.noteId.value)}” $how $topic")
        }
        is WorkspaceWrite.PutExternalRef -> listOf("Linked record: ${write.reference.key}, ${write.reference.url.value}")
        is WorkspaceWrite.PutExternalLink -> {
            val topic = storedTopic(write.link.topicId.value)?.name ?: "a Topic"
            val key = write.link.referenceId.substringAfterLast('~')
            val line =
                when {
                    write.link.linked -> "$key linked to $topic"
                    write.link.dismissed == true -> "$key not linked to $topic and not suggested there"
                    write.link.dismissed == false -> "$key suggested in $topic again"
                    else -> "$key unlinked from $topic"
                }
            listOf(line)
        }
        is WorkspaceWrite.DeleteForever -> listOf("Delete forever: ${write.title}")
    }

/** "Label: value", or null for an empty value. */
private fun lineOf(
    label: String,
    value: String,
): String? = if (value.isBlank()) null else "$label: $value"

private fun WorkspaceModel.noteTitle(id: String): String = note(id)?.title?.ifBlank { null } ?: UNTITLED_NOTE

private fun String.capitalized(): String = replaceFirstChar { it.uppercaseChar() }

/** "1 related change", "3 related changes". */
private fun countOf(
    count: Int,
    noun: String,
): String = if (count == 1) "1 $noun" else "$count ${noun}s"
