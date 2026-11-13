package thread.data

import thread.data.http.HttpRequest
import thread.data.http.HttpResponse
import thread.data.http.HttpTransport
import thread.data.synthetic.SyntheticHttpTransport
import thread.data.synthetic.SyntheticScenario

/** Records every request and answers with [respond]. */
class CapturingTransport(
    var respond: suspend (HttpRequest) -> HttpResponse,
) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun get(request: HttpRequest): HttpResponse {
        requests += request
        return respond(request)
    }
}

/** SYNTHETIC providers in the normal scenario, online, with the clock at [NOW]. */
fun syntheticTransport(scenario: SyntheticScenario = SyntheticScenario.Normal): CapturingTransport {
    val synthetic = SyntheticHttpTransport(scenario = { scenario }, isOnline = { true }, nowMillis = { NOW })
    return CapturingTransport { synthetic.get(it) }
}

fun answer(
    status: Int,
    body: String = "{}",
    headers: Map<String, String> = emptyMap(),
): HttpResponse = HttpResponse(status, headers, body)

const val NOW: Long = 1_800_000_000_000L
