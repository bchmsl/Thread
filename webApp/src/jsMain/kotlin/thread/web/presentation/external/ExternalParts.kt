package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Code
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import thread.domain.external.CiState
import thread.domain.external.CiSummary
import thread.domain.external.ExternalBlock
import thread.domain.external.ExternalDocument
import thread.domain.external.ExternalLoader
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalView
import thread.domain.external.JiraStatus
import thread.domain.external.JiraStatusCategory
import thread.domain.external.ReviewState
import thread.domain.external.Saved
import thread.domain.note.LinkHref
import thread.web.platform.nowMillis
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.notes.Banner
import thread.web.presentation.notes.BannerButton
import thread.web.presentation.notes.BannerTone
import thread.web.presentation.notes.RichTextBlockView
import kotlin.js.Date
import kotlin.math.ceil

/** Loads [key] and returns the latest view, or null before the first one. A new [reload] value loads again. */
@Composable
internal fun <K : Any, T> rememberExternalView(
    loader: ExternalLoader<K, T>,
    key: K?,
    reload: Any,
): ExternalView<T>? {
    var view by remember(loader, key) { mutableStateOf<ExternalView<T>?>(null) }
    LaunchedEffect(loader, key, reload) {
        if (key != null) loader.load(key).collect { view = it }
    }
    return view
}

/** The saved copy a view shows, if any. */
internal val <T> ExternalView<T>.savedOrNull: Saved<T>?
    get() =
        when (this) {
            is ExternalView.Fresh -> saved
            is ExternalView.Refreshing -> saved
            is ExternalView.SavedCopy -> saved
            else -> null
        }

internal val ExternalView<*>.problemOrNull: ExternalProblem?
    get() =
        when (this) {
            is ExternalView.SavedCopy -> problem
            is ExternalView.Missing -> problem
            else -> null
        }

/** Whether the copy shown is also kept on this device. */
internal val ExternalView<*>.isOnDevice: Boolean
    get() = this !is ExternalView.Fresh || storedOnDevice

internal class Message(
    val tone: BannerTone,
    val icon: Icon,
    val title: String,
    val text: String,
)

/**
 * The v3 external-state wording for [problem] (external.js banners and empty states). [savedAtMillis] is the time of
 * the saved copy on screen, or null when there is none. [what] is the plural record name, such as "tickets".
 */
internal fun describe(
    problem: ExternalProblem,
    provider: String,
    what: String,
    savedAtMillis: Long?,
    nowMillis: Long,
): Message {
    val saved = savedAtMillis?.let { " Showing the saved copy from ${Times.clock(it)}." } ?: ""
    return when (problem) {
        ExternalProblem.Offline ->
            if (savedAtMillis != null) {
                Message(BannerTone.Warn, Icon.CloudOff, "Offline", saved.trim() + " Refresh works again when you are back online.")
            } else {
                Message(
                    BannerTone.Warn,
                    Icon.CloudOff,
                    "Not saved on this device",
                    "This one was never opened here, so there is no copy to show offline. Connect to the internet to load it.",
                )
            }
        ExternalProblem.NotConnected ->
            Message(
                BannerTone.Info,
                Icon.Unlink,
                "$provider is not connected",
                "Connect it in Settings to see your $what here. Thread only reads; it never changes anything in $provider.",
            )
        ExternalProblem.Unauthorized ->
            Message(
                BannerTone.Bad,
                Icon.Lock,
                "$provider access expired",
                if (savedAtMillis != null) {
                    "Saved copies stay readable. Reconnect to refresh them."
                } else {
                    "The token expired, was revoked or is wrong. Replace it in Settings, then refresh."
                },
            )
        ExternalProblem.Forbidden ->
            Message(BannerTone.Bad, Icon.Lock, "$provider refused access", "The token may not read this. Check its permissions.$saved")
        ExternalProblem.NotFound ->
            Message(BannerTone.Warn, Icon.Search, "Not found", "It may not exist, or it is not shared with you.")
        is ExternalProblem.RateLimited -> {
            val minutes = ceil((problem.retryAtMillis - nowMillis) / 60_000.0).toInt().coerceAtLeast(1)
            val next = if (minutes == 1) "about a minute" else "about $minutes minutes"
            Message(BannerTone.Warn, Icon.Clock, "$provider is limiting requests", "Next refresh in $next.$saved")
        }
        ExternalProblem.Unreachable ->
            Message(
                BannerTone.Warn,
                Icon.CloudOff,
                "$provider did not answer",
                "The network failed, or the browser blocked the request.$saved",
            )
        is ExternalProblem.Failed ->
            Message(BannerTone.Warn, Icon.Alert, "Refresh failed", "$provider answered with an error (${problem.code}).$saved")
    }
}

