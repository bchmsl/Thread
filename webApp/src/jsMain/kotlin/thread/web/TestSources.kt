package thread.web

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import thread.data.http.HttpRequest
import thread.data.http.HttpResponse
import thread.data.http.HttpTransport
import thread.domain.calendar.MeetingStore
import thread.domain.calendar.MeetingsState

/*
 * Sources that automated checks can hold or reshape through `window.threadTest` (TestHooks.kt), for Today's card states
 * (D96). Emulator builds only: Main.kt wraps the real sources with these only when the app runs against the emulators.
 */

/** A meetings answer a check can force, instead of the server's. */
internal enum class TestMeetingsState {
    /** The first answer never comes. */
    Loading,

    /** The read fails. */
    Unavailable,
}

/** [inner], unless [state] forces an answer when Today starts following the meetings. */
internal class TestMeetingStore(
    private val inner: MeetingStore,
    private val state: () -> TestMeetingsState?,
) : MeetingStore {
    override fun observe(zone: String): Flow<MeetingsState> =
        when (state()) {
            TestMeetingsState.Loading ->
                flow {
                    emit(MeetingsState.Loading)
                    awaitCancellation()
                }
            TestMeetingsState.Unavailable -> flowOf(MeetingsState.Unavailable(TEST_UNAVAILABLE_CODE))
            null -> inner.observe(zone)
        }
}

/** The diagnostics code of a meetings read a check failed. */
internal const val TEST_UNAVAILABLE_CODE = "test-unavailable"

/**
 * SYNTHETIC GitHub answers ([inner]) with the Review requested search held while [holdReviews] is true, or answering
 * [reviewCount] rows when set: the fixture's rows first, then long SYNTHETIC ones.
 */
internal class TestGitHubTransport(
    private val inner: HttpTransport,
    private val holdReviews: () -> Boolean,
    private val reviewCount: () -> Int?,
) : HttpTransport {
    override suspend fun get(request: HttpRequest): HttpResponse {
        val reviews = REVIEW_SEARCH in request.url
        if (reviews && holdReviews()) awaitCancellation()
        val response = inner.get(request)
        val count = reviewCount()
        if (!reviews || count == null || response.status != 200) return response
        return HttpResponse(response.status, response.headers, withRows(response.body, count))
    }

    private fun withRows(
        body: String,
        count: Int,
    ): String {
        val page: dynamic = JSON.parse<dynamic>(body)
        val fixture = (page.items as Array<dynamic>).toList()
        val rows =
            List(count) { index ->
                if (index < fixture.size) {
                    fixture[index]
                } else {
                    val row: dynamic = JSON.parse<dynamic>(JSON.stringify(fixture[index % fixture.size]))
                    row.number = EXTRA_ROW_NUMBER + index
                    row.title = "SYNTHETIC: Review row ${index + 1}, a long title that wraps in a narrow place to check the row"
                    row
                }
            }
        page.items = rows.toTypedArray()
        page.total_count = count
        return JSON.stringify(page)
    }

    private companion object {
        /** The Review requested search, as the GitHub client asks it (URL-encoded). */
        const val REVIEW_SEARCH = "review-requested%3A%40me"

        /** Extra rows get numbers from here, clear of the fixture's. */
        const val EXTRA_ROW_NUMBER = 900
    }
}
