package thread.web.presentation.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
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
import thread.web.presentation.IconButton
import thread.web.presentation.IconView
import thread.web.presentation.ItemCard
import thread.web.presentation.LocalCards
import thread.web.presentation.LocalNow
import thread.web.presentation.LocalToasts
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.notes.topicsPhrase
import thread.web.presentation.topics.TopicTag
import thread.web.presentation.topics.TopicsPickerDialog

/**
 * One meeting as a card (D78, calendar-plan.md section 6): its title, when it happens, where, your answer, Join and the
 * Topics its series is linked to (P5). Thread never changes a meeting, so the card has no Edit and no full page; only
 * the Topic links are Thread's own.
 */
@Composable
internal fun MeetingCard(
    workspace: WorkspaceModel,
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
                Fact(Icon.Topics, "Topics") { MeetingTopics(workspace, meeting) }
            }
            P({ classes("dmeet__foot") }) { Text("From Apple Calendar on your Mac. Thread does not change it.") }
        }
    }
}

/**
 * The Topics the meeting's series is linked to, each with Unlink, then "Link to Topic" (many to many, QB). A repeating
 * meeting links as a whole series. Unlinking writes `linked: false` and never changes the meeting.
 */
@Composable
private fun MeetingTopics(
    workspace: WorkspaceModel,
    meeting: Meeting,
) {
    val toasts = LocalToasts.current
    val cards = LocalCards.current
    var picking by remember(meeting.seriesKey) { mutableStateOf(false) }
    val topics = workspace.topicsOfSeries(meeting.seriesKey)
    Div({ classes("dmeet__topics") }) {
        if (topics.isEmpty()) None("Not linked to a Topic yet")
        topics.forEach { topic ->
            Span({ classes("lchip", "lchip--hit") }) {
                TopicTag(topic)
                IconButton(Icon.X, "Unlink ${topic.name}", small = true) {
                    val edit = workspace.unlinkSeries(meeting.seriesKey, topic.id) ?: return@IconButton
                    toasts.show("Unlinked from ${topic.name}. The meeting is unchanged.", undo = { workspace.undo(edit) })
                }
            }
        }
        Button({
            type(ButtonType.Button)
            classes("btn", "btn--text", "btn--sm", "dmeet__link")
            onClick { picking = true }
        }) {
            IconView(Icon.Link, 15)
            Text(" Link to Topic")
        }
    }
    if (picking) {
        TopicsPickerDialog(
            workspace = workspace,
            title = "Link ${meeting.displayTitle} to Topics",
            hint = "A repeating meeting links as a whole series. The meeting is not changed.",
            confirmLabel = "Save links",
            selected = topics.map { it.id },
            onPick = { picked ->
                picking = false
                val edit = workspace.setSeriesTopics(meeting.seriesKey, meeting.title, picked) ?: return@TopicsPickerDialog
                val names = picked.mapNotNull { workspace.topic(it.value)?.name }
                val single = if (picked.size == 1) workspace.topic(picked[0].value) else null
                toasts.show(
                    if (picked.isEmpty()) "Removed all Topic links from ${meeting.displayTitle}" else "Linked to ${topicsPhrase(names)}",
                    undo = { workspace.undo(edit) },
                    open = single?.let { topic -> { cards?.openTopic(topic.id) } },
                    openLabel = "Open Topic",
                )
            },
            onCancel = { picking = false },
        )
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
