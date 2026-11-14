package thread.data.workspace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import thread.data.external.bool
import thread.data.external.int
import thread.data.external.long
import thread.data.external.obj
import thread.data.external.str
import thread.domain.note.LinkHref
import thread.domain.note.NoteId
import thread.domain.note.NoteSummary
import thread.domain.note.NoteTopicLink
import thread.domain.reminder.Reminder
import thread.domain.reminder.ReminderId
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.task.Deadline
import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.StatusCategory
import thread.domain.topic.StatusId
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import thread.domain.topic.TopicStatus
import thread.domain.workspace.CaptureKind
import thread.domain.workspace.ExternalProvider
import thread.domain.workspace.ExternalReference
import thread.domain.workspace.TopicExternalLink
import thread.domain.workspace.TopicsView
import thread.domain.workspace.TrashKind
import thread.domain.workspace.WorkspacePrefs
import thread.domain.workspace.WorkspaceWrite

/**
 * [WorkspaceWrite]s as JSON, for the device-local outbox record ([WorkspaceOutbox]). Each write is
 * `{type, value, before?, isNew?}`. Decoding is strict: a write this version cannot read makes the whole list null,
 * because a change must never come back with a part missing.
 */
internal object WorkspaceWriteJson {
    fun encode(writes: List<WorkspaceWrite>): JsonArray = buildJsonArray { writes.forEach { add(encode(it)) } }

    /** The writes, or null when one of them cannot be read. */
    fun decode(array: JsonArray): List<WorkspaceWrite>? {
        val writes = array.map { (it as? JsonObject)?.let(::decode) ?: return null }
        return writes.takeIf { it.isNotEmpty() }
    }

    private fun encode(write: WorkspaceWrite): JsonObject =
        when (write) {
            is WorkspaceWrite.PutTopic -> put("topic", topic(write.topic), write.before?.let(::topic), write.isNew)
            is WorkspaceWrite.PutStatus -> put("status", status(write.status), write.before?.let(::status))
            is WorkspaceWrite.PutPrefs -> put("prefs", prefs(write.prefs), write.before?.let(::prefs))
            is WorkspaceWrite.PutTask -> put("task", task(write.task), write.before?.let(::task), write.isNew)
            is WorkspaceWrite.PutReminder -> put("reminder", reminder(write.reminder), write.before?.let(::reminder), write.isNew)
            is WorkspaceWrite.PutNote -> put("note", note(write.note), write.before?.let(::note), write.isNew)
            is WorkspaceWrite.PutNoteLink -> put("noteLink", noteLink(write.link))
            is WorkspaceWrite.PutExternalRef -> put("externalRef", externalRef(write.reference))
            is WorkspaceWrite.PutExternalLink -> put("externalLink", externalLink(write.link))
            is WorkspaceWrite.DeleteForever ->
                put(
                    "deleteForever",
                    buildJsonObject {
                        put("kind", write.kind.name)
                        put("id", write.id)
                        put("title", write.title)
                    },
                )
        }

    private fun put(
        type: String,
        value: JsonObject,
        before: JsonObject? = null,
        isNew: Boolean = false,
    ): JsonObject =
        buildJsonObject {
            put(TYPE, type)
            put(VALUE, value)
            if (before != null) put(BEFORE, before)
            if (isNew) put(IS_NEW, true)
        }

    private fun decode(json: JsonObject): WorkspaceWrite? {
        val value = json.obj(VALUE) ?: return null
        val before = json.obj(BEFORE)
        val isNew = json.bool(IS_NEW) ?: false
        return when (json.str(TYPE)) {
            "topic" -> WorkspaceWrite.PutTopic(topic(value) ?: return null, isNew, before?.let { topic(it) ?: return null })
            "status" -> WorkspaceWrite.PutStatus(status(value) ?: return null, before?.let { status(it) ?: return null })
            "prefs" -> WorkspaceWrite.PutPrefs(prefs(value) ?: return null, before?.let { prefs(it) ?: return null })
            "task" -> WorkspaceWrite.PutTask(task(value) ?: return null, isNew, before?.let { task(it) ?: return null })
            "reminder" -> WorkspaceWrite.PutReminder(reminder(value) ?: return null, isNew, before?.let { reminder(it) ?: return null })
            "note" -> WorkspaceWrite.PutNote(note(value) ?: return null, isNew, before?.let { note(it) ?: return null })
            "noteLink" -> WorkspaceWrite.PutNoteLink(noteLink(value) ?: return null)
            "externalRef" -> WorkspaceWrite.PutExternalRef(externalRef(value) ?: return null)
            "externalLink" -> WorkspaceWrite.PutExternalLink(externalLink(value) ?: return null)
            "deleteForever" -> {
                val kind = TrashKind.entries.firstOrNull { it.name == value.str("kind") } ?: return null
                WorkspaceWrite.DeleteForever(kind, value.str("id") ?: return null, value.str("title") ?: return null)
            }
            else -> null
        }
    }

