package thread.data.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * [HttpTransport] on the browser's `fetch` (adr/ADR-006, ADR-007).
 *
 * Requests omit cookies (`credentials: "omit"`), skip the HTTP cache (`cache: "no-store"`, so saved copies live
 * only in IndexedDB) and send no referrer. A request that gets no answer within [timeoutMillis] counts as no
 * answer. Nothing is logged; failures carry no URL or header.
 */
class FetchHttpTransport(
    private val timeoutMillis: Int = 20_000,
) : HttpTransport {
    override suspend fun get(request: HttpRequest): HttpResponse {
        val headers = js("({})")
        for ((name, value) in request.headers) headers[name] = value
        val init = js("({})")
        init.method = "GET"
        init.headers = headers
        init.mode = "cors"
        init.credentials = "omit"
        init.cache = "no-store"
        init.referrerPolicy = "no-referrer"
        val signal = timeoutSignal(timeoutMillis)
        if (signal != null) init.signal = signal

        val response: dynamic =
            try {
                fetch(request.url, init).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw HttpNoResponseException("No answer")
            }
        val readable = LinkedHashMap<String, String>()
        response.headers.forEach { value: String, name: String -> readable[name.lowercase()] = value }
        val body =
            try {
                response.text().unsafeCast<Promise<String>>().await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw HttpNoResponseException("The answer could not be read")
            }
        return HttpResponse(response.status.unsafeCast<Int>(), readable, body)
    }
}

private fun fetch(
    url: String,
    init: dynamic,
): Promise<dynamic> = js("fetch(url, init)").unsafeCast<Promise<dynamic>>()

/** `AbortSignal.timeout(ms)` where the browser has it, else null (no timeout). */
private fun timeoutSignal(millis: Int): dynamic =
    js("(typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function') ? AbortSignal.timeout(millis) : null")
