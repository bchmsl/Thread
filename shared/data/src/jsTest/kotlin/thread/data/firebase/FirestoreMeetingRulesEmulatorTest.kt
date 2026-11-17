package thread.data.firebase

import kotlinx.coroutines.await
import kotlin.js.Date
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The calendar rules in firebase/firestore.rules against the Firestore emulator (adr/ADR-020-calendar-meetings-sync.md):
 * meetings, the Mac app's source document and the Topic links. They cover the calendar spike's rules cases (U9). All
 * accounts, titles, places, links and ids are SYNTHETIC.
 */
class FirestoreMeetingRulesEmulatorTest {
    // One "now" per test, so two instants with the same offsets are exactly as far apart as written.
    private val now = Date.now()

    /** An instant [offsetMillis] from now, written as a Firestore timestamp. */
    private fun fromNow(offsetMillis: Double): Date = Date(now + offsetMillis)

    private fun meeting(
        vararg extra: Pair<String, Any?>,
        without: Set<String> = emptySet(),
    ): dynamic {
        val fields =
            linkedMapOf<String, Any?>(
                "title" to "Synthetic planning",
                "allDay" to false,
                "start" to fromNow(HOUR),
                "end" to fromNow(2 * HOUR),
                "response" to "accepted",
                "location" to "Synthetic room 1",
                "joinUrl" to "https://teams.microsoft.com/l/meetup-join/synthetic",
                "seriesKey" to SERIES_KEY,
                "updatedAt" to serverTimestamp(),
                "schemaVersion" to 1,
            )
        extra.forEach { (key, value) -> fields[key] = value }
        without.forEach { fields.remove(it) }
        return jsObject(*fields.toList().toTypedArray())
    }

    private fun allDayMeeting(
        vararg extra: Pair<String, Any?>,
        without: Set<String> = emptySet(),
    ): dynamic =
        meeting(
            "allDay" to true,
            "firstDay" to "2026-11-20",
            "lastDayExclusive" to "2026-11-21",
            *extra,
            without = setOf("start", "end") + without,
        )

    private fun source(
        vararg extra: Pair<String, Any?>,
        without: Set<String> = emptySet(),
    ): dynamic {
        val fields =
            linkedMapOf<String, Any?>(
                "state" to "ok",
                "calendarTitle" to "Synthetic work calendar",
                "fromDay" to "2026-11-16",
                "toDay" to "2026-12-01",
                "zone" to "Asia/Tbilisi",
                "uploaderId" to "synthetic-uploader-1",
                "appVersion" to "1.0.0",
                "lastSuccessAt" to serverTimestamp(),
                "updatedAt" to serverTimestamp(),
                "schemaVersion" to 1,
            )
        extra.forEach { (key, value) -> fields[key] = value }
        without.forEach { fields.remove(it) }
        return jsObject(*fields.toList().toTypedArray())
    }

    private fun link(
        vararg extra: Pair<String, Any?>,
        without: Set<String> = emptySet(),
    ): dynamic {
        val fields =
            linkedMapOf<String, Any?>(
                "topicId" to TOPIC_ID,
                "seriesKey" to SERIES_KEY,
                "linked" to true,
                "title" to "Synthetic planning",
                "updatedAt" to serverTimestamp(),
                "schemaVersion" to 1,
            )
        extra.forEach { (key, value) -> fields[key] = value }
        without.forEach { fields.remove(it) }
        return jsObject(*fields.toList().toTypedArray())
    }

    private fun meetingRef(
        db: Firestore,
        uid: String,
        id: String = MEETING_ID,
    ) = doc(db, "users", uid, "meetings", id)

    private fun sourceRef(
        db: Firestore,
        uid: String,
        id: String = "main",
    ) = doc(db, "users", uid, "meetingSource", id)

    private fun linkRef(
        db: Firestore,
        uid: String,
        id: String = TOPIC_ID + "_" + SERIES_KEY,
    ) = doc(db, "users", uid, "topicMeetingLinks", id)

