package thread.domain.workspace

import thread.domain.note.NoteId
import thread.domain.note.NoteSummary
import thread.domain.task.Deadline
import thread.domain.task.DueGroup
import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.task.groupByDue
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import kotlin.test.Test
import kotlin.test.assertEquals

// Every Task and Note here is SYNTHETIC test data.
class InboxGroupsTest {
    private val today = LocalDay(2026, 11, 13)
    private val tomorrow = today.plusDays(1)
    private val noon = WallTime.of(today, 12, 0)

    private fun task(
        id: String,
        createdAt: Long?,
        done: Boolean = false,
        completedOn: LocalDay? = null,
        plannedDay: LocalDay? = null,
        deadline: Deadline? = null,
    ) = Task(TaskId(id), "Task $id", null, done, completedOn, plannedDay, deadline, inbox = true, createdAtMillis = createdAt)

    private fun note(
        id: String,
        createdAt: Long?,
    ) = NoteSummary(NoteId(id), title = "Note $id", inbox = true, createdAtMillis = createdAt)

    // Newest first, as `inboxTasks` and `inboxNotes` give them.
    private val openNoDate = task("open-no-date", 900)
    private val doneLongAgo = task("done-long-ago", 800, done = true, completedOn = today.plusDays(-20))
    private val plannedTomorrow = task("planned-tomorrow", 700, plannedDay = tomorrow)
    private val overdue = task("overdue", 600, deadline = Deadline(today.plusDays(-1)))
    private val doneToday = task("done-today", 500, done = true, completedOn = today, plannedDay = today)
    private val plannedLater = task("planned-later", 400, plannedDay = today.plusDays(4))
    private val tasks = listOf(openNoDate, doneLongAgo, plannedTomorrow, overdue, doneToday, plannedLater)
    private val notes = listOf(note("note-new", 950), note("note-mid", 650), note("note-old", 100))

    private fun keys(entries: List<InboxEntry>) =
        entries.map {
            when (it) {
                is InboxEntry.OfTask -> it.task.id.value
                is InboxEntry.OfNote -> it.note.id.value
            }
        }

    private fun groups(
        shown: InboxShown,
        groupBy: InboxGroupBy,
        remindAt: Map<TaskId, WallTime> = emptyMap(),
    ) = inboxGroups(shown, groupBy, noon, remindAt).map { it.heading to keys(it.entries) }

    @Test
    fun whatShowsAndWhatCountsForEachMixOfShowCompletedAndShowNotes() {
        val open = listOf("open-no-date", "planned-tomorrow", "overdue", "planned-later")
        val cases =
            listOf(
                // showCompleted, showNotes -> Tasks shown, Notes shown, hidden completed, hidden Notes
                Triple(true, true, Triple(tasks.map { it.id.value }, notes.map { it.id.value }, 0 to 0)),
                Triple(false, true, Triple(open, notes.map { it.id.value }, 2 to 0)),
                Triple(true, false, Triple(tasks.map { it.id.value }, emptyList(), 0 to 3)),
                Triple(false, false, Triple(open, emptyList(), 2 to 3)),
            )
        cases.forEach { (showCompleted, showNotes, expected) ->
            val label = "completed $showCompleted, notes $showNotes"
            val shown = inboxShown(tasks, notes, showCompleted, showNotes)
            val (taskIds, noteIds, hidden) = expected
            assertEquals(taskIds, shown.tasks.map { it.id.value }, label)
            assertEquals(noteIds, shown.notes.map { it.id.value }, label)
            assertEquals(hidden, shown.hiddenCompleted to shown.hiddenNotes, label)
            // Q8: the Inbox count is what shows.
            assertEquals(taskIds.size + noteIds.size, shown.count, label)
            assertEquals(shown.count, shown.entries.size, label)
            // Every mode lists each shown item once, and nothing else.
            InboxGroupBy.entries.forEach { groupBy ->
                val listed = inboxGroups(shown, groupBy, noon).flatMap { keys(it.entries) }
                assertEquals((taskIds + noteIds).sorted(), listed.sorted(), "$label, $groupBy")
            }
        }
    }

