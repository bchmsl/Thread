package thread.data.firebase

import kotlinx.coroutines.await
import kotlin.test.Test

/**
 * The Focus timer rules in firebase/firestore.rules against the Firestore emulator (adr/ADR-019-focus-timer.md): the
 * history and the lengths in the preferences. All accounts, times and ids are SYNTHETIC.
 */
class FirestoreFocusRulesEmulatorTest {
    private fun session(vararg extra: Pair<String, Any?>): dynamic =
        jsObject(
            "startedAt" to T0,
            "endedAt" to T0 + 25 * MIN,
            "focusedMs" to 25 * MIN,
            "plannedMin" to 25,
            "outcome" to "completed",
            "targetKind" to "task",
            "targetId" to "k-synthetic-1",
            "schemaVersion" to 1,
            *extra,
        )

    private fun sessionRef(
        db: Firestore,
        uid: String,
        id: String = "f-run-1",
    ) = doc(db, "users", uid, "focusSessions", id)

    private fun prefs(vararg extra: Pair<String, Any?>): dynamic =
        jsObject("topicsView" to "list", "captureType" to "task", "updatedAt" to serverTimestamp(), "schemaVersion" to 1, *extra)

    private fun prefsRef(
        db: Firestore,
        uid: String,
    ) = doc(db, "users", uid, "prefs", "main")

    @Test
    fun theOwnerSavesASessionOnceAndTheSameWriteAgainChangesNothing() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()

            assertSucceeds(setDoc(sessionRef(db, owner), session())).await()
            assertSucceeds(setDoc(sessionRef(db, owner), session())).await()
            assertSucceeds(getDocs(collection(db, "users", owner, "focusSessions"))).await()

            assertFails(setDoc(sessionRef(db, owner), session("endedAt" to T0 + 26 * MIN))).await()
            assertFails(setDoc(sessionRef(db, owner), session("outcome" to "stopped"))).await()
            assertFails(setDoc(sessionRef(db, owner), session("targetKind" to null, "targetId" to null))).await()
            assertFails(setDoc(sessionRef(db, owner), session("rejectedForTesting" to true))).await()
            assertFails(deleteDoc(sessionRef(db, owner))).await()
        }

    @Test
    fun otherAccountsAndSignedOutVisitorsCannotReadOrWriteTheHistory() =
        rulesTest { env, owner, other ->
            val ownerDb = env.authenticatedContext(owner).firestore()
            assertSucceeds(setDoc(sessionRef(ownerDb, owner), session())).await()

            for (db in listOf(env.authenticatedContext(other).firestore(), env.unauthenticatedContext().firestore())) {
                assertFails(getDocs(collection(db, "users", owner, "focusSessions"))).await()
                assertFails(getDoc(sessionRef(db, owner))).await()
                assertFails(setDoc(sessionRef(db, owner, "f-run-2"), session())).await()
                assertFails(deleteDoc(sessionRef(db, owner))).await()
            }
        }

    @Test
    fun aStoppedSessionAndOneWithNoTaskOrTopicAreAccepted() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()

            assertSucceeds(setDoc(sessionRef(db, owner, "f-a"), session("outcome" to "stopped", "focusedMs" to MIN))).await()
            assertSucceeds(setDoc(sessionRef(db, owner, "f-b"), session("targetKind" to null, "targetId" to null))).await()
            assertSucceeds(setDoc(sessionRef(db, owner, "f-c"), session("targetKind" to "topic", "targetId" to "x".repeat(128)))).await()
            assertSucceeds(
                setDoc(sessionRef(db, owner, "f-" + "R".repeat(64)), session("plannedMin" to 90, "focusedMs" to 90 * MIN)),
            ).await()
            assertSucceeds(
                setDoc(sessionRef(db, owner, "f-d_1-x"), session("plannedMin" to 1, "focusedMs" to MIN, "endedAt" to T0)),
            ).await()
        }

    @Test
    fun unknownSessionShapesAreRefused() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()
            val refused =
                listOf(
                    session("outcome" to "abandoned"),
                    session("focusedMs" to MIN - 1),
                    session("focusedMs" to 25 * MIN + 1),
                    session("focusedMs" to 60_000.5),
                    session("plannedMin" to 0),
                    session("plannedMin" to 91, "focusedMs" to MIN),
                    session("targetKind" to "note"),
                    session("targetKind" to "task", "targetId" to null),
                    session("targetKind" to null),
                    session("targetId" to ""),
                    session("targetId" to "x".repeat(129)),
                    session("startedAt" to T0 + 26 * MIN),
                    session("startedAt" to T0 + 0.5),
                    session("startedAt" to "2026-11-16T09:00"),
                    session("schemaVersion" to 2),
                    session("title" to "Synthetic task title"),
                    session("createdAt" to serverTimestamp()),
                )
            for (data in refused) assertFails(setDoc(sessionRef(db, owner), data)).await()

            val missingTarget =
                jsObject(
                    "startedAt" to T0,
                    "endedAt" to T0 + 25 * MIN,
                    "focusedMs" to 25 * MIN,
                    "plannedMin" to 25,
                    "outcome" to "completed",
                    "schemaVersion" to 1,
                )
            assertFails(setDoc(sessionRef(db, owner), missingTarget)).await()
            for (id in listOf("run-1", "f-", "f-bad id", "f-" + "x".repeat(65), "f-a.b")) {
                assertFails(setDoc(sessionRef(db, owner, id), session())).await()
            }
        }

    @Test
    fun theFocusLengthsAreOptionalPreferencesWithinTheirLimits() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()

            assertSucceeds(setDoc(prefsRef(db, owner), prefs())).await()
            assertSucceeds(setDoc(prefsRef(db, owner), prefs("focusMin" to 1, "breakMin" to 1))).await()
            assertSucceeds(setDoc(prefsRef(db, owner), prefs("focusMin" to 90, "breakMin" to 30))).await()
            assertSucceeds(setDoc(prefsRef(db, owner), prefs("focusMin" to 50))).await()

            for (extra in listOf(
                "focusMin" to 0,
                "focusMin" to 91,
                "focusMin" to 25.5,
                "focusMin" to "25",
                "focusMin" to null,
                "breakMin" to 0,
                "breakMin" to 31,
                "longBreakMin" to 15,
            )) {
                assertFails(setDoc(prefsRef(db, owner), prefs(extra))).await()
            }
        }

    private companion object {
        const val MIN = 60_000.0

        // A SYNTHETIC start time, as a JS number like the app writes.
        const val T0 = 1_794_820_800_000.0
    }
}