    /** A SYNTHETIC 32-hex id that differs per [index]. */
    private fun hexId(index: Int): String = index.toString(16).padStart(32, '0')

    @Test
    fun theMacAppWritesMeetingsAndItsSourceInOneCommitAndTheOwnerReadsThem() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()
            val batch = writeBatch(db)
            for (index in 1..5) batch.set(meetingRef(db, owner, hexId(index)), meeting("title" to "Synthetic meeting $index"))
            batch.set(sourceRef(db, owner), source())
            assertSucceeds(batch.commit()).await()

            assertSucceeds(getDoc(meetingRef(db, owner, hexId(1)))).await()
            assertSucceeds(getDoc(sourceRef(db, owner))).await()
            val listed = getDocs(collection(db, "users", owner, "meetings")).await()
            assertEquals((1..5).map { hexId(it) }, listed.docs.map { it.id }.sorted())

            // updatedAt is the server's time of the write, not the device's.
            val stored: dynamic = getDoc(meetingRef(db, owner, hexId(1))).await().data(undefined)
            val written: Double = stored.updatedAt.toMillis()
            val skew = abs(Date.now() - written)
            assertTrue(skew < 60_000, "updatedAt is the request time, $skew ms off")
        }

    @Test
    fun theOwnerUpdatesAndDeletesAMeetingAndTheSource() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()
            assertSucceeds(setDoc(meetingRef(db, owner), meeting())).await()
            assertSucceeds(setDoc(sourceRef(db, owner), source())).await()

            val moved = meeting("title" to "Synthetic planning, moved", "start" to fromNow(3 * HOUR), "end" to fromNow(4 * HOUR))
            assertSucceeds(setDoc(meetingRef(db, owner), moved)).await()
            assertSucceeds(deleteDoc(meetingRef(db, owner))).await()
            assertFalse(getDoc(meetingRef(db, owner)).await().exists(), "The deleted meeting is gone")

            // "Stop syncing and remove meetings" deletes the source too.
            assertSucceeds(deleteDoc(sourceRef(db, owner))).await()
            assertFalse(getDoc(sourceRef(db, owner)).await().exists(), "The deleted source is gone")
        }

    @Test
    fun allDayUntitledAndLinkFreeMeetingsAndEveryResponseAndServiceAreAccepted() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()
            val accepted =
                listOf(
                    allDayMeeting(),
                    allDayMeeting("lastDayExclusive" to "2026-11-23"),
                    meeting("title" to ""),
                    meeting(without = setOf("location", "joinUrl")),
                    meeting("response" to "tentative"),
                    meeting("response" to "pending"),
                    meeting("response" to "none"),
                    meeting("joinUrl" to "https://zoom.us/j/1000000001"),
                    meeting("joinUrl" to "https://us02web.zoom.us/j/1000000001?pwd=SYNTHETIC"),
                    meeting("joinUrl" to "https://teams.live.com/meet/1000000001"),
                    meeting("joinUrl" to "https://meet.google.com/aaa-bbbb-ccc"),
                    meeting("joinUrl" to "https://synthetic.webex.com/meet/someone"),
                    meeting("joinUrl" to "https://teams.microsoft.com"),
                    meeting("joinUrl" to "https://teams.microsoft.com/" + "x".repeat(2048 - 28)),
                    meeting("title" to "ა".repeat(300)),
                    meeting("location" to "x".repeat(300)),
                    // Started 2 days ago and still running: inside the window.
                    meeting("start" to fromNow(-2 * DAY), "end" to fromNow(HOUR)),
                    meeting("start" to fromNow(HOUR), "end" to fromNow(HOUR)),
                    meeting("start" to fromNow(HOUR), "end" to fromNow(HOUR + 14 * DAY)),
                    meeting("start" to fromNow(15 * DAY), "end" to fromNow(15 * DAY + HOUR)),
                )
            accepted.forEachIndexed { index, data -> assertSucceeds(setDoc(meetingRef(db, owner, hexId(index + 1)), data)).await() }
        }

    @Test
    fun meetingShapesTheRulesDoNotKnowAreRefused() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()
            val refused =
                listOf(
                    meeting("attendees" to "SYNTHETIC"),
                    meeting("response" to "declined"),
                    meeting("joinUrl" to "http://teams.microsoft.com/l/meetup-join/synthetic"),
                    meeting("joinUrl" to "https://example.com/meeting"),
                    meeting("joinUrl" to "https://teams.microsoft.com.example.com/l/meetup-join"),
                    meeting("joinUrl" to "https://teams.microsoft.com@example.com/l/meetup-join"),
                    meeting("joinUrl" to "https://webex.com/meet/someone"),
                    meeting("joinUrl" to "https://teams.microsoft.com/" + "x".repeat(2048 - 27)),
                    meeting("title" to "x".repeat(301)),
                    meeting("title" to "ა".repeat(301)),
                    // 200 emoji are 400 UTF-16 units: size() counts UTF-16 units, as the Mac app's cap does.
                    meeting("title" to "😀".repeat(200)),
                    meeting("title" to null),
                    meeting("location" to "x".repeat(301)),
                    meeting("start" to fromNow(17 * DAY), "end" to fromNow(17 * DAY + HOUR)),
                    meeting("start" to fromNow(-5 * DAY), "end" to fromNow(-4 * DAY)),
                    meeting("start" to fromNow(2 * HOUR), "end" to fromNow(HOUR)),
                    meeting("start" to fromNow(HOUR), "end" to fromNow(HOUR + 14 * DAY + MINUTE)),
                    meeting("start" to "2026-11-20T10:00"),
                    meeting("updatedAt" to fromNow(0.0)),
                    meeting("schemaVersion" to 2),
                    meeting("seriesKey" to "F".repeat(32)),
                    meeting("seriesKey" to "a".repeat(31)),
                    meeting(without = setOf("seriesKey")),
                    meeting(without = setOf("response")),
                    meeting("allDay" to true, "firstDay" to "2026-11-20", "lastDayExclusive" to "2026-11-21"),
                    meeting("firstDay" to "2026-11-20"),
                    allDayMeeting("firstDay" to "20-11-2026"),
                    allDayMeeting("lastDayExclusive" to "2026-11-20"),
                    allDayMeeting(without = setOf("lastDayExclusive")),
                )
            for (data in refused) assertFails(setDoc(meetingRef(db, owner), data)).await()

            for (id in listOf("synthetic-meeting-1", "A".repeat(32), "a".repeat(33), hexId(1).drop(1))) {
                assertFails(setDoc(meetingRef(db, owner, id), meeting())).await()
            }
        }

    @Test
    fun otherAccountsAndSignedOutVisitorsCannotReadWriteOrDelete() =
        rulesTest { env, owner, other ->
            val ownerDb = env.authenticatedContext(owner).firestore()
            assertSucceeds(setDoc(meetingRef(ownerDb, owner), meeting())).await()
            assertSucceeds(setDoc(sourceRef(ownerDb, owner), source())).await()
            assertSucceeds(setDoc(linkRef(ownerDb, owner), link())).await()

            for (db in listOf(env.authenticatedContext(other).firestore(), env.unauthenticatedContext().firestore())) {
                assertFails(getDoc(meetingRef(db, owner))).await()
                assertFails(getDocs(collection(db, "users", owner, "meetings"))).await()
                assertFails(setDoc(meetingRef(db, owner, hexId(2)), meeting())).await()
                assertFails(deleteDoc(meetingRef(db, owner))).await()
                assertFails(getDoc(sourceRef(db, owner))).await()
                assertFails(setDoc(sourceRef(db, owner), source())).await()
                assertFails(deleteDoc(sourceRef(db, owner))).await()
                assertFails(getDoc(linkRef(db, owner))).await()
                assertFails(setDoc(linkRef(db, owner), link("linked" to false))).await()
            }
        }

    @Test
    fun theSourceKeepsItsShapeAndAFailedPassKeepsTheLastSuccess() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()
            assertSucceeds(setDoc(sourceRef(db, owner), source())).await()

            // A pass that failed reports its state and writes the stored lastSuccessAt again.
            val stored: dynamic = getDoc(sourceRef(db, owner)).await().data(undefined)
            assertSucceeds(setDoc(sourceRef(db, owner), source("state" to "noAccess", "lastSuccessAt" to stored.lastSuccessAt))).await()
            for (state in listOf("noCalendar", "paused")) assertSucceeds(setDoc(sourceRef(db, owner), source("state" to state))).await()
            val neverSucceeded = source("lastSuccessAt" to null, without = setOf("calendarTitle", "fromDay", "toDay"))
            assertSucceeds(setDoc(sourceRef(db, owner), neverSucceeded)).await()

            val refused =
                listOf(
                    source("state" to "broken"),
                    source("lastSuccessAt" to fromNow(HOUR)),
                    source("calendarTitle" to "x".repeat(101)),
                    source("fromDay" to "2026-11"),
                    source("zone" to "x".repeat(65)),
                    source("uploaderId" to "x".repeat(65)),
                    source("appVersion" to "x".repeat(21)),
                    source("account" to "SYNTHETIC"),
                    source("updatedAt" to fromNow(0.0)),
                    source("schemaVersion" to 2),
                    source(without = setOf("lastSuccessAt")),
                    source(without = setOf("uploaderId")),
                )
            for (data in refused) assertFails(setDoc(sourceRef(db, owner), data)).await()
            assertFails(setDoc(sourceRef(db, owner, "other"), source())).await()
        }

    @Test
    fun aTopicLinksAMeetingSeriesAndUnlinksItWithoutDeleting() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()
            assertSucceeds(setDoc(linkRef(db, owner), link())).await()
            assertSucceeds(setDoc(linkRef(db, owner), link("linked" to false))).await()
            assertSucceeds(setDoc(linkRef(db, owner), link(without = setOf("title")))).await()
            assertSucceeds(getDocs(collection(db, "users", owner, "topicMeetingLinks"))).await()

            val refused =
                listOf(
                    link("seriesKey" to "x".repeat(32)),
                    link("title" to "x".repeat(301)),
                    link("linked" to "yes"),
                    link("joinUrl" to "https://teams.microsoft.com/l/meetup-join/synthetic"),
                    link("updatedAt" to fromNow(0.0)),
                    link("schemaVersion" to 2),
                )
            for (data in refused) assertFails(setDoc(linkRef(db, owner), data)).await()
            assertFails(setDoc(linkRef(db, owner, TOPIC_ID + "-" + SERIES_KEY), link())).await()
            assertFails(setDoc(linkRef(db, owner, "t-other_" + SERIES_KEY), link())).await()
            assertFails(deleteDoc(linkRef(db, owner))).await()
        }

    @Test
    fun aCommitOf250MeetingsAndADeleteOfAllOfThemAreAccepted() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()
            val ids = (1..250).map { hexId(it) }

            val writes = writeBatch(db)
            for (id in ids) writes.set(meetingRef(db, owner, id), meeting())
            assertSucceeds(writes.commit()).await()

            val deletes = writeBatch(db)
            for (id in ids) deletes.delete(meetingRef(db, owner, id))
            assertSucceeds(deletes.commit()).await()
            assertEquals(0, getDocs(collection(db, "users", owner, "meetings")).await().docs.size)
        }

    private companion object {
        const val MINUTE = 60_000.0
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR

        // SYNTHETIC hashes and a SYNTHETIC Topic id.
        const val MEETING_ID = "0123456789abcdef0123456789abcdef"
        const val SERIES_KEY = "fedcba9876543210fedcba9876543210"
        const val TOPIC_ID = "t-synthetic-1"
    }
}
