package thread.web.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import org.jetbrains.compose.web.dom.Span

/** Icons from the approved v3 design (design/claude-pilot-prototype-v3/assets/icons.js), same paths. */
internal enum class Icon(
    val paths: String,
) {
    Alert("""<path d="M12 3.8l9 15.7H3z"/><path d="M12 10v4.3M12 17.2v.1"/>"""),
    Bold("""<path d="M7.5 5h5.5a3.5 3.5 0 0 1 0 7H7.5zM7.5 12h6.5a3.5 3.5 0 0 1 0 7H7.5z" stroke-width="2.1"/>"""),
    Checklist(
        """<rect x="3.5" y="4.5" width="5.5" height="5.5" rx="1.4"/><path d="M4.9 7.3l1.1 1.1 1.7-1.9M12 7.3h8.5M12 16.8h8.5"/>""" +
            """<rect x="3.5" y="14" width="5.5" height="5.5" rx="1.4"/>""",
    ),
    ChevLeft("""<path d="M14.5 6.5L9 12l5.5 5.5"/>"""),
    Copy(
        """<rect x="8.5" y="8.5" width="11.5" height="11.5" rx="2"/>""" +
            """<path d="M15.5 8.5V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v9.5a1 1 0 0 0 1 1h3.5"/>""",
    ),
    Device("""<rect x="4.5" y="5" width="15" height="10" rx="1.5"/><path d="M2.8 19h18.4"/>"""),
    Flask("""<path d="M9 3.5h6M10 3.5v5.8L4.8 18.6a1.6 1.6 0 0 0 1.4 2.4h11.6a1.6 1.6 0 0 0 1.4-2.4L14 9.3V3.5M7.3 15h9.4"/>"""),
    Heading("""<path d="M6 5v14M15 5v14M6 12h9M18.5 19v-6l-1.8 1.2"/>"""),
    Italic("""<path d="M10.5 5h8M5.5 19h8M14.5 5l-5 14"/>"""),
    Link(
        """<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1"/>""" +
            """<path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>""",
    ),
    List(
        """<path d="M9.5 6.5H20M9.5 12H20M9.5 17.5H20"/><circle cx="5" cy="6.5" r="1" fill="currentColor"/>""" +
            """<circle cx="5" cy="12" r="1" fill="currentColor"/><circle cx="5" cy="17.5" r="1" fill="currentColor"/>""",
    ),
    ListOrdered("""<path d="M10 6.5h10M10 12h10M10 17.5h10M4.2 5.2l1.3-.8v4.2M3.8 14.6a1.4 1.4 0 0 1 2.7.4c0 1-2.7 2.1-2.7 3.4h2.8"/>"""),
    Refresh(
        """<path d="M19.5 10.5a7.8 7.8 0 0 0-14.2-3.4L4 8.6M4 4v4.6h4.6M4.5 13.5a7.8 7.8 0 0 0 14.2 3.4l1.3-1.5M20 20v-4.6h-4.6"/>""",
    ),
}

/**
 * A decorative v3 icon. The SVG markup is a constant from [Icon], never user content. The wrapper uses
 * `display: contents`, so the SVG lays out as if it were the direct child.
 */
@Composable
internal fun IconView(
    icon: Icon,
    size: Int = 18,
) {
    key(icon, size) {
        Span({
            classes("icon")
            ref { element ->
                element.innerHTML =
                    """<svg class="i" viewBox="0 0 24 24" width="$size" height="$size" fill="none" stroke="currentColor" """ +
                    """stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">""" +
                    icon.paths + "</svg>"
                onDispose { }
            }
        })
    }
}
