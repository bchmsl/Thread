package thread.domain.calendar

/*
 * The owner's own title and join link for a meeting (D100). The calendar's values stay as the Mac app uploaded them;
 * these are app-owned, written by Thread, and win wherever the meeting shows. Both are optional. They cover a whole
 * series, like Topic links (QB), so a repeating meeting needs them once.
 */

/**
 * The alternate title and join link of the series [seriesKey]. [title] is trimmed and never blank. [joinUrl] passes
 * [isAlternateJoinUrl]. A field that is null shows the calendar's value; both null is the same as no alternate.
 */
data class MeetingAlternate(
    val seriesKey: SeriesKey,
    val title: String? = null,
    val joinUrl: String? = null,
) {
    init {
        require(title == null || alternateTitleOf(title) == title) { "An alternate title is trimmed, not blank and short enough" }
        require(joinUrl == null || isAlternateJoinUrl(joinUrl)) { "An alternate join link is an https link" }
    }

    val isEmpty: Boolean get() = title == null && joinUrl == null
}

/** A link Join opens. [service] is null for a link that is not on the allowlist ([joinServiceOf]): it shows plain "Join". */
data class MeetingJoin(
    val url: String,
    val service: JoinService?,
)

/** [text] as an alternate title: trimmed, or null when blank or longer than [MEETING_TEXT_MAX_LENGTH]. */
fun alternateTitleOf(text: String): String? = text.trim().takeIf { it.isNotEmpty() && it.length <= MEETING_TEXT_MAX_LENGTH }

/**
 * True when [url] can be an alternate join link: https, a host with a dot and an optional port, nothing between the
 * host and the path (so no `user@host`), printable ASCII only and at most [JOIN_URL_MAX_LENGTH]. Unlike the calendar's
 * link, any host is allowed: the owner typed it. The pattern matches the rules' `validAlternateJoinUrl`.
 */
fun isAlternateJoinUrl(url: String): Boolean = url.length <= JOIN_URL_MAX_LENGTH && ALTERNATE_JOIN_URL.matches(url)

private val ALTERNATE_JOIN_URL = Regex("""https://[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+(?::[0-9]{1,5})?(?:[/?#][!-~]*)?""")

/** Gives each of [meetings] its series' alternate from [alternates]; an empty alternate counts as none. */
fun withAlternates(
    meetings: List<Meeting>,
    alternates: Map<SeriesKey, MeetingAlternate>,
): List<Meeting> {
    if (alternates.isEmpty() && meetings.none { it.alternate != null }) return meetings
    return meetings.map { meeting ->
        val alternate = alternates[meeting.seriesKey]?.takeUnless { it.isEmpty }
        if (alternate == meeting.alternate) meeting else meeting.copy(alternate = alternate)
    }
}

/** [state] with each meeting's alternate. States without meetings are returned as they are. */
fun withAlternates(
    state: MeetingsState,
    alternates: Map<SeriesKey, MeetingAlternate>,
): MeetingsState =
    when (state) {
        is MeetingsState.Ready -> {
            val meetings = withAlternates(state.meetings, alternates)
            if (meetings === state.meetings) state else state.copy(meetings = meetings)
        }
        else -> state
    }
