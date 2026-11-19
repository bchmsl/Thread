package thread.web.presentation.calendar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import thread.domain.calendar.Meeting
import thread.domain.calendar.MeetingAlternate
import thread.domain.calendar.MeetingStore
import thread.domain.calendar.MeetingsState
import thread.domain.calendar.SeriesKey
import thread.domain.calendar.withAlternates
import thread.web.platform.readDeviceText
import thread.web.platform.writeDeviceText

/**
 * The meetings the Mac app uploaded (D78, adr/ADR-020-calendar-meetings-sync.md), for Today, the status line, the
 * Meeting card and Settings. Read-only: Thread never writes meetings.
 *
 * Each meeting carries the title and join link set for its series in Thread, if any ([alternates], D100), so every place
 * that shows a meeting shows them in place of the calendar's.
 *
 * This device remembers whether it has seen meetings set up, so Today keeps the Meetings card's place while the first
 * answer of a page loads, and an account without meetings never sees an empty card flash.
 */
internal class MeetingsModel(
    private val store: MeetingStore,
    private val alternatesOf: () -> Map<SeriesKey, MeetingAlternate> = { emptyMap() },
) {
    /** What the store last said, without the alternates. */
    private var calendar: MeetingsState by mutableStateOf(MeetingsState.Loading)

    /** The meetings as Thread shows them: [calendar] with the [alternates] of their series. */
    val state: MeetingsState
        get() {
            val raw = calendar
            val alternates = alternatesOf()
            if (raw !== shownFrom || (alternates !== shownWith && alternates != shownWith)) {
                shown = withAlternates(raw, alternates)
                shownFrom = raw
                shownWith = alternates
            }
            return shown
        }

    // Worked out once per store answer and set of alternates: screens read the state many times per frame.
    private var shownFrom: MeetingsState? = null
    private var shownWith: Map<SeriesKey, MeetingAlternate> = emptyMap()
    private var shown: MeetingsState = MeetingsState.Loading

    /** The title and join link set for each series that has one (D100). */
    val alternates: Map<SeriesKey, MeetingAlternate> get() = alternatesOf()

    private var seen by mutableStateOf(readDeviceText(SEEN_NAME) == "1")

    /**
     * The meetings are set up for Today. Then the Meetings card, or the timeline, shows unless Settings hides it while
     * it has nothing to show (D86, [TodayMeetingsView.hasContent]).
     */
    val cardShown: Boolean get() = meetingsCardShown(state, seen)

    /** The meetings, empty unless [state] is Ready. */
    val meetings: List<Meeting> get() = (state as? MeetingsState.Ready)?.meetings.orEmpty()

    fun meeting(id: String): Meeting? = meetings.firstOrNull { it.id.value == id }

    /** Follows the store with times in [zone] until cancelled. A read that fails ends with Unavailable. */
    suspend fun follow(zone: String) {
        store.observe(zone).collect { next ->
            calendar = next
            when (next) {
                is MeetingsState.Ready -> keepSeen(true)
                MeetingsState.NotSetUp -> keepSeen(false)
                else -> Unit
            }
        }
    }

    private fun keepSeen(value: Boolean) {
        if (seen == value) return
        seen = value
        writeDeviceText(SEEN_NAME, if (value) "1" else "0")
    }

    private companion object {
        /** Where this device keeps whether it has seen meetings set up: `1` or `0`. Not synced. */
        const val SEEN_NAME = "thread.meetings.seen"
    }
}

/**
 * Whether Today shows the Meetings card. Set up (Ready) or in need of an update: yes. Not set up: no. While loading or
 * unavailable, as last seen on this device, so the card neither flashes in nor disappears.
 */
internal fun meetingsCardShown(
    state: MeetingsState,
    seen: Boolean,
): Boolean =
    when (state) {
        is MeetingsState.Ready, MeetingsState.NeedsUpdate -> true
        MeetingsState.NotSetUp -> false
        MeetingsState.Loading, is MeetingsState.Unavailable -> seen
    }
