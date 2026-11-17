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

    // Today's categories by default (D59), as Settings > Today "Topics shown" starts.
    private val activeAndPaused = setOf(StatusCategory.Active, StatusCategory.Paused)

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

        assertEquals(listOf("Analysis", "In Progress", "QA", "Hold"), todayStatuses(order, activeAndPaused).map { it.name })
        val groups = todayTopicGroups(listOf(a, b, c, d, e, f), order, activeAndPaused)

        // QA has no Topic, so it has no group. Planned and Done are not picked, so they don't show.
        assertEquals(listOf("Analysis", "In Progress", "Hold"), groups.map { it.status.name })
        assertEquals(listOf("c", "b"), groups[0].topics.map { it.id.value })
        assertEquals(listOf("a"), groups[1].topics.map { it.id.value })
        assertEquals(listOf("e"), groups[2].topics.map { it.id.value })
        // Moving Hold to the top in Settings moves its group first.
        val moved = listOf(hold, todo, analysis, inProgress, qa)
        assertEquals(
            listOf("Hold", "Analysis", "In Progress"),
            todayTopicGroups(listOf(a, b, c, e), moved, activeAndPaused).map { it.status.name },
        )
    }

    @Test
    fun todayHasNoGroupsWithoutActiveOrPausedTopics() {
        assertTrue(todayTopicGroups(listOf(topic("a", planned), topic("b", done)), statuses, activeAndPaused).isEmpty())
        assertTrue(todayTopicGroups(emptyList(), statuses, activeAndPaused).isEmpty())
        // A Topic whose status is gone counts under the first status, Planned here, so Today leaves it out.
        val orphan = Topic(TopicId("t1"), "Orphan", TopicColor.Blue, StatusId("s-gone"))
        assertTrue(todayTopicGroups(listOf(orphan), statuses, activeAndPaused).isEmpty())
        assertEquals(
            listOf("t1"),
            todayTopicGroups(listOf(orphan), listOf(paused, done), activeAndPaused).single().topics.map { it.id.value },
        )
    }

    @Test
    fun todayShowsTheStatusesOfEveryPickedCategoryAndNoneWithoutAPick() {
        // Two statuses per category, in a workspace order that mixes the categories.
        val todo = TopicStatus(StatusId("s-todo"), "To-Do", StatusCategory.Planned, 0)
        val analysis = TopicStatus(StatusId("s-analysis"), "Analysis", StatusCategory.Active, 1)
        val hold = TopicStatus(StatusId("s-hold"), "Hold", StatusCategory.Paused, 2)
        val shipped = TopicStatus(StatusId("s-shipped"), "Shipped", StatusCategory.Done, 3)
        val idea = TopicStatus(StatusId("s-idea"), "Idea", StatusCategory.Planned, 4)
        val qa = TopicStatus(StatusId("s-qa"), "QA", StatusCategory.Active, 5)
        val blocked = TopicStatus(StatusId("s-blocked"), "Blocked", StatusCategory.Paused, 6)
        val dropped = TopicStatus(StatusId("s-dropped"), "Dropped", StatusCategory.Done, 7)
        val order = listOf(todo, analysis, hold, shipped, idea, qa, blocked, dropped)
        val topics = order.mapIndexed { i, status -> topic("t$i", status, i.toLong()) }

        // Every one of the 16 subsets of the four categories, the empty one included.
        val subsets = (0 until 16).map { bits -> StatusCategory.entries.filterIndexed { i, _ -> bits and (1 shl i) != 0 }.toSet() }
        assertEquals(16, subsets.toSet().size)
        subsets.forEach { picked ->
            val expected = order.filter { it.category in picked }
            assertEquals(expected, todayStatuses(order, picked), "statuses for $picked")
            val groups = todayTopicGroups(topics, order, picked)
            assertEquals(expected, groups.map { it.status }, "groups for $picked, in workspace order")
            assertEquals(expected.size, groups.sumOf { it.topics.size }, "one Topic per status for $picked")
        }
        assertTrue(todayStatuses(order, emptySet()).isEmpty())
        assertTrue(todayTopicGroups(topics, order, emptySet()).isEmpty())
        // All four categories show every status, Done included.
        assertEquals(order, todayStatuses(order, StatusCategory.entries.toSet()))
    }

    @Test
    fun aStatusMovedToAnotherCategoryMovesItsTopicsInOrOutOfToday() {
        val plannedAndDone = setOf(StatusCategory.Planned, StatusCategory.Done)
        val a = topic("a", active, 1)
        val b = topic("b", planned, 2)
        assertEquals(listOf("b"), todayTopicGroups(listOf(a, b), statuses, plannedAndDone).flatMap { it.topics }.map { it.id.value })
        // Settings > Topic statuses moves Active into the Done category: its Topic now shows, after Planned's.
        val moved = statuses.map { if (it == active) it.copy(category = StatusCategory.Done) else it }
        assertEquals(
            listOf("Planned", "Active"),
            todayTopicGroups(listOf(a, b), moved, plannedAndDone).map { it.status.name },
        )
        // And back to Active, out again, while the default categories pick it up.
        assertEquals(listOf("b"), todayTopicGroups(listOf(a, b), statuses, plannedAndDone).flatMap { it.topics }.map { it.id.value })
        assertEquals(listOf("a"), todayTopicGroups(listOf(a, b), statuses, activeAndPaused).flatMap { it.topics }.map { it.id.value })
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

    // D81: the line under a Topic's name. Every text below is SYNTHETIC.
    private val full = topic("a", active).copy(info = "SYNTHETIC context", goal = "SYNTHETIC goal")

    @Test
    fun anOpenTaskComesFirst() {
        assertEquals(TopicLine.NextTask("Write tests"), topicLine(full, "Write tests", StatusCategory.Active))
        assertEquals(TopicLine.NextTask("Write tests"), topicLine(topic("b", active), "Write tests", StatusCategory.Active))
        assertEquals(TopicLine.NextTask("Ship"), topicLine(full, "Ship", StatusCategory.Done), "an open Task shows in Done too")
    }

    @Test
    fun withoutATaskTheAdditionalInformationShows() {
        val infoOnly = topic("a", active).copy(info = "SYNTHETIC context")
        assertEquals(TopicLine.About("SYNTHETIC context"), topicLine(infoOnly, null, StatusCategory.Active))
        assertEquals(TopicLine.About("SYNTHETIC context"), topicLine(full, null, StatusCategory.Active), "before the goal")
    }

    @Test
    fun withoutInformationTheGoalShows() {
        val goalOnly = topic("a", active).copy(goal = "SYNTHETIC goal")
        assertEquals(TopicLine.About("SYNTHETIC goal"), topicLine(goalOnly, null, StatusCategory.Paused))
        val blankInfo = goalOnly.copy(info = " \n\t ")
        assertEquals(TopicLine.About("SYNTHETIC goal"), topicLine(blankInfo, null, StatusCategory.Active), "blank info")
        assertEquals(TopicLine.About("SYNTHETIC goal"), topicLine(goalOnly.copy(info = ""), null, StatusCategory.Active), "saved empty")
        assertEquals(
            TopicLine.About("SYNTHETIC goal"),
            topicLine(goalOnly.copy(leftOff = "Halfway", info = ""), null, StatusCategory.Active),
            "a saved empty text does not fall back to the old text, so the goal shows",
        )
    }

    @Test
    fun theOldPickUpTextCountsAsInformation() {
        val legacy = topic("a", active).copy(leftOff = "Halfway", nextStep = "Call QA", goal = "SYNTHETIC goal")
        assertEquals(TopicLine.About("Where I left off: Halfway Next step: Call QA"), topicLine(legacy, null, StatusCategory.Active))
        assertEquals(
            TopicLine.About("Next step: Call QA"),
            topicLine(legacy.copy(leftOff = " "), null, StatusCategory.Active),
            "a blank old part is left out",
        )
    }

    @Test
    fun withNothingToSayTheStatusDecides() {
        val empty = topic("a", active).copy(info = "  ", goal = "\n")
        assertEquals(TopicLine.NoOpenTasks, topicLine(empty, null, StatusCategory.Active))
        assertEquals(TopicLine.NoOpenTasks, topicLine(topic("b", planned), null, StatusCategory.Planned))
        assertEquals(TopicLine.NoOpenTasks, topicLine(topic("c", paused), null, StatusCategory.Paused))
        assertEquals(TopicLine.Finished, topicLine(topic("d", done), null, StatusCategory.Done))
        assertEquals(
            TopicLine.About("SYNTHETIC goal"),
            topicLine(topic("e", done).copy(goal = "SYNTHETIC goal"), null, StatusCategory.Done),
            "a Done Topic with a goal shows it",
        )
    }

    @Test
    fun theTextIsOneLine() {
        val info = topic("a", active).copy(info = "  First line\n\nSecond\t  line\r\n- third  ")
        assertEquals(TopicLine.About("First line Second line - third"), topicLine(info, null, StatusCategory.Active))
    }

    @Test
    fun aLongTextEndsAtAWordWithAnEllipsis() {
        val words = "SYNTHETIC context ".repeat(20) // 360 characters
        val line = topicLine(topic("a", active).copy(info = words), null, StatusCategory.Active) as TopicLine.About
        assertTrue(line.text.endsWith("context…") || line.text.endsWith("SYNTHETIC…"), line.text)
        assertTrue(line.text.length <= TOPIC_LINE_MAX_LENGTH + 1, "${line.text.length}")
        assertTrue(words.startsWith(line.text.dropLast(1)))

        val oneWord = topicLine(topic("b", active).copy(goal = "x".repeat(300)), null, StatusCategory.Active)
        assertEquals(TopicLine.About("x".repeat(TOPIC_LINE_MAX_LENGTH) + "…"), oneWord)

        val exact = "y".repeat(TOPIC_LINE_MAX_LENGTH)
        assertEquals(TopicLine.About(exact), topicLine(topic("c", active).copy(info = exact), null, StatusCategory.Active))
    }

    @Test
    fun aCutNeverSplitsAnEmoji() {
        // 199 letters, then an emoji (two UTF-16 units) across the limit.
        val text = "z".repeat(TOPIC_LINE_MAX_LENGTH - 1) + "🚀" + "z"
        val line = topicLine(topic("a", active).copy(info = text), null, StatusCategory.Active)
        assertEquals(TopicLine.About("z".repeat(TOPIC_LINE_MAX_LENGTH - 1) + "…"), line)
    }
}
