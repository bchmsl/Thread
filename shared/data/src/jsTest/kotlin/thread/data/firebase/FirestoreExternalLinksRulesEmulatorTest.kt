package thread.data.firebase

import kotlinx.coroutines.await
import kotlin.test.Test

/**
 * The external link rules in firebase/firestore.rules against the Firestore emulator
 * (adr/ADR-013-jira-in-the-workspace.md): records hold ids and the URL only, and links are never deleted. All
 * accounts, sites and tickets are synthetic.
 */
class FirestoreExternalLinksRulesEmulatorTest {
    private val cloudId = "00000000-0000-4000-8000-000000000001"
    private val refId = "jira~$cloudId~SYN-101"

    private fun record(vararg extra: Pair<String, Any?>): dynamic =
        jsObject(
            "provider" to "jira",
            "instance" to cloudId,
            "key" to "SYN-101",
            "url" to "https://synthetic.atlassian.net/browse/SYN-101",
            "updatedAt" to serverTimestamp(),
            "schemaVersion" to 1,
            *extra,
        )

    private fun link(vararg extra: Pair<String, Any?>): dynamic =
        jsObject(
            "topicId" to "t-synthetic",
            "refId" to refId,
            "linked" to true,
            "updatedAt" to serverTimestamp(),
            "schemaVersion" to 1,
            *extra,
        )

    private fun merge(): dynamic = jsObject("merge" to true)

    @Test
    fun aRecordHoldsIdsAndTheUrlOnly() =
        rulesTest { env, owner, other ->
            val db = env.authenticatedContext(owner).firestore()
            val ref = doc(db, "users", owner, "externalRefs", refId)
            assertFails(setDoc(ref, record("title" to "Synthetic ticket title"))).await()
            assertFails(setDoc(ref, record("url" to "http://synthetic.atlassian.net/browse/SYN-101"))).await()
            assertFails(setDoc(ref, record("provider" to "trello"))).await()
            assertFails(setDoc(doc(db, "users", owner, "externalRefs", "jira~$cloudId~SYN-999"), record())).await()
            assertFails(setDoc(doc(env.authenticatedContext(other).firestore(), "users", owner, "externalRefs", refId), record())).await()
            assertSucceeds(setDoc(ref, record())).await()

            // Two devices linking the same ticket write the same document; only the URL can change.
            assertSucceeds(setDoc(ref, record("url" to "https://synthetic-renamed.atlassian.net/browse/SYN-101"), merge())).await()
            assertFails(setDoc(ref, jsObject("key" to "SYN-102", "updatedAt" to serverTimestamp()), merge())).await()
            assertFails(setDoc(ref, jsObject("summary" to "Synthetic", "updatedAt" to serverTimestamp()), merge())).await()
            assertFails(deleteDoc(ref)).await()

            val pr = doc(db, "users", owner, "externalRefs", "github~synthetic~app~42")
            val prRecord =
                record(
                    "provider" to "github",
                    "instance" to "synthetic/app",
                    "key" to "42",
                    "url" to "https://github.com/synthetic/app/pull/42",
                )
            assertSucceeds(setDoc(pr, prRecord)).await()
        }

    @Test
    fun aLinkIsOneTopicAndOneRecordAndIsNeverDeleted() =
        rulesTest { env, owner, other ->
            val db = env.authenticatedContext(owner).firestore()
            val ref = doc(db, "users", owner, "topicExternalLinks", "t-synthetic_$refId")
            assertFails(setDoc(doc(db, "users", owner, "topicExternalLinks", "t-other_$refId"), link())).await()
            assertFails(setDoc(ref, link("title" to "Synthetic ticket title"))).await()
            assertFails(setDoc(ref, link("linked" to "yes"))).await()
            assertFails(
                setDoc(
                    doc(env.authenticatedContext(other).firestore(), "users", owner, "topicExternalLinks", "t-synthetic_$refId"),
                    link(),
                ),
            ).await()
            assertSucceeds(setDoc(ref, link())).await()
            assertSucceeds(setDoc(ref, link("linked" to false), merge())).await()
            assertFails(deleteDoc(ref)).await()
        }
}
