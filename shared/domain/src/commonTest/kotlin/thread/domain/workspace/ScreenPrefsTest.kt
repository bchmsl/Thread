package thread.domain.workspace

import thread.domain.topic.StatusCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenPrefsTest {
    @Test
    fun theDefaultsKeepTheBehaviourFromBefore() {
        val prefs = WorkspacePrefs()

        assertEquals(TopicsView.List, prefs.todayTopicsLayout(onThisDevice = null))
        assertEquals(emptySet(), prefs.todayHiddenWhenEmpty)
        assertEquals(TodayGroupBy.DueDate, prefs.todayGroupBy)
        assertNull(prefs.todayPastMeetings, "Every past meeting shows")
        assertEquals(setOf(StatusCategory.Active, StatusCategory.Paused), prefs.todayTopicCategories, "D59")
        assertTrue(prefs.inboxShowCompleted)
        assertTrue(prefs.inboxShowNotes)
        assertEquals(InboxGroupBy.None, prefs.inboxGroupBy)
        assertTrue(prefs.topicShowCompleted)
        assertEquals(emptySet(), prefs.topicHideEmpty)
        assertFalse(prefs.topicGroupByDue)
        assertFalse(prefs.currentSprintOnly(onThisDevice = null))
        assertEquals(MyPrsGroupBy.Repository, prefs.prsMineGroupBy, "D62")
        assertEquals(ReviewPrsGroupBy.Repository, prefs.prsReviewGroupBy, "D62")
    }

    @Test
    fun todaysTopicsLayoutIsTheSyncedOneElseThisDevicesOne() {
        assertEquals(TopicsView.Board, WorkspacePrefs().todayTopicsLayout(onThisDevice = TopicsView.Board))
        assertEquals(TopicsView.List, WorkspacePrefs(todayTopicsView = TopicsView.List).todayTopicsLayout(onThisDevice = TopicsView.Board))
        assertEquals(TopicsView.Board, WorkspacePrefs(todayTopicsView = TopicsView.Board).todayTopicsLayout(onThisDevice = null))
    }

    @Test
    fun theOldHideEmptySwitchHidesRemindersAndReviewRequestedUntilSectionsArePicked() {
        val on = WorkspacePrefs(hideEmptySections = true)

        assertEquals(setOf(TodayHideableSection.Reminders, TodayHideableSection.ReviewRequested), on.todayHiddenWhenEmpty)
        assertEquals(emptySet(), WorkspacePrefs(hideEmptySections = false).todayHiddenWhenEmpty)
        assertEquals(emptySet(), on.copy(todayHideEmpty = emptySet()).todayHiddenWhenEmpty, "None picked wins over the old switch")
        assertEquals(
            setOf(TodayHideableSection.Meetings),
            WorkspacePrefs(todayHideEmpty = setOf(TodayHideableSection.Meetings)).todayHiddenWhenEmpty,
        )
    }

    @Test
    fun currentSprintOnlyIsTheSyncedChoiceElseThisDevicesOne() {
        assertTrue(WorkspacePrefs().currentSprintOnly(onThisDevice = true))
        assertFalse(WorkspacePrefs(jiraCurrentSprintOnly = false).currentSprintOnly(onThisDevice = true))
        assertTrue(WorkspacePrefs(jiraCurrentSprintOnly = true).currentSprintOnly(onThisDevice = false))
    }

    @Test
    fun everyChoiceReadsBackFromItsIdAndUnknownIdsReadAsNothing() {
        TodayGroupBy.entries.forEach { assertEquals(it, TodayGroupBy.fromId(it.id)) }
        InboxGroupBy.entries.forEach { assertEquals(it, InboxGroupBy.fromId(it.id)) }
        MyPrsGroupBy.entries.forEach { assertEquals(it, MyPrsGroupBy.fromId(it.id)) }
        ReviewPrsGroupBy.entries.forEach { assertEquals(it, ReviewPrsGroupBy.fromId(it.id)) }
        TodayHideableSection.entries.forEach { assertEquals(it, TodayHideableSection.fromId(it.id)) }
        TopicHideableSection.entries.forEach { assertEquals(it, TopicHideableSection.fromId(it.id)) }

        assertNull(TodayGroupBy.fromId("weekly"))
        assertNull(InboxGroupBy.fromId("topic"))
        assertNull(MyPrsGroupBy.fromId("author"))
        assertNull(ReviewPrsGroupBy.fromId("topic"))
        assertNull(TodayHideableSection.fromId("notes"))
        assertNull(TopicHideableSection.fromId("reminders"))
        assertNull(TodayGroupBy.fromId(null))
    }

    @Test
    fun theStoredIdsMatchTheRules() {
        // firebase/firestore.rules, validPrefs: these lists are spelled out there too.
        assertEquals(listOf("none", "topic", "due"), TodayGroupBy.entries.map { it.id })
        assertEquals(listOf("none", "type", "due"), InboxGroupBy.entries.map { it.id })
        assertEquals(listOf("repo", "topic", "review"), MyPrsGroupBy.entries.map { it.id })
        assertEquals(listOf("repo", "author", "review"), ReviewPrsGroupBy.entries.map { it.id })
        assertEquals(listOf("meetings", "reminders", "review"), TodayHideableSection.entries.map { it.id })
        assertEquals(listOf("notes", "jira", "prs", "meetings", "info"), TopicHideableSection.entries.map { it.id })
        assertEquals(listOf("planned", "active", "paused", "done"), StatusCategory.entries.map { it.id })
        assertEquals(20, PAST_MEETINGS_MAX)
    }
}
