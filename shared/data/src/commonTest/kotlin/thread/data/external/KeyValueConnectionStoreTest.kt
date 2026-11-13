package thread.data.external

import kotlinx.coroutines.test.runTest
import thread.data.synthetic.SyntheticConnections
import thread.domain.external.ConnectionStoreException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** SYNTHETIC connections only. */
class KeyValueConnectionStoreTest {
    private val values = InMemoryKeyValueStore()
    private val store = KeyValueConnectionStore(values)

    @Test
    fun connectionsRoundTripAndClearSeparately() =
        runTest {
            store.saveJira(SyntheticConnections.jira)
            store.saveGitHub(SyntheticConnections.gitHub)

            assertEquals(SyntheticConnections.jira, store.jira())
            assertEquals(SyntheticConnections.gitHub, store.gitHub())

            store.clearJira()

            assertNull(store.jira())
            assertEquals(SyntheticConnections.gitHub, store.gitHub())
        }

    @Test
    fun damagedOrUnknownRecordsReadAsNotConnected() =
        runTest {
            for (raw in listOf("not json", "[]", """{"recordVersion":2,"token":"x"}""", """{"recordVersion":1,"site":"example.com"}""")) {
                values.put("jira", raw)
                values.put("github", raw)

                assertNull(store.jira(), raw)
                assertNull(store.gitHub(), raw)
            }
        }

    @Test
    fun storageErrorsNeverCarryTheRecord() =
        runTest {
            val failing =
                object : KeyValueStore by InMemoryKeyValueStore() {
                    override suspend fun put(
                        key: String,
                        value: String,
                    ): Unit = throw KeyValueStoreException("Device storage failed: $value")
                }

            val error =
                assertFailsWith<ConnectionStoreException> { KeyValueConnectionStore(failing).saveGitHub(SyntheticConnections.gitHub) }

            assertFalse("synthetic-token-not-real" in error.toString())
            assertEquals(null, error.cause)
        }
}
