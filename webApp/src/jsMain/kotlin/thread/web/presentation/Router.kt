package thread.web.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import org.w3c.dom.events.Event

/**
 * A place in the app, kept in the URL hash, for example `#/topic/t1` or `#/prs?tab=review`. The v3 prototype used
 * one HTML file per page with query parameters; the hash keeps deep links, back and forward in one page.
 */
internal data class Place(
    val path: List<String>,
    val params: Map<String, String> = emptyMap(),
) {
    val head: String get() = path.firstOrNull().orEmpty()

    fun segment(index: Int): String? = path.getOrNull(index)

    fun param(name: String): String? = params[name]

    fun with(
        name: String,
        value: String?,
    ): Place = copy(params = if (value == null) params - name else params + (name to value))

    val hash: String
        get() {
            val pathPart = path.joinToString("/") { encode(it) }
            val query = params.entries.joinToString("&") { (k, v) -> encode(k) + "=" + encode(v) }
            return "#/" + pathPart + if (query.isEmpty()) "" else "?$query"
        }

    companion object {
        fun of(
            vararg path: String,
            params: Map<String, String> = emptyMap(),
        ): Place = Place(path.toList(), params)

        /** Reads a hash such as `#/topic/t1?x=1`. Anything that is not an app place reads as the empty place. */
        fun parse(hash: String): Place {
            if (!hash.startsWith("#/")) return Place(emptyList())
            val body = hash.removePrefix("#/")
            val pathPart = body.substringBefore('?')
            val queryPart = body.substringAfter('?', "")
            val path = pathPart.split('/').filter { it.isNotEmpty() }.map { decode(it) }
            val params =
                queryPart
                    .split('&')
                    .filter { it.isNotEmpty() }
                    .associate { pair -> decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', "")) }
            return Place(path, params)
        }

        private fun encode(text: String): String = js("encodeURIComponent")(text).unsafeCast<String>()

        private fun decode(text: String): String =
            try {
                js("decodeURIComponent")(text).unsafeCast<String>()
            } catch (e: Throwable) {
                text
            }
    }
}

/** The app's views in the order of the status line, plus Settings and Trash (v3 shell.js). */
internal enum class View(
    val id: String,
    val label: String,
    val short: String?,
    val icon: Icon,
    val inLine: Boolean,
) {
    Today("today", "Today", null, Icon.Today, true),
    Inbox("inbox", "Inbox", null, Icon.Inbox, true),
    Topics("topics", "Topics", null, Icon.Topics, true),
    Notes("notes", "Notes", null, Icon.Notes, true),
    Jira("jira", "Jira", null, Icon.Jira, true),
    Prs("prs", "Pull requests", "PRs", Icon.Pr, true),
    Settings("settings", "Settings", null, Icon.Settings, false),
    Trash("trash", "Trash", null, Icon.Trash, false),
    ;

    val place: Place get() = Place.of(id)

    companion object {
        /** The view a place belongs to. Topic details count as Topics, a Jira or PR page as its list. */
        fun of(place: Place): View =
            when (place.head) {
                "topic" -> Topics
                "item" -> if (place.segment(1) == "pr") Prs else Jira
                else -> entries.firstOrNull { it.id == place.head } ?: Today
            }
    }
}

/** The current [Place], following the URL hash. [go] adds a history entry; [replace] does not. */
internal class Router {
    var place: Place by mutableStateOf(Place.parse(window.location.hash))
        private set

    fun go(to: Place) {
        if (to.hash == window.location.hash) return
        window.location.hash = to.hash
    }

    fun replace(to: Place) {
        window.history.replaceState(null, "", to.hash)
        place = to
    }

    /** Listens for hash changes (links, back, forward). Returns a function that stops listening. */
    fun start(): () -> Unit {
        val listener: (Event) -> Unit = {
            val hash = window.location.hash
            // In-page anchors such as the skip link are not places.
            if (hash.isEmpty() || hash.startsWith("#/")) place = Place.parse(hash)
        }
        window.addEventListener("hashchange", listener)
        return { window.removeEventListener("hashchange", listener) }
    }
}
