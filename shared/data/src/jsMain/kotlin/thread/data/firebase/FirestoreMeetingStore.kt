package thread.data.firebase

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import thread.data.local.newJsObject
import thread.domain.account.AccountId
import thread.domain.calendar.Meeting
import thread.domain.calendar.MeetingId
import thread.domain.calendar.MeetingResponse
import thread.domain.calendar.MeetingSource
import thread.domain.calendar.MeetingSourceState
import thread.domain.calendar.MeetingStore
import thread.domain.calendar.MeetingTime
import thread.domain.calendar.MeetingsState
import thread.domain.calendar.SeriesKey
import thread.domain.calendar.isMeetingHash
import thread.domain.calendar.joinServiceOf
import thread.domain.time.LocalDay
import thread.domain.time.WallTime

/**
 * Reads `users/{uid}/meetings` and `users/{uid}/meetingSource/main`, which the Mac app writes
 * (adr/ADR-020-calendar-meetings-sync.md). Never writes them. The Firestore cache keeps the last copy, so Today shows
 * meetings offline too, with the time of the Mac app's last pass.
 *
 * [wallTimeAt] turns a stored instant into a wall-clock time in a zone (the browser's `Intl`), so the domain never
 * sees an instant.
 */
class FirestoreMeetingStore internal constructor(
    private val firestore: Firestore,
    private val accountId: AccountId,
    private val wallTimeAt: (Double, String) -> WallTime,
) : MeetingStore {
    constructor(
        backend: FirebaseBackend,
        accountId: AccountId,
        wallTimeAt: (Double, String) -> WallTime,
    ) : this(backend.firestore, accountId, wallTimeAt)

    override fun observe(zone: String): Flow<MeetingsState> =
        callbackFlow {
            var source: DocumentSnapshot? = null
            var meetings: QuerySnapshot? = null
            val emitWhenComplete = {
                val s = source
                val m = meetings
                if (s != null && m != null) trySend(meetingsStateOf(s, m, zone))
            }
            // A listener stops for good after an error. The state says so and the flow ends; the caller listens again
            // later (when the device is back online, or on the next page).
            val stop = { error: FirestoreError ->
                trySend(MeetingsState.Unavailable(error.code))
                close()
                Unit
            }
            // Metadata changes for the source only: a missing source read from the cache becomes Not set up once the
            // server agrees, a change that touches only the metadata.
            val sourceOptions = newJsObject()
            sourceOptions.includeMetadataChanges = true
            val stopSource =
                onDocumentSnapshot(doc(firestore, USERS, accountId.value, MEETING_SOURCE, MEETING_SOURCE_ID), sourceOptions, {
                    source = it
                    emitWhenComplete()
                }, stop)
            val stopMeetings =
                onCollectionSnapshot(collection(firestore, USERS, accountId.value, MEETINGS), newJsObject(), {
                    meetings = it
                    emitWhenComplete()
                }, stop)
            awaitClose {
                stopSource()
                stopMeetings()
            }
        }.conflate()

    private fun meetingsStateOf(
        source: DocumentSnapshot,
        meetings: QuerySnapshot,
        zone: String,
    ): MeetingsState {
        if (!source.exists()) return if (source.metadata.fromCache) MeetingsState.Loading else MeetingsState.NotSetUp
        val options = estimatedTimestamps()
        return meetingsStateFrom(
            sourceData = source.data(options),
            meetings = meetings.docs.map { it.id to it.data(options) },
            zone = zone,
            wallTimeAt = wallTimeAt,
        )
    }

    internal companion object {
        const val USERS = "users"
        const val MEETINGS = "meetings"
        const val MEETING_SOURCE = "meetingSource"
        const val MEETING_SOURCE_ID = "main"
        const val SCHEMA_VERSION = 1
    }
}

/**
 * The state for a stored source and its meetings. A source this version cannot read asks for an update; a meeting it
 * cannot read is left out, so one bad document never hides the others.
 */
