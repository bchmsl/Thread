package thread.web

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import thread.data.http.HttpRequest
import thread.data.http.HttpResponse
import thread.data.http.HttpTransport
import thread.data.synthetic.SyntheticHttpTransport
import thread.data.synthetic.SyntheticScenario
import thread.domain.calendar.MeetingStore
import thread.domain.calendar.MeetingsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sources automated checks hold or reshape for Today's card states (D96, emulator builds only). Every answer is
 * from the SYNTHETIC fixtures.
 */
class TestSourcesTest {
    private val server: MeetingStore =
        object : MeetingStore {
            override fun observe(zone: String): Flow<MeetingsState> = flowOf(MeetingsState.NotSetUp)
        }

    @Test
    fun meetingsFollowTheServerUnlessACheckForcesAnAnswer() =
        runTest {
            var forced: TestMeetingsState? = null
            val store = TestMeetingStore(server) { forced }

            assertEquals(listOf<MeetingsState>(MeetingsState.NotSetUp), store.observe("Asia/Tbilisi").toList())

            forced = TestMeetingsState.Unavailable
            assertEquals(listOf<MeetingsState>(MeetingsState.Unavailable(TEST_UNAVAILABLE_CODE)), store.observe("Asia/Tbilisi").toList())

            forced = TestMeetingsState.Loading
            assertEquals(MeetingsState.Loading, store.observe("Asia/Tbilisi").first())
            // Held: no second answer and no end.
            assertNull(withTimeoutOrNull(60_000) { store.observe("Asia/Tbilisi").toList() })
        }

    @Test
    fun reviewRequestsAnswerAsManyRowsAsTheCheckAsks() =
        runTest {
            var count: Int? = null
            val transport = TestGitHubTransport(fixtures(), { false }) { count }

            assertEquals(listOf(44, 7, 101, 9), numbers(transport.get(REVIEWS)))

            count = 6
            val six = transport.get(REVIEWS)
            assertEquals(listOf(44, 7, 101, 9, 904, 905), numbers(six))
            val page: dynamic = JSON.parse<dynamic>(six.body)
            assertEquals(6, page.total_count as Int)
            assertTrue((page.items[4].title as String).startsWith("SYNTHETIC: Review row 5, a long title"))

            count = 0
            assertEquals(emptyList(), numbers(transport.get(REVIEWS)))
        }

    @Test
    fun otherAnswersAndFailuresAreLeftAsTheyAre() =
        runTest {
            val mine = fixtures().get(MINE)
            assertEquals(mine.body, TestGitHubTransport(fixtures(), { true }) { 2 }.get(MINE).body)

            val failing = SyntheticHttpTransport({ SyntheticScenario.ServerError }, { true }, { 0L })
            val failed = TestGitHubTransport(failing, { false }) { 2 }.get(REVIEWS)
            assertEquals(500, failed.status)
            assertTrue("Synthetic error 500" in failed.body)
        }

    @Test
    fun aHeldReviewSearchNeverAnswers() =
        runTest {
            var hold = true
            val transport = TestGitHubTransport(fixtures(), { hold }) { null }

            assertNull(withTimeoutOrNull(60_000) { transport.get(REVIEWS) })

            hold = false
            assertEquals(4, numbers(transport.get(REVIEWS)).size)
        }

    private fun fixtures(): HttpTransport = SyntheticHttpTransport({ SyntheticScenario.Normal }, { true }, { 0L })

    private fun numbers(response: HttpResponse): List<Int> {
        val page: dynamic = JSON.parse<dynamic>(response.body)
        return (page.items as Array<dynamic>).map { it.number as Int }
    }

    private companion object {
        val REVIEWS =
            HttpRequest(
                "https://api.github.com/search/issues?q=is%3Apr+is%3Aopen+review-requested%3A%40me&per_page=50",
                emptyMap(),
                "GET /search/issues",
            )
        val MINE =
            HttpRequest(
                "https://api.github.com/search/issues?q=is%3Apr+is%3Aopen+author%3A%40me&per_page=50",
                emptyMap(),
                "GET /search/issues",
            )
    }
}
