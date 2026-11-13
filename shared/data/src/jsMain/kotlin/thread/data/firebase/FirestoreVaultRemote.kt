package thread.data.firebase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.withTimeout
import thread.data.local.newJsObject
import thread.data.vault.VaultRecord
import thread.data.vault.VaultRemote
import thread.data.vault.VaultRemoteException
import thread.domain.account.AccountId
import kotlin.js.Promise
import kotlin.time.Duration.Companion.seconds

/**
 * The encrypted connections of one account in users/{uid}/vault/connections (adr/ADR-016-encrypted-connection-sync.md).
 * Reads come from the server and writes are transactions, so nothing is answered from or queued in the offline cache:
 * every change to the record checks what the server holds right then.
 */
class FirestoreVaultRemote(
    backend: FirebaseBackend,
    private val accountId: AccountId,
) : VaultRemote {
    private val firestore = backend.firestore

    override suspend fun read(): VaultRecord? {
        val snapshot = call { getDocFromServer(reference()) }
        if (!snapshot.exists()) return null
        return decode(snapshot.data(undefined)) ?: throw VaultRemoteException(VaultRemoteException.Kind.Damaged)
    }

    override suspend fun write(
        record: VaultRecord,
        expectedSalt: String?,
    ): Boolean {
        val written =
            call {
                runTransaction(firestore) { transaction ->
                    val reference = reference()
                    transaction
                        .get(reference)
                        .then { snapshot ->
                            val salt: dynamic = if (snapshot.exists()) snapshot.data(undefined).salt else null
                            val matches =
                                if (expectedSalt == null) {
                                    !snapshot.exists()
                                } else {
                                    snapshot.exists() && jsTypeOf(salt) == "string" && salt == expectedSalt
                                }
                            if (matches) transaction.set(reference, encode(record))
                            matches
                        }.unsafeCast<Promise<dynamic>>()
                }
            }
        return written == true
    }

    override suspend fun delete() {
        call {
            runTransaction(firestore) { transaction ->
                transaction.delete(reference())
                Promise.resolve(null).unsafeCast<Promise<dynamic>>()
            }
        }
    }

    private fun reference(): DocumentReference = doc(firestore, USERS, accountId.value, VAULT, CONNECTIONS)

    /** Firestore errors are JS errors; they become [VaultRemoteException] by their code and keep nothing else. */
    private suspend fun <T> call(block: () -> Promise<T>): T =
        try {
            withTimeout(TIMEOUT) { block().await() }
        } catch (e: TimeoutCancellationException) {
            throw VaultRemoteException(VaultRemoteException.Kind.Offline)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VaultRemoteException) {
            throw e
        } catch (e: Throwable) {
            throw VaultRemoteException(kindOf(e))
        }

    private fun kindOf(error: Throwable): VaultRemoteException.Kind {
        val code: dynamic = error.asDynamic().code
        return when (if (jsTypeOf(code) == "string") code.unsafeCast<String>() else null) {
            "permission-denied", "unauthenticated" -> VaultRemoteException.Kind.Refused
            "unavailable", "deadline-exceeded" -> VaultRemoteException.Kind.Offline
            else -> VaultRemoteException.Kind.Failed
        }
    }

    private fun encode(record: VaultRecord): dynamic {
        val data = newJsObject()
        data.schemaVersion = SCHEMA_VERSION
        data.kdf = record.kdf
        data.iterations = record.iterations
        data.salt = record.salt
        data.iv = record.iv
        data.ciphertext = record.ciphertext
        data.updatedAt = serverTimestamp()
        return data
    }

    /** The record, or null when a field is missing or of another type. */
    private fun decode(data: dynamic): VaultRecord? {
        if (data == null || data == undefined || data.schemaVersion != SCHEMA_VERSION) return null
        val iterations: dynamic = data.iterations
        val fields = listOf<dynamic>(data.kdf, data.salt, data.iv, data.ciphertext)
        if (!isWholeNumber(iterations) || fields.any { jsTypeOf(it) != "string" }) return null
        return VaultRecord(
            kdf = data.kdf.unsafeCast<String>(),
            iterations = iterations.unsafeCast<Int>(),
            salt = data.salt.unsafeCast<String>(),
            iv = data.iv.unsafeCast<String>(),
            ciphertext = data.ciphertext.unsafeCast<String>(),
        )
    }

    private companion object {
        const val USERS = "users"
        const val VAULT = "vault"
        const val CONNECTIONS = "connections"
        const val SCHEMA_VERSION = 1
        val TIMEOUT = 20.seconds
    }
}

private fun isWholeNumber(value: dynamic): Boolean = js("Number.isSafeInteger(value)") as Boolean
