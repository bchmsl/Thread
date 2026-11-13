import java.security.MessageDigest

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

// Offline app shell (adr/ADR-008-offline-shell-and-token-storage.md). Fills webApp/serviceWorker/sw.js with the
// production shell files and a build id made from their SHA-256, and adds the result to the distribution. A new
// build gets a new id, so its worker installs a fresh cache instead of mixing files of two builds.
val generateServiceWorker =
    tasks.register("generateServiceWorker") {
        val template = layout.projectDirectory.file("serviceWorker/sw.js")
        val webpackOutput = layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable")
        val resources = layout.buildDirectory.dir("processedResources/js/main")
        val output = layout.buildDirectory.file("serviceWorker/sw.js")
        dependsOn("jsBrowserProductionWebpack", "jsProcessResources")
        inputs.file(template)
        inputs.dir(webpackOutput)
        inputs.dir(resources)
        outputs.file(output)
        doLast {
            val shellFiles =
                listOf(webpackOutput.get().asFile, resources.get().asFile)
                    .flatMap { root ->
                        root
                            .walkTopDown()
                            .filter { it.isFile && !it.name.endsWith(".map") && !it.name.endsWith(".LICENSE.txt") && it.name != "sw.js" }
                            .map { it.relativeTo(root).invariantSeparatorsPath to it }
                            .toList()
                    }.sortedBy { it.first }
            check(shellFiles.any { it.first == "index.html" } && shellFiles.any { it.first == "thread.js" }) {
                "The production shell is incomplete: ${shellFiles.map { it.first }}"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            for ((path, file) in shellFiles) {
                digest.update(path.toByteArray())
                digest.update(0)
                digest.update(file.readBytes())
                digest.update(0)
            }
            val buildId = digest.digest().joinToString("") { "%02x".format(it) }.take(16)
            val precache = shellFiles.joinToString(", ", "[", "]") { "'${it.first}'" }
            val text =
                template.asFile
                    .readText()
                    .replace("'__BUILD_ID__'", "'$buildId'")
                    .replace("__PRECACHE__", precache)
            output.get().asFile.apply {
                parentFile.mkdirs()
                writeText(text)
            }
        }
    }

tasks.named<AbstractCopyTask>("jsBrowserDistribution") {
    from(generateServiceWorker)
}
