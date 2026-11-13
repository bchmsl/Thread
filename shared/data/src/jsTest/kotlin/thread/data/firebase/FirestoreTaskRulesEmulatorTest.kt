package thread.data.firebase

import kotlinx.coroutines.await
import kotlin.test.Test

/**
 * The Task, Reminder and Phase 7 preference rules in firebase/firestore.rules against the Firestore emulator
 * (adr/ADR-010-tasks-reminders-and-dates.md). All accounts and content are synthetic.
 */
class FirestoreTaskRulesEmulatorTest {
    private fun task(vararg extra: Pair<String, Any?>): dynamic = taskChange("createdAt" to serverTimestamp(), *extra)

    /** A later write: like the app's, it does not name createdAt and merges into the stored document. */
    private fun taskChange(vararg extra: Pair<String, Any?>): dynamic =
        jsObject(
            "title" to "Synthetic task",
            "topicId" to null,
            "done" to false,
            "completedOn" to null,
            "plannedDay" to "2026-11-13",
            "deadline" to "2026-11-14T18:00",
            "inbox" to true,
            "deletedAt" to null,
            "updatedAt" to serverTimestamp(),
            "schemaVersion" to 1,
            *extra,
        )

    private fun reminder(vararg extra: Pair<String, Any?>): dynamic =
        jsObject(
            "at" to "2026-11-13T15:00",
            "zone" to "Asia/Tbilisi",
            "text" to "Synthetic reminder",
            "targetKind" to "task",
            "targetId" to "k-synthetic-1",
            "dismissedAt" to null,
            "deletedAt" to null,
            "createdAt" to serverTimestamp(),
            "updatedAt" to serverTimestamp(),
            "schemaVersion" to 1,
            *extra,
        )

    private fun taskRef(
        db: Firestore,
        uid: String,
        id: String = "k-synthetic-1",
    ) = doc(db, "users", uid, "tasks", id)

    private fun reminderRef(
        db: Firestore,
        uid: String,
        id: String = "r-synthetic-1",
    ) = doc(db, "users", uid, "reminders", id)

    private suspend fun mergeSet(
        reference: DocumentReference,
        data: dynamic,
        db: Firestore,
    ) {
        val batch = writeBatch(db)
        batch.set(reference, data, jsObject("merge" to true))
        batch.commit().await()
    }

    @Test
    fun ownerCanWriteAndReadTasksAndReminders() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()

