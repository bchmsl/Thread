package thread.data.firebase

import kotlinx.coroutines.await
import thread.data.local.newJsObject

/**
 * The web config of a real Firebase project (owner decision O4, queued as Q5). Firebase web config values identify
 * the project; they are not secrets. The build still reads them only from a git-ignored local file, so the
 * repository never names a real project.
 */
class FirebaseProjectConfig(
    val apiKey: String,
    val authDomain: String,
    val projectId: String,
    val appId: String,
) {
    override fun toString(): String = "FirebaseProjectConfig(projectId=$projectId)"
}

/**
 * One Firebase app with Auth and Firestore (adr/ADR-002-local-store-and-sync.md, ADR-005).
 *
 * With no [project], it connects to the local emulators with the `demo-thread` project: a `demo-` project has no
 * cloud resources, and the emulators accept any API key. With a [project], it talks to that real project.
 *
 * [persistentCache] keeps Firestore's local copy in IndexedDB, shared by all tabs, so it survives reloads and works
 * offline. Tests use the in-memory cache.
 */
class FirebaseBackend(
    appName: String = DEFAULT_APP_NAME,
    persistentCache: Boolean = true,
    emulatorHost: String = EMULATOR_HOST,
    project: FirebaseProjectConfig? = null,
) {
    /** True when Auth and Firestore are the local emulators. Test hooks exist only then. */
    val usesEmulators: Boolean = project == null

    internal val app: FirebaseApp = initializeApp(project?.let(::projectOptions) ?: emulatorOptions(), appName)

    internal val auth: Auth =
        getAuth(app).also {
            if (project == null) {
                val options = newJsObject()
                // The emulator banner would cover the v3 layout. The emulator use is shown on the page instead.
                options.disableWarnings = true
                connectAuthEmulator(it, "http://$emulatorHost:$AUTH_EMULATOR_PORT", options)
            }
        }

    internal val firestore: Firestore =
        initializeFirestore(app, firestoreSettings(persistentCache)).also {
            if (project == null) connectFirestoreEmulator(it, emulatorHost, FIRESTORE_EMULATOR_PORT)
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

private fun projectOptions(project: FirebaseProjectConfig): FirebaseOptions {
    val options = newJsObject().unsafeCast<FirebaseOptions>()
    options.apiKey = project.apiKey
    options.projectId = project.projectId
    options.authDomain = project.authDomain
    options.appId = project.appId
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