    private fun topic(topic: Topic): JsonObject =
        buildJsonObject {
            put("id", topic.id.value)
            put("name", topic.name)
            put("color", topic.color.id)
            put("statusId", topic.statusId.value)
            put("goal", topic.goal)
            put("leftOff", topic.leftOff)
            put("nextStep", topic.nextStep)
            put("createdAtMillis", topic.createdAtMillis)
            put("deleted", topic.deleted)
            put("deletedAtMillis", topic.deletedAtMillis)
        }

    private fun topic(json: JsonObject): Topic? =
        Topic(
            id = TopicId(json.str("id") ?: return null),
            name = json.str("name") ?: return null,
            color = json.str("color")?.let(TopicColor::fromId) ?: return null,
            statusId = StatusId(json.str("statusId") ?: return null),
            goal = json.str("goal") ?: return null,
            leftOff = json.str("leftOff") ?: return null,
            nextStep = json.str("nextStep") ?: return null,
            createdAtMillis = json.long("createdAtMillis"),
            deleted = json.bool("deleted") ?: return null,
            deletedAtMillis = json.long("deletedAtMillis"),
        )

    private fun status(status: TopicStatus): JsonObject =
        buildJsonObject {
            put("id", status.id.value)
            put("name", status.name)
            put("category", status.category.id)
            put("sortOrder", status.sortOrder)
            put("deleted", status.deleted)
        }

    private fun status(json: JsonObject): TopicStatus? =
        TopicStatus(
            id = StatusId(json.str("id") ?: return null),
            name = json.str("name") ?: return null,
            category = json.str("category")?.let(StatusCategory::fromId) ?: return null,
            sortOrder = json.int("sortOrder") ?: return null,
            deleted = json.bool("deleted") ?: return null,
        )

    private fun prefs(prefs: WorkspacePrefs): JsonObject =
        buildJsonObject {
            put("topicsView", prefs.topicsView.id)
            put("captureType", prefs.captureType.id)
            put("keepCompleted", prefs.keepCompleted)
            put("timeZone", prefs.timeZone)
        }

    private fun prefs(json: JsonObject): WorkspacePrefs? =
        WorkspacePrefs(
            topicsView = TopicsView.fromId(json.str("topicsView")) ?: return null,
            captureType = CaptureKind.fromId(json.str("captureType")) ?: return null,
            keepCompleted = json.bool("keepCompleted") ?: return null,
            timeZone = json.str("timeZone") ?: return null,
        )

    private fun task(task: Task): JsonObject =
        buildJsonObject {
            put("id", task.id.value)
            put("title", task.title)
            put("topicId", task.topicId?.value)
            put("done", task.done)
            put("completedOn", task.completedOn?.toString())
            put("plannedDay", task.plannedDay?.toString())
            put("deadline", task.deadline?.toString())
            put("inbox", task.inbox)
            put("createdAtMillis", task.createdAtMillis)
            put("deleted", task.deleted)
            put("discarded", task.discarded)
            put("deletedAtMillis", task.deletedAtMillis)
        }

    private fun task(json: JsonObject): Task? =
        Task(
            id = TaskId(json.str("id") ?: return null),
            title = json.str("title") ?: return null,
            topicId = json.str("topicId")?.let(::TopicId),
            done = json.bool("done") ?: return null,
            completedOn = (json.optional("completedOn", LocalDay::parse) ?: return null).value,
            plannedDay = (json.optional("plannedDay", LocalDay::parse) ?: return null).value,
            deadline = (json.optional("deadline", Deadline::parse) ?: return null).value,
            inbox = json.bool("inbox") ?: return null,
            createdAtMillis = json.long("createdAtMillis"),
            deleted = json.bool("deleted") ?: return null,
            discarded = json.bool("discarded") ?: return null,
            deletedAtMillis = json.long("deletedAtMillis"),
        )

