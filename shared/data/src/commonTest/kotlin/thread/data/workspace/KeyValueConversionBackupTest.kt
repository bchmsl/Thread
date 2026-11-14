package thread.data.workspace

import kotlinx.coroutines.test.runTest
import thread.data.external.InMemoryKeyValueStore
import thread.data.external.KeyValueStore
import thread.data.external.KeyValueStoreException
import thread.domain.reminder.Reminder
import thread.domain.reminder.ReminderId
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.time.DEFAULT_WORKSPACE_ZONE
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import thread.domain.workspace.WorkspaceData
import thread.domain.workspace.topicReminderConversions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Every Topic and Reminder here is SYNTHETIC test data.
class KeyValueConversionBackupTest {
    private val topic = Topic(TopicId("t-1"), "Synthetic topic", TopicColor.entries.first(), SEED_STATUSES[0].id)

    private fun onTopic(
        id: String,
        hour: Int,
    ) = Reminder(
        ReminderId(id),
        WallTime.of(LocalDay(2026, 11, 14), hour, 0),
        DEFAULT_WORKSPACE_ZONE,
        "Synthetic reminder $id",
        ReminderTarget(TargetKind.Topic, topic.id.value),
        createdAtMillis = 5,
    )

    private val conversions =
        topicReminderConversions(
            WorkspaceData(statuses = SEED_STATUSES, topics = listOf(topic), reminders = listOf(onTopic("r-1", 9), onTopic("r-2", 10))),
        )

    @Test
    fun aRunIsStoredWithItsWritesAndTheWayBack() =
        runTest {
            val storage = InMemoryKeyValueStore()
            val backup = KeyValueConversionBackup(storage, now = { 1_000L })
            backup.save(conversions)

            val saved = backup.saved().single()
            assertEquals(1_000L, saved.savedAt)
            assertEquals(conversions.flatMap { it.writes }, saved.writes)
            assertEquals(conversions.flatMap { it.undo }, saved.undo, "the reminders exactly as they were stored")
            assertEquals(listOf("topic-reminders-1000-r-1"), storage.all().map { it.first })
        }

    @Test
    fun nothingToConvertStoresNothing() =
        runTest {
            val storage = InMemoryKeyValueStore()
            KeyValueConversionBackup(storage, now = { 1L }).save(emptyList())
            assertTrue(storage.all().isEmpty())
        }

    @Test
    fun aStorageFailureReachesTheConverter() =
        runTest {
            val failing =
                object : KeyValueStore by InMemoryKeyValueStore() {
                    override suspend fun put(
                        key: String,
                        value: String,
                    ): Unit = throw KeyValueStoreException("Synthetic storage failure")
                }
            assertFailsWith<KeyValueStoreException> { KeyValueConversionBackup(failing, now = { 1L }).save(conversions) }
        }
}
