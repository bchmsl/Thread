package thread.domain.calendar

import kotlinx.coroutines.flow.Flow

/**
 * The meetings the Mac app uploaded, read-only (D78, adr/ADR-020-calendar-meetings-sync.md). Platform code implements
 * it (Firestore on the web). Thread never writes meetings or the source.
 */
interface MeetingStore {
    /**
     * The device's copy, with times as wall-clock times in [zone]. Emits again on every change. Never fails: a read
     * the server refuses ends in [MeetingsState.Unavailable].
     */
    fun observe(zone: String): Flow<MeetingsState>
}

/** What Today and the status line know about meetings. */
sealed interface MeetingsState {
    /** Before the first answer, from the device's copy or the server. */
    data object Loading : MeetingsState

    /** The Mac app never wrote a source: meetings are not set up. Today shows no Meetings card. */
    data object NotSetUp : MeetingsState

    /** The Mac app's last pass and the meetings it uploaded. Meetings this version cannot read are left out. */
    data class Ready(
        val source: MeetingSource,
        val meetings: List<Meeting>,
    ) : MeetingsState

    /** The source has a newer shape than this version reads: "Update Thread to see meetings". */
    data object NeedsUpdate : MeetingsState

    /** The read failed, for example `permission-denied` before the rules are deployed. [code] is for diagnostics only. */
    data class Unavailable(
        val code: String,
    ) : MeetingsState
}
