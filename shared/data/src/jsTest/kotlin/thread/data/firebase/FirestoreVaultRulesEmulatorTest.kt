package thread.data.firebase

import kotlinx.coroutines.await
import kotlin.test.Test

/**
 * The vault rules in firebase/firestore.rules against the Firestore emulator (adr/ADR-016-encrypted-connection-sync.md):
 * one owner-only record of base64 values, never a plain-text field. Accounts and values are synthetic.
 */
class FirestoreVaultRulesEmulatorTest {
    private fun vault(vararg changes: Pair<String, Any?>): dynamic {
        val fields =
            mutableMapOf<String, Any?>(
                "schemaVersion" to 1,
                "kdf" to "PBKDF2-SHA-256",
                "iterations" to 600_000,
                "salt" to "c3ludGhldGljLXNhbHQtMQ==",
                "iv" to "c3ludGhldGljLWl2",
                "ciphertext" to "c3ludGhldGljIGNpcGhlcnRleHQgb25seQ==",
                "updatedAt" to serverTimestamp(),
            )
        changes.forEach { (key, value) -> if (value == REMOVE) fields.remove(key) else fields[key] = value }
        return jsObject(*fields.toList().toTypedArray())
    }

    @Test
    fun theOwnerWritesReadsAndDeletesTheRecord() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()
            val ref = doc(db, "users", owner, "vault", "connections")

            assertSucceeds(setDoc(ref, vault())).await()
            assertSucceeds(getDoc(ref)).await()
            assertSucceeds(setDoc(ref, vault("iv" to "c3ludGhldGljLWl3", "iterations" to 1_200_000))).await()
            assertSucceeds(deleteDoc(ref)).await()
        }

    @Test
    fun noOtherAccountCanReadWriteOrDeleteIt() =
        rulesTest { env, owner, other ->
            val ownerRef = doc(env.authenticatedContext(owner).firestore(), "users", owner, "vault", "connections")
            assertSucceeds(setDoc(ownerRef, vault())).await()

            val asOther = doc(env.authenticatedContext(other).firestore(), "users", owner, "vault", "connections")
            assertFails(getDoc(asOther)).await()
            assertFails(setDoc(asOther, vault())).await()
            assertFails(deleteDoc(asOther)).await()

            val signedOut = doc(env.unauthenticatedContext().firestore(), "users", owner, "vault", "connections")
            assertFails(getDoc(signedOut)).await()
            assertFails(setDoc(signedOut, vault())).await()
            assertFails(deleteDoc(signedOut)).await()
        }

    @Test
    fun onlyTheExactShapeIsAcceptedAndNeverAPlainTextField() =
        rulesTest { env, owner, _ ->
            val db = env.authenticatedContext(owner).firestore()
            val ref = doc(db, "users", owner, "vault", "connections")
            val refused =
                listOf(
                    vault("token" to "synthetic-token-not-real"),
                    vault("jira" to jsObject("token" to "synthetic-token-not-real")),
                    vault("passphrase" to "synthetic passphrase one"),
                    vault("ciphertext" to """{"token":"synthetic-token-not-real"}"""),
                    vault("ciphertext" to "synthetic token not real"),
                    vault("ciphertext" to "A".repeat(8196)),
                    vault("ciphertext" to "c3ludA=="),
                    vault("ciphertext" to REMOVE),
                    vault("kdf" to "SHA-1"),
                    vault("iterations" to 1_000),
                    vault("iterations" to 600_000.5),
                    vault("iterations" to "600000"),
                    vault("iterations" to 20_000_000),
                    vault("salt" to "short=="),
                    vault("salt" to "c3ludGhldGljLXNhbHQtMQ"),
                    vault("iv" to "c3ludGhldGljLWl2Mg=="),
                    vault("schemaVersion" to 2),
                    vault("updatedAt" to 0),
                )
            for (data in refused) assertFails(setDoc(ref, data)).await()
            assertFails(setDoc(doc(db, "users", owner, "vault", "keys"), vault())).await()

            assertSucceeds(setDoc(ref, vault())).await()
            assertFails(setDoc(ref, jsObject("token" to "synthetic-token-not-real"), jsObject("merge" to true))).await()
            assertFails(deleteDoc(doc(db, "users", owner, "vault", "keys"))).await()
        }

    private companion object {
        val REMOVE = Any()
    }
}
