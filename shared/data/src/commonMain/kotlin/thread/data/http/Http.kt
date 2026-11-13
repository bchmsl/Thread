package thread.data.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalResult

/**
 * One read-only GET request to Jira or GitHub.
 *
 * [headers] may hold a token, so [toString] shows only [endpoint]: a template such as `GET /rest/api/3/issue/{key}`
 * that never holds a token, an id or a text from the provider. The request log uses it too.
 */
class HttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val endpoint: String,
) {
    override fun toString(): String = "HttpRequest($endpoint)"
}

/**
 * An answer. [headers] has lowercase names and holds only the headers the platform let the app read: in a browser,
 * the CORS-safelisted ones plus the ones the provider exposes.
 */
class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
) {
    override fun toString(): String = "HttpResponse($status)"
}

/** No answer reached the app: offline, a network failure, a timeout, or the browser blocked it (for example CORS). */
class HttpNoResponseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Sends GET requests. Thread never writes to Jira or GitHub in v1 (D08, D09), so there is no other method. */
interface HttpTransport {
    /** Throws [HttpNoResponseException] when no answer arrives. Any HTTP status is a normal answer. */
    suspend fun get(request: HttpRequest): HttpResponse
}

/**
 * Sends [request] and reads a JSON answer. A 2xx answer that is not JSON is `Failed("bad-response")`; any other
 * status goes to [problemFor].
 */
internal suspend fun HttpTransport.getJson(
    request: HttpRequest,
    problemFor: (HttpResponse) -> ExternalProblem,
): ExternalResult<JsonElement> {
    val response =
        try {
            get(request)
        } catch (e: HttpNoResponseException) {
            return ExternalResult.Problem(ExternalProblem.Unreachable)
        }
    if (response.status !in 200..299) return ExternalResult.Problem(problemFor(response))
    return try {
        ExternalResult.Ok(Json.parseToJsonElement(response.body))
    } catch (e: IllegalArgumentException) {
        ExternalResult.Problem(BAD_RESPONSE)
    }
}

internal val BAD_RESPONSE: ExternalProblem = ExternalProblem.Failed("bad-response")

/** Percent-encodes [value] for a URL query or path segment: everything but `A-Z a-z 0-9 - _ . ~`, as UTF-8. */
internal fun urlEncode(value: String): String =
    buildString {
        for (byte in value.encodeToByteArray()) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
                append(c)
            } else {
                append('%')
                append(HEX[b shr 4])
                append(HEX[b and 0x0F])
            }
        }
    }

private const val HEX = "0123456789ABCDEF"
