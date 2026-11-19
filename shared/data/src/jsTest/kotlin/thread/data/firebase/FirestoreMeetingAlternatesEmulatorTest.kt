package thread.data.firebase

import kotlinx.coroutines.await
import kotlinx.coroutines.flow.first
import thread.domain.calendar.MeetingAlternate
import thread.domain.calendar.SeriesKey
import thread.domain.workspace.EditResult
import thread.domain.workspace.alternateOfSeries
import thread.domain.workspace.seedWrites
import thread.domain.workspace.setAlternateJoinUrl
import thread.domain.workspace.setAlternateTitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A meeting series' alternate title and join link through [FirestoreWorkspaceStore] against the emulators (D100). All
 * accounts, series, titles and links are synthetic.
 */
class FirestoreMeetingAlternatesEmulatorTest {
    private val series = SeriesKey("0123456789abcdef0123456789abcdef")

    @Test
    fun anAlternateSyncsChangesOfflineClearsToNullAndUndoes() =
        emulatorTest {
            val subject = syntheticSubject()
            val deviceA = EmulatedDevice.signIn(subject)
            val deviceB = EmulatedDevice.signIn(subject)
            val storeA = FirestoreWorkspaceStore(deviceA.backend, deviceA.accountId)
            val storeB = FirestoreWorkspaceStore(deviceB.backend, deviceB.accountId)

            fun changed(result: EditResult) = assertIs<EditResult.Changed>(result).edit

            try {
                storeA.write(seedWrites())
                val seeded = storeA.observe().first { !it.pendingWrites && it.statuses.isNotEmpty() }
                storeA.write(changed(setAlternateTitle(series, "  SYNTHETIC Weekly sync  ", seeded)).writes)
                val titled = storeA.observe().first { !it.pendingWrites && it.meetingAlternates.isNotEmpty() }
                storeA.write(changed(setAlternateJoinUrl(series, "https://meet.synthetic.example/room-1", titled)).writes)
                storeA.observe().first { !it.pendingWrites && alternateOfSeries(series, it.meetingAlternates)?.joinUrl != null }

                val onB = storeB.observe().first { !it.pendingWrites && alternateOfSeries(series, it.meetingAlternates)?.joinUrl != null }
                assertEquals(
                    MeetingAlternate(series, "SYNTHETIC Weekly sync", "https://meet.synthetic.example/room-1"),
                    alternateOfSeries(series, onB.meetingAlternates),
                )
                assertEquals(0, onB.unreadable)

                // The stored document holds the series key, the two fields and the bookkeeping only.
                val stored =
                    getDocsFromServer(
                        collection(deviceB.backend.firestore, "users", deviceB.accountId.value, "meetingAlternates"),
                    ).await()
                assertEquals(series.value, stored.docs.single().id)
                val keys = js("Object").keys(stored.docs.single().data(jsObject())).unsafeCast<Array<String>>().sorted()
                assertEquals(listOf("joinUrl", "schemaVersion", "seriesKey", "title", "updatedAt"), keys)

                // B clears the title while offline. The link stays: the merge writes only the title, as null.
                deviceB.backend.goOfflineForTesting()
                val cleared = changed(setAlternateTitle(series, " ", onB))
                storeB.write(cleared.writes)
                deviceB.backend.goOnlineForTesting()
                storeB.observe().first { !it.pendingWrites && alternateOfSeries(series, it.meetingAlternates)?.title == null }
                val onA = storeA.observe().first { !it.pendingWrites && alternateOfSeries(series, it.meetingAlternates)?.title == null }
                assertEquals(
                    MeetingAlternate(series, null, "https://meet.synthetic.example/room-1"),
                    alternateOfSeries(series, onA.meetingAlternates),
                )

                // Undo on B brings the title back on A.
                storeB.write(cleared.undo)
                val back = storeA.observe().first { !it.pendingWrites && alternateOfSeries(series, it.meetingAlternates)?.title != null }
                assertEquals("SYNTHETIC Weekly sync", alternateOfSeries(series, back.meetingAlternates)?.title)

                // Clearing both leaves an empty document, which reads as no alternate. Nothing is deleted.
                val noLink = changed(setAlternateJoinUrl(series, "", back))
                storeA.write(noLink.writes)
                val linkless =
                    storeA.observe().first { !it.pendingWrites && alternateOfSeries(series, it.meetingAlternates)?.joinUrl == null }
                storeA.write(changed(setAlternateTitle(series, "", linkless)).writes)
                val empty = storeB.observe().first { !it.pendingWrites && alternateOfSeries(series, it.meetingAlternates)?.isEmpty == true }
                assertEquals(1, empty.meetingAlternates.size)
                assertNull(alternateOfSeries(series, empty.meetingAlternates)?.title)
                assertTrue(storeA.refused.value.isEmpty() && storeB.refused.value.isEmpty(), "the rules accepted every write")
            } finally {
                deviceA.backend.close()
                deviceB.backend.close()
            }
        }
}
