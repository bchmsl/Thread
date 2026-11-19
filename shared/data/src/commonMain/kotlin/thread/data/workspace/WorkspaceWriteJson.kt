package thread.data.workspace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import thread.data.external.arr
import thread.data.external.bool
import thread.data.external.int
import thread.data.external.long
import thread.data.external.obj
import thread.data.external.str
import thread.domain.calendar.MeetingAlternate
import thread.domain.calendar.MeetingLink
import thread.domain.calendar.MeetingTitleFit
import thread.domain.calendar.SeriesKey
import thread.domain.calendar.alternateTitleOf
import thread.domain.calendar.isAlternateJoinUrl
import thread.domain.calendar.isMeetingHash
import thread.domain.focus.FocusOutcome
import thread.domain.focus.FocusSession
import thread.domain.focus.FocusSettings
import thread.domain.focus.FocusTarget
import thread.domain.focus.FocusTargetKind
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
import thread.domain.workspace.DEFAULT_TODAY_TOPIC_CATEGORIES
import thread.domain.workspace.ExternalProvider
import thread.domain.workspace.ExternalReference
import thread.domain.workspace.InboxGroupBy
import thread.domain.workspace.MeetingsLayout
import thread.domain.workspace.MyPrsGroupBy
import thread.domain.workspace.PAST_MEETINGS_MAX
import thread.domain.workspace.ReviewPrsGroupBy
import thread.domain.workspace.TodayCard
import thread.domain.workspace.TodayGroupBy
import thread.domain.workspace.TodayHideableSection
import thread.domain.workspace.TodayLayout
import thread.domain.workspace.TodayZone
import thread.domain.workspace.TopicExternalLink
import thread.domain.workspace.TopicHideableSection
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
            is WorkspaceWrite.PutMeetingLink -> put("meetingLink", meetingLink(write.link))
            is WorkspaceWrite.PutMeetingAlternate ->
                put("meetingAlternate", meetingAlternate(write.alternate), write.before?.let(::meetingAlternate))
            is WorkspaceWrite.PutFocusSession -> put("focusSession", focusSession(write.session))
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
            "meetingLink" -> WorkspaceWrite.PutMeetingLink(meetingLink(value) ?: return null)
            "meetingAlternate" ->
                WorkspaceWrite.PutMeetingAlternate(
                    meetingAlternate(value) ?: return null,
                    before?.let { meetingAlternate(it) ?: return null },
                )
            "focusSession" -> WorkspaceWrite.PutFocusSession(focusSession(value) ?: return null)
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
            put("info", topic.info)
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
            // Absent in changes saved by builds before D52: never saved.
            info = json.str("info"),
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
            put("focusMin", prefs.focusWorkMinutes)
            put("breakMin", prefs.focusBreakMinutes)
            put("hideEmptySections", prefs.hideEmptySections)
            put("meetingsLayout", prefs.meetingsLayout.id)
            put("meetingTitleFit", prefs.meetingTitleFit.id)
            put("todayTopicsView", prefs.todayTopicsView?.id)
            put("todayHideEmpty", prefs.todayHideEmpty?.let { ids(it) { section -> section.id } } ?: JsonNull)
            put("todayGroupBy", prefs.todayGroupBy.id)
            put("todayPastMeetings", prefs.todayPastMeetings)
            put("todayTopicCategories", ids(prefs.todayTopicCategories) { it.id })
            put("inboxShowCompleted", prefs.inboxShowCompleted)
            put("inboxShowNotes", prefs.inboxShowNotes)
            put("inboxGroupBy", prefs.inboxGroupBy.id)
            put("topicShowCompleted", prefs.topicShowCompleted)
            put("topicHideEmpty", ids(prefs.topicHideEmpty) { it.id })
            put("topicGroupByDue", prefs.topicGroupByDue)
            put("jiraCurrentSprintOnly", prefs.jiraCurrentSprintOnly)
            put("prsMineGroupBy", prefs.prsMineGroupBy.id)
            put("prsReviewGroupBy", prefs.prsReviewGroupBy.id)
            put("todayLayout", prefs.todayLayout?.let(::layout) ?: JsonNull)
            put("todayPhoneLayout", prefs.todayPhoneLayout?.let(::cards) ?: JsonNull)
        }

    private fun layout(layout: TodayLayout): JsonObject = buildJsonObject { TodayZone.entries.forEach { put(it.id, cards(layout[it])) } }

    private fun cards(cards: List<TodayCard>): JsonArray = buildJsonArray { cards.forEach { add(JsonPrimitive(it.id)) } }

    /** A saved layout, its cards in order. A card or place this build does not know is left out. */
    private fun layout(json: JsonObject): TodayLayout =
        TodayLayout.of(TodayZone.entries.associateWith { zone -> json.strings(zone.id)?.mapNotNull(TodayCard::fromId).orEmpty() })

    private fun <E : Enum<E>> ids(
        choices: Set<E>,
        id: (E) -> String,
    ): JsonArray = buildJsonArray { choices.sortedBy { it.ordinal }.forEach { add(JsonPrimitive(id(it))) } }

    /** The strings in [key]'s list, or null when it holds no list. */
    private fun JsonObject.strings(key: String): List<String>? =
        arr(key)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }

    private fun prefs(json: JsonObject): WorkspacePrefs? =
        WorkspacePrefs(
            topicsView = TopicsView.fromId(json.str("topicsView")) ?: return null,
            captureType = CaptureKind.fromId(json.str("captureType")) ?: return null,
            keepCompleted = json.bool("keepCompleted") ?: return null,
            timeZone = json.str("timeZone") ?: return null,
            // Absent in changes saved by builds before the Focus timer: the defaults.
            focusWorkMinutes = json.int("focusMin") ?: FocusSettings.DEFAULT_WORK_MINUTES,
            focusBreakMinutes = json.int("breakMin") ?: FocusSettings.DEFAULT_BREAK_MINUTES,
            // Absent in changes saved by builds before D77: off.
            hideEmptySections = json.bool("hideEmptySections") ?: false,
            // Absent in changes saved by builds before D79: vertical.
            meetingsLayout = MeetingsLayout.fromId(json.str("meetingsLayout")) ?: MeetingsLayout.Vertical,
            // Absent in changes saved by builds before D97, or a value this build does not know: diagonal.
            meetingTitleFit = MeetingTitleFit.fromId(json.str("meetingTitleFit")) ?: MeetingTitleFit.Diagonal,
            // Absent in changes saved by builds before D83, so those leave these preferences alone: the defaults, and
            // not set where a value can be not set. A value this build does not know reads the same way.
            todayTopicsView = TopicsView.fromId(json.str("todayTopicsView")),
            todayHideEmpty = json.strings("todayHideEmpty")?.mapNotNull(TodayHideableSection::fromId)?.toSet(),
            todayGroupBy = TodayGroupBy.fromId(json.str("todayGroupBy")) ?: TodayGroupBy.DueDate,
            todayPastMeetings = json.int("todayPastMeetings")?.takeIf { it in 0..PAST_MEETINGS_MAX },
            todayTopicCategories =
                json.strings("todayTopicCategories")?.mapNotNull(StatusCategory::fromId)?.toSet()
                    ?: DEFAULT_TODAY_TOPIC_CATEGORIES,
            inboxShowCompleted = json.bool("inboxShowCompleted") ?: true,
            inboxShowNotes = json.bool("inboxShowNotes") ?: true,
            inboxGroupBy = InboxGroupBy.fromId(json.str("inboxGroupBy")) ?: InboxGroupBy.None,
            topicShowCompleted = json.bool("topicShowCompleted") ?: true,
            topicHideEmpty = json.strings("topicHideEmpty")?.mapNotNull(TopicHideableSection::fromId)?.toSet() ?: emptySet(),
            topicGroupByDue = json.bool("topicGroupByDue") ?: false,
            jiraCurrentSprintOnly = json.bool("jiraCurrentSprintOnly"),
            prsMineGroupBy = MyPrsGroupBy.fromId(json.str("prsMineGroupBy")) ?: MyPrsGroupBy.Repository,
            prsReviewGroupBy = ReviewPrsGroupBy.fromId(json.str("prsReviewGroupBy")) ?: ReviewPrsGroupBy.Repository,
            // Absent in changes saved by builds before D95: not set, Today's layout before #29.
            todayLayout = json.obj("todayLayout")?.let(::layout),
            todayPhoneLayout = json.strings("todayPhoneLayout")?.mapNotNull(TodayCard::fromId),
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

    private fun meetingLink(link: MeetingLink): JsonObject =
        buildJsonObject {
            put("topicId", link.topicId.value)
            put("seriesKey", link.seriesKey.value)
            put("linked", link.linked)
            link.title?.let { put("title", it) }
        }

    private fun meetingLink(json: JsonObject): MeetingLink? =
        MeetingLink(
            topicId = TopicId(json.str("topicId")?.takeIf { it.isNotBlank() } ?: return null),
            seriesKey = SeriesKey(json.str("seriesKey")?.takeIf(::isMeetingHash) ?: return null),
            linked = json.bool("linked") ?: return null,
            title = json.str("title"),
        )

    private fun meetingAlternate(alternate: MeetingAlternate): JsonObject =
        buildJsonObject {
            put("seriesKey", alternate.seriesKey.value)
            alternate.title?.let { put("title", it) }
            alternate.joinUrl?.let { put("joinUrl", it) }
        }

    private fun meetingAlternate(json: JsonObject): MeetingAlternate? {
        val title = json.str("title")
        val joinUrl = json.str("joinUrl")
        if (title != null && alternateTitleOf(title) != title) return null
        if (joinUrl != null && !isAlternateJoinUrl(joinUrl)) return null
        return MeetingAlternate(
            seriesKey = SeriesKey(json.str("seriesKey")?.takeIf(::isMeetingHash) ?: return null),
            title = title,
            joinUrl = joinUrl,
        )
    }

    private fun focusSession(session: FocusSession): JsonObject =
        buildJsonObject {
            put("id", session.id)
            put("startedAt", session.startedAtMillis)
            put("endedAt", session.endedAtMillis)
            put("focusedMs", session.focusedMillis)
            put("plannedMin", session.plannedMinutes)
            put("outcome", session.outcome.id)
            session.target?.let { target ->
                put("targetKind", target.kind.id)
                put("targetId", target.id)
            }
        }

    private fun focusSession(json: JsonObject): FocusSession? {
        val kind = json.str("targetKind")
        val target =
            if (kind == null) {
                null
            } else {
                FocusTarget(
                    FocusTargetKind.fromId(kind) ?: return null,
                    json.str("targetId")?.takeIf { it.isNotBlank() && it.length <= FocusTarget.MAX_ID_LENGTH } ?: return null,
                )
            }
        return FocusSession(
            id = json.str("id") ?: return null,
            startedAtMillis = json.long("startedAt") ?: return null,
            endedAtMillis = json.long("endedAt") ?: return null,
            focusedMillis = json.long("focusedMs") ?: return null,
            plannedMinutes = json.int("plannedMin") ?: return null,
            outcome = FocusOutcome.fromId(json.str("outcome")) ?: return null,
            target = target,
        )
    }

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
