package thread.web.presentation

import thread.domain.workspace.TodayCard
import thread.domain.workspace.TodayLayout
import thread.domain.workspace.WorkspacePrefs
import kotlin.test.Test
import kotlin.test.assertEquals

/** D95: how a refused change names Today's layouts. */
class TodayLayoutLinesTest {
    @Test
    fun notSavedReadsNotSet() {
        assertEquals(listOf("Today layout: not set", "Today one-column order: not set"), todayLayoutLines(WorkspacePrefs()))
    }

    @Test
    fun aSavedLayoutNamesEveryPlaceInOrder() {
        val prefs =
            WorkspacePrefs(
                todayLayout = TodayLayout(left = listOf(TodayCard.Tasks, TodayCard.Topics), right = listOf(TodayCard.ReviewRequested)),
                todayPhoneLayout = emptyList(),
            )

        assertEquals(
            listOf(
                "Today layout: top none; left tasks, topics; right review; bottom none",
                "Today one-column order: none",
            ),
            todayLayoutLines(prefs),
        )
        assertEquals(
            "Today one-column order: reminders, meetings",
            todayLayoutLines(prefs.copy(todayPhoneLayout = listOf(TodayCard.Reminders, TodayCard.Meetings)))[1],
        )
    }
}
