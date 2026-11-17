package thread.domain.topic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    ) = Topic(TopicId(id), "Topic $id", TopicColor.Green, status.id, createdAtMillis = createdAt)

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
        val orphan = Topic(TopicId("t1"), "Orphan", TopicColor.Blue, StatusId("s-gone"))
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
    fun todayGroupsTopicsByEachActiveAndPausedStatusInWorkspaceOrder() {
        // The owner's statuses: To-Do (Planned), Analysis, In Progress, QA (Active), Hold (Paused), Done (Done).
        val todo = planned.copy(name = "To-Do")
        val analysis = active.copy(name = "Analysis")
        val inProgress = TopicStatus(StatusId("s-progress"), "In Progress", StatusCategory.Active, 2)
        val qa = TopicStatus(StatusId("s-qa"), "QA", StatusCategory.Active, 3)
        val hold = paused.copy(name = "Hold", sortOrder = 4)
        val finished = done.copy(sortOrder = 5)
        val order = listOf(todo, analysis, inProgress, qa, hold, finished)
        val a = topic("a", inProgress, 1)
        val b = topic("b", analysis, 9)
        val c = topic("c", analysis, 2)
        val d = topic("d", todo, 1)
        val e = topic("e", hold, 1)
        val f = topic("f", finished, 1)

        assertEquals(listOf("Analysis", "In Progress", "QA", "Hold"), todayStatuses(order).map { it.name })
        val groups = todayTopicGroups(listOf(a, b, c, d, e, f), order)

        // QA has no Topic, so it has no group. Planned and Done never show.
        assertEquals(listOf("Analysis", "In Progress", "Hold"), groups.map { it.status.name })
        assertEquals(listOf("c", "b"), groups[0].topics.map { it.id.value })
        assertEquals(listOf("a"), groups[1].topics.map { it.id.value })
        assertEquals(listOf("e"), groups[2].topics.map { it.id.value })
        // Moving Hold to the top in Settings moves its group first.
        val moved = listOf(hold, todo, analysis, inProgress, qa)
        assertEquals(listOf("Hold", "Analysis", "In Progress"), todayTopicGroups(listOf(a, b, c, e), moved).map { it.status.name })
    }

    @Test
    fun todayHasNoGroupsWithoutActiveOrPausedTopics() {
        assertTrue(todayTopicGroups(listOf(topic("a", planned), topic("b", done)), statuses).isEmpty())
        assertTrue(todayTopicGroups(emptyList(), statuses).isEmpty())
        // A Topic whose status is gone counts under the first status, Planned here, so Today leaves it out.
        val orphan = Topic(TopicId("t1"), "Orphan", TopicColor.Blue, StatusId("s-gone"))
        assertTrue(todayTopicGroups(listOf(orphan), statuses).isEmpty())
        assertEquals(listOf("t1"), todayTopicGroups(listOf(orphan), listOf(paused, done)).single().topics.map { it.id.value })
    }

    @Test
    fun aNewTopicStartsAsPlannedWithTheNextColor() {
        val created = newTopic(TopicId("t-new"), "  Card limits  ", statuses, topicCount = 7)!!
        assertEquals("Card limits", created.name)
        assertEquals(planned.id, created.statusId)
        assertEquals(TopicColor.Cyan, created.color)
        assertEquals(TopicColor.Purple, colorForNewTopic(0))
        assertEquals(TopicColor.Yellow, colorForNewTopic(4))
        assertEquals(TopicColor.Red, colorForNewTopic(5))
        assertEquals(TopicColor.Grey, colorForNewTopic(8))
        assertEquals(TopicColor.Purple, colorForNewTopic(9))
    }

    @Test
    fun newTopicsGetTheFiveOldColorsFirstThenEveryOtherColorOnce() {
        // D72: the first five Topics get the colors they got before Chrome's palette, in the same order.
        assertEquals(
            listOf("lavender", "mint", "sky", "peach", "butter"),
            (0..4).map { colorForNewTopic(it).id },
        )
        assertEquals(TopicColor.entries.toSet(), (0..8).map(::colorForNewTopic).toSet())
    }

    @Test
    fun storedColorIdsNeverChange() {
        // D72: the five old ids stay, so saved Topics and older clients keep working without a migration.
        assertEquals(
            listOf("grey", "sky", "red", "butter", "mint", "pink", "lavender", "cyan", "peach"),
            TopicColor.entries.map { it.id },
        )
        TopicColor.entries.forEach { assertEquals(it, TopicColor.fromId(it.id)) }
        assertNull(TopicColor.fromId("teal"))
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

    @Test
    fun additionalInformationFallsBackToTheOldPickUpText() {
        val t = topic("a", active)
        assertEquals("", topicInfo(t))
        assertEquals("Where I left off: Halfway", topicInfo(t.copy(leftOff = "Halfway")))
        assertEquals("Next step: Call QA", topicInfo(t.copy(nextStep = "Call QA")))
        assertEquals("Where I left off: Halfway\n\nNext step: Call QA", topicInfo(t.copy(leftOff = "Halfway", nextStep = "Call QA")))
        assertEquals("", topicInfo(t.copy(leftOff = "Halfway", info = "")), "a saved empty text does not fall back")
        assertEquals("Mine", topicInfo(t.copy(nextStep = "Call QA", info = "Mine")))
        val longest = t.copy(leftOff = "x".repeat(TOPIC_TEXT_MAX_LENGTH), nextStep = "y".repeat(TOPIC_TEXT_MAX_LENGTH))
        assertTrue(topicInfo(longest).length <= TOPIC_INFO_MAX_LENGTH, "both old fields at their limit still fit")
    }
}