    @Test
    fun groupByNoneIsOneListNewestFirstAsBeforeD91() {
        val unstamped = task("not-on-the-server-yet", null)
        val shown = inboxShown(listOf(unstamped) + tasks, notes, showCompleted = true, showNotes = true)
        assertEquals(
            listOf(
                InboxHeading.All to
                    listOf(
                        "not-on-the-server-yet",
                        "note-new",
                        "open-no-date",
                        "done-long-ago",
                        "planned-tomorrow",
                        "note-mid",
                        "overdue",
                        "done-today",
                        "planned-later",
                        "note-old",
                    ),
            ),
            groups(shown, InboxGroupBy.None),
        )
    }

    @Test
    fun groupByTypeIsTasksThenNotesEachNewestFirst() {
        val shown = inboxShown(tasks, notes, showCompleted = true, showNotes = true)
        assertEquals(
            listOf(
                InboxHeading.Tasks to tasks.map { it.id.value },
                InboxHeading.Notes to listOf("note-new", "note-mid", "note-old"),
            ),
            groups(shown, InboxGroupBy.Type),
        )
    }

    @Test
    fun groupByTypeWithNotesOffShowsOnlyTheTasksGroup() {
        // A13: Type stays selectable; the Notes group is simply absent.
        val shown = inboxShown(tasks, notes, showCompleted = true, showNotes = false)
        assertEquals(listOf(InboxHeading.Tasks), groups(shown, InboxGroupBy.Type).map { it.first })
        val onlyNotes = inboxShown(emptyList(), notes, showCompleted = true, showNotes = true)
        assertEquals(listOf(InboxHeading.Notes), groups(onlyNotes, InboxGroupBy.Type).map { it.first })
    }

    @Test
    fun groupByDueDateUsesTheSharedRuleThenTheNotesLast() {
        val shown = inboxShown(tasks, notes, showCompleted = true, showNotes = true)
        val result = groups(shown, InboxGroupBy.DueDate)
        assertEquals(
            listOf(
                InboxHeading.Due(DueGroup.Completed) to listOf("done-today", "done-long-ago"),
                InboxHeading.Due(DueGroup.Today) to listOf("overdue"),
                InboxHeading.Due(DueGroup.Tomorrow) to listOf("planned-tomorrow"),
                InboxHeading.Due(DueGroup.Upcoming) to listOf("planned-later", "open-no-date"),
                InboxHeading.Notes to listOf("note-new", "note-mid", "note-old"),
            ),
            result,
        )
        // The Tasks' groups and order are the shared rule's, not a second copy of it (D90).
        assertEquals(
            groupByDue(shown.tasks, noon).map { InboxHeading.Due(it.group) to it.tasks.map { task -> task.id.value } },
            result.dropLast(1),
        )
    }

    @Test
    fun completedHoldsEveryCompletedTaskInInboxNotOnlyTodays() {
        // Inbox keeps a completed Task until it is filed, whatever day it was completed.
        val shown = inboxShown(tasks, emptyList(), showCompleted = true, showNotes = true)
        val completed = groups(shown, InboxGroupBy.DueDate).first()
        assertEquals(InboxHeading.Due(DueGroup.Completed), completed.first)
        assertEquals(setOf("done-today", "done-long-ago"), completed.second.toSet())
    }

    @Test
    fun dueDateLeavesOutCompletedWhileHiddenAndNotesWhileHidden() {
        val shown = inboxShown(tasks, notes, showCompleted = false, showNotes = false)
        assertEquals(
            listOf(InboxHeading.Due(DueGroup.Today), InboxHeading.Due(DueGroup.Tomorrow), InboxHeading.Due(DueGroup.Upcoming)),
            groups(shown, InboxGroupBy.DueDate).map { it.first },
        )
    }

    @Test
    fun aReminderDayCountsAsADateInInbox() {
        val shown = inboxShown(listOf(openNoDate), emptyList(), showCompleted = true, showNotes = true)
        val remindAt = mapOf(openNoDate.id to WallTime.of(tomorrow, 9, 30))
        assertEquals(
            listOf(InboxHeading.Due(DueGroup.Tomorrow) to listOf("open-no-date")),
            groups(shown, InboxGroupBy.DueDate, remindAt),
        )
    }

    @Test
    fun nothingShownGivesNoGroupInAnyMode() {
        val onlyHidden = inboxShown(listOf(doneToday), notes, showCompleted = false, showNotes = false)
        assertEquals(0, onlyHidden.count)
        assertEquals(1 to 3, onlyHidden.hiddenCompleted to onlyHidden.hiddenNotes)
        InboxGroupBy.entries.forEach { assertEquals(emptyList(), inboxGroups(onlyHidden, it, noon), it.name) }
    }
}
