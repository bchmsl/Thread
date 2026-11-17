package thread.web.presentation.tasks

import thread.domain.task.DueGroup
import thread.domain.workspace.InboxHeading
import thread.domain.workspace.InboxShown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The words Inbox shows for Settings > Inbox (D91). No Task or Note is needed: only the counts matter.
class InboxTextTest {
    private fun hidden(
        completed: Int,
        notes: Int,
    ) = hiddenInboxText(InboxShown(emptyList(), emptyList(), hiddenCompleted = completed, hiddenNotes = notes))

    @Test
    fun theClearInboxSaysWhatSettingsHides() {
        // A14: only hidden items wait, so Inbox is clear and says what is hidden.
        assertNull(hidden(0, 0))
        assertEquals("1 note is hidden by your Inbox settings.", hidden(0, 1))
        assertEquals("3 notes are hidden by your Inbox settings.", hidden(0, 3))
        assertEquals("1 completed task is hidden by your Inbox settings.", hidden(1, 0))
        assertEquals("2 completed tasks are hidden by your Inbox settings.", hidden(2, 0))
        assertEquals("1 note and 1 completed task are hidden by your Inbox settings.", hidden(1, 1))
        assertEquals("4 notes and 2 completed tasks are hidden by your Inbox settings.", hidden(2, 4))
    }

    @Test
    fun groupHeadingsNameTheTypeOrTheDueGroupAsOnToday() {
        assertEquals("Tasks", inboxGroupTitle(InboxHeading.Tasks))
        assertEquals("Notes", inboxGroupTitle(InboxHeading.Notes))
        assertEquals(
            listOf("Completed", "Today", "Tomorrow", "Upcoming"),
            DueGroup.entries.map { inboxGroupTitle(InboxHeading.Due(it)) },
        )
    }
}