internal fun meetingsStateFrom(
    sourceData: dynamic,
    meetings: List<Pair<String, dynamic>>,
    zone: String,
    wallTimeAt: (Double, String) -> WallTime,
): MeetingsState {
    val source = meetingSourceFrom(sourceData) ?: return MeetingsState.NeedsUpdate
    return MeetingsState.Ready(source, meetings.mapNotNull { (id, data) -> meetingFrom(id, data, zone, wallTimeAt) })
}

/** The Mac app's last pass, or null when the document has another schema version or an unknown state. */
internal fun meetingSourceFrom(data: dynamic): MeetingSource? {
    if (data == null || !isSchemaVersion(data.schemaVersion)) return null
    val state = MeetingSourceState.fromId(stringOrNull(data.state)) ?: return null
    return MeetingSource(
        state = state,
        calendarTitle = stringOrNull(data.calendarTitle)?.takeIf { it.isNotBlank() },
        lastSuccessAtMillis = timestampMillisOrNull(data.lastSuccessAt)?.toLong(),
        fromDay = stringOrNull(data.fromDay)?.let(LocalDay::parse),
        toDay = stringOrNull(data.toDay)?.let(LocalDay::parse),
        appVersion = stringOrNull(data.appVersion).orEmpty(),
    )
}

/**
 * One meeting, or null when this version cannot read it: another schema version, a bad id or series key, a missing
 * field, or an unknown response. A timed meeting's instants become wall-clock times in [zone]; an end before the start
 * (a clock change inside the meeting) becomes the start. A join link off the allowlist is dropped, the meeting kept.
 */
internal fun meetingFrom(
    id: String,
    data: dynamic,
    zone: String,
    wallTimeAt: (Double, String) -> WallTime,
): Meeting? {
    if (data == null || !isMeetingHash(id) || !isSchemaVersion(data.schemaVersion)) return null
    val seriesKey = stringOrNull(data.seriesKey)?.takeIf(::isMeetingHash) ?: return null
    val title = stringOrNull(data.title) ?: return null
    val response = MeetingResponse.fromId(stringOrNull(data.response)) ?: return null
    val time =
        when (booleanOrNull(data.allDay)) {
            true -> {
                val first = stringOrNull(data.firstDay)?.let(LocalDay::parse) ?: return null
                val last = stringOrNull(data.lastDayExclusive)?.let(LocalDay::parse) ?: return null
                if (last <= first) return null
                MeetingTime.AllDay(first, last)
            }
            false -> {
                val start = wallTimeAt(timestampMillisOrNull(data.start) ?: return null, zone)
                val end = wallTimeAt(timestampMillisOrNull(data.end) ?: return null, zone)
                MeetingTime.Timed(start, maxOf(start, end))
            }
            null -> return null
        }
    return Meeting(
        id = MeetingId(id),
        seriesKey = SeriesKey(seriesKey),
        title = title,
        time = time,
        response = response,
        location = stringOrNull(data.location)?.takeIf { it.isNotBlank() },
        joinUrl = stringOrNull(data.joinUrl)?.takeIf { joinServiceOf(it) != null },
    )
}

private fun isSchemaVersion(value: dynamic): Boolean = jsTypeOf(value) == "number" && value == FirestoreMeetingStore.SCHEMA_VERSION

/** A Firestore Timestamp's milliseconds, or null for anything else. */
private fun timestampMillisOrNull(value: dynamic): Double? {
    if (value == null || jsTypeOf(value.toMillis) != "function") return null
    val millis = value.unsafeCast<Timestamp>().toMillis()
    return if (millis.isNaN()) null else millis
}

private fun stringOrNull(value: dynamic): String? = if (jsTypeOf(value) == "string") value.unsafeCast<String>() else null

private fun booleanOrNull(value: dynamic): Boolean? = if (jsTypeOf(value) == "boolean") value.unsafeCast<Boolean>() else null

/** Pending server timestamps read as the device's estimate instead of null. */
private fun estimatedTimestamps(): dynamic {
    val options = newJsObject()
    options.serverTimestamps = "estimate"
    return options
}
