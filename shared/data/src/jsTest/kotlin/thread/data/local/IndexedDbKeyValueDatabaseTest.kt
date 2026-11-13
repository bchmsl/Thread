package thread.data.local

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Runs against the real IndexedDB of ChromeHeadless, in a database of its own. SYNTHETIC values only. */
class IndexedDbKeyValueDatabaseTest {
    private val name = "thread-test-kv-${Random.nextLong().toString(16)}"

    @Test
    fun storesAreSeparateAndSupportEveryOperation() =
        runTest {
            val database = IndexedDbKeyValueDatabase(name, listOf("a", "b"), strictDurability = true)
            val a = database.store("a")
            val b = database.store("b")

            a.put("SYN-1", "one")
            a.put("SYN-2", "two")
            b.put("SYN-1", "other")
            a.put("SYN-1", "one again")

            assertEquals("one again", a.get("SYN-1"))
            assertEquals("other", b.get("SYN-1"))
            assertNull(a.get("missing"))
            assertEquals(setOf("SYN-1" to "one again", "SYN-2" to "two"), a.all().toSet())

            a.delete("SYN-2")
            assertEquals(listOf("SYN-1" to "one again"), a.all())

            a.clear()
            assertEquals(emptyList(), a.all())
            assertEquals("other", b.get("SYN-1"))
        }

    @Test
    fun aSecondInstanceReadsWhatTheFirstWrote() =
        runTest {
            IndexedDbKeyValueDatabase(name, listOf("a")).store("a").put("k", "v")

            assertEquals("v", IndexedDbKeyValueDatabase(name, listOf("a")).store("a").get("k"))
        }
}
