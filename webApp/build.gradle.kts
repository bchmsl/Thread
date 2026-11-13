plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "thread.js"
            }
            // Editor tests run the real Tiptap editor, so they need a browser (adr/ADR-003-editor-and-richtext.md).
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":shared:domain"))
            implementation(project(":shared:data"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.html.core)
            // Note editor. Exact versions, all MIT (checked in each package.json, ADR-003). @tiptap/pm is the
            // ProseMirror peer dependency of the others. Link and the list package are used directly to narrow
            // their attributes to the RichText v1 schema.
            val tiptap = libs.versions.tiptap.get()
            implementation(npm("@tiptap/core", tiptap))
            implementation(npm("@tiptap/pm", tiptap))
            implementation(npm("@tiptap/starter-kit", tiptap))
            implementation(npm("@tiptap/extension-list", tiptap))
            implementation(npm("@tiptap/extension-link", tiptap))
        }
        jsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
