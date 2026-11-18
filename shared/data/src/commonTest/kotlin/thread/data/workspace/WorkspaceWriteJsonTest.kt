package thread.data.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import thread.domain.calendar.MeetingLink
import thread.domain.calendar.SeriesKey
import thread.domain.focus.FocusOutcome
import thread.domain.focus.FocusSession
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
import thread.domain.workspace.ExternalProvider
import thread.domain.workspace.ExternalReference
import thread.domain.workspace.InboxGroupBy
import thread.domain.workspace.MeetingsLayout
import thread.domain.workspace.MyPrsGroupBy
import thread.domain.workspace.ReviewPrsGroupBy
import thread.domain.workspace.TodayCard
import thread.domain.workspace.TodayGroupBy
import thread.domain.workspace.TodayHideableSection
import thread.domain.workspace.TodayLayout
import thread.domain.workspace.TopicExternalLink
import thread.domain.workspace.TopicHideableSection
import thread.domain.workspace.TopicsView
import thread.domain.workspace.TrashKind
import thread.domain.workspace.WorkspacePrefs
import thread.domain.workspace.WorkspaceWrite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Every Topic, Task, Reminder, Note and link here is SYNTHETIC test data.
class WorkspaceWriteJsonTest {
    private val topic =
        Topic(
            TopicId("t-1"),
            "Synthetic topic",
            TopicColor.entries.last(),
            StatusId("s-active"),
            goal = "Synthetic goal",
            leftOff = "Synthetic left off",
            nextStep = "Synthetic next step",
            createdAtMillis = 1_700_000_000_000,
        )
    private val task =
        Task(
            TaskId("k-1"),
            "Synthetic task",
            topic.id,
            done = true,
            completedOn = LocalDay(2026, 11, 12),
            plannedDay = LocalDay(2026, 11, 13),
            deadline = Deadline(LocalDay(2026, 11, 14), minuteOfDay = 17 * 60 + 30),
            createdAtMillis = 1_700_000_000_001,
        )
    private val reminder =
        Reminder(
            ReminderId("r-1"),
            WallTime(LocalDay(2026, 11, 13), 9 * 60),
            "Asia/Tashkent",
            "Synthetic reminder",
            ReminderTarget(TargetKind.Task, task.id.value),
            dismissed = true,
            deletedAtMillis = 1_700_000_000_002,
        )
    private val note = NoteSummary(NoteId("n-1"), "Synthetic note", "Synthetic preview", inbox = true, updatedAtMillis = 5)
    private val reference =
        ExternalReference(ExternalProvider.GitHub, "synthetic/repo", "7", LinkHref.parse("https://github.com/synthetic/repo/pull/7")!!)

    private val series = SeriesKey("0123456789abcdef0123456789abcdef")

    private val focusSession =
        FocusSession(
            id = "f-run-1",
            startedAtMillis = 1_794_820_800_000,
            endedAtMillis = 1_794_822_300_000,
            focusedMillis = 1_500_000,
            plannedMinutes = 25,
            outcome = FocusOutcome.Completed,
            target = FocusTarget(FocusTargetKind.Topic, topic.id.value),
        )

    /** Every per-screen preference (D83) away from its default. */
    private val screenPrefs =
        WorkspacePrefs(
            todayTopicsView = TopicsView.Board,
            todayHideEmpty = setOf(TodayHideableSection.ReviewRequested, TodayHideableSection.Meetings),
            todayGroupBy = TodayGroupBy.Topic,
            todayPastMeetings = 0,
            todayTopicCategories = setOf(StatusCategory.Done, StatusCategory.Planned),
            inboxShowCompleted = false,
            inboxShowNotes = false,
            inboxGroupBy = InboxGroupBy.DueDate,
            topicShowCompleted = false,
            topicHideEmpty = TopicHideableSection.entries.toSet(),
            topicGroupByDue = true,
            jiraCurrentSprintOnly = false,
            prsMineGroupBy = MyPrsGroupBy.ReviewStatus,
            prsReviewGroupBy = ReviewPrsGroupBy.Author,
        )

