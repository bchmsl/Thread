package thread.web

import org.jetbrains.compose.web.renderComposable
import thread.data.DataLayer
import thread.web.presentation.App

/** Entry point and, for now, the composition root: the only webApp code that may touch the data module. */
fun main() {
    val layers = listOf("webApp", DataLayer.NAME, DataLayer.DEPENDS_ON)
    renderComposable(rootElementId = "root") {
        App(layers = layers)
    }
}
