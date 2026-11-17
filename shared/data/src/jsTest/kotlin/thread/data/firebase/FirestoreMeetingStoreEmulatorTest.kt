package thread.data.firebase

import kotlinx.coroutines.await
import kotlinx.coroutines.flow.first
import thread.domain.account.AccountId
import thread.domain.calendar.MeetingResponse
import thread.domain.calendar.MeetingSourceState
import thread.domain.calendar.MeetingTime
import thread.domain.calendar.MeetingsState
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [FirestoreMeetingStore] against the emulators and the committed rules (adr/ADR-020-calendar-meetings-sync.md). The
 * owner's device writes what the Mac app would write; the store only reads. All accounts, titles and ids are SYNTHETIC.
 */
class FirestoreMeetingStoreEmulatorTest {
    private val now = Date.now()

    private fun utcWallTime(
        millis: Double,
        zone: String,
    ): WallTime {
        assertEquals(ZONE, zone)
        val whole = millis.toLong()
        return WallTime(LocalDay.fromEpochDay(whole.floorDiv(DAY_MILLIS)), (whole.mod(DAY_MILLIS) / MINUTE_MILLIS).toInt())
    }

    private fun meeting(
        title: String,
        startOffsetMillis: Double,
    ): dynamic =
        jsObject(
            "title" to title,
            "allDay" to false,
            "start" to Date(now + startOffsetMillis),
            "end" to Date(now + startOffsetMillis + HOUR),
            "response" to "tentative",
            "seriesKey" to SERIES_KEY,
            "updatedAt" to serverTimestamp(),
            "schemaVersion" to 1,
        )

    private fun source(): dynamic =
        jsObject(
            "state" to "ok",
            "calendarTitle" to "Synthetic work calendar",
            "fromDay" to "2026-11-16",
            "toDay" to "2026-12-01",
            "zone" to ZONE,
            "uploaderId" to "synthetic-uploader-1",
            "appVersion" to "1.0.0",
            "lastSuccessAt" to serverTimestamp(),
            "updatedAt" to serverTimestamp(),
            "schemaVersion" to 1,
        )

    private fun meetingRef(
        device: EmulatedDevice,
        id: String,
    ) = doc(device.backend.firestore, "users", device.accountId.value, "meetings", id)

    private fun sourceRef(device: EmulatedDevice) = doc(device.backend.firestore, "users", device.accountId.value, "meetingSource", "main")

    @Test
    fun theStoreReadsTheMeetingsAndTheSourceAndFollowsChanges() =
        emulatorTest {
            val owner = EmulatedDevice.signIn(syntheticSubject())
            try {
                val batch = writeBatch(owner.backend.firestore)
                batch.set(meetingRef(owner, FIRST), meeting("Synthetic standup", HOUR))
                batch.set(meetingRef(owner, SECOND), meeting("Synthetic review", 3 * HOUR))
                batch.set(sourceRef(owner), source())
                batch.commit().await()

                val store = FirestoreMeetingStore(owner.backend, owner.accountId, ::utcWallTime)
                val ready = store.observe(ZONE).first { it is MeetingsState.Ready && it.meetings.size == 2 }
                assertIs<MeetingsState.Ready>(ready)
                assertEquals(MeetingSourceState.Ok, ready.source.state)
                assertEquals("Synthetic work calendar", ready.source.calendarTitle)
                assertEquals(LocalDay(2026, 11, 16), ready.source.fromDay)
                assertEquals("1.0.0", ready.source.appVersion)
                val lastSuccess = assertNotNull(ready.source.lastSuccessAtMillis)
                assertTrue(kotlin.math.abs(Date.now() - lastSuccess) < 60_000, "lastSuccessAt is the server's time of the write")
                val standup = ready.meetings.first { it.title == "Synthetic standup" }
                assertEquals(MeetingResponse.Tentative, standup.response)
                assertEquals(utcWallTime(now + HOUR, ZONE), (standup.time as MeetingTime.Timed).start)

                // The Mac app deletes a meeting: the store emits again without it.
                deleteDoc(meetingRef(owner, SECOND)).await()
                val after = store.observe(ZONE).first { it is MeetingsState.Ready && it.meetings.size == 1 }
                assertEquals(listOf("Synthetic standup"), (after as MeetingsState.Ready).meetings.map { it.title })
            } finally {
                owner.backend.close()
            }
        }

    @Test
    fun withNoSourceMeetingsAreNotSetUp() =
        emulatorTest {
            val owner = EmulatedDevice.signIn(syntheticSubject())
            try {
                val store = FirestoreMeetingStore(owner.backend, owner.accountId, ::utcWallTime)
                assertEquals(MeetingsState.NotSetUp, store.observe(ZONE).first { it != MeetingsState.Loading })
            } finally {
                owner.backend.close()
            }
        }

    @Test
    fun anotherAccountGetsUnavailableAndNoMeetings() =
        emulatorTest {
            val owner = EmulatedDevice.signIn(syntheticSubject())
            val other = EmulatedDevice.signIn(syntheticSubject())
            try {
                val batch = writeBatch(owner.backend.firestore)
                batch.set(meetingRef(owner, FIRST), meeting("Synthetic standup", HOUR))
                batch.set(sourceRef(owner), source())
                batch.commit().await()

                val store = FirestoreMeetingStore(other.backend.firestore, AccountId(owner.accountId.value), ::utcWallTime)
                assertEquals(MeetingsState.Unavailable("permission-denied"), store.observe(ZONE).first { it != MeetingsState.Loading })
            } finally {
                owner.backend.close()
                other.backend.close()
            }
        }

    private companion object {
        const val ZONE = "Asia/Tbilisi"
        const val FIRST = "00000000000000000000000000000001"
        const val SECOND = "00000000000000000000000000000002"
        const val SERIES_KEY = "0000000000000000000000000000000a"
        const val HOUR = 3_600_000.0
        const val DAY_MILLIS = 86_400_000L
        const val MINUTE_MILLIS = 60_000L
    }
}