/** The banner above a record, or nothing when the copy on screen is fresh and saved. */
@Composable
internal fun ViewBanner(
    view: ExternalView<*>?,
    provider: String,
    what: String,
    onGoToConnections: () -> Unit,
) {
    val now = Times.now()
    when (view) {
        is ExternalView.Refreshing ->
            Banner(
                BannerTone.Info,
                Icon.Refresh,
                "Refreshing",
                "Showing the saved copy from ${Times.clock(view.saved.savedAtMillis)} until $provider answers.",
            )
        is ExternalView.SavedCopy -> {
            val message = describe(view.problem, provider, what, view.saved.savedAtMillis, now)
            Banner(message.tone, message.icon, message.title, message.text) {
                if (view.problem == ExternalProblem.Unauthorized || view.problem == ExternalProblem.Forbidden) {
                    BannerButton(Icon.Lock, "Reconnect", onGoToConnections)
                }
            }
        }
        is ExternalView.Fresh ->
            if (!view.storedOnDevice) {
                Banner(
                    BannerTone.Warn,
                    Icon.Device,
                    "Not saved on this device",
                    "This copy could not be saved here, so it will not be available offline.",
                )
            }
        else -> Unit
    }
}

/** The empty state for a record that has nothing to show. */
@Composable
internal fun MissingState(
    problem: ExternalProblem,
    provider: String,
    what: String,
    notFoundTitle: String,
    notFoundText: String,
    onGoToConnections: () -> Unit,
) {
    val message = describe(problem, provider, what, null, Times.now())
    val isNotFound = problem == ExternalProblem.NotFound
    EmptyState(message.icon, if (isNotFound) notFoundTitle else message.title, if (isNotFound) notFoundText else message.text) {
        if (problem == ExternalProblem.NotConnected || problem == ExternalProblem.Unauthorized || problem == ExternalProblem.Forbidden) {
            GoToConnectionsButton(onGoToConnections)
        }
    }
}

@Composable
internal fun GoToConnectionsButton(onClick: () -> Unit) {
    Button({
        classes("btn", "btn--line")
        type(ButtonType.Button)
        onClick { onClick() }
    }) { Text("Open Settings") }
}

/**
 * The v3 cache bar: when the copy on screen is from, and Refresh. [refreshName] is the button's accessible name, such as
 * "Refresh this ticket": a list and an open record each have a Refresh, and screen readers must tell them apart. It
 * starts with the visible word, so speech input still finds the button.
 */
@Composable
internal fun CacheBar(
    view: ExternalView<*>,
    refreshName: String,
    onRefresh: () -> Unit,
) {
    val busy = view is ExternalView.Refreshing || view is ExternalView.Loading
    val saved = view.savedOrNull
    val (icon, label) =
        when {
            view is ExternalView.Fresh -> Icon.CloudCheck to "Updated ${Times.clock(view.saved.savedAtMillis)}"
            busy -> Icon.Refresh to "Refreshing…"
            saved != null -> Icon.Device to "Saved copy from ${Times.clock(saved.savedAtMillis)}"
            else -> Icon.CloudOff to "Nothing saved yet"
        }
    Div({ classes("cachebar") }) {
        IconView(icon, 15)
        Span { Text(label) }
        Span({ classes("cachebar__sp") })
        Button({
            classes("btn", "btn--text", "btn--sm")
            type(ButtonType.Button)
            attr("aria-label", refreshName)
            if (busy) disabled()
            onClick { onRefresh() }
        }) {
            IconView(Icon.Refresh, 15)
            Text("Refresh")
        }
    }
}

/**
 * Jira rich text, read-only (adr/ADR-006). Built from DOM elements only, never HTML strings. Content Thread does not
 * show becomes the v3 placeholder.
 */
@Composable
internal fun ExternalDocumentView(document: ExternalDocument) {
    document.blocks.forEach { block ->
        when (block) {
            is ExternalBlock.Rich -> RichTextBlockView(block.block, smallHeadings = true)
            is ExternalBlock.Code -> Pre { Code { Text(block.text) } }
            is ExternalBlock.Unsupported ->
                P({ classes("unsup") }) {
                    IconView(Icon.Doc, 16)
                    Text("${block.kind} is not supported here. Open the ticket in Jira to see it.")
                }
        }
    }
}

/** A link to the record on the provider's site, in a new tab. Hidden in the fixture mode. */
@Composable
internal fun OpenOutside(
    href: LinkHref?,
    label: String,
    hidden: Boolean,
) {
    if (hidden || href == null) return
    A(href.value, {
        classes("btn", "btn--text")
        target(ATarget.Blank)
        attr("rel", "noopener noreferrer")
    }) {
        IconView(Icon.External, 16)
        Text(label)
    }
}

@Composable
internal fun ReadOnlyNote(provider: String) {
    P({ classes("ro-note") }) {
        IconView(Icon.Lock, 15)
        Text("Read-only in v1. Changes happen in $provider.")
    }
}

