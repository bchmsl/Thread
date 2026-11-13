@file:JsModule("firebase/auth")
@file:JsNonModule

package thread.data.firebase

import kotlin.js.Promise

// The `firebase/auth` entry point. Only the members Thread uses are declared.

internal external fun getAuth(app: FirebaseApp): Auth

/** Points [auth] at the Auth emulator. Call before any other Auth call. */
internal external fun connectAuthEmulator(
    auth: Auth,
    url: String,
    options: dynamic,
)

/** Reports the signed-in user now and on every change; null when nobody is signed in. Returns the unsubscribe function. */
internal external fun onAuthStateChanged(
    auth: Auth,
    nextOrObserver: (FirebaseUser?) -> Unit,
    error: (Throwable) -> Unit,
): () -> Unit

internal external fun signInWithPopup(
    auth: Auth,
    provider: AuthProvider,
): Promise<UserCredential>

internal external fun signInWithCredential(
    auth: Auth,
    credential: AuthCredential,
): Promise<UserCredential>

@JsName("signOut")
internal external fun firebaseSignOut(auth: Auth): Promise<Unit>

internal external class GoogleAuthProvider : AuthProvider {
    companion object {
        /** With the Auth emulator, [idToken] may be an unsigned JSON object of claims. */
        fun credential(idToken: String?): AuthCredential
    }
}
