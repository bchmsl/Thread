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
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
