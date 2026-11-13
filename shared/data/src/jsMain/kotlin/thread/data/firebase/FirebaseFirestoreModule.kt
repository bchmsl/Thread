@file:JsModule("firebase/firestore")
@file:JsNonModule

package thread.data.firebase

import kotlin.js.Promise

// The `firebase/firestore` entry point. Only the members Thread uses are declared.

/** Call once per app, before any other Firestore call. [settings] holds `localCache`. */
internal external fun initializeFirestore(
    app: FirebaseApp,
    settings: dynamic,
): Firestore

/** IndexedDB cache that survives reloads and works offline. [settings] holds `tabManager`. */
internal external fun persistentLocalCache(settings: dynamic): dynamic

/** Lets several tabs share one persistent cache. */
internal external fun persistentMultipleTabManager(): dynamic

/** In-memory cache, gone on reload. Used by tests. */
internal external fun memoryLocalCache(): dynamic

/** Points [firestore] at the Firestore emulator. Call before any read or write. */
internal external fun connectFirestoreEmulator(
    firestore: Firestore,
    host: String,
    port: Int,
)

internal external fun terminate(firestore: Firestore): Promise<Unit>

/** Segments are separate arguments, so an id that contains a slash cannot become two segments. */
internal external fun doc(
    firestore: Firestore,
    path: String,
    vararg pathSegments: String,
): DocumentReference

internal external fun collection(
    firestore: Firestore,
    path: String,
    vararg pathSegments: String,
): CollectionReference

/** Listens to one document. [options] holds `includeMetadataChanges`. Returns the unsubscribe function. */
@JsName("onSnapshot")
internal external fun onDocumentSnapshot(
    reference: DocumentReference,
    options: dynamic,
    onNext: (DocumentSnapshot) -> Unit,
    onError: (FirestoreError) -> Unit,
): () -> Unit

/** Listens to a collection. [options] holds `includeMetadataChanges`. Returns the unsubscribe function. */
@JsName("onSnapshot")
internal external fun onCollectionSnapshot(
    reference: CollectionReference,
    options: dynamic,
    onNext: (QuerySnapshot) -> Unit,
    onError: (FirestoreError) -> Unit,
): () -> Unit

/** Writes that apply together or not at all. Applied to the local cache at once, also offline. */
internal external fun writeBatch(firestore: Firestore): WriteBatch

/** Reads the documents of [query] from the server only. Rejects offline. */
internal external fun getDocsFromServer(query: CollectionReference): Promise<QuerySnapshot>

/** The server's time of the write. Shown as the device's estimate until acknowledged (with `serverTimestamps`). */
internal external fun serverTimestamp(): dynamic

internal external fun disableNetwork(firestore: Firestore): Promise<Unit>

internal external fun enableNetwork(firestore: Firestore): Promise<Unit>
