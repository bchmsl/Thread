@file:JsModule("firebase/app")
@file:JsNonModule

package thread.data.firebase

import kotlin.js.Promise

/*
 * The `firebase/app` entry point (Firebase JS SDK 13, modular). A `@file:JsModule` file holds only external
 * declarations, so the shapes these use are in FirebaseTypes.kt.
 */

internal external fun initializeApp(
    options: FirebaseOptions,
    name: String,
): FirebaseApp

internal external fun deleteApp(app: FirebaseApp): Promise<Unit>