            assertSucceeds(setDoc(taskRef(db, owner), task())).await()
            assertSucceeds(setDoc(reminderRef(db, owner), reminder())).await()
            assertSucceeds(getDocs(collection(db, "users", owner, "tasks"))).await()
            assertSucceeds(getDocs(collection(db, "users", owner, "reminders"))).await()
        }

    @Test
    fun otherAccountsAndSignedOutVisitorsCannotReadOrWrite() =
        rulesTest { env, owner, other ->
            val ownerDb = env.authenticatedContext(owner).firestore()
            assertSucceeds(setDoc(taskRef(ownerDb, owner), task())).await()
            assertSucceeds(setDoc(reminderRef(ownerDb, owner), reminder())).await()

            for (db in listOf(env.authenticatedContext(other).firestore(), env.unauthenticatedContext().firestore())) {
                assertFails(getDocs(collection(db, "users", owner, "tasks"))).await()
                assertFails(getDocs(collection(db, "users", owner, "reminders"))).await()
                assertFails(setDoc(taskRef(db, owner, "k-other"), task())).await()
                assertFails(setDoc(reminderRef(db, owner, "r-other"), reminder())).await()
            }
        }

    @Test
    fun nothingCanBeDeletedAndTrashIsAField() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()
            assertSucceeds(setDoc(taskRef(db, owner), task())).await()
            assertSucceeds(setDoc(reminderRef(db, owner), reminder())).await()

            assertFails(deleteDoc(taskRef(db, owner))).await()
            assertFails(deleteDoc(reminderRef(db, owner))).await()
            // Throw when refused.
            mergeSet(taskRef(db, owner), jsObject("deletedAt" to serverTimestamp(), "updatedAt" to serverTimestamp()), db)
            mergeSet(taskRef(db, owner), jsObject("discarded" to true, "updatedAt" to serverTimestamp()), db)
            mergeSet(reminderRef(db, owner), jsObject("dismissedAt" to serverTimestamp(), "updatedAt" to serverTimestamp()), db)
            mergeSet(reminderRef(db, owner), jsObject("deletedAt" to serverTimestamp(), "updatedAt" to serverTimestamp()), db)
        }

    @Test
    fun daysAndDeadlinesAreCalendarStrings() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()

            assertSucceeds(setDoc(taskRef(db, owner, "k-a"), task("deadline" to "2026-11-14"))).await()
            assertSucceeds(setDoc(taskRef(db, owner, "k-b"), task("deadline" to null, "plannedDay" to null))).await()
            assertSucceeds(setDoc(taskRef(db, owner, "k-c"), task("done" to true, "completedOn" to "2026-11-13"))).await()
            assertFails(setDoc(taskRef(db, owner, "k-d"), task("plannedDay" to "2026-11-13T10:00"))).await()
            assertFails(setDoc(taskRef(db, owner, "k-e"), task("deadline" to "2026-11-14T18:00:00Z"))).await()
            assertFails(setDoc(taskRef(db, owner, "k-f"), task("deadline" to serverTimestamp()))).await()
            assertFails(setDoc(taskRef(db, owner, "k-g"), task("completedOn" to "13/11/2026"))).await()
            assertFails(setDoc(reminderRef(db, owner), reminder("at" to "2026-11-13"))).await()
        }

    @Test
    fun unknownTaskShapesAreRefused() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()

            assertFails(setDoc(taskRef(db, owner), task("title" to ""))).await()
            assertFails(setDoc(taskRef(db, owner), task("title" to "x".repeat(501)))).await()
            assertFails(setDoc(taskRef(db, owner), task("done" to "yes"))).await()
            assertFails(setDoc(taskRef(db, owner), task("inbox" to null))).await()
            assertFails(setDoc(taskRef(db, owner), task("topicId" to ""))).await()
            assertFails(setDoc(taskRef(db, owner), task("status" to "open"))).await()
            assertFails(setDoc(taskRef(db, owner), task("schemaVersion" to 2))).await()
            assertFails(setDoc(taskRef(db, owner), task("createdAt" to null))).await()
            assertFails(setDoc(taskRef(db, owner), taskChange())).await()
            assertSucceeds(setDoc(taskRef(db, owner), task("title" to "x".repeat(500), "topicId" to "t-synthetic"))).await()
        }

    @Test
    fun unknownReminderShapesAreRefused() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()

            assertFails(setDoc(reminderRef(db, owner), reminder("targetKind" to "jira"))).await()
            assertFails(setDoc(reminderRef(db, owner), reminder("targetId" to ""))).await()
            assertFails(setDoc(reminderRef(db, owner), reminder("text" to ""))).await()
            assertFails(setDoc(reminderRef(db, owner), reminder("zone" to ""))).await()
            assertFails(setDoc(reminderRef(db, owner), reminder("pushToken" to "synthetic"))).await()
            assertSucceeds(setDoc(reminderRef(db, owner), reminder("targetKind" to "topic", "targetId" to "t-synthetic"))).await()
        }

    @Test
    fun aTaskAndAReminderKeepTheirCreationTime() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()
            assertSucceeds(setDoc(taskRef(db, owner), task())).await()
            assertSucceeds(setDoc(reminderRef(db, owner), reminder())).await()

            mergeSet(taskRef(db, owner), jsObject("done" to true, "completedOn" to "2026-11-13", "updatedAt" to serverTimestamp()), db)
            assertFails(setDoc(taskRef(db, owner), task())).await()
            assertFails(setDoc(reminderRef(db, owner), reminder())).await()
        }

    @Test
    fun preferencesAcceptThePhase7FieldsAndOlderDocuments() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()
            val prefs = doc(db, "users", owner, "prefs", "main")
            val base =
                arrayOf<Pair<String, Any?>>(
                    "topicsView" to "list",
                    "captureType" to "task",
                    "updatedAt" to serverTimestamp(),
                    "schemaVersion" to 1,
                )

            // A Phase 6 document without the new fields, then a Phase 7 change that adds one of them.
            assertSucceeds(setDoc(prefs, jsObject(*base))).await()
            mergeSet(prefs, jsObject("keepCompleted" to false, "updatedAt" to serverTimestamp()), db)
            assertSucceeds(setDoc(prefs, jsObject(*base, "keepCompleted" to true, "timeZone" to "Asia/Tbilisi"))).await()
            assertFails(setDoc(prefs, jsObject(*base, "keepCompleted" to "yes"))).await()
            assertFails(setDoc(prefs, jsObject(*base, "timeZone" to ""))).await()
        }
}
