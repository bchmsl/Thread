package thread.data.firebase

import kotlinx.coroutines.await
import thread.domain.topic.TopicColor
import kotlin.test.Test

/**
 * The status, Topic and preferences rules in firebase/firestore.rules against the Firestore emulator
 * (adr/ADR-009-workspace-entities-and-sync.md). All accounts and content are synthetic.
 */
class FirestoreWorkspaceRulesEmulatorTest {
    private fun status(vararg extra: Pair<String, Any?>): dynamic =
        jsObject(
            "name" to "Planned",
            "category" to "planned",
            "sortOrder" to 0,
            "deletedAt" to null,
            "updatedAt" to serverTimestamp(),
            "schemaVersion" to 1,
            *extra,
        )

    private fun topic(vararg extra: Pair<String, Any?>): dynamic = topicChange("createdAt" to serverTimestamp(), *extra)

    /** A later write: like the app's, it does not name createdAt and merges into the stored document. */
    private fun topicChange(vararg extra: Pair<String, Any?>): dynamic =
        jsObject(
            "name" to "Synthetic topic",
            "color" to "mint",
            "statusId" to "s-planned",
            "goal" to "",
            "leftOff" to "",
            "nextStep" to "",
            "updatedAt" to serverTimestamp(),
            "schemaVersion" to 1,
            *extra,
        )

    private fun prefs(vararg extra: Pair<String, Any?>): dynamic =
        jsObject("topicsView" to "board", "captureType" to "topic", "updatedAt" to serverTimestamp(), "schemaVersion" to 1, *extra)

    private fun statusRef(
        db: Firestore,
        uid: String,
        id: String = "s-planned",
    ) = doc(db, "users", uid, "statuses", id)

    private fun topicRef(
        db: Firestore,
        uid: String,
        id: String = "t-synthetic-1",
    ) = doc(db, "users", uid, "topics", id)

    private fun prefsRef(
        db: Firestore,
        uid: String,
        id: String = "main",
    ) = doc(db, "users", uid, "prefs", id)

    private suspend fun mergeSet(
        reference: DocumentReference,
        data: dynamic,
        db: Firestore,
    ) {
        val batch = writeBatch(db)
        batch.set(reference, data, jsObject("merge" to true))
        batch.commit().await()
    }

    private suspend fun seed(
        env: RulesTestEnvironment,
        owner: String,
    ): Firestore {
        val db = env.authenticatedContext(owner).firestore()
        assertSucceeds(setDoc(statusRef(db, owner), status())).await()
        assertSucceeds(setDoc(topicRef(db, owner), topic())).await()
        assertSucceeds(setDoc(prefsRef(db, owner), prefs())).await()
        return db
    }

    @Test
    fun ownerCanWriteAndReadTheWorkspace() =
        rulesTest { env, owner, _ ->
            val db = seed(env, owner)

            assertSucceeds(getDocs(collection(db, "users", owner, "statuses"))).await()
            assertSucceeds(getDocs(collection(db, "users", owner, "topics"))).await()
            assertSucceeds(getDoc(prefsRef(db, owner))).await()
        }

    @Test
    fun otherAccountsAndSignedOutVisitorsCannotReadOrWrite() =
        rulesTest { env, owner, other ->
            seed(env, owner)

            for (db in listOf(env.authenticatedContext(other).firestore(), env.unauthenticatedContext().firestore())) {
                assertFails(getDocs(collection(db, "users", owner, "statuses"))).await()
                assertFails(getDocs(collection(db, "users", owner, "topics"))).await()
                assertFails(getDoc(prefsRef(db, owner))).await()
                assertFails(setDoc(statusRef(db, owner, "s-other"), status())).await()
                assertFails(setDoc(topicRef(db, owner, "t-other"), topic())).await()
                assertFails(setDoc(prefsRef(db, owner), prefs())).await()
            }
        }

