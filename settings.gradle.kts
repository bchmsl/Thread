pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // org.jetbrains.compose.runtime:runtime-js depends on androidx.compose.runtime:runtime-js, published on Google Maven.
        google()
        mavenCentral()
    }
}

rootProject.name = "thread"

include(":shared:domain", ":shared:data", ":webApp")
