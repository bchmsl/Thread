plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ktlint)
}

kotlin {
    js {
        nodejs()
    }

    // Compile-only iOS targets (adr/ADR-001-build-baseline.md). They make a JS-only API in common code fail the
    // build early, so the domain stays reusable for the later SwiftUI app. No framework is built yet.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
