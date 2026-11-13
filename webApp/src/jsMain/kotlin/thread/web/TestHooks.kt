package thread.web

import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.promise
import thread.data.firebase.FirebaseAccountSession
import thread.data.firebase.FirebaseBackend

/** Failure switches for automated checks. Only the emulator test hooks change them. */
internal class TestFlags {
    /** When true, every local save aborts its IndexedDB transaction. */
    var failLocalSaves: Boolean = false

    /** When true, every Note write to the server carries a field the security rules refuse. */
    var failServerWrites: Boolean = false
}

private val hookScope = MainScope()

/**
 * Test hooks on `window.threadTest`, for automated checks through Chrome DevTools. Installed only when the app runs
 * against the local emulators: the Auth emulator accepts the unsigned sign-in below, a real project would not.
 * Every value is SYNTHETIC; nothing here reads or prints user data.
 */
internal fun installTestHooks(
    backend: FirebaseBackend,
    account: FirebaseAccountSession,
    flags: TestFlags,
) {
    val hooks: dynamic = js("({})")
    hooks.signIn = { subject: String ->
        hookScope.promise { account.signInToEmulatorForTesting(subject, "$subject@example.test") }
    }
    hooks.signOut = { hookScope.promise { account.signOut() } }
    hooks.goOffline = { hookScope.promise { backend.goOfflineForTesting() } }
    hooks.goOnline = { hookScope.promise { backend.goOnlineForTesting() } }
    hooks.setFailLocalSaves = { on: Boolean -> flags.failLocalSaves = on }
    hooks.setFailServerWrites = { on: Boolean -> flags.failServerWrites = on }
    window.asDynamic().threadTest = hooks
}
