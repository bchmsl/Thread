package thread.data.http

import kotlinx.coroutines.test.runTest
import thread.data.CapturingTransport
import thread.data.answer
import thread.domain.external.RequestLogEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** SYNTHETIC requests only. */
class RecordingTransportTest {
    private var now = 1_000L
    private val log = InMemoryRequestLog()

    private fun recording(inner: HttpTransport) = RecordingTransport("Jira", inner, log, nowMillis = { now }, isOnline = { true })

    private val request =
        HttpRequest(
            url = "https://api.atlassian.com/ex/jira/00000000-0000-4000-8000-000000000000/rest/api/3/issue/SYN-1",
            headers = mapOf("Authorization" to "Basic synthetic-secret"),
            endpoint = "GET /rest/api/3/issue/{key}",
        )

    @Test
    fun recordsMetadataOnlyAndSafeHeaderValues() =
        runTest {
            val headers =
                mapOf(
                    "content-type" to "application/json",
                    "retry-after" to "120",
                    "x-ratelimit-remaining" to "0",
                    "link" to "<https://api.github.com/search/issues?q=private-search&page=2>; rel=\"next\"",
                    "x-github-sso" to "required; url=https://github.com/orgs/synthetic-org/sso",
                )
            val inner =
                CapturingTransport {
                    now += 250
                    answer(429, body = """{"secret":"synthetic body"}""", headers = headers)
                }

            recording(inner).get(request)

            val entry = log.entries.value.single()
            assertEquals(
                RequestLogEntry(
                    atMillis = 1_000,
                    provider = "Jira",
                    endpoint = "GET /rest/api/3/issue/{key}",
                    status = 429,
                    durationMillis = 250,
                    onlineAtStart = true,
                    readableHeaders = listOf("retry-after", "x-ratelimit-remaining", "link", "x-github-sso"),
                    headerValues = listOf("retry-after" to "120", "x-ratelimit-remaining" to "0"),
                ),
                entry,
            )
            for (leak in listOf("synthetic-secret", "synthetic body", "https://", "SYN-1", "private-search")) {
                assertFalse(leak in entry.toString(), "The log entry holds $leak")
            }
        }

    @Test
    fun noAnswerIsRecordedWithoutAStatus() =
        runTest {
            val inner = CapturingTransport { throw HttpNoResponseException("No answer") }

            assertFailsWith<HttpNoResponseException> { recording(inner).get(request) }

            assertEquals(
                null,
                log.entries.value
                    .single()
                    .status,
            )
        }

    @Test
    fun theLogKeepsTheNewestEntriesAndClears() =
        runTest {
            val small = InMemoryRequestLog(limit = 2)
            val transport = RecordingTransport("GitHub", CapturingTransport { answer(200) }, small, { now++ }, { false })

            repeat(3) { transport.get(request) }

            // Each request reads the clock twice: at the start and at the end.
            assertEquals(listOf(1_002L, 1_004L), small.entries.value.map { it.atMillis })
            assertEquals(
                false,
                small.entries.value
                    .first()
                    .onlineAtStart,
            )
            small.clear()
            assertEquals(emptyList(), small.entries.value)
        }

    @Test
    fun urlEncodingKeepsOnlyUnreservedCharacters() {
        assertEquals("is%3Apr%20author%3A%40me", urlEncode("is:pr author:@me"))
        assertEquals("a-b_c.d~e", urlEncode("a-b_c.d~e"))
        assertEquals("%C3%A9%2F%3F%26%23", urlEncode("é/?&#"))
    }
}