    private val everyWrite =
        listOf(
            WorkspaceWrite.PutTopic(topic, isNew = true),
            WorkspaceWrite.PutTopic(topic.copy(name = "Renamed", deleted = true, deletedAtMillis = 9), before = topic),
            WorkspaceWrite.PutTopic(topic.copy(info = "Synthetic additional information"), before = topic),
            WorkspaceWrite.PutTopic(topic, before = topic.copy(info = "")),
            WorkspaceWrite.PutStatus(
                TopicStatus(StatusId("s-1"), "Building", StatusCategory.Active, 1),
                before = TopicStatus(StatusId("s-1"), "Active", StatusCategory.Active, 1),
            ),
            WorkspaceWrite.PutPrefs(WorkspacePrefs(TopicsView.Board, CaptureKind.Note, keepCompleted = false, timeZone = "Asia/Tbilisi")),
            WorkspaceWrite.PutPrefs(WorkspacePrefs(focusWorkMinutes = 50, focusBreakMinutes = 10)),
            WorkspaceWrite.PutPrefs(WorkspacePrefs(hideEmptySections = true), before = WorkspacePrefs()),
            WorkspaceWrite.PutPrefs(WorkspacePrefs(meetingsLayout = MeetingsLayout.Horizontal), before = WorkspacePrefs()),
            WorkspaceWrite.PutPrefs(screenPrefs, before = WorkspacePrefs()),
            // Back to not set, and an empty pick, which is not the same as not set.
            WorkspaceWrite.PutPrefs(WorkspacePrefs(todayHideEmpty = emptySet(), todayTopicCategories = emptySet()), before = screenPrefs),
            WorkspaceWrite.PutFocusSession(focusSession),
            WorkspaceWrite.PutFocusSession(focusSession.copy(id = "f-run-2", outcome = FocusOutcome.Stopped, target = null)),
            WorkspaceWrite.PutTask(task, isNew = true),
            WorkspaceWrite.PutTask(Task(TaskId("k-2"), "Date-only deadline", deadline = Deadline(LocalDay(2026, 1, 2))), before = task),
            WorkspaceWrite.PutReminder(reminder, isNew = true, before = reminder.copy(text = "Old")),
            WorkspaceWrite.PutNote(note, before = note.copy(inbox = false)),
            WorkspaceWrite.PutNoteLink(NoteTopicLink(topic.id, note.id, linked = false)),
            WorkspaceWrite.PutExternalRef(reference),
            WorkspaceWrite.PutExternalLink(TopicExternalLink(topic.id, reference.id)),
            WorkspaceWrite.PutExternalLink(TopicExternalLink(topic.id, reference.id, linked = false, dismissed = true)),
            WorkspaceWrite.PutMeetingLink(MeetingLink(topic.id, series, title = "Synthetic standup")),
            WorkspaceWrite.PutMeetingLink(MeetingLink(topic.id, series, linked = false)),
            WorkspaceWrite.DeleteForever(TrashKind.Reminder, reminder.id.value, "Synthetic reminder"),
        )

    private fun roundTrip(writes: List<WorkspaceWrite>): List<WorkspaceWrite>? {
        val stored = WorkspaceWriteJson.encode(writes).toString()
        return WorkspaceWriteJson.decode(Json.parseToJsonElement(stored).jsonArray)
    }

    @Test
    fun everyKindOfWriteComesBackUnchanged() {
        assertEquals(everyWrite, roundTrip(everyWrite))
    }

    @Test
    fun aTopicSavedBeforeAdditionalInformationExistedReadsAsNeverSaved() {
        val stored = WorkspaceWriteJson.encode(listOf(WorkspaceWrite.PutTopic(topic, isNew = true))).single().jsonObject
        val value = stored.getValue("value").jsonObject
        val older = JsonObject(stored + ("value" to JsonObject(value - "info")))
        assertEquals(listOf(WorkspaceWrite.PutTopic(topic, isNew = true)), WorkspaceWriteJson.decode(JsonArray(listOf(older))))
    }

    @Test
    fun preferencesSavedBeforeTheFocusTimerReadAsTheDefaultDurations() {
        val prefs = WorkspacePrefs(TopicsView.Board, CaptureKind.Note, keepCompleted = false, timeZone = "Asia/Tbilisi")
        val stored = WorkspaceWriteJson.encode(listOf(WorkspaceWrite.PutPrefs(prefs.copy(focusWorkMinutes = 50)))).single().jsonObject
        val value = stored.getValue("value").jsonObject
        val older = JsonObject(stored + ("value" to JsonObject(value - "focusMin" - "breakMin")))

        assertEquals(listOf(WorkspaceWrite.PutPrefs(prefs)), WorkspaceWriteJson.decode(JsonArray(listOf(older))))
    }

    @Test
    fun preferencesSavedBeforeHidingEmptySectionsReadAsOff() {
        val prefs = WorkspacePrefs(TopicsView.Board, CaptureKind.Note, keepCompleted = false, timeZone = "Asia/Tbilisi")
        val stored = WorkspaceWriteJson.encode(listOf(WorkspaceWrite.PutPrefs(prefs.copy(hideEmptySections = true)))).single().jsonObject
        val value = stored.getValue("value").jsonObject
        val older = JsonObject(stored + ("value" to JsonObject(value - "hideEmptySections")))

        assertEquals(listOf(WorkspaceWrite.PutPrefs(prefs)), WorkspaceWriteJson.decode(JsonArray(listOf(older))))
    }