    @Test
    fun nothingCanBeDeletedAndAStatusIsDeletedByAField() =
        rulesTest { env, owner, _ ->
            val db = seed(env, owner)

            assertFails(deleteDoc(statusRef(db, owner))).await()
            assertFails(deleteDoc(topicRef(db, owner))).await()
            assertFails(deleteDoc(prefsRef(db, owner))).await()
            assertSucceeds(setDoc(statusRef(db, owner), status("deletedAt" to serverTimestamp()))).await()
            assertSucceeds(setDoc(statusRef(db, owner), status("deletedAt" to null))).await()
        }

    @Test
    fun unknownStatusShapesAreRefused() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()

            assertFails(setDoc(statusRef(db, owner), status("category" to "blocked"))).await()
            assertFails(setDoc(statusRef(db, owner), status("name" to ""))).await()
            assertFails(setDoc(statusRef(db, owner), status("name" to "x".repeat(25)))).await()
            assertFails(setDoc(statusRef(db, owner), status("sortOrder" to 1.5))).await()
            assertFails(setDoc(statusRef(db, owner), status("color" to "red"))).await()
            assertFails(setDoc(statusRef(db, owner), status("schemaVersion" to 2))).await()
            assertFails(setDoc(statusRef(db, owner), status("updatedAt" to null))).await()
            assertFails(setDoc(statusRef(db, owner), status("rejectedForTesting" to true))).await()
            assertSucceeds(setDoc(statusRef(db, owner), status("name" to "x".repeat(24)))).await()
        }

    @Test
    fun unknownTopicShapesAreRefused() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()

            assertFails(setDoc(topicRef(db, owner), topic("color" to "teal"))).await()
            assertFails(setDoc(topicRef(db, owner), topic("color" to "Purple"))).await()
            assertFails(setDoc(topicRef(db, owner), topic("name" to ""))).await()
            assertFails(setDoc(topicRef(db, owner), topic("statusId" to ""))).await()
            assertFails(setDoc(topicRef(db, owner), topic("goal" to "x".repeat(10_001)))).await()
            assertFails(setDoc(topicRef(db, owner), topic("jiraDescription" to "synthetic"))).await()
            assertFails(setDoc(topicRef(db, owner), topic("createdAt" to null))).await()
            assertFails(setDoc(topicRef(db, owner), topicChange())).await()
        }

    @Test
    fun everyStickerColorIsAccepted() =
        rulesTest { env, owner, _ ->
            val db = seed(env, owner)

            // D72: Chrome's nine tab group colors, by stored id, on a new Topic and on a later change.
            TopicColor.entries.forEach { color ->
                assertSucceeds(setDoc(topicRef(db, owner, "t-color-${color.id}"), topic("color" to color.id))).await()
                mergeSet(topicRef(db, owner), topicChange("color" to color.id), db)
            }
        }

    @Test
    fun aTopicMayCarryAdditionalInformationAndNothingElse() =
        rulesTest { env, owner, other ->
            val db = seed(env, owner)

            // The seeded Topic has no info (the old shape). Throws when refused.
            mergeSet(topicRef(db, owner), topicChange("info" to "Synthetic additional information"), db)
            mergeSet(topicRef(db, owner), topicChange("info" to null), db)
            mergeSet(topicRef(db, owner), topicChange("info" to ""), db)
            mergeSet(topicRef(db, owner), topicChange("info" to "x".repeat(20_100)), db)
            assertSucceeds(setDoc(topicRef(db, owner, "t-synthetic-2"), topic("info" to "Created from SYN-101."))).await()

            assertFails(setDoc(topicRef(db, owner, "t-synthetic-3"), topic("info" to "x".repeat(20_101)))).await()
            assertFails(setDoc(topicRef(db, owner, "t-synthetic-3"), topic("info" to 5))).await()
            assertFails(setDoc(topicRef(db, owner, "t-synthetic-3"), topic("info" to true))).await()
            assertFails(setDoc(topicRef(db, owner, "t-synthetic-3"), topic("infoHtml" to "synthetic"))).await()
            val otherDb = env.authenticatedContext(other).firestore()
            assertFails(setDoc(topicRef(otherDb, owner, "t-synthetic-3"), topic("info" to "synthetic"))).await()
        }

    @Test
    fun aTopicKeepsItsCreationTime() =
        rulesTest { env, owner, _ ->
            val db = seed(env, owner)

            // Throws when refused.
            mergeSet(topicRef(db, owner), topicChange("name" to "Renamed", "statusId" to "s-active"), db)
            assertFails(setDoc(topicRef(db, owner), topic())).await()
        }

    @Test
    fun preferencesHaveOneDocumentAndKnownValues() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()

            assertSucceeds(setDoc(prefsRef(db, owner), prefs())).await()
            assertFails(setDoc(prefsRef(db, owner, "other"), prefs())).await()
            assertFails(setDoc(prefsRef(db, owner), prefs("topicsView" to "grid"))).await()
            assertFails(setDoc(prefsRef(db, owner), prefs("captureType" to "jira"))).await()
            assertFails(setDoc(prefsRef(db, owner), prefs("token" to "synthetic"))).await()
        }

    @Test
    fun hidingEmptySectionsIsAnOptionalTrueOrFalsePreference() =
        rulesTest { env, owner, other ->
            val db = env.authenticatedContext(owner).firestore()

            // A document from before D77, then the switch turned on and off by a change of that field only.
            assertSucceeds(setDoc(prefsRef(db, owner), prefs())).await()
            mergeSet(prefsRef(db, owner), jsObject("hideEmptySections" to true, "updatedAt" to serverTimestamp()), db)
            mergeSet(prefsRef(db, owner), jsObject("hideEmptySections" to false, "updatedAt" to serverTimestamp()), db)
            // A build that does not know the field still changes the others in a document that has it.
            mergeSet(prefsRef(db, owner), jsObject("hideEmptySections" to true, "updatedAt" to serverTimestamp()), db)
            mergeSet(prefsRef(db, owner), jsObject("keepCompleted" to false, "updatedAt" to serverTimestamp()), db)
            assertSucceeds(setDoc(prefsRef(db, owner), prefs("hideEmptySections" to false))).await()

            for (value in listOf<Any?>("yes", 1, null)) {
                assertFails(setDoc(prefsRef(db, owner), prefs("hideEmptySections" to value))).await()
            }
            val stranger = env.authenticatedContext(other).firestore()
            assertFails(setDoc(prefsRef(stranger, owner), prefs("hideEmptySections" to true))).await()
        }

    @Test
    fun theMeetingsLayoutIsAnOptionalVerticalOrHorizontalPreference() =
        rulesTest { env, owner, other ->
            val db = env.authenticatedContext(owner).firestore()

            // A document from before D79, then the layout changed by a change of that field only.
            assertSucceeds(setDoc(prefsRef(db, owner), prefs())).await()
            mergeSet(prefsRef(db, owner), jsObject("meetingsLayout" to "horizontal", "updatedAt" to serverTimestamp()), db)
            mergeSet(prefsRef(db, owner), jsObject("meetingsLayout" to "vertical", "updatedAt" to serverTimestamp()), db)
            // A build that does not know the field still changes the others in a document that has it.
            mergeSet(prefsRef(db, owner), jsObject("meetingsLayout" to "horizontal", "updatedAt" to serverTimestamp()), db)
            mergeSet(prefsRef(db, owner), jsObject("hideEmptySections" to true, "updatedAt" to serverTimestamp()), db)
            assertSucceeds(setDoc(prefsRef(db, owner), prefs("meetingsLayout" to "vertical"))).await()
            assertSucceeds(setDoc(prefsRef(db, owner), prefs("meetingsLayout" to "horizontal", "hideEmptySections" to false))).await()

            for (value in listOf<Any?>("grid", "Horizontal", "", true, 1, null)) {
                assertFails(setDoc(prefsRef(db, owner), prefs("meetingsLayout" to value))).await()
            }
            val stranger = env.authenticatedContext(other).firestore()
            assertFails(setDoc(prefsRef(stranger, owner), prefs("meetingsLayout" to "horizontal"))).await()
        }
}
