package thread.domain.search

import thread.domain.note.NoteId
import thread.domain.note.NoteSummary
import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Every Topic, Task and Note here is SYNTHETIC test data.
class SearchTest {
    private val status = SEED_STATUSES[0].id
    private val topics =
        listOf(
            Topic(TopicId("t-1"), "Synthetic payments", TopicColor.Mint, status),
            Topic(TopicId("t-2"), "Synthetic other", TopicColor.Sky, status, nextStep = "Ask about PAYMENTS limits"),
            Topic(TopicId("t-3"), "Synthetic third", TopicColor.Peach, status, goal = "Nothing related"),
        )
    private val tasks =
        listOf(
            Task(TaskId("k-1"), "Synthetic payment retry", done = true),
            Task(TaskId("k-2"), "Synthetic trashed payment", deleted = true),
            Task(TaskId("k-3"), "Synthetic unrelated"),
        )
    private val notes =
        listOf(
            NoteSummary(NoteId("n-1"), title = "Synthetic standalone", preview = "Notes on payment screens"),
            NoteSummary(NoteId("n-2"), title = "Synthetic payment note in Trash", inTrash = true),
        )

    @Test
    fun aBlankQueryFindsNothing() {
        assertNull(searchWorkspace("   ", topics, tasks, notes))
    }

    @Test
    fun matchesIgnoreCaseAndCoverTopicFieldsTaskTitlesAndNotePreviews() {
        val found = searchWorkspace("  Payment ", topics, tasks, notes)!!
        assertEquals(listOf("t-1", "t-2"), found.topics.map { it.id.value })
        assertEquals(listOf("k-1"), found.tasks.map { it.id.value }, "completed tasks count, Trash does not")
        assertEquals(listOf("n-1"), found.notes.map { it.id.value }, "a triaged standalone Note stays searchable (example 6)")
    }

    @Test
    fun eachGroupStopsAtSix() {
        val many = (1..9).map { Task(TaskId("k-many-$it"), "Synthetic match $it") }
        val found = searchWorkspace("match", emptyList(), many, emptyList())!!
        assertEquals(SEARCH_GROUP_LIMIT, found.tasks.size)
        assertEquals(many.take(SEARCH_GROUP_LIMIT), found.tasks)
        assertTrue(searchWorkspace("zzz", topics, tasks, notes)!!.isEmpty)
    }
}
