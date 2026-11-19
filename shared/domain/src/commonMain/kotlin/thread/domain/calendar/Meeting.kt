package thread.domain.calendar

import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.TopicId
import kotlin.jvm.JvmInline

/*
 * Work meetings from the owner's calendar (D78, adr/ADR-020-calendar-meetings-sync.md). The "Thread Calendar" Mac app
 * reads one calendar with EventKit and uploads these fields only, from yesterday to 14 days ahead. Thread reads them and
 * never changes them; it writes only the Topic links and the owner's alternate title and link ([MeetingAlternate],
 * D100). Times are wall-clock times in the workspace time zone: the data
 * layer converts the stored instants, so these rules never see a time zone.
 */

/** True for a meeting id or a series key: a SHA-256 hash made by the Mac app, as 32 lowercase hex. */
fun isMeetingHash(text: String): Boolean = MEETING_HASH.matches(text)

private val MEETING_HASH = Regex("[0-9a-f]{32}")

/** One meeting's id, or one occurrence's for a repeating meeting. A hash, so no raw calendar id is stored. */
@JvmInline
value class MeetingId(
    val value: String,
) {
    init {
        require(isMeetingHash(value)) { "A meeting id is 32 lowercase hex" }
    }

    override fun toString(): String = value
}

/** The same for every occurrence of a repeating meeting, also one that was moved. Topic links use it (QB). */
@JvmInline
value class SeriesKey(
    val value: String,
) {
    init {
        require(isMeetingHash(value)) { "A series key is 32 lowercase hex" }
    }

    override fun toString(): String = value
}

/** The owner's answer to the invite. Declined meetings are never stored (Q4). */
enum class MeetingResponse(
    val id: String,
) {
    Accepted("accepted"),
    Tentative("tentative"),

    /** Not answered yet. */
    Pending("pending"),

    /** No answer to give, for example a meeting the owner organizes. Shown without a badge. */
    None("none"),
    ;

    companion object {
        fun fromId(id: String?): MeetingResponse? = entries.firstOrNull { it.id == id }
    }
}

/** When a meeting happens. */
sealed interface MeetingTime {
    /** The first minute, as a wall-clock time, for ordering. All-day meetings start at midnight. */
    val startsAt: WallTime

    /** The minute after the meeting. All-day meetings end at the midnight after their last day. */
    val endsAt: WallTime

    /** From [start] to [end] in the workspace time zone. [end] equal to [start] is a meeting with no length. */
    data class Timed(
        val start: WallTime,
        val end: WallTime,
    ) : MeetingTime {
        init {
            require(end >= start) { "A meeting ends at or after its start" }
        }

        override val startsAt: WallTime get() = start
        override val endsAt: WallTime get() = end
    }

    /** Whole days, never instants, so they never shift (like a Task's planned day). [lastDayExclusive] is not part of it. */
    data class AllDay(
        val firstDay: LocalDay,
        val lastDayExclusive: LocalDay,
    ) : MeetingTime {
        init {
            require(lastDayExclusive > firstDay) { "An all-day meeting has at least one day" }
        }

        override val startsAt: WallTime get() = WallTime(firstDay, 0)
        override val endsAt: WallTime get() = WallTime(lastDayExclusive, 0)
    }
}

/**
 * One meeting, or one occurrence of a repeating meeting. [title] may be empty (shown as [UNTITLED_MEETING]).
 * [joinUrl], when present, is an https link on the allowlist ([joinServiceOf]); it may hold a passcode, so it is never
 * shown as text. [title], [location] and [joinUrl] are the calendar's. [alternate] is the owner's own title and link for
 * the series (D100), which win over the calendar's: read [displayTitle] and [join], not [title] and [joinUrl].
 */
