package thread.data.firebase

import kotlinx.coroutines.await
import kotlin.test.Test

/**
 * The Note listing fields and Topic link rules in firebase/firestore.rules against the Firestore emulator
 * (adr/ADR-011-notes-in-the-workspace.md). All accounts and content are synthetic.
 */
class FirestoreNoteRulesEmulatorTest {
    private fun link(vararg extra: Pair<String, Any?>): dynamic =
        jsObject(
            "topicId" to "t-synthetic",
            "noteId" to "n-synthetic",
            "linked" to true,
            "updatedAt" to serverTimestamp(),
            "schemaVersion" to 1,
            *extra,
        )

    private fun filing(vararg extra: Pair<String, Any?>): dynamic =
        jsObject(
            "inbox" to true,
            "updatedAt" to serverTimestamp(),
            "schemaVersion" to 1,
            *extra,
        )

    private fun linkRef(
        db: Firestore,
        uid: String,
        id: String = "t-synthetic_n-synthetic",
    ) = doc(db, "users", uid, "topicNoteLinks", id)

    private fun noteRef(
        db: Firestore,
        uid: String,
        id: String = "n-synthetic",
    ) = doc(db, "users", uid, "notes", id)

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
    fun ownerCanLinkAndUnlinkButNotDelete() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()

            assertSucceeds(setDoc(linkRef(db, owner), link())).await()
            assertSucceeds(setDoc(linkRef(db, owner), link("linked" to false))).await()
            assertSucceeds(getDocs(collection(db, "users", owner, "topicNoteLinks"))).await()
            assertFails(deleteDoc(linkRef(db, owner))).await()
        }

    @Test
    fun otherAccountsAndSignedOutVisitorsCannotReadOrWriteLinks() =
        rulesTest { env, owner, other ->
            assertSucceeds(setDoc(linkRef(env.authenticatedContext(owner).firestore(), owner), link())).await()

            for (db in listOf(env.authenticatedContext(other).firestore(), env.unauthenticatedContext().firestore())) {
                assertFails(getDocs(collection(db, "users", owner, "topicNoteLinks"))).await()
                assertFails(setDoc(linkRef(db, owner, "t-other_n-synthetic"), link("topicId" to "t-other"))).await()
            }
        }

    @Test
    fun aLinkIdMustBeItsPairAndUnknownShapesAreRefused() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()

            assertFails(setDoc(linkRef(db, owner, "n-synthetic_t-synthetic"), link())).await()
            assertFails(setDoc(linkRef(db, owner, "random-id"), link())).await()
            assertFails(setDoc(linkRef(db, owner), link("linked" to "yes"))).await()
            assertFails(setDoc(linkRef(db, owner), link("sortOrder" to 1))).await()
            assertFails(setDoc(linkRef(db, owner), link("schemaVersion" to 2))).await()
            assertFails(setDoc(linkRef(db, owner, "_n-synthetic"), link("topicId" to ""))).await()
            val noTimeOrVersion = jsObject("topicId" to "t-synthetic", "noteId" to "n-synthetic", "linked" to true)
            assertFails(setDoc(linkRef(db, owner), noTimeOrVersion)).await()
        }

    @Test
    fun noteFilingFieldsHaveFixedTypesAndCreationTimeNeverChanges() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()

            assertFails(setDoc(noteRef(db, owner), filing("inbox" to "yes"))).await()
            assertFails(setDoc(noteRef(db, owner), filing("discarded" to 1))).await()
            assertFails(setDoc(noteRef(db, owner), filing("preview" to "x".repeat(1001)))).await()
            assertFails(setDoc(noteRef(db, owner), filing("topicIds" to "t-synthetic"))).await()
            assertFails(setDoc(noteRef(db, owner), filing("createdAt" to "2026-11-13"))).await()

            assertSucceeds(setDoc(noteRef(db, owner), filing("createdAt" to serverTimestamp(), "preview" to "Synthetic"))).await()
            mergeSet(noteRef(db, owner), jsObject("inbox" to false, "updatedAt" to serverTimestamp(), "schemaVersion" to 1), db)
            assertFails(setDoc(noteRef(db, owner), filing("createdAt" to serverTimestamp()), jsObject("merge" to true))).await()
            assertFails(deleteDoc(noteRef(db, owner))).await()
        }

    @Test
    fun aNoteMadeBeforePhase8GetsItsCreationTimeOnce() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()
            val head = jsObject("title" to "Synthetic", "headRevisionId" to "r1", "updatedAt" to serverTimestamp(), "schemaVersion" to 1)
            assertSucceeds(setDoc(noteRef(db, owner), head)).await()

            mergeSet(noteRef(db, owner), filing("inbox" to false), db)
            mergeSet(noteRef(db, owner), filing("createdAt" to serverTimestamp()), db)
            assertFails(setDoc(noteRef(db, owner), filing("createdAt" to serverTimestamp()), jsObject("merge" to true))).await()
        }
}
