package thread.web.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import org.jetbrains.compose.web.dom.Span

/** Icons from the approved v3 design (design/claude-pilot-prototype-v3/assets/icons.js), same paths. */
internal enum class Icon(
    val paths: String,
) {
    Alert("""<path d="M12 3.8l9 15.7H3z"/><path d="M12 10v4.3M12 17.2v.1"/>"""),
    Approved("""<circle cx="9.5" cy="8" r="3.5"/><path d="M3 19.5a6.5 6.5 0 0 1 13 0M15.8 11.2l2 2 4-4.2"/>"""),
    ArrowDown("""<path d="M12 5v13.5M6.5 13l5.5 5.5 5.5-5.5"/>"""),
    ArrowRight("""<path d="M5 12h13.5M13 6.5l5.5 5.5-5.5 5.5"/>"""),
    ArrowUp("""<path d="M12 19V5.5M6.5 11L12 5.5l5.5 5.5"/>"""),
    Bell("""<path d="M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15z"/><path d="M10 20.8a2.1 2.1 0 0 0 4 0"/>"""),
    Bold("""<path d="M7.5 5h5.5a3.5 3.5 0 0 1 0 7H7.5zM7.5 12h6.5a3.5 3.5 0 0 1 0 7H7.5z" stroke-width="2.1"/>"""),
    Branch(
        """<circle cx="7" cy="5.5" r="2"/><circle cx="7" cy="18.5" r="2"/><circle cx="17" cy="8.5" r="2"/>""" +
            """<path d="M7 7.5v9M17 10.5a5 5 0 0 1-5 5H7"/>""",
    ),
    Changes("""<rect x="5" y="3.5" width="14" height="17" rx="2"/><path d="M12 7.5v6M9 10.5h6M9 16.8h6"/>"""),
    Check("""<path d="M5 12.5l4.5 4.5L19 7.5"/>"""),
    Checklist(
        """<rect x="3.5" y="4.5" width="5.5" height="5.5" rx="1.4"/>""" +
            """<path d="M4.9 7.3l1.1 1.1 1.7-1.9M12 7.3h8.5M12 16.8h8.5"/>""" +
            """<rect x="3.5" y="14" width="5.5" height="5.5" rx="1.4"/>""",
    ),
    ChevDown("""<path d="M6.5 9.5l5.5 5.5 5.5-5.5"/>"""),
    ChevLeft("""<path d="M14.5 6.5L9 12l5.5 5.5"/>"""),
    ChevRight("""<path d="M9.5 6.5l5.5 5.5-5.5 5.5"/>"""),
    CiFail("""<circle cx="12" cy="12" r="8.3"/><path d="M9.4 9.4l5.2 5.2M14.6 9.4l-5.2 5.2"/>"""),
    CiPass("""<circle cx="12" cy="12" r="8.3"/><path d="M8.6 12.3l2.4 2.4 4.4-4.8"/>"""),
    CiRun(
        """<circle cx="12" cy="12" r="8.3" stroke-dasharray="3 2.6"/>""" +
            """<circle cx="12" cy="12" r="2.4" fill="currentColor" stroke="none"/>""",
    ),
    Clock("""<circle cx="12" cy="12" r="8.3"/><path d="M12 7.5V12l3 2"/>"""),
    Cloud("""<path d="M7.2 18.5a4.3 4.3 0 0 1-.5-8.6 5.8 5.8 0 0 1 11.1.9 3.9 3.9 0 0 1-.6 7.7z"/>"""),
    CloudCheck(
        """<path d="M7.2 18.5a4.3 4.3 0 0 1-.5-8.6 5.8 5.8 0 0 1 11.1.9 3.9 3.9 0 0 1-.6 7.7z"/>""" +
            """<path d="M9.6 13.6l1.9 1.9 3.4-3.5"/>""",
    ),
    CloudOff("""<path d="M7.2 18.5a4.3 4.3 0 0 1-.5-8.6M10 6.7a5.8 5.8 0 0 1 7.8 4.1 3.9 3.9 0 0 1 1.6 7.1M3.5 3.5l17 17"/>"""),
    CloudUp(
        """<path d="M7.2 18.5a4.3 4.3 0 0 1-.5-8.6 5.8 5.8 0 0 1 11.1.9 3.9 3.9 0 0 1-.6 7.7z"/>""" +
            """<path d="M12 16.2v-4.6M9.9 13.6l2.1-2.1 2.1 2.1"/>""",
    ),
    Comment("""<path d="M4.5 5.5h15v10.5H10l-5.5 4z"/>"""),

    /** A commit on its line (owner change #13). Drawn by Thread. */
    Commit("""<circle cx="12" cy="12" r="3.6"/><path d="M3 12h5.4M15.6 12H21"/>"""),
    Compare(
        """<rect x="3.5" y="4.5" width="7.5" height="15" rx="1.5"/>""" +
            """<rect x="13" y="4.5" width="7.5" height="15" rx="1.5"/>""" +
            """<path d="M6 9h2.5M15.5 9H18M6 12.5h2.5M15.5 12.5H18"/>""",
    ),
    Copy(
        """<rect x="8.5" y="8.5" width="11.5" height="11.5" rx="2"/>""" +
            """<path d="M15.5 8.5V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v9.5a1 1 0 0 0 1 1h3.5"/>""",
    ),
    Device("""<rect x="4.5" y="5" width="15" height="10" rx="1.5"/><path d="M2.8 19h18.4"/>"""),

    /** Lines added and removed (owner change #13). Drawn by Thread. */
    Diff("""<path d="M12 4.5v7M8.5 8h7M8.5 18.5h7"/>"""),
    Doc("""<path d="M6 3h8.5L19 7.5V21H6z"/><path d="M14 3v5h5M9 12.5h7M9 16h7"/>"""),
    Edit("""<path d="M4.5 19.5h4L19 9l-4-4L4.5 15.5z"/><path d="M13.5 6.5l4 4"/>"""),
    External("""<path d="M14 4h6v6M20 4l-8.5 8.5M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/>"""),
    Eye(
        """<path d="M2.8 12S6.2 5.8 12 5.8 21.2 12 21.2 12 17.8 18.2 12 18.2 2.8 12 2.8 12z"/>""" +
            """<circle cx="12" cy="12" r="2.6"/>""",
    ),
    Flask(
        """<path d="M9 3.5h6M10 3.5v5.8L4.8 18.6a1.6 1.6 0 0 0 1.4 2.4h11.6a1.6 1.6 0 0 0 1.4-2.4L14""" +
            """ 9.3V3.5M7.3 15h9.4"/>""",
    ),
    Grip(
        """<circle cx="9" cy="6.5" r="1.1" fill="currentColor"/>""" +
            """<circle cx="15" cy="6.5" r="1.1" fill="currentColor"/>""" +
            """<circle cx="9" cy="12" r="1.1" fill="currentColor"/>""" +
            """<circle cx="15" cy="12" r="1.1" fill="currentColor"/>""" +
            """<circle cx="9" cy="17.5" r="1.1" fill="currentColor"/>""" +
            """<circle cx="15" cy="17.5" r="1.1" fill="currentColor"/>""",
    ),
    Heading("""<path d="M6 5v14M15 5v14M6 12h9M18.5 19v-6l-1.8 1.2"/>"""),

    /** A Topic's hex, the kind mark of a Topic card (owner change #12). */
    Hex("""<polygon points="12,3 19.8,7.5 19.8,16.5 12,21 4.2,16.5 4.2,7.5"/>"""),
    Inbox(
        """<path d="M3.5 13.5L6.5 5h11l3 8.5V19a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 19z"/>""" +
            """<path d="M3.5 13.5h4.5l1.5 2.5h5l1.5-2.5h4.5"/>""",
    ),
    Italic("""<path d="M10.5 5h8M5.5 19h8M14.5 5l-5 14"/>"""),
    Jira(
        """<path d="M4 7.5A2 2 0 0 1 6 5.5h12a2 2 0 0 1 2 2v2.2a2.3 2.3 0 0 0 0 4.6v2.2a2 2 0 0 1-2 2H6a2 2 0 0""" +
            """ 1-2-2v-2.2a2.3 2.3 0 0 0 0-4.6z"/>""" +
            """<path d="M10 8.5v1M10 11.5v1M10 14.5v1"/>""",
    ),
    Link(
        """<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1"/>""" +
            """<path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>""",
    ),
    List(
        """<path d="M9.5 6.5H20M9.5 12H20M9.5 17.5H20"/><circle cx="5" cy="6.5" r="1" fill="currentColor"/>""" +
            """<circle cx="5" cy="12" r="1" fill="currentColor"/><circle cx="5" cy="17.5" r="1" fill="currentColor"/>""",
    ),
    ListOrdered(
        """<path d="M10 6.5h10M10 12h10M10 17.5h10M4.2 5.2l1.3-.8v4.2M3.8 14.6a1.4 1.4 0 0 1 2.7.4c0 1-2.7""" +
            """ 2.1-2.7 3.4h2.8"/>""",
    ),
    Lock("""<rect x="5" y="10.5" width="14" height="9.5" rx="2"/><path d="M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5"/>"""),
    Menu("""<path d="M4 7h16M4 12h16M4 17h16"/>"""),
    More(
        """<circle cx="6" cy="12" r="1.2" fill="currentColor"/>""" +
            """<circle cx="12" cy="12" r="1.2" fill="currentColor"/>""" +
            """<circle cx="18" cy="12" r="1.2" fill="currentColor"/>""",
    ),
    Notes(
        """<path d="M7 3h10.5A1.5 1.5 0 0 1 19 4.5v15a1.5 1.5 0 0 1-1.5 1.5H7z"/>""" +
            """<path d="M7 3v18M4.5 7.5H9M4.5 12H9M4.5 16.5H9M11.5 8h4.5M11.5 11.5h4.5"/>""",
    ),
    Pause(
        """<rect x="6.5" y="5.5" width="3.8" height="13" rx="1" fill="currentColor" stroke="none"/>""" +
            """<rect x="13.7" y="5.5" width="3.8" height="13" rx="1" fill="currentColor" stroke="none"/>""",
    ),
    Play("""<path d="M8 5.5l11 6.5-11 6.5z" fill="currentColor" stroke="none"/>"""),
    Plus("""<path d="M12 5v14M5 12h14"/>"""),
    Pr(
        """<circle cx="6.5" cy="6" r="2.2"/><circle cx="6.5" cy="18" r="2.2"/><circle cx="17.5" cy="18" r="2.2"/>""" +
            """<path d="M6.5 8.2v7.6M17.5 15.8V9.5a3 3 0 0 0-3-3H11"/><path d="M13 4l-2.5 2.5L13 9"/>""",
    ),
    Refresh(
        """<path d="M19.5 10.5a7.8 7.8 0 0 0-14.2-3.4L4 8.6M4 4v4.6h4.6M4.5 13.5a7.8 7.8 0 0 0 14.2""" +
            """ 3.4l1.3-1.5M20 20v-4.6h-4.6"/>""",
    ),

    /** A repository (owner change #13). Drawn by Thread. */
    Repo(
        """<path d="M5.5 19.5V5A1.5 1.5 0 0 1 7 3.5h11.5v13H7a1.5 1.5 0 0 0-1.5 1.5v1A1.5 1.5 0 0 0 7 20.5h11.5"/>""" +
            """<path d="M9 7.5h6"/>""",
    ),
    Restore("""<path d="M8.5 13.5L4 9l4.5-4.5"/><path d="M4 9h10a6 6 0 0 1 0 12h-3"/>"""),
    Search("""<circle cx="10.8" cy="10.8" r="6.3"/><path d="M15.6 15.6l4.6 4.6"/>"""),
    Settings(
        """<path d="M4 7h9M18 7h2M4 17h3.5M12.5 17H20"/><circle cx="15.5" cy="7" r="2.2"/>""" +
            """<circle cx="10" cy="17" r="2.2"/>""",
    ),
    Signin("""<path d="M14 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-4M10 16.5l4.5-4.5L10 7.5M14.5 12H4"/>"""),
    Sparkle("""<path d="M12 4v4M12 16v4M4 12h4M16 12h4M7 7l2 2M15 15l2 2M17 7l-2 2M9 15l-2 2"/>"""),
    Stop("""<rect x="6.5" y="6.5" width="11" height="11" rx="1.8" fill="currentColor" stroke="none"/>"""),
    Task("""<rect x="4" y="4" width="16" height="16" rx="4.5"/><path d="M8.5 12.2l2.4 2.4 4.6-4.9"/>"""),

    /** A GitHub team asked to review (owner change #13). Drawn by Thread. */
    Team(
        """<circle cx="9" cy="8.5" r="3"/><path d="M3.5 19a5.5 5.5 0 0 1 11 0"/><circle cx="16.5" cy="9.5" r="2.4"/>""" +
            """<path d="M15.5 14.2a4.6 4.6 0 0 1 5.5 4.8"/>""",
    ),
    Timer("""<circle cx="12" cy="13.5" r="7.2"/><path d="M12 13.5V10M9.5 2.8h5M18.4 6.6l1.3-1.3"/>"""),
    Today(
        """<rect x="3.5" y="5" width="17" height="15.5" rx="3"/><path d="M3.5 10h17M8 3v4M16 3v4"/>""" +
            """<path d="M9 15l2 2 4-4"/>""",
    ),
    Topics(
        """<path d="M12 2.8l7.8 4.5v9.4L12 21.2l-7.8-4.5V7.3z"/>""" +
            """<path d="M12 8.2l3.4 2v3.6L12 15.8l-3.4-2v-3.6z"/>""",
    ),
    Trash("""<path d="M4 7h16M9.5 7V4.5h5V7M6.2 7l.9 13h9.8l.9-13M10 11v5.5M14 11v5.5"/>"""),
    Unlink("""<path d="M15.5 13.2L18.7 10a4 4 0 0 0-5.7-5.7l-1.5 1.5M8.5 10.8L5.3 14a4 4 0 0 0 5.7 5.7l1.5-1.5M4 4l16 16"/>"""),
    User("""<circle cx="12" cy="8.2" r="3.8"/><path d="M4.8 20a7.2 7.2 0 0 1 14.4 0"/>"""),
    X("""<path d="M6.5 6.5l11 11M17.5 6.5l-11 11"/>"""),
}

/** The SVG markup of [icon]. A constant from [Icon], never user content. */
internal fun iconSvg(
    icon: Icon,
    size: Int = 18,
): String =
    """<svg class="i" viewBox="0 0 24 24" width="$size" height="$size" fill="none" stroke="currentColor" """ +
        """stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">""" +
        icon.paths + "</svg>"

/**
 * A decorative v3 icon. The wrapper uses `display: contents`, so the SVG lays out as if it were the direct child.
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
                element.innerHTML = iconSvg(icon, size)
                onDispose { }
            }
        })
    }
}