@Composable
internal fun OpenPill() {
    Span({ classes("pill-open") }) {
        IconView(Icon.Pr, 13)
        Text("Open")
    }
}

@Composable
internal fun CiIndicator(ci: CiSummary) {
    val (css, icon, text) =
        when (ci.state) {
            CiState.Passing -> Triple("ind--pass", Icon.CiPass, "Checks passed")
            CiState.Failing -> Triple("ind--fail", Icon.CiFail, "Checks failing")
            CiState.Running -> Triple("ind--running", Icon.CiRun, "Checks running")
            CiState.None -> Triple("ind--none", Icon.CiRun, "No checks")
        }
    Span({ classes("ind", css) }) {
        IconView(icon, 15)
        Span({ classes("vh") }) { Text("CI: ") }
        Text(text)
    }
}

@Composable
internal fun ReviewIndicator(review: ReviewState) {
    val (css, icon, text) =
        when (review) {
            ReviewState.Approved -> Triple("ind--approved", Icon.Approved, "Approved")
            ReviewState.ChangesRequested -> Triple("ind--changes", Icon.Changes, "Changes requested")
            ReviewState.ReviewRequested -> Triple("ind--requested", Icon.Eye, "Review requested")
            ReviewState.NoReviews -> Triple("ind--none", Icon.Eye, "No reviews yet")
        }
    Span({ classes("ind", css) }) {
        IconView(icon, 15)
        Span({ classes("vh") }) { Text("Review: ") }
        Text(text)
    }
}

/** Two letters for a comment avatar. */
internal fun initials(name: String?): String =
    name
        ?.split(Regex("\\s+"))
        ?.filter { it.isNotEmpty() }
        ?.take(2)
        ?.joinToString("") { it.take(1) }
        ?.uppercase()
        ?.ifEmpty { null } ?: "?"

/** Moves focus to [id], for a detail title or the Connections heading. */
internal fun focusElement(id: String) {
    val element = document.getElementById(id) as? HTMLElement ?: return
    element.scrollIntoView(js("({ block: 'start', behavior: 'smooth' })"))
    element.asDynamic().focus(js("({ preventScroll: true })"))
}

/** Runs [block] and returns [fallback] when it throws. Device storage errors must not break the page. */
internal suspend fun <R> orFallback(
    fallback: R,
    block: suspend () -> R,
): R =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        fallback
    }

/** Local times in the v3 formats (store.js D.time and D.when). */
internal object Times {
    private val DAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun now(): Long = nowMillis()

    /** `16:42`. */
    fun clock(millis: Long): String {
        val date = Date(millis.toDouble())
        return pad(date.getHours()) + ":" + pad(date.getMinutes())
    }

    /** `16:42:10`. */
    fun clockWithSeconds(millis: Long): String = clock(millis) + ":" + pad(Date(millis.toDouble()).getSeconds())

    /** `Today 16:42`, `Yesterday 16:42`, `Thu 12 Nov 16:42`, with the year when it is not this year. */
    fun whenText(
        millis: Long,
        nowMillis: Long = now(),
    ): String {
        val date = Date(millis.toDouble())
        val today = Date(nowMillis.toDouble())
        val yesterday = Date(today.getFullYear(), today.getMonth(), today.getDate() - 1)
        val day =
            when {
                sameDay(date, today) -> "Today"
                sameDay(date, yesterday) -> "Yesterday"
                else -> {
                    val year = if (date.getFullYear() != today.getFullYear()) " ${date.getFullYear()}" else ""
                    "${DAYS[date.getDay()]} ${date.getDate()} ${MONTHS[date.getMonth()]}$year"
                }
            }
        return "$day ${clock(millis)}"
    }

    private fun sameDay(
        a: Date,
        b: Date,
    ): Boolean = a.getFullYear() == b.getFullYear() && a.getMonth() == b.getMonth() && a.getDate() == b.getDate()

    private fun pad(value: Int): String = value.toString().padStart(2, '0')
}

/**
 * The classes of a Jira status lozenge, colored by Jira's status category the way Jira Cloud shows it (D50): To Do
 * gray, In Progress blue, Done green. A status Jira gives no known category keeps the plain outline.
 */
internal fun jiraStatusClasses(category: JiraStatusCategory): List<String> =
    listOf(
        "jst",
        when (category) {
            JiraStatusCategory.ToDo -> "jst--todo"
            JiraStatusCategory.InProgress -> "jst--progress"
            JiraStatusCategory.Done -> "jst--done"
            JiraStatusCategory.Unknown -> "jst--unknown"
        },
    )

/** A Jira status as a colored lozenge. The status name stays the text, so the color never carries meaning alone. */
@Composable
internal fun JiraStatusLozenge(status: JiraStatus) {
    Span({ classes(*jiraStatusClasses(status.category).toTypedArray()) }) { Text(status.name) }
}
