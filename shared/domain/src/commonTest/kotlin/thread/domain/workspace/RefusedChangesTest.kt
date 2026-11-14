package thread.domain.workspace

import thread.domain.note.NoteId
import thread.domain.note.NoteTopicLink
import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Every Topic, Task and Note here is SYNTHETIC test data.
class RefusedChangesTest {
    private val topic = Topic(TopicId("t-1"), "Synthetic topic", TopicColor.entries.first(), SEED_STATUSES[0].id, goal = "Old goal")
    private val task = Task(TaskId("k-1"), "Synthetic task", topic.id)

    private fun rename(
        name: String,
        from: Topic = topic,
    ) = WorkspaceWrite.PutTopic(from.copy(name = name), before = from)

    private fun change(
        id: String,
        vararg writes: WorkspaceWrite,
    ) = RefusedChange(id, writes.toList())

    private fun data(
        topics: List<Topic> = listOf(topic),
        tasks: List<Task> = listOf(task),
        noteLinks: List<NoteTopicLink> = emptyList(),
        prefsStored: Boolean = false,
    ) = WorkspaceData(statuses = SEED_STATUSES, topics = topics, tasks = tasks, noteLinks = noteLinks, prefsStored = prefsStored)

    @Test
    fun aChangeTheStoredDataShowsGotThrough() {
        val leftovers = listOf(change("c1", rename("Renamed")))

        assertEquals(emptyList(), unconfirmedChanges(leftovers, emptyList(), data(topics = listOf(topic.copy(name = "Renamed")))))
    }

    @Test
    fun aChangeTheStoredDataDoesNotShowIsUnconfirmed() {
        val leftovers = listOf(change("c1", rename("Renamed")))

        val result = unconfirmedChanges(leftovers, emptyList(), data())

        assertEquals(listOf(leftovers.single().copy(unconfirmed = true)), result)
    }

    @Test
    fun aCreationThatNeverArrivedIsUnconfirmed() {
        val created = Topic(TopicId("t-new"), "New synthetic topic", TopicColor.entries.first(), SEED_STATUSES[0].id)
        val leftovers = listOf(change("c1", WorkspaceWrite.PutTopic(created, isNew = true)))

        assertEquals(listOf("c1"), unconfirmedChanges(leftovers, emptyList(), data()).map { it.id })
    }

    @Test
    fun onlyTheFieldsAChangeWroteAreCompared() {
        // Another device changed the goal; this device only renamed.
        val leftovers = listOf(change("c1", rename("Renamed")))
        val stored = topic.copy(name = "Renamed", goal = "Goal from another device")

        assertEquals(emptyList(), unconfirmedChanges(leftovers, emptyList(), data(topics = listOf(stored))))
    }

    @Test
    fun aLaterChangeToTheSameFieldHidesTheEarlierOne() {
        val first = rename("First")
        val second = rename("Second", from = topic.copy(name = "First"))
        val leftovers = listOf(change("c1", first), change("c2", second))

        assertEquals(emptyList(), unconfirmedChanges(leftovers, emptyList(), data(topics = listOf(topic.copy(name = "Second")))))
        assertEquals(listOf("c2"), unconfirmedChanges(leftovers, emptyList(), data()).map { it.id }, "Both refused: the later one says it")
    }

    @Test
    fun changesToDifferentFieldsAreEachChecked() {
        val goal = WorkspaceWrite.PutTopic(topic.copy(goal = "New goal"), before = topic)
        val leftovers = listOf(change("c1", rename("Renamed")), change("c2", goal))

        val result = unconfirmedChanges(leftovers, emptyList(), data(topics = listOf(topic.copy(goal = "New goal"))))

        assertEquals(listOf("c1"), result.map { it.id })
    }

