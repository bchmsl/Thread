package thread.data.firebase

import kotlin.js.Promise

/*
 * Structural types for the Firebase JS SDK 13 (modular). No `@JsModule` here, so these add no imports. `var` marks
 * objects the app builds and hands to Firebase; `val` marks objects Firebase hands back.
 */

internal external interface FirebaseApp

internal external interface FirebaseOptions {
    var apiKey: String
    var projectId: String
    var authDomain: String
    var appId: String
}

internal external interface Auth

internal external interface FirebaseUser {
    val uid: String
    val email: String?
    val displayName: String?
}

internal external interface UserCredential {
    val user: FirebaseUser
}

internal external interface AuthProvider

internal external interface AuthCredential

internal external interface Firestore

internal external interface DocumentReference

internal external interface CollectionReference

internal external interface SnapshotMetadata {
    /** True while this device's own write to the document is not acknowledged by the server. */
    val hasPendingWrites: Boolean

    /** True when the data came from the device's local cache only. */
    val fromCache: Boolean
}

internal external interface DocumentSnapshot {
    val id: String
    val ref: DocumentReference
    val metadata: SnapshotMetadata

    fun exists(): Boolean

    /** The fields as a plain object, or undefined when the document does not exist. [options] holds `serverTimestamps`. */
    fun data(options: dynamic): dynamic
}

internal external interface QuerySnapshot {
    val docs: Array<DocumentSnapshot>
    val metadata: SnapshotMetadata
}

internal external interface WriteBatch {
    fun set(
        reference: DocumentReference,
        data: dynamic,
        options: dynamic = definedExternally,
    ): WriteBatch

    fun update(
        reference: DocumentReference,
        data: dynamic,
    ): WriteBatch

    fun delete(reference: DocumentReference): WriteBatch

    /** Resolves when the server acknowledged every write; rejects when it refused them. Waits while offline. */
    fun commit(): Promise<Unit>
}

internal external interface Transaction {
    fun get(reference: DocumentReference): Promise<DocumentSnapshot>

    fun set(
        reference: DocumentReference,
        data: dynamic,
    ): Transaction

    fun delete(reference: DocumentReference): Transaction
}

internal external interface Timestamp {
    fun toMillis(): Double
}

internal external interface FirestoreError {
    /** For example `permission-denied`. */
    val code: String
}
