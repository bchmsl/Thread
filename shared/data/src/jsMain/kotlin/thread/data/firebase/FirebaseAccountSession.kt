package thread.data.firebase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import thread.data.local.newJsObject
import thread.domain.account.AccountException
import thread.domain.account.AccountId
import thread.domain.account.AccountSession
import thread.domain.account.AccountState

/**
 * [AccountSession] on Firebase Auth with Google sign-in (O3, adr/ADR-005-auth-and-security-rules.md). Against the
 * Auth emulator, the Google popup is the emulator's account picker. No other provider and no extra scopes.
 *
 * Errors carry only the Firebase error code. Messages and user details are never logged.
 */
class FirebaseAccountSession internal constructor(
    private val auth: Auth,
) : AccountSession {
    constructor(backend: FirebaseBackend) : this(backend.auth)

    private val mutableState = MutableStateFlow<AccountState>(AccountState.Checking)
    override val state: StateFlow<AccountState> = mutableState.asStateFlow()

    init {
        onAuthStateChanged(
            auth,
            { user -> mutableState.value = user?.let(::signedIn) ?: AccountState.SignedOut },
            { mutableState.value = AccountState.SignedOut },
        )
    }

    override suspend fun signIn(): Boolean =
        try {
            signInWithPopup(auth, GoogleAuthProvider()).await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val code = errorCode(e)
            if (code in CANCELLED_CODES) false else throw AccountException("Sign-in failed ($code)", e)
        }

    override suspend fun signOut() {
        try {
            firebaseSignOut(auth).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw AccountException("Sign-out failed (${errorCode(e)})", e)
        }
    }

    /**
     * Signs in to the Auth emulator as a synthetic Google account, without the popup. Test hook for automated checks;
     * the emulator accepts the unsigned claims, a real project would refuse them.
     */
    suspend fun signInToEmulatorForTesting(
        subject: String,
        email: String,
    ) {
        val claims = newJsObject()
        claims.sub = subject
        claims.email = email
        claims.email_verified = true
        signInWithCredential(auth, GoogleAuthProvider.credential(JSON.stringify(claims))).await()
    }

    private fun signedIn(user: FirebaseUser) = AccountState.SignedIn(AccountId(user.uid), user.email ?: user.displayName)

    private companion object {
        val CANCELLED_CODES = setOf("auth/popup-closed-by-user", "auth/cancelled-popup-request", "auth/user-cancelled")
    }
}

internal fun errorCode(error: Throwable): String {
    val code: dynamic = error.asDynamic().code
    return if (jsTypeOf(code) == "string") code.unsafeCast<String>() else "unknown"
}
