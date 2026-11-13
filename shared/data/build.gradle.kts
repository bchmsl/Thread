plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ktlint)
}

kotlin {
    // JS only for now. iOS targets come with the iOS spike (adr/ADR-001-build-baseline.md).
    js {
        browser {
            // IndexedDB tests need a real browser (adr/ADR-003-editor-and-richtext.md).
            testTask {
                useKarma {
                    useChromeHeadless()
                }
                // Tests named *Emulator* need the Firebase emulators. They run only with -Pthread.emulatorTests,
                // inside firebase/emulators.sh exec (adr/ADR-005-auth-and-security-rules.md).
                if (!providers.gradleProperty("thread.emulatorTests").isPresent) {
                    filter.excludeTestsMatching("*Emulator*")
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
            // JsonElement tree API only, to read Jira and GitHub answers (adr/ADR-006, ADR-007). No plugin.
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jsMain.dependencies {
            // Modular SDK: only firebase/app, firebase/auth and firebase/firestore are imported. Apache-2.0.
            implementation(
                npm(
                    "firebase",
                    libs.versions.firebase.js
                        .get(),
                ),
            )
        }
        jsTest.dependencies {
            // Security rules tests against the Firestore emulator. Apache-2.0. Test only.
            implementation(
                npm(
                    "@firebase/rules-unit-testing",
                    libs.versions.firebase.rules.testing
                        .get(),
                ),
            )
        }
    }
}
