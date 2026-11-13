@file:JsModule("firebase/firestore")
@file:JsNonModule

package thread.data.firebase

import kotlin.js.Promise

// Firestore functions that only tests use.

internal external fun setDoc(
    reference: DocumentReference,
    data: dynamic,
): Promise<Unit>

internal external fun setDoc(
    reference: DocumentReference,
    data: dynamic,
    options: dynamic,
): Promise<Unit>

internal external fun updateDoc(
    reference: DocumentReference,
    data: dynamic,
): Promise<Unit>

internal external fun deleteDoc(reference: DocumentReference): Promise<Unit>

internal external fun getDoc(reference: DocumentReference): Promise<DocumentSnapshot>

internal external fun getDocs(reference: CollectionReference): Promise<QuerySnapshot>
