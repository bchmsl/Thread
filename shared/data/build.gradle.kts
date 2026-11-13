plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ktlint)
}

kotlin {
    // JS only for now. iOS targets come with the iOS spike (adr/ADR-001-build-baseline.md).
    js {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
        }
    }
}
