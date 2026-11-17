package thread.web.presentation.tasks

import thread.domain.topic.StatusCategory
import thread.domain.topic.StatusCategory.Active
import thread.domain.topic.StatusCategory.Done
import thread.domain.topic.StatusCategory.Paused
import thread.domain.topic.StatusCategory.Planned
import thread.domain.workspace.DEFAULT_TODAY_TOPIC_CATEGORIES
import kotlin.test.Test
import kotlin.test.assertEquals

/** D89: the words Today's Topics card uses for the categories Settings > Today "Topics shown" picks. */
class TodayTopicsTextTest {
    private val all = StatusCategory.entries.toSet()

    @Test
    fun theDefaultKeepsTheOldWords() {
        assertEquals("Active and paused Topics", todayTopicsTitle(DEFAULT_TODAY_TOPIC_CATEGORIES))
        assertEquals("Active or Paused", todayCategoryChoice(DEFAULT_TODAY_TOPIC_CATEGORIES))
        assertEquals("No active or paused Topics", todayNoTopicsText(DEFAULT_TODAY_TOPIC_CATEGORIES))
        assertEquals(
            "Topics show here when their status is in the Active or Paused category.",
            todayNoTopicsWhy(DEFAULT_TODAY_TOPIC_CATEGORIES),
        )
    }

    @Test
    fun oneCategoryIsNamedAlone() {
        assertEquals("Planned Topics", todayTopicsTitle(setOf(Planned)))
        assertEquals("Done", todayCategoryChoice(setOf(Done)))
        assertEquals("No done Topics", todayNoTopicsText(setOf(Done)))
    }

    @Test
    fun categoriesAreNamedInTheirFixedOrderWithoutAnOxfordComma() {
        // Picked in any order, named Planned, Active, Paused, Done.
        val three = linkedSetOf(Paused, Planned, Active)
        assertEquals("Planned, active and paused Topics", todayTopicsTitle(three))
        assertEquals("Planned, Active or Paused", todayCategoryChoice(three))
        assertEquals("No planned, active or paused Topics", todayNoTopicsText(three))
        assertEquals("Planned and done Topics", todayTopicsTitle(linkedSetOf(Done, Planned)))
    }

    @Test
    fun allFourAreAllTopics() {
        assertEquals("All Topics", todayTopicsTitle(all))
        assertEquals("No Topics", todayNoTopicsText(all))
        assertEquals("Every Topic shows here, whatever its status.", todayNoTopicsWhy(all))
    }

    @Test
    fun noneHasAPlainTitle() {
        // The card is left out with none picked; the title is only a safe fallback.
        assertEquals("Topics", todayTopicsTitle(emptySet()))
        assertEquals("", todayCategoryChoice(emptySet()))
    }
}
