package thread.data.firebase

import kotlinx.coroutines.await
import thread.domain.note.RichTextJson
import kotlin.test.Test

/**
 * The Trash rules in firebase/firestore.rules against the Firestore emulator (adr/ADR-012-trash-and-search.md): Topics
 * in Trash, tombstones that no later write can change, and deleting the versions of a Note deleted forever. All
 * accounts and content are synthetic.
 */
class FirestoreTrashRulesEmulatorTest {
    private fun topic(vararg extra: Pair<String, Any?>): dynamic =
        jsObject(
            "name" to "Synthetic topic",
            "color" to "mint",
            "statusId" to "s-planned",
            "goal" to "",
            "leftOff" to "",
            "nextStep" to "",
            "createdAt" to serverTimestamp(),
            "updatedAt" to serverTimestamp(),
            "schemaVersion" to 1,
            *extra,
        )

    private fun task(): dynamic =
        jsObject(
            "title" to "Synthetic task",
            "topicId" to null,
            "done" to false,
            "completedOn" to null,
            "plannedDay" to null,
            "deadline" to null,
            "inbox" to true,
            "createdAt" to serverTimestamp(),
            "updatedAt" to serverTimestamp(),
            "schemaVersion" to 1,
        )

    private fun revision(): dynamic =
        jsObject(
            "parentRevisionId" to null,
            "title" to "Synthetic title",
            "body" to RichTextJson.encode(syntheticDoc("synthetic text")),
            "schemaVersion" to 1,
            "deviceId" to "synthetic-device",
            "createdAt" to serverTimestamp(),
        )

    private fun note(): dynamic =
        jsObject("title" to "Synthetic title", "headRevisionId" to "rev-1", "updatedAt" to serverTimestamp(), "schemaVersion" to 1)

    private fun tombstone(vararg extra: Pair<String, Any?>): dynamic =
        jsObject("purgedAt" to serverTimestamp(), "schemaVersion" to 1, *extra)

    private fun change(vararg fields: Pair<String, Any?>): dynamic = jsObject("updatedAt" to serverTimestamp(), *fields)

    private fun merge(): dynamic = jsObject("merge" to true)

    private fun ref(
        db: Firestore,
        uid: String,
        collection: String,
        id: String,
    ) = doc(db, "users", uid, collection, id)

    private fun revisionRef(
        db: Firestore,
        uid: String,
        revisionId: String,
    ) = doc(db, "users", uid, "notes", "n-synthetic", "revisions", revisionId)

    @Test
    fun aTopicGoesToTrashAndBackAsAField() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()
            val topic = ref(db, owner, "topics", "t-synthetic")
            assertSucceeds(setDoc(topic, topic())).await()
            assertSucceeds(setDoc(topic, change("deletedAt" to serverTimestamp()), merge())).await()
            assertSucceeds(setDoc(topic, change("deletedAt" to null), merge())).await()
            assertFails(setDoc(topic, change("deletedAt" to "yesterday"), merge())).await()
            assertFails(deleteDoc(topic)).await()
        }

    @Test
    fun aTombstoneHoldsNoContentAndNoWriteChangesItAfterwards() =
        rulesTest { env, owner, other ->
            val db = env.authenticatedContext(owner).firestore()
            val topic = ref(db, owner, "topics", "t-synthetic")
            val task = ref(db, owner, "tasks", "k-synthetic")
            assertSucceeds(setDoc(topic, topic())).await()
            assertSucceeds(setDoc(task, task())).await()

            assertFails(setDoc(topic, tombstone("name" to "Synthetic topic"))).await()
            assertFails(setDoc(topic, tombstone("schemaVersion" to 2))).await()
            assertFails(setDoc(topic, jsObject("purgedAt" to null, "schemaVersion" to 1))).await()
            assertFails(setDoc(ref(env.authenticatedContext(other).firestore(), owner, "topics", "t-synthetic"), tombstone())).await()
            assertSucceeds(setDoc(topic, tombstone())).await()
            assertSucceeds(setDoc(task, tombstone())).await()

            // A stale edit, a restore or a new create on the same id: all refused. A second device deleting it too is fine.
            assertFails(setDoc(topic, change("name" to "Synthetic stale edit"), merge())).await()
            assertFails(setDoc(topic, change("deletedAt" to null), merge())).await()
            assertFails(setDoc(topic, topic())).await()
            assertSucceeds(setDoc(topic, tombstone())).await()
            assertFails(setDoc(topic, tombstone("name" to "Synthetic topic"))).await()
            assertFails(setDoc(task, change("done" to true), merge())).await()
            assertFails(deleteDoc(topic)).await()
        }

    @Test
    fun onlyTheVersionsOfANoteDeletedForeverCanBeDeleted() =
        rulesTest { env, owner, other ->
            val db = env.authenticatedContext(owner).firestore()
            val head = ref(db, owner, "notes", "n-synthetic")
            assertSucceeds(setDoc(revisionRef(db, owner, "rev-1"), revision())).await()
            assertSucceeds(setDoc(head, note())).await()
            assertFails(deleteDoc(revisionRef(db, owner, "rev-1"))).await()

            assertSucceeds(setDoc(head, tombstone())).await()
            assertFails(deleteDoc(revisionRef(env.authenticatedContext(other).firestore(), owner, "rev-1"))).await()
            assertFails(setDoc(revisionRef(db, owner, "rev-2"), revision())).await()
            assertFails(setDoc(head, change("title" to "Synthetic stale push", "schemaVersion" to 1), merge())).await()
            assertSucceeds(deleteDoc(revisionRef(db, owner, "rev-1"))).await()
        }
}
