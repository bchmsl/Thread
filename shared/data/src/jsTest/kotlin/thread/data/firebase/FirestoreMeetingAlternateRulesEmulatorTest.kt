package thread.data.firebase

import kotlinx.coroutines.await
import kotlin.js.Date
import kotlin.test.Test

/**
 * The rules for a meeting series' alternate title and join link (D100) in firebase/firestore.rules, against the
 * Firestore emulator. All accounts, titles, links and series keys are SYNTHETIC.
 */
class FirestoreMeetingAlternateRulesEmulatorTest {
    private fun alternate(
        vararg extra: Pair<String, Any?>,
        without: Set<String> = emptySet(),
    ): dynamic {
        val fields =
            linkedMapOf<String, Any?>(
                "seriesKey" to SERIES_KEY,
                "title" to "SYNTHETIC weekly sync",
                "joinUrl" to "https://meet.synthetic.example/room-1",
                "updatedAt" to serverTimestamp(),
                "schemaVersion" to 1,
            )
        extra.forEach { (key, value) -> fields[key] = value }
        without.forEach { fields.remove(it) }
        return jsObject(*fields.toList().toTypedArray())
    }

    private fun alternateRef(
        db: Firestore,
        uid: String,
        id: String = SERIES_KEY,
    ) = doc(db, "users", uid, "meetingAlternates", id)

    private fun merge() = jsObject("merge" to true)

    @Test
    fun theOwnerSetsChangesAndClearsEachFieldAndReadsThem() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()
            val ref = alternateRef(db, owner)
            assertSucceeds(setDoc(ref, alternate())).await()
            // Each field on its own, as Thread's merge writes them.
            assertSucceeds(setDoc(ref, jsObject("title" to "SYNTHETIC renamed", "updatedAt" to serverTimestamp()), merge())).await()
            assertSucceeds(setDoc(ref, jsObject("joinUrl" to null, "updatedAt" to serverTimestamp()), merge())).await()
            assertSucceeds(setDoc(ref, jsObject("title" to null, "updatedAt" to serverTimestamp()), merge())).await()
            assertSucceeds(getDoc(ref)).await()
            assertSucceeds(getDocs(collection(db, "users", owner, "meetingAlternates"))).await()

            val accepted =
                listOf(
                    alternate(without = setOf("title", "joinUrl")),
                    alternate("title" to null, "joinUrl" to null),
                    alternate("title" to "x".repeat(300)),
                    alternate("joinUrl" to "https://teams.microsoft.com/l/meetup-join/synthetic"),
                    alternate("joinUrl" to "https://synthetic.example:8443/join?id=1&pwd=SYNTHETIC#top"),
                    alternate("joinUrl" to "https://synthetic.example"),
                    alternate("joinUrl" to "https://synthetic.example/" + "x".repeat(2048 - 26)),
                )
            for (data in accepted) assertSucceeds(setDoc(ref, data)).await()
        }

    @Test
    fun badValuesAndShapesAreRefusedAfterAValidWrite() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()
            val ref = alternateRef(db, owner)
            assertSucceeds(setDoc(ref, alternate())).await()

            val refused =
                listOf(
                    alternate("title" to ""),
                    alternate("title" to " SYNTHETIC padded "),
                    alternate("title" to "x".repeat(301)),
                    alternate("title" to 42),
                    alternate("joinUrl" to "http://meet.synthetic.example/room-1"),
                    alternate("joinUrl" to "javascript:alert(1)"),
                    alternate("joinUrl" to "https://synthetic.example@evil.example/room"),
                    alternate("joinUrl" to "https://localhost/room"),
                    alternate("joinUrl" to "https://synthetic.example/room 1"),
                    alternate("joinUrl" to ""),
                    alternate("joinUrl" to "https://synthetic.example/" + "x".repeat(2048 - 25)),
                    alternate("seriesKey" to "fedcba9876543210fedcba9876543210"),
                    alternate("location" to "Synthetic room"),
                    alternate("updatedAt" to Date(Date.now())),
                    alternate("schemaVersion" to 2),
                    alternate(without = setOf("seriesKey")),
                    alternate(without = setOf("updatedAt")),
                )
            for (data in refused) assertFails(setDoc(ref, data)).await()
            // A merge that leaves a bad document is refused too.
            assertFails(setDoc(ref, jsObject("joinUrl" to "http://synthetic.example", "updatedAt" to serverTimestamp()), merge())).await()
            // The id must be the series key, a 32-hex hash.
            assertFails(setDoc(alternateRef(db, owner, "not-a-hash"), alternate("seriesKey" to "not-a-hash"))).await()
            assertFails(deleteDoc(ref)).await()
            // The valid document is still there, unchanged by the refusals.
            assertSucceeds(getDoc(ref)).await()
        }

    @Test
    fun otherAccountsAndSignedOutVisitorsCannotReadOrWrite() =
        rulesTest { env, owner, other ->
            assertSucceeds(setDoc(alternateRef(env.authenticatedContext(owner).firestore(), owner), alternate())).await()
            for (db in listOf(env.authenticatedContext(other).firestore(), env.unauthenticatedContext().firestore())) {
                assertFails(getDoc(alternateRef(db, owner))).await()
                assertFails(getDocs(collection(db, "users", owner, "meetingAlternates"))).await()
                assertFails(setDoc(alternateRef(db, owner), alternate())).await()
                assertFails(deleteDoc(alternateRef(db, owner))).await()
            }
        }

    private companion object {
        // A SYNTHETIC series key.
        const val SERIES_KEY = "0123456789abcdef0123456789abcdef"
    }
}
