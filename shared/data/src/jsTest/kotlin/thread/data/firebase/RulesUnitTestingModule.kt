@file:JsModule("@firebase/rules-unit-testing")
@file:JsNonModule

package thread.data.firebase

import kotlin.js.Promise

// @firebase/rules-unit-testing 6, tests only. The emulator already runs firebase/firestore.rules (firebase.json).

internal external fun initializeTestEnvironment(config: dynamic): Promise<RulesTestEnvironment>

/** Resolves when [promise] fails with permission denied; rejects otherwise. */
internal external fun assertFails(promise: Promise<*>): Promise<dynamic>

internal external fun assertSucceeds(promise: Promise<*>): Promise<dynamic>

internal external interface RulesTestEnvironment {
    fun authenticatedContext(uid: String): RulesTestContext

    fun unauthenticatedContext(): RulesTestContext

    fun cleanup(): Promise<Unit>
}

internal external interface RulesTestContext {
    /** A compat Firestore instance. The modular functions accept it. */
    fun firestore(): Firestore
}
