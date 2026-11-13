package thread.data.firebase

import kotlinx.coroutines.await
import thread.domain.note.RichTextJson
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * firebase/firestore.rules against the Firestore emulator (adr/ADR-005-auth-and-security-rules.md). Each test uses
 * fresh synthetic account ids, so tests do not see each other's data.
 */
class FirestoreRulesEmulatorTest {
    private fun rulesTest(block: suspend (env: RulesTestEnvironment, owner: String, other: String) -> Unit): Promise<Unit> =
        emulatorTest {
            // The library also reads Node environment variables for emulators we do not use. The browser has none.
            js("if (typeof globalThis.process === 'undefined') { globalThis.process = { env: {} }; }")
            val firestore = jsObject("host" to FirebaseBackend.EMULATOR_HOST, "port" to FirebaseBackend.FIRESTORE_EMULATOR_PORT)
            val env = initializeTestEnvironment(jsObject("projectId" to FirebaseBackend.PROJECT_ID, "firestore" to firestore)).await()
            try {
                block(env, syntheticSubject(), syntheticSubject())
            } finally {
                env.cleanup().await()
            }
        }

    private fun note(vararg extra: Pair<String, Any?>): dynamic =
        jsObject(
            "title" to "Synthetic title",
            "headRevisionId" to "rev-1",
            "updatedAt" to serverTimestamp(),
            "schemaVersion" to 1,
            *extra,
        )

    private fun revision(
        text: String = "synthetic text",
        vararg extra: Pair<String, Any?>,
    ): dynamic =
        jsObject(
            "parentRevisionId" to null,
            "title" to "Synthetic title",
            "body" to RichTextJson.encode(syntheticDoc(text)),
            "schemaVersion" to 1,
            "deviceId" to "synthetic-device",
            "createdAt" to serverTimestamp(),
            *extra,
        )

    private fun noteRef(
        db: Firestore,
        uid: String,
        noteId: String = "synthetic-note-1",
    ) = doc(db, "users", uid, "notes", noteId)

    private fun revisionRef(
        db: Firestore,
        uid: String,
        revisionId: String = "rev-1",
    ) = doc(db, "users", uid, "notes", "synthetic-note-1", "revisions", revisionId)

    private fun revisionsRef(
        db: Firestore,
        uid: String,
    ) = collection(db, "users", uid, "notes", "synthetic-note-1", "revisions")

    private suspend fun seed(
        env: RulesTestEnvironment,
        owner: String,
    ): Firestore {
        val db = env.authenticatedContext(owner).firestore()
        assertSucceeds(setDoc(revisionRef(db, owner), revision())).await()
        assertSucceeds(setDoc(noteRef(db, owner), note())).await()
        return db
    }

    @Test
    fun ownerCanWriteAndReadTheirNotes() =
        rulesTest { env, owner, _ ->
            val db = seed(env, owner)

            assertSucceeds(getDoc(noteRef(db, owner))).await()
            assertSucceeds(getDocs(revisionsRef(db, owner))).await()
        }

    @Test
    fun otherAccountsAndSignedOutVisitorsCannotReadOrWrite() =
        rulesTest { env, owner, other ->
            seed(env, owner)

            for (db in listOf(env.authenticatedContext(other).firestore(), env.unauthenticatedContext().firestore())) {
                assertFails(getDoc(noteRef(db, owner))).await()
                assertFails(getDocs(revisionsRef(db, owner))).await()
                assertFails(setDoc(noteRef(db, owner, "synthetic-note-2"), note())).await()
                assertFails(setDoc(revisionRef(db, owner, "rev-2"), revision())).await()
                assertFails(updateDoc(revisionRef(db, owner), jsObject("discardedAt" to serverTimestamp()))).await()
            }
        }

    @Test
    fun nothingCanBeDeleted() =
        rulesTest { env, owner, _ ->
            val db = seed(env, owner)

            assertFails(deleteDoc(revisionRef(db, owner))).await()
            assertFails(deleteDoc(noteRef(db, owner))).await()
        }

    @Test
    fun revisionContentNeverChangesButCanBeDiscarded() =
        rulesTest { env, owner, _ ->
            val db = seed(env, owner)

            assertFails(updateDoc(revisionRef(db, owner), jsObject("title" to "Changed"))).await()
            assertFails(setDoc(revisionRef(db, owner), revision("changed text"))).await()
            assertSucceeds(updateDoc(revisionRef(db, owner), jsObject("discardedAt" to serverTimestamp()))).await()
            assertFails(updateDoc(revisionRef(db, owner), jsObject("discardedAt" to null))).await()
        }

    @Test
    fun theSameCopyOfAVersionCanBeWrittenAgain() =
        rulesTest { env, owner, _ ->
            val db = seed(env, owner)

            assertSucceeds(setDoc(revisionRef(db, owner), revision())).await()
        }

    @Test
    fun unknownShapesAreRefused() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()

            assertFails(setDoc(revisionRef(db, owner), revision("x", "rejectedForTesting" to true))).await()
            assertFails(setDoc(revisionRef(db, owner), revision("x", "schemaVersion" to 2))).await()
            assertFails(setDoc(revisionRef(db, owner), revision("x", "createdAt" to null))).await()
            assertFails(setDoc(revisionRef(db, owner), revision("x", "discardedAt" to serverTimestamp()))).await()
            assertFails(setDoc(revisionRef(db, owner), revision("x", "deviceId" to ""))).await()
            assertFails(setDoc(noteRef(db, owner), note("jiraIssue" to "synthetic"))).await()
            assertFails(setDoc(noteRef(db, owner), note("updatedAt" to null))).await()
            assertFails(setDoc(noteRef(db, owner), note("schemaVersion" to 2))).await()
        }

    @Test
    fun pathsOutsideNotesAreDenied() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()

            assertFails(setDoc(doc(db, "users", owner), jsObject("name" to "synthetic"))).await()
            assertFails(getDoc(doc(db, "users", owner))).await()
            assertFails(setDoc(doc(db, "users", owner, "jiraCache", "synthetic"), jsObject("x" to 1))).await()
        }

    @Test
    fun aMergeWriteThatDoesNotNameDeletedAtKeepsTheNoteInTrash() =
        rulesTest { env, owner, _ ->
            val db = seed(env, owner)
            assertSucceeds(setDoc(noteRef(db, owner), note("deletedAt" to serverTimestamp()))).await()

            val batch = writeBatch(db)
            batch.set(noteRef(db, owner), note("headRevisionId" to "rev-2"), jsObject("merge" to true))
            assertSucceeds(batch.commit()).await()

            val stored: dynamic = getDoc(noteRef(db, owner)).await().data(undefined)
            assertTrue(stored.deletedAt != null, "Still in Trash")
        }
}
