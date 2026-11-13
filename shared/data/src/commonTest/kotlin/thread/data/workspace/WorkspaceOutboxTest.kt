package thread.data.workspace

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import thread.data.external.InMemoryKeyValueStore
import thread.data.external.KeyValueStore
import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import thread.domain.workspace.RefusedChange
import thread.domain.workspace.WorkspaceData
import thread.domain.workspace.WorkspaceWrite
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Every Topic here is SYNTHETIC test data.
class WorkspaceOutboxTest {
    private val storage = InMemoryKeyValueStore()
    private var clock = 0L
    private val topic = Topic(TopicId("t-1"), "Synthetic topic", TopicColor.entries.first(), SEED_STATUSES[0].id)

    private fun rename(name: String) = listOf(WorkspaceWrite.PutTopic(topic.copy(name = name), before = topic))

    private fun stored(name: String) = WorkspaceData(statuses = SEED_STATUSES, topics = listOf(topic.copy(name = name)))

    /** One page of the app. Its storage jobs run in the test's background: [runCurrent] or a [WorkspaceOutbox.load] runs them. */
    private fun TestScope.page(
        session: String,
        values: KeyValueStore = storage,
    ) = WorkspaceOutbox(values, backgroundScope, now = { ++clock }, session = session)

    @Test
    fun aRefusedChangeIsStillListedOnTheNextPage() =
        runTest {
            val first = page("a")
            val id = first.sent(rename("Refused"))
            first.refused(id, rename("Refused"))
            assertEquals(listOf(RefusedChange(id, rename("Refused"))), first.refused.value)

            val next = page("b")
            assertEquals(emptyList(), next.load(), "A refused change is not a leftover")
            assertEquals(listOf(RefusedChange(id, rename("Refused"))), next.refused.value)
        }

    @Test
    fun anAcceptedChangeIsForgotten() =
        runTest {
            val first = page("a")
            first.accepted(first.sent(rename("Accepted")))
            runCurrent()

            assertEquals(emptyList(), storage.all())
            assertEquals(emptyList(), page("b").load())
        }

    @Test
    fun aChangeThatGotNoAnswerIsALeftoverForTheNextPageOnly() =
        runTest {
            val first = page("a")
            val id = first.sent(rename("No answer"))

            assertEquals(emptyList(), first.load(), "This page still waits for the answer")
            val next = page("b")
            assertEquals(listOf(RefusedChange(id, rename("No answer"))), next.load())
            assertEquals(emptyList(), next.refused.value, "Not listed until resolved")
        }

    @Test
    fun resolvingKeepsOnlyTheLeftoversTheDataDoesNotShow() =
        runTest {
            val first = page("a")
            val shown = first.sent(rename("Shown"))
            val missing = first.sent(listOf(WorkspaceWrite.PutTopic(topic.copy(goal = "Missing goal"), before = topic)))
            val next = page("b")
            val leftovers = next.load()

            next.resolve(leftovers, stored("Shown"))
            runCurrent()

            val unconfirmed = leftovers.single { it.id == missing }.copy(unconfirmed = true)
            assertEquals(listOf(unconfirmed), next.refused.value)
            assertEquals(listOf(missing), storage.all().map { it.first }, "The shown one ($shown) is forgotten")
            val third = page("c")
            assertEquals(emptyList(), third.load())
            assertEquals(listOf(unconfirmed), third.refused.value, "Still listed after another reload")
        }

    @Test
    fun aWriteMadeBeforeResolvingHidesTheSameField() =
        runTest {
            page("a").sent(rename("Before the reload"))
            val next = page("b")
            val leftovers = next.load()
            next.sent(
                listOf(WorkspaceWrite.PutTopic(topic.copy(name = "After the reload"), before = topic.copy(name = "Before the reload"))),
            )

            next.resolve(leftovers, stored("After the reload"))

            assertEquals(emptyList(), next.refused.value)
        }

    @Test
    fun discardForgetsAChangeAndKeepBringsItBack() =
        runTest {
            val first = page("a")
            val id = first.sent(rename("Refused"))
            first.refused(id, rename("Refused"))
            val change = first.refused.value.single()

            first.discard(change)
            runCurrent()
            assertEquals(emptyList(), first.refused.value)
            assertEquals(emptyList(), storage.all())

            first.keep(change)
            assertEquals(listOf(change), first.refused.value)
            assertEquals(listOf(change), page("b").also { it.load() }.refused.value)
        }

    @Test
    fun aRecordThisVersionCannotReadIsLeftAlone() =
        runTest {
            storage.put("damaged", "not json")
            storage.put("later", """{"recordVersion":2,"id":"later"}""")

            val next = page("b")
            assertEquals(emptyList(), next.load())
            assertEquals(emptyList(), next.refused.value)
            assertEquals(listOf("damaged", "later"), storage.all().map { it.first })
        }

    @Test
    fun aFailingDeviceStorageNeverBlocksTheList() =
        runTest {
            val broken = page("a", values = FailingStore)
            val id = broken.sent(rename("Refused"))
            broken.refused(id, rename("Refused"))

            assertEquals(emptyList(), broken.load())
            assertEquals(listOf(RefusedChange(id, rename("Refused"))), broken.refused.value, "Listed until the page closes")
            assertTrue(broken.refused.value.none { it.unconfirmed })
        }

    private object FailingStore : KeyValueStore {
        override suspend fun get(key: String): String? = error("Synthetic storage failure")

        override suspend fun put(
            key: String,
            value: String,
        ): Unit = error("Synthetic storage failure")

        override suspend fun delete(key: String): Unit = error("Synthetic storage failure")

        override suspend fun all(): List<Pair<String, String>> = error("Synthetic storage failure")

        override suspend fun clear(): Unit = error("Synthetic storage failure")
    }
}