    @Test
    fun aWriteMadeSinceTheReloadHidesTheSameField() {
        val leftovers = listOf(change("c1", rename("Before the reload")))
        val later = listOf(rename("After the reload", from = topic.copy(name = "Before the reload")))

        val result = unconfirmedChanges(leftovers, later, data(topics = listOf(topic.copy(name = "After the reload"))))

        assertEquals(emptyList(), result)
    }

    @Test
    fun deleteForeverGotThroughWhenTheDocumentIsGone() {
        val delete = WorkspaceWrite.DeleteForever(TrashKind.Task, task.id.value, task.title)
        val leftovers = listOf(change("c1", delete))

        assertEquals(emptyList(), unconfirmedChanges(leftovers, emptyList(), data(tasks = emptyList())))
        assertEquals(listOf("c1"), unconfirmedChanges(leftovers, emptyList(), data()).map { it.id })
    }

    @Test
    fun aLaterDeleteForeverHidesEarlierEdits() {
        val edit = WorkspaceWrite.PutTask(task.copy(title = "Edited"), before = task)
        val leftovers = listOf(change("c1", edit))
        val later = listOf(WorkspaceWrite.DeleteForever(TrashKind.Task, task.id.value, task.title))

        assertEquals(emptyList(), unconfirmedChanges(leftovers, later, data(tasks = emptyList())))
    }

    @Test
    fun aBatchIsUnconfirmedWhenAnyOfItsWritesIsMissing() {
        val trashed = topic.copy(deleted = true)
        val batch =
            change(
                "c1",
                WorkspaceWrite.PutTopic(trashed, before = topic),
                WorkspaceWrite.PutTask(task.copy(deleted = true), before = task),
            )

        val result = unconfirmedChanges(listOf(batch), emptyList(), data(topics = listOf(trashed)))

        assertTrue(result.single().unconfirmed)
        assertEquals(batch.writes, result.single().writes)
    }

    @Test
    fun preferencesThatWereNeverStoredAreUnconfirmed() {
        val prefs = WorkspaceWrite.PutPrefs(WorkspacePrefs(topicsView = TopicsView.Board))

        assertEquals(1, unconfirmedChanges(listOf(change("c1", prefs)), emptyList(), data()).size)
        assertEquals(
            emptyList(),
            unconfirmedChanges(
                listOf(change("c1", prefs)),
                emptyList(),
                data(prefsStored = true).copy(prefs = WorkspacePrefs(topicsView = TopicsView.Board)),
            ),
        )
    }

    @Test
    fun linksAreComparedByTheirLinkedFlag() {
        val link = NoteTopicLink(topic.id, NoteId("n-1"), linked = false)
        val leftovers = listOf(change("c1", WorkspaceWrite.PutNoteLink(link)))

        assertEquals(emptyList(), unconfirmedChanges(leftovers, emptyList(), data(noteLinks = listOf(link))))
        assertEquals(1, unconfirmedChanges(leftovers, emptyList(), data(noteLinks = listOf(link.copy(linked = true)))).size)
    }

    @Test
    fun aDismissalIsShownOnlyWhenTheStoredLinkIsDismissed() {
        val link = TopicExternalLink(topic.id, "jira~synthetic-cloud~SYN-101", linked = false, dismissed = true)
        val leftovers = listOf(change("c1", WorkspaceWrite.PutExternalLink(link)))
        val oldShape = data().copy(externalLinks = listOf(link.copy(dismissed = null)))
        val dismissed = data().copy(externalLinks = listOf(link))

        assertEquals(listOf("c1"), unconfirmedChanges(leftovers, emptyList(), oldShape).map { it.id }, "the rules refused the field")
        assertEquals(emptyList(), unconfirmedChanges(leftovers, emptyList(), dismissed))

        // A link write without the field checks only linked.
        val linkOnly = listOf(change("c2", WorkspaceWrite.PutExternalLink(link.copy(linked = true, dismissed = null))))
        assertEquals(emptyList(), unconfirmedChanges(linkOnly, emptyList(), data().copy(externalLinks = listOf(link.copy(linked = true)))))
    }
}
