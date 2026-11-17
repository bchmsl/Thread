package thread.web.presentation.calendar

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.TagElement
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import thread.domain.calendar.Meeting
import thread.domain.calendar.MeetingResponse
import thread.domain.calendar.MeetingTime
import thread.web.presentation.CardKind
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.ItemCard
import thread.web.presentation.LocalNow

/**
 * One meeting as a card (D78, calendar-plan.md section 6): its title, when it happens, where, your answer and Join.
 * Read-only: Thread never changes a meeting, so the card has no Edit and no full page.
 */
@Composable
internal fun MeetingCard(
    meeting: Meeting,
    backTo: String?,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val now = LocalNow.current
    ItemCard(
        kind = CardKind.Meeting,
        label = "Meeting",
        classes = listOf("dcard--task"),
        backTo = backTo,
        onBack = onBack,
        fullPage = null,
        onClose = onClose,
    ) {
        Div({ classes("dcard__body", "dmeet") }) {
            H2({ classes("dmeet__t") }) { Text(meeting.displayTitle) }
            P({ classes("dmeet__when") }) {
                TagElement<HTMLElement>("time", {
                    attr("datetime", datetimeOf(meeting.time))
                }) { Text(meetingWhenText(meeting.time)) }
            }
            JoinLink(meeting, now, small = false)
            TagElement<HTMLElement>("dl", { classes("facts") }) {
                Fact(Icon.Pin, "Where") {
                    val location = meeting.location
                    if (location != null) Span({ classes("dmeet__where") }) { Text(location) } else None("No place given")
                }
                Fact(Icon.User, "Your answer") {
                    when (meeting.response) {
                        MeetingResponse.Accepted -> Span { Text("Accepted") }
                        MeetingResponse.Tentative -> Span { Text("Tentative") }
                        MeetingResponse.Pending -> Span { Text("Not answered") }
                        MeetingResponse.None -> None("No answer needed")
                    }
                }
            }
            P({ classes("dmeet__foot") }) { Text("From Apple Calendar on your Mac. Read-only.") }
        }
    }
}

/** The `<time>` value, the start: `2026-11-17T10:00`, or the first day of an all-day meeting. */
private fun datetimeOf(time: MeetingTime): String =
    when (time) {
        is MeetingTime.AllDay -> "${time.firstDay}"
        is MeetingTime.Timed -> "${time.start}"
    }

@Composable
private fun Fact(
    icon: Icon,
    label: String,
    value: @Composable () -> Unit,
) {
    Div({ classes("fact") }) {
        TagElement<HTMLElement>("dt", null) {
            IconView(icon, 15)
            Text(label)
        }
        TagElement<HTMLElement>("dd", null) { value() }
    }
}

@Composable
private fun None(text: String) {
    Span({ classes("facts__none") }) { Text(text) }
}