    @Test
    fun preferencesSavedBeforeTheMeetingsLayoutReadAsVertical() {
        val prefs = WorkspacePrefs(TopicsView.Board, CaptureKind.Note, keepCompleted = false, timeZone = "Asia/Tbilisi")
        val stored =
            WorkspaceWriteJson
                .encode(listOf(WorkspaceWrite.PutPrefs(prefs.copy(meetingsLayout = MeetingsLayout.Horizontal))))
                .single()
                .jsonObject
        val value = stored.getValue("value").jsonObject
        val older = JsonObject(stored + ("value" to JsonObject(value - "meetingsLayout")))
        val later = JsonObject(stored + ("value" to JsonObject(value + ("meetingsLayout" to JsonPrimitive("grid")))))

        assertEquals(listOf(WorkspaceWrite.PutPrefs(prefs)), WorkspaceWriteJson.decode(JsonArray(listOf(older))))
        val unknown = WorkspaceWriteJson.decode(JsonArray(listOf(later)))
        assertEquals(listOf(WorkspaceWrite.PutPrefs(prefs)), unknown, "A layout this build does not know reads as vertical")
    }

    @Test
    fun aPreferencesChangeSavedBeforeTheScreenPreferencesLeavesThemAlone() {
        // An older build wrote neither the value's nor the before value's D83 fields. Both read as the defaults, so
        // the store, which writes only what differs from before, leaves the stored choices alone.
        val stored =
            WorkspaceWriteJson
                .encode(listOf(WorkspaceWrite.PutPrefs(screenPrefs, before = screenPrefs.copy(topicsView = TopicsView.Board))))
                .single()
                .jsonObject
        val older =
            JsonObject(
                stored +
                    ("value" to JsonObject(stored.getValue("value").jsonObject - SCREEN_PREF_KEYS)) +
                    ("before" to JsonObject(stored.getValue("before").jsonObject - SCREEN_PREF_KEYS)),
            )

        val decoded = WorkspaceWriteJson.decode(JsonArray(listOf(older)))?.single()

        assertEquals(WorkspaceWrite.PutPrefs(WorkspacePrefs(), before = WorkspacePrefs(topicsView = TopicsView.Board)), decoded)
    }

    @Test
    fun screenPreferencesThisBuildDoesNotKnowReadAsTheirDefaults() {
        val stored = WorkspaceWriteJson.encode(listOf(WorkspaceWrite.PutPrefs(screenPrefs))).single().jsonObject
        val later =
            JsonObject(
                stored.getValue("value").jsonObject +
                    mapOf(
                        "todayTopicsView" to JsonPrimitive("grid"),
                        "todayHideEmpty" to JsonArray(listOf(JsonPrimitive("meetings"), JsonPrimitive("later"), JsonPrimitive(1))),
                        "todayGroupBy" to JsonPrimitive("weekly"),
                        "todayPastMeetings" to JsonPrimitive(21),
                        "todayTopicCategories" to JsonPrimitive("active"),
                        "inboxShowNotes" to JsonPrimitive("no"),
                        "prsMineGroupBy" to JsonPrimitive("author"),
                    ),
            )

        val decoded = WorkspaceWriteJson.decode(JsonArray(listOf(JsonObject(stored + ("value" to later)))))?.single()

        val expected =
            screenPrefs.copy(
                todayTopicsView = null,
                todayHideEmpty = setOf(TodayHideableSection.Meetings),
                todayGroupBy = TodayGroupBy.DueDate,
                todayPastMeetings = null,
                todayTopicCategories = setOf(StatusCategory.Active, StatusCategory.Paused),
                inboxShowNotes = true,
                prsMineGroupBy = MyPrsGroupBy.Repository,
            )
        assertEquals(WorkspaceWrite.PutPrefs(expected), decoded)
    }

