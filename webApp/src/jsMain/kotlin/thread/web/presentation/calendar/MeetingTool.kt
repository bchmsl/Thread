package thread.web.presentation.calendar

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import thread.domain.calendar.Meeting
import thread.domain.calendar.NextMeeting
import thread.domain.calendar.nextMeeting
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalNow

/**
 * The status line's meeting chip (D78, Q6): "Standup in 12 min" for the next meeting in the hour ahead, "Standup now"
 * for one that just started. Nothing when neither. It opens the Meeting card. Like the Focus tool it is not a live
 * region; its name is a full sentence ([chipLabel]). On a narrow line it shows "12m" or "Now".
 */
@Composable
internal fun MeetingTool(
    model: MeetingsModel,
    onOpen: (Meeting) -> Unit,
) {
    val next = nextMeeting(model.meetings, LocalNow.current) ?: return
    Button({
        type(ButtonType.Button)
        classes("tool", "tool--meet")
        if (next is NextMeeting.Started) classes("is-now")
        attr("aria-label", chipLabel(next))
        attr("title", chipText(next))
        onClick { onOpen(next.meeting) }
    }) {
        IconView(Icon.Calendar, 18)
        Span({ classes("tool__label", "mchip") }) {
            Span({ classes("mchip__name") }) { Text(next.meeting.displayTitle) }
            Span({ classes("mchip__when") }) { Text(chipWhenText(next)) }
        }
        Span({
            classes("mchip__short")
            attr("aria-hidden", "true")
        }) { Text(chipShortText(next)) }
    }
}
