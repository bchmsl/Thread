package thread.web.presentation.topics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import thread.domain.calendar.Meeting
import thread.domain.calendar.MeetingId
import thread.domain.calendar.SeriesKey
import thread.domain.calendar.TopicMeeting
import thread.domain.calendar.hasEnded
import thread.domain.calendar.isMeetingHash
import thread.domain.calendar.meetingsForTopic
import thread.domain.time.WallTime
import thread.domain.topic.Topic
import thread.web.presentation.CardKind
import thread.web.presentation.Icon
import thread.web.presentation.IconButton
import thread.web.presentation.IconView
import thread.web.presentation.KindMark
import thread.web.presentation.LocalNow
import thread.web.presentation.LocalToasts
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.calendar.JoinLink
import thread.web.presentation.calendar.MeetingsModel
import thread.web.presentation.calendar.nextOccurrenceText
import thread.web.presentation.calendar.occurrenceText
import thread.web.presentation.itemCard
import thread.web.presentation.scopedId

/**
 * The meeting series linked to the Topic (D78 P5, calendar-plan.md section 6, Q8 and QB). One row per series: its
 * title, its next occurrence or "None in the next 14 days", Join when that occurrence is today, and Unlink. A row opens
 * the next occurrence as a card; [openId] is the meeting a card shows now. Unlinking writes `linked: false`; the meeting
 * is never changed. The section shows once meetings are set up on this device, or while the Topic has a link.
 */
@Composable
internal fun TopicMeetingsSection(
    workspace: WorkspaceModel,
    meetings: MeetingsModel,
    topic: Topic,
    open: Boolean,
    onToggle: () -> Unit,
    openId: String?,
    onOpen: (MeetingId) -> Unit,
) {
    val toasts = LocalToasts.current
    val now = LocalNow.current
    val linkId = scopedId("td-link-meeting")
    var linking by remember(topic.id) { mutableStateOf(false) }
    val rows = meetingsForTopic(topic.id, workspace.meetingLinks, meetings.meetings, now)
    if (!meetings.cardShown && rows.isEmpty()) return
    TopicSection(
        "meetings",
        CardKind.Meeting,
        "Meetings",
        rows.size.toString(),
        open,
        onToggle,
        actions = {
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text", "btn--sm")
                id(linkId)
                onClick { linking = true }
            }) {
                IconView(Icon.Link, 15)
                Text(" Link")
            }
        },
    ) {
        if (rows.isEmpty()) {
            P({ classes("sect__empty") }) { Text("Nothing linked. Link a meeting here or from its card.") }
        } else {
            Ul({ classes("tlist") }) {
                rows.forEach { row ->
                    key(row.link.seriesKey.value) {
                        LinkedMeeting(row, now, openId, onOpen) {
                            val edit = workspace.unlinkSeries(row.link.seriesKey, topic.id) ?: return@LinkedMeeting
                            toasts.show("Unlinked from ${topic.name}. The meeting is unchanged.", undo = { workspace.undo(edit) })
                        }
                    }
                }
            }
        }
    }
    if (linking) {
        val linked = rows.map { it.link.seriesKey }.toSet()
        val choices = seriesToLink(meetings.meetings, linked, now)
        ItemPickerDialog(
            title = "Link a meeting",
            note = "A repeating meeting links as a whole series. The meeting is not changed.",
            items =
                choices.map { choice ->
                    val whenText = occurrenceText(choice.shown.time, now.day)
                    val sub = if (choice.count > 1) "$whenText, repeats" else whenText
                    PickItem(choice.shown.seriesKey.value, Icon.Calendar, choice.shown.displayTitle, sub)
                },
            empty =
                if (meetings.meetings.isEmpty()) {
                    "No meetings to link. Thread shows meetings from yesterday to 14 days ahead, from Thread Calendar on your Mac."
                } else {
                    "Every meeting from yesterday to 14 days ahead is already linked to this Topic."
                },
            onPick = { picked ->
                linking = false
                if (!isMeetingHash(picked)) return@ItemPickerDialog
                val shown = choices.firstOrNull { it.shown.seriesKey.value == picked }?.shown ?: return@ItemPickerDialog
                val edit = workspace.linkSeries(SeriesKey(picked), shown.title, topic.id) ?: return@ItemPickerDialog
                toasts.show("Linked", undo = { workspace.undo(edit) })
            },
            onCancel = { linking = false },
        )
    }
}

/** One linked series. The row opens its next occurrence; a series with none in the window shows as text. */
@Composable
private fun LinkedMeeting(
    row: TopicMeeting,
    now: WallTime,
    openId: String?,
    onOpen: (MeetingId) -> Unit,
    onUnlink: () -> Unit,
) {
    val next = row.next
    val isOpen = next != null && next.id.value == openId
    Li({
        itemCard(CardKind.Meeting)
        classes("tmeet")
        if (isOpen) classes("is-current")
    }) {
        KindMark(CardKind.Meeting)
        val content: @Composable () -> Unit = {
            Span({ classes("xrow__title") }) { Text(row.title) }
            Span({ classes("xrow__snip") }) { Text(nextOccurrenceText(next, now)) }
        }
        if (next != null) {
            Button({
                type(ButtonType.Button)
                classes("xrow__main")
                if (isOpen) attr("aria-current", "true")
                onClick { onOpen(next.id) }
            }) { content() }
        } else {
            Div({ classes("xrow__main") }) { content() }
        }
        if (next != null && next.time.startsAt.day <= now.day && !hasEnded(next.time, now)) JoinLink(next, now, small = true)
        IconButton(Icon.Unlink, "Unlink ${row.title} from this Topic", small = true) { onUnlink() }
    }
}

/** A series the picker offers: the occurrence it shows (the next one, else the latest) and how many are in the window. */
private class SeriesChoice(
    val shown: Meeting,
    val count: Int,
)

/** The series not linked to the Topic yet: those with an occurrence ahead first, soonest first, then the rest by title. */
private fun seriesToLink(
    meetings: List<Meeting>,
    linked: Set<SeriesKey>,
    now: WallTime,
): List<SeriesChoice> {
    val choices =
        meetings
            .filter { it.seriesKey !in linked }
            .groupBy { it.seriesKey }
            .values
            .map { occurrences ->
                val sorted = occurrences.sortedWith(compareBy<Meeting>({ it.time.startsAt }, { it.id.value }))
                SeriesChoice(sorted.firstOrNull { !hasEnded(it.time, now) } ?: sorted.last(), sorted.size)
            }
    val (ahead, over) = choices.partition { !hasEnded(it.shown.time, now) }
    return ahead.sortedWith(compareBy<SeriesChoice>({ it.shown.time.startsAt }, { it.shown.displayTitle })) +
        over.sortedWith(compareBy<SeriesChoice>({ it.shown.displayTitle.lowercase() }, { it.shown.seriesKey.value }))
}