    @Test
    fun todaysLayoutsComeBackInOrderAndNotSetStaysNotSet() {
        val layouts =
            WorkspacePrefs(
                todayLayout =
                    TodayLayout(
                        top = listOf(TodayCard.Topics, TodayCard.Meetings),
                        left = emptyList(),
                        right = listOf(TodayCard.ReviewRequested, TodayCard.Tasks),
                        bottom = listOf(TodayCard.Reminders),
                    ),
                todayPhoneLayout = listOf(TodayCard.Reminders, TodayCard.Topics, TodayCard.Tasks),
            )
        val writes =
            listOf(
                WorkspaceWrite.PutPrefs(layouts, before = WorkspacePrefs()),
                // Reset: back to not set, which is not the same as an empty layout or order.
                WorkspaceWrite.PutPrefs(WorkspacePrefs(), before = layouts),
                WorkspaceWrite.PutPrefs(WorkspacePrefs(todayLayout = TodayLayout(), todayPhoneLayout = emptyList())),
            )

        assertEquals(writes, roundTrip(writes))
        val value =
            WorkspaceWriteJson
                .encode(writes.take(1))
                .single()
                .jsonObject
                .getValue("value")
                .jsonObject
        assertEquals(
            Json.parseToJsonElement("""{"top":["topics","meetings"],"left":[],"right":["review","tasks"],"bottom":["reminders"]}"""),
            value.getValue("todayLayout"),
            "Every place is written, so a merge never keeps an old place",
        )
    }

    @Test
    fun todaysLayoutsSavedBeforeD95ReadAsNotSetAndUnknownCardsAreLeftOut() {
        val stored = WorkspaceWriteJson.encode(listOf(WorkspaceWrite.PutPrefs(WorkspacePrefs()))).single().jsonObject
        val value = stored.getValue("value").jsonObject
        val older = JsonObject(stored + ("value" to JsonObject(value - "todayLayout" - "todayPhoneLayout")))
        val unknown =
            mapOf(
                "todayLayout" to
                    Json.parseToJsonElement("""{"top":["inbox","tasks"],"middle":["topics"],"right":"meetings","bottom":[1,"review"]}"""),
                "todayPhoneLayout" to Json.parseToJsonElement("""["notes","topics",2]"""),
            )
        val later = JsonObject(stored + ("value" to JsonObject(value + unknown)))
        val notAMap = JsonObject(stored + ("value" to JsonObject(value + ("todayLayout" to JsonPrimitive("top")))))

        assertEquals(listOf(WorkspaceWrite.PutPrefs(WorkspacePrefs())), WorkspaceWriteJson.decode(JsonArray(listOf(older))))
        val known =
            WorkspacePrefs(
                todayLayout = TodayLayout(top = listOf(TodayCard.Tasks), bottom = listOf(TodayCard.ReviewRequested)),
                todayPhoneLayout = listOf(TodayCard.Topics),
            )
        assertEquals(listOf(WorkspaceWrite.PutPrefs(known)), WorkspaceWriteJson.decode(JsonArray(listOf(later))))
        assertEquals(listOf(WorkspaceWrite.PutPrefs(WorkspacePrefs())), WorkspaceWriteJson.decode(JsonArray(listOf(notAMap))))
    }

    @Test
    fun aWriteThisVersionCannotReadDropsTheWholeChange() {
        val good = WorkspaceWriteJson.encode(everyWrite.take(1)).single()
        val unknown = Json.parseToJsonElement("""{"type":"later","value":{}}""")
        val badDeadline =
            Json.parseToJsonElement(
                WorkspaceWriteJson
                    .encode(listOf(WorkspaceWrite.PutTask(task)))
                    .single()
                    .toString()
                    .replace("2026-11-14", "not a day"),
            )

        assertNull(WorkspaceWriteJson.decode(JsonArray(listOf(good, unknown))))
        assertNull(WorkspaceWriteJson.decode(JsonArray(listOf(good, badDeadline))))
        assertNull(WorkspaceWriteJson.decode(JsonArray(emptyList())), "A change always has a write")
    }

    @Test
    fun aMeetingLinkWithASeriesKeyThatIsNotAHashDropsTheWholeChange() {
        val link = WorkspaceWriteJson.encode(listOf(WorkspaceWrite.PutMeetingLink(MeetingLink(topic.id, series))))
        val damaged = Json.parseToJsonElement(link.toString().replace(series.value, "NOT-A-HASH")).jsonArray

        assertNull(WorkspaceWriteJson.decode(damaged))
    }

    private companion object {
        /** The per-screen preference keys (D83), which builds before D83 did not write. */
        val SCREEN_PREF_KEYS =
            listOf(
                "todayTopicsView",
                "todayHideEmpty",
                "todayGroupBy",
                "todayPastMeetings",
                "todayTopicCategories",
                "inboxShowCompleted",
                "inboxShowNotes",
                "inboxGroupBy",
                "topicShowCompleted",
                "topicHideEmpty",
                "topicGroupByDue",
                "jiraCurrentSprintOnly",
                "prsMineGroupBy",
                "prsReviewGroupBy",
            )
    }
}
