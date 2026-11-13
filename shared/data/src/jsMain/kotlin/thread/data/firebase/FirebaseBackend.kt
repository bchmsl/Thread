package thread.data.firebase

import kotlinx.coroutines.await
import thread.data.local.newJsObject

/**
 * One Firebase app with Auth and Firestore, connected to the local emulators (adr/ADR-002-local-store-and-sync.md).
 *
 * M1-B runs only against the emulators with the `demo-thread` project: a `demo-` project has no cloud resources, and
 * the emulators accept any API key. Connecting to a real project is a later, separate decision.
 *
 * [persistentCache] keeps Firestore's local copy in IndexedDB, shared by all tabs, so it survives reloads and works
 * offline. Tests use the in-memory cache.
 */
class FirebaseBackend(
    appName: String = DEFAULT_APP_NAME,
    persistentCache: Boolean = true,
    emulatorHost: String = EMULATOR_HOST,
) {
    internal val app: FirebaseApp = initializeApp(emulatorOptions(), appName)

    internal val auth: Auth =
        getAuth(app).also {
            val options = newJsObject()
            // The emulator banner would cover the v3 layout. The emulator use is shown on the page instead.
            options.disableWarnings = true
            connectAuthEmulator(it, "http://$emulatorHost:$AUTH_EMULATOR_PORT", options)
        }

    internal val firestore: Firestore =
        initializeFirestore(app, firestoreSettings(persistentCache)).also {
            connectFirestoreEmulator(it, emulatorHost, FIRESTORE_EMULATOR_PORT)
        }

    /** Stops sending Firestore writes and listening to the server, as if the device were offline. Test hook. */
    suspend fun goOfflineForTesting() {
        disableNetwork(firestore).await()
    }

    suspend fun goOnlineForTesting() {
        enableNetwork(firestore).await()
    }

    /** Stops Firestore and removes the app. Writes still waiting stay in the persistent cache. */
    suspend fun close() {
        terminate(firestore).await()
        deleteApp(app).await()
    }

    companion object {
        const val DEFAULT_APP_NAME: String = "thread"
        const val PROJECT_ID: String = "demo-thread"
        const val EMULATOR_HOST: String = "127.0.0.1"
        const val AUTH_EMULATOR_PORT: Int = 9099
        const val FIRESTORE_EMULATOR_PORT: Int = 8085

        // Not a secret: the emulators accept any value, and a demo- project has no real key.
        internal const val EMULATOR_API_KEY: String = "demo-api-key-for-emulators-only"
    }
}

private fun emulatorOptions(): FirebaseOptions {
    val options = newJsObject().unsafeCast<FirebaseOptions>()
    options.apiKey = FirebaseBackend.EMULATOR_API_KEY
    options.projectId = FirebaseBackend.PROJECT_ID
    options.authDomain = "${FirebaseBackend.PROJECT_ID}.firebaseapp.com"
    return options
}

private fun firestoreSettings(persistentCache: Boolean): dynamic {
    val settings = newJsObject()
    settings.localCache =
        if (persistentCache) {
            val cacheSettings = newJsObject()
            cacheSettings.tabManager = persistentMultipleTabManager()
            persistentLocalCache(cacheSettings)
        } else {
            memoryLocalCache()
        }
    return settings
}
