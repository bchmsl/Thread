package thread.domain.topic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Every Topic and status here is SYNTHETIC test data.
class TopicRulesTest {
    private val planned = TopicStatus(StatusId("s-planned"), "Planned", StatusCategory.Planned, 0)
    private val active = TopicStatus(StatusId("s-active"), "Active", StatusCategory.Active, 1)
    private val paused = TopicStatus(StatusId("s-paused"), "Paused", StatusCategory.Paused, 2)
    private val done = TopicStatus(StatusId("s-done"), "Done", StatusCategory.Done, 3)
    private val statuses = listOf(planned, active, paused, done)

    private fun topic(
        id: String,
        status: TopicStatus,
        createdAt: Long? = null,
    ) = Topic(TopicId(id), "Topic $id", TopicColor.Mint, status.id, createdAtMillis = createdAt)

    @Test
    fun monogramFollowsV3() {
        assertEquals("PA", monogram("Payments"))
        assertEquals("CR", monogram("Card limits redesign"))
        assertEquals("MR", monogram("Move to the new repo"))
        assertEquals("BL", monogram("  build   log  "))
        assertEquals("?", monogram("the of and"))
        assertEquals("?", monogram(""))
        assertEquals("X", monogram("x"))
    }

    @Test
    fun nothingStoredShowsTheSeedStatuses() {
        assertEquals(SEED_STATUSES, visibleStatuses(emptyList()))
        assertEquals(listOf("Planned", "Active", "Paused", "Done"), SEED_STATUSES.map { it.name })
        assertEquals(StatusCategory.entries, SEED_STATUSES.map { it.category })
    }

    @Test
    fun visibleStatusesFollowTheWorkspaceOrderAndHideDeletedOnes() {
        val stored = listOf(done, paused.copy(deleted = true), active, planned.copy(sortOrder = 5))
        assertEquals(listOf(active, done, planned.copy(sortOrder = 5)), visibleStatuses(stored))
    }

    @Test
    fun aTopicWithAMissingStatusShowsTheFirstStatus() {
        val orphan = Topic(TopicId("t1"), "Orphan", TopicColor.Sky, StatusId("s-gone"))
        assertEquals(planned, statusOf(orphan, statuses))
        assertEquals(StatusCategory.Planned, categoryOf(orphan, statuses))
    }

    @Test
    fun topicsAreInStatusOrderThenOldestFirst() {
        val a = topic("a", done, 1)
        val b = topic("b", active, 5)
        val c = topic("c", active, 2)
        val d = topic("d", planned, null)
        assertEquals(listOf("d", "c", "b", "a"), topicsInOrder(listOf(a, b, c, d), statuses).map { it.id.value })
        // A different workspace order changes the result.
        assertEquals(
            listOf("a", "c", "b", "d"),
            topicsInOrder(listOf(a, b, c, d), listOf(done, active, paused, planned)).map { it.id.value },
        )
    }

    @Test
    fun aNewTopicStartsAsPlannedWithTheNextColor() {
        val created = newTopic(TopicId("t-new"), "  Card limits  ", statuses, topicCount = 7)!!
        assertEquals("Card limits", created.name)
        assertEquals(planned.id, created.statusId)
        assertEquals(TopicColor.Sky, created.color)
        assertEquals(TopicColor.Butter, colorForNewTopic(4))
        assertEquals(TopicColor.Lavender, colorForNewTopic(0))
        assertEquals(TopicColor.Lavender, colorForNewTopic(5))
    }

    @Test
    fun aNewTopicNeedsAName() {
        assertNull(newTopic(TopicId("t1"), "   ", statuses, 0))
        assertNull(newTopic(TopicId("t1"), "x".repeat(TOPIC_NAME_MAX_LENGTH + 1), statuses, 0))
    }

    @Test
    fun withoutAPlannedStatusANewTopicTakesTheFirstStatus() {
        val renamed = listOf(active.copy(name = "Building"), done)
        assertEquals(active.id, statusForNewTopic(renamed).id)
        // A status named "Planned" that is not in the Planned category does not count: categories decide.
        val misleading = listOf(active.copy(name = "Planned"), planned.copy(name = "Later", sortOrder = 9))
        assertEquals(planned.id, statusForNewTopic(misleading).id)
    }

    @Test
    fun completeMovesToTheFirstDoneStatus() {
        val cancelled = TopicStatus(StatusId("s-cancelled"), "Cancelled", StatusCategory.Done, 4)
        val list = listOf(planned, active, cancelled, done)
        assertEquals(cancelled.id, completeTopic(topic("a", active), list)!!.statusId)
        assertNull(completeTopic(topic("a", done), list))
        assertNull(completeTopic(topic("a", active), listOf(planned, active)))
    }

    @Test
    fun reopenMovesADoneTopicToActiveElsePlanned() {
        assertEquals(active.id, reopenTopic(topic("a", done), statuses)!!.statusId)
        assertEquals(planned.id, reopenTopic(topic("a", done), listOf(planned, done))!!.statusId)
        assertEquals(paused.id, reopenTopic(topic("a", done), listOf(paused, done))!!.statusId)
        assertNull(reopenTopic(topic("a", active), statuses))
        assertNull(reopenTopic(topic("a", done), listOf(done)))
    }

    @Test
    fun useCountCountsTopicsWithTheStatus() {
        val topics = listOf(topic("a", active), topic("b", active), topic("c", done))
        assertEquals(2, useCount(active.id, topics))
        assertEquals(0, useCount(paused.id, topics))
    }
}