    private fun reminder(reminder: Reminder): JsonObject =
        buildJsonObject {
            put("id", reminder.id.value)
            put("at", reminder.at.toString())
            put("zone", reminder.zone)
            put("text", reminder.text)
            put("targetKind", reminder.target.kind.id)
            put("targetId", reminder.target.id)
            put("dismissed", reminder.dismissed)
            put("deleted", reminder.deleted)
            put("createdAtMillis", reminder.createdAtMillis)
            put("deletedAtMillis", reminder.deletedAtMillis)
        }

    private fun reminder(json: JsonObject): Reminder? {
        val targetId = json.str("targetId")?.takeIf { it.isNotBlank() } ?: return null
        return Reminder(
            id = ReminderId(json.str("id") ?: return null),
            at = json.str("at")?.let(WallTime::parse) ?: return null,
            zone = json.str("zone") ?: return null,
            text = json.str("text") ?: return null,
            target = ReminderTarget(TargetKind.fromId(json.str("targetKind")) ?: return null, targetId),
            dismissed = json.bool("dismissed") ?: return null,
            deleted = json.bool("deleted") ?: return null,
            createdAtMillis = json.long("createdAtMillis"),
            deletedAtMillis = json.long("deletedAtMillis"),
        )
    }

    private fun note(note: NoteSummary): JsonObject =
        buildJsonObject {
            put("id", note.id.value)
            put("title", note.title)
            put("preview", note.preview)
            put("inbox", note.inbox)
            put("inTrash", note.inTrash)
            put("discarded", note.discarded)
            put("createdAtMillis", note.createdAtMillis)
            put("updatedAtMillis", note.updatedAtMillis)
            put("pendingWrites", note.pendingWrites)
            put("fromConflict", note.fromConflict)
            put("deletedAtMillis", note.deletedAtMillis)
        }

    private fun note(json: JsonObject): NoteSummary? =
        NoteSummary(
            id = NoteId(json.str("id") ?: return null),
            title = json.str("title") ?: return null,
            preview = json.str("preview") ?: return null,
            inbox = json.bool("inbox") ?: return null,
            inTrash = json.bool("inTrash") ?: return null,
            discarded = json.bool("discarded") ?: return null,
            createdAtMillis = json.long("createdAtMillis"),
            updatedAtMillis = json.long("updatedAtMillis"),
            pendingWrites = json.bool("pendingWrites") ?: return null,
            fromConflict = json.bool("fromConflict") ?: return null,
            deletedAtMillis = json.long("deletedAtMillis"),
        )

    private fun noteLink(link: NoteTopicLink): JsonObject =
        buildJsonObject {
            put("topicId", link.topicId.value)
            put("noteId", link.noteId.value)
            put("linked", link.linked)
        }

    private fun noteLink(json: JsonObject): NoteTopicLink? =
        NoteTopicLink(
            topicId = TopicId(json.str("topicId") ?: return null),
            noteId = NoteId(json.str("noteId") ?: return null),
            linked = json.bool("linked") ?: return null,
        )

    private fun externalRef(reference: ExternalReference): JsonObject =
        buildJsonObject {
            put("provider", reference.provider.id)
            put("instance", reference.instance)
            put("key", reference.key)
            put("url", reference.url.value)
        }

    private fun externalRef(json: JsonObject): ExternalReference? =
        ExternalReference(
            provider = ExternalProvider.fromId(json.str("provider")) ?: return null,
            instance = json.str("instance") ?: return null,
            key = json.str("key") ?: return null,
            url = json.str("url")?.let(LinkHref::parse) ?: return null,
        )

    private fun externalLink(link: TopicExternalLink): JsonObject =
        buildJsonObject {
            put("topicId", link.topicId.value)
            put("referenceId", link.referenceId)
            put("linked", link.linked)
            link.dismissed?.let { put("dismissed", it) }
        }

    private fun externalLink(json: JsonObject): TopicExternalLink? =
        TopicExternalLink(
            topicId = TopicId(json.str("topicId") ?: return null),
            referenceId = json.str("referenceId") ?: return null,
            linked = json.bool("linked") ?: return null,
            dismissed = json.bool("dismissed"),
        )

    /** A nullable day or deadline: [Field] of null when absent, null when present but unreadable. */
    private class Field<T>(
        val value: T?,
    )

    private fun <T> JsonObject.optional(
        key: String,
        parse: (String) -> T?,
    ): Field<T>? {
        val raw = this[key]
        if (raw == null || raw == JsonNull) return Field(null)
        val text = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return parse(text)?.let { Field(it) }
    }

    private const val TYPE = "type"
    private const val VALUE = "value"
    private const val BEFORE = "before"
    private const val IS_NEW = "isNew"
}
