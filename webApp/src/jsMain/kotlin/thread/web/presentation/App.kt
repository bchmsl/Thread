package thread.web.presentation

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/** Phase 0 build page. The M1 spike route replaces it in Phase 1. */
@Composable
fun App(layers: List<String>) {
    H1 { Text("Thread") }
    P { Text("Build baseline: ${layers.joinToString(" → ")}") }
}