data class Meeting(
    val id: MeetingId,
    val seriesKey: SeriesKey,
    val title: String,
    val time: MeetingTime,
    val response: MeetingResponse,
    val location: String? = null,
    val joinUrl: String? = null,
    val alternate: MeetingAlternate? = null,
) {
    /** What Thread shows: the alternate title when set, else the calendar's. */
    val displayTitle: String get() = alternate?.title ?: calendarTitle

    /** The calendar's own title, also when an alternate title is set. */
    val calendarTitle: String get() = title.ifBlank { UNTITLED_MEETING }

    /** What Join opens: the alternate link when set, else the calendar's. Null when there is none to offer. */
    val join: MeetingJoin? get() = alternate?.joinUrl?.let { MeetingJoin(it, joinServiceOf(it)) } ?: calendarJoin

    /** The calendar's own join link, also when an alternate link is set. */
    val calendarJoin: MeetingJoin? get() = joinUrl?.let { url -> joinServiceOf(url)?.let { MeetingJoin(url, it) } }
}

/** Shown for a meeting without a title. */
const val UNTITLED_MEETING: String = "(No title)"

/** Longest title, location and Topic link title, in UTF-16 units, as the rules count them. */
const val MEETING_TEXT_MAX_LENGTH: Int = 300

/** Longest join link. */
const val JOIN_URL_MAX_LENGTH: Int = 2048

/** The services a meeting's Join button can open. The label names the service: "Join (Teams)". */
enum class JoinService(
    val label: String,
) {
    Teams("Teams"),
    Zoom("Zoom"),
    GoogleMeet("Google Meet"),
    Webex("Webex"),
}

/**
 * The service [url] opens, or null when Thread must not offer it. Thread checks again what the rules already checked
 * before it shows Join: https, a host on the allowlist and nothing between the host and the path, so a lookalike host
 * or a `user@host` link gets no button. The pattern matches the rules' `validJoinUrl`.
 */
fun joinServiceOf(url: String): JoinService? {
    if (url.length > JOIN_URL_MAX_LENGTH) return null
    val host = JOIN_URL.matchEntire(url)?.groupValues?.get(1) ?: return null
    return when {
        host == "teams.microsoft.com" || host == "teams.live.com" -> JoinService.Teams
        host == "zoom.us" || host.endsWith(".zoom.us") -> JoinService.Zoom
        host == "meet.google.com" -> JoinService.GoogleMeet
        else -> JoinService.Webex
    }
}

private val JOIN_URL =
    Regex(
        """https://(teams\.microsoft\.com|teams\.live\.com|(?:[a-z0-9-]+\.)*zoom\.us|meet\.google\.com|(?:[a-z0-9-]+\.)+webex\.com)""" +
            """(?:[/?#].*)?""",
    )

/** What the Mac app last reported. */
enum class MeetingSourceState(
    val id: String,
) {
    Ok("ok"),

    /** The Mac app has no calendar access. */
    NoAccess("noAccess"),

    /** The picked calendar is gone, or none is picked. */
    NoCalendar("noCalendar"),

    /** The owner paused syncing on the Mac. */
    Paused("paused"),
    ;

    companion object {
        fun fromId(id: String?): MeetingSourceState? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The Mac app's last pass (`meetingSource/main`). [lastSuccessAtMillis] is when the last pass that succeeded wrote it;
 * [fromDay] to [toDay], both included, are the days that pass covered. Null when no pass has succeeded yet.
 */
data class MeetingSource(
    val state: MeetingSourceState,
    val calendarTitle: String? = null,
    val lastSuccessAtMillis: Long? = null,
    val fromDay: LocalDay? = null,
    val toDay: LocalDay? = null,
    val appVersion: String = "",
)

/**
 * One Topic's link to a meeting series (Q8, QB). Its id is made from the pair, so linking twice writes one document.
 * Unlinking sets [linked] to false; nothing is deleted. [title] is a snapshot, so the Topic still names the meeting
 * after it leaves the window (QA).
 */
data class MeetingLink(
    val topicId: TopicId,
    val seriesKey: SeriesKey,
    val linked: Boolean = true,
    val title: String? = null,
) {
    /** Topic ids never contain `_`, so the pair is unique. */
    val id: String get() = "${topicId.value}_${seriesKey.value}"
}
