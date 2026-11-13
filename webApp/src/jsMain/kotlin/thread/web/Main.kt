package thread.web

import org.jetbrains.compose.web.renderComposable
import thread.data.local.IndexedDbDraftStore
import thread.web.presentation.App
import thread.web.presentation.m1spike.SpikeTestHooks

/** Composition root: the only place that picks data implementations. */
fun main() {
    val spikeTestHooks = SpikeTestHooks()
    val draftStore = IndexedDbDraftStore(failWritesForTesting = { spikeTestHooks.failLocalSaves })
    renderComposable(rootElementId = "root") {
        App(draftStore, spikeTestHooks)
    }
}
