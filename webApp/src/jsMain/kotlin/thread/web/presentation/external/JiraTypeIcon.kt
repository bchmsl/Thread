package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import org.jetbrains.compose.web.dom.Span
import thread.web.presentation.Icon
import thread.web.presentation.IconView

/**
 * The Jira issue types Thread draws its own icon for (owner change #9, D63). The icons are Thread's, in colors and
 * shapes a Jira user knows; Jira's own icon files are not fetched or copied. Every other type gets [Other].
 */
internal enum class JiraTypeKind(
    val css: String,
    /** The white mark on the colored tile, in a 16 by 16 box. A constant, never Jira content. */
    val glyph: String,
) {
    Epic("epic", """<path d="M9.1 2.6L4.4 9.1h3.2l-.8 4.3 4.8-6.6H8.4z" fill="#fff"/>"""),
    Story("story", """<path d="M5 3.4h6v9.4L8 10.7l-3 2.1z" fill="#fff"/>"""),
    Task(
        "task",
        """<path d="M4.2 8.4l2.5 2.5 5.1-5.3" fill="none" stroke="#fff" stroke-width="1.9" """ +
            """stroke-linecap="round" stroke-linejoin="round"/>""",
    ),
    Subtask(
        "subtask",
        """<rect x="3.4" y="3.4" width="5.6" height="5.6" rx="1.2" fill="none" stroke="#fff" stroke-width="1.5"/>""" +
            """<rect x="7" y="7" width="5.6" height="5.6" rx="1.2" fill="#fff"/>""",
    ),
    Bug("bug", """<circle cx="8" cy="8" r="3.5" fill="#fff"/>"""),
    Defect(
        "defect",
        """<path d="M8 3.9v4.8" stroke="#fff" stroke-width="2" stroke-linecap="round"/>""" +
            """<circle cx="8" cy="11.7" r="1.2" fill="#fff"/>""",
    ),
    Other(
        "other",
        """<path d="M4.7 5.4h6.6M4.7 8h6.6M4.7 10.6h4.2" stroke="#fff" stroke-width="1.5" stroke-linecap="round"/>""",
    ),
}

/** The kind of a Jira type name. Letter case, spaces and hyphens do not count, so "Sub-task" is a [JiraTypeKind.Subtask]. */
internal fun jiraTypeKind(name: String): JiraTypeKind =
    when (name.lowercase().filterNot { it == '-' || it.isWhitespace() }) {
        "epic" -> JiraTypeKind.Epic
        "story" -> JiraTypeKind.Story
        "task" -> JiraTypeKind.Task
        "subtask" -> JiraTypeKind.Subtask
        "bug" -> JiraTypeKind.Bug
        "defect" -> JiraTypeKind.Defect
        else -> JiraTypeKind.Other
    }

/** The icon's SVG markup: the tile takes the color of its `.jti--` class. A constant from [JiraTypeKind]. */
internal fun jiraTypeSvg(
    kind: JiraTypeKind,
    size: Int,
): String =
    """<svg class="jti__svg" viewBox="0 0 16 16" width="$size" height="$size" aria-hidden="true" focusable="false">""" +
        """<rect width="16" height="16" rx="3.6" fill="currentColor"/>""" + kind.glyph + "</svg>"

/**
 * The type of a Jira ticket as an icon. Screen readers and the hover title get the type's name as Jira gives it, which
 * goes in attributes only. [decorative] is for an icon next to the type's name in text, so it is not read twice. A
 * ticket whose type is not on this device keeps the plain Jira icon.
 */
@Composable
internal fun JiraTypeIcon(
    type: String?,
    size: Int = 16,
    decorative: Boolean = false,
) {
    if (type.isNullOrBlank()) {
        IconView(Icon.Jira, size)
        return
    }
    val kind = jiraTypeKind(type)
    key(kind, size, decorative) {
        Span({
            classes("jti", "jti--${kind.css}")
            if (decorative) {
                attr("aria-hidden", "true")
            } else {
                attr("role", "img")
                attr("aria-label", type)
                title(type)
            }
            ref { element ->
                element.innerHTML = jiraTypeSvg(kind, size)
                onDispose { }
            }
        })
    }
}
