package thread.domain.account

import kotlinx.coroutines.flow.StateFlow
import kotlin.jvm.JvmInline

/** The id the sign-in provider gives the account. A random id, not the email address. */
@JvmInline
value class AccountId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "An account id is not blank" }
    }

    override fun toString(): String = value
}

sealed interface AccountState {
    /** The app does not know yet whether an account is signed in on this device. */
    data object Checking : AccountState

    data object SignedOut : AccountState

    /** [label] is the email address or name to show to the person. It is never logged. */
    data class SignedIn(
        val accountId: AccountId,
        val label: String?,
    ) : AccountState
}

/**
 * The signed-in account on this device (adr/ADR-005-auth-and-security-rules.md). Platform code implements it
 * (Firebase Auth with Google on the web). Signing out keeps this device's stored drafts.
 */
interface AccountSession {
    val state: StateFlow<AccountState>

    /** Starts sign-in. Returns false when the person cancelled it. Throws [AccountException] when it failed. */
    suspend fun signIn(): Boolean

    suspend fun signOut()
}

class AccountException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
