package thread.web.presentation.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.maxLength
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.TagElement
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import thread.domain.calendar.JOIN_URL_MAX_LENGTH
import thread.domain.calendar.MEETING_TEXT_MAX_LENGTH
import thread.domain.calendar.Meeting
import thread.domain.calendar.MeetingResponse
import thread.domain.calendar.MeetingTime
import thread.domain.workspace.EditProblem
import thread.domain.workspace.EditResult
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
import thread.web.presentation.settings.FormField
import thread.web.presentation.settings.fieldAttrs
import thread.web.presentation.topics.TopicTag
import thread.web.presentation.topics.TopicsPickerDialog

/**
 * One meeting as a card (D78, calendar-plan.md section 6): its title, when it happens, where, your answer, Join and the
 * Topics its series is linked to (P5). Thread never changes the meeting in the calendar, so the card has no full page;
 * "Show in Calendar" opens the Calendar page at its day instead (D102), unless [showInCalendar] is false because the
 * card is open over that page.
 *
 * Edit sets a title and a join link of Thread's own for the meeting's series (D100). Both are optional; when set they
 * show in place of the calendar's everywhere the meeting shows, and the card then lists the calendar's own title and link
 * as facts. [repeats]: the series has more than one meeting, so the card says the change applies to each.
 */
@Composable
internal fun MeetingCard(
    workspace: WorkspaceModel,
    meeting: Meeting,
    repeats: Boolean,
    backTo: String?,
    onBack: () -> Unit,
    onClose: () -> Unit,
    showInCalendar: Boolean = true,
) {
    val now = LocalNow.current
    val toasts = LocalToasts.current
    var editing by remember { mutableStateOf(false) }
    // A link the server would refuse stays in its field with this text under it, so nothing typed is lost.
    var linkError by remember(meeting.seriesKey) { mutableStateOf<String?>(null) }
    // Done (or Esc) was pressed once with a bad link: the next press leaves it and keeps the saved link.
    var held by remember(meeting.seriesKey) { mutableStateOf(false) }
    var titleInput by remember { mutableStateOf<HTMLInputElement?>(null) }
    var linkInput by remember { mutableStateOf<HTMLInputElement?>(null) }
    val alternate = meeting.alternate

    /** Saves the title; false when it was refused and the field goes back to the saved one. */
    fun saveTitle(text: String): Boolean {
        val result = workspace.setMeetingTitle(meeting.seriesKey, text)
        if (result is EditResult.Refused) toasts.show(alternateProblemText(result.problem))
        return result !is EditResult.Refused
    }

    /** Saves the link; false when it was refused for a reason the field cannot show, and the field goes back. */
    fun saveLink(text: String): Boolean {
        val result = workspace.setMeetingJoinUrl(meeting.seriesKey, text)
        held = false
        val problem = (result as? EditResult.Refused)?.problem
        linkError = problem?.takeIf { it == EditProblem.BadLink }?.let(::alternateProblemText)
        if (problem != null && problem != EditProblem.BadLink) toasts.show(alternateProblemText(problem))
        return problem == null || problem == EditProblem.BadLink
    }

    fun setEditing(next: Boolean) {
        if (next) {
            editing = true
            return
        }
        // Done or Esc can come while a field still holds unsaved text: leaving the field saves it first.
        listOfNotNull(titleInput, linkInput).firstOrNull { it == document.activeElement }?.blur()
        if (linkError != null && !held) {
            held = true
            linkInput?.focus()
            return
        }
        editing = false
        held = false
        linkError = null
    }

    ItemCard(
        kind = CardKind.Meeting,
        label = "Meeting",
        classes = listOf("dcard--task"),
        editing = editing,
        onEditing = ::setEditing,
        backTo = backTo,
        onBack = onBack,
        fullPage = if (showInCalendar) calendarPlace(meeting.time.startsAt.day) else null,
        onClose = onClose,
        fullPageLabel = "Show in Calendar",
        fullPageIcon = Icon.Calendar,
    ) {
        Div({ classes("dcard__body", "dmeet") }) {
            H2({ classes("dmeet__t") }) { Text(meeting.displayTitle) }
            P({ classes("dmeet__when") }) {
                TagElement<HTMLElement>("time", {
                    attr("datetime", datetimeOf(meeting.time))
                }) { Text(meetingWhenText(meeting.time)) }
            }
            if (editing) {
                Div({ classes("dmeet__edit") }) {
                    FormField(TITLE_ID, "Title", null, "Shown instead of the calendar's title. Leave empty to use it.") {
                        AlternateInput(
                            id = TITLE_ID,
                            value = alternate?.title.orEmpty(),
                            placeholder = meeting.calendarTitle,
                            maxLength = MEETING_TEXT_MAX_LENGTH,
                            link = false,
                            error = null,
                            focus = true,
                            onElement = { titleInput = it },
                            onSave = ::saveTitle,
                        )
                    }
                    FormField(LINK_ID, "Join link", linkError, LINK_HINT) {
                        AlternateInput(
                            id = LINK_ID,
                            value = alternate?.joinUrl.orEmpty(),
                            placeholder = "https://",
                            maxLength = JOIN_URL_MAX_LENGTH,
                            link = true,
                            error = linkError,
                            focus = false,
                            onElement = { linkInput = it },
                            onSave = ::saveLink,
                        )
                    }
                }
                P({ classes("ts-effect") }) {
                    IconView(Icon.Calendar, 16)
                    Span {
                        Text(
                            if (repeats) {
                                "Applies to every meeting in this series. Your calendar does not change."
                            } else {
                                "Shows only in Thread. Your calendar does not change."
                            },
                        )
                    }
                }
            }
            JoinLink(meeting, now, small = false)
            TagElement<HTMLElement>("dl", { classes("facts") }) {
                if (alternate?.title != null) {
                    Fact(Icon.Calendar, "Calendar title") { Span({ classes("dmeet__where") }) { Text(meeting.calendarTitle) } }
                }
                if (alternate?.joinUrl != null) Fact(Icon.Link, "Calendar link") { CalendarLink(meeting) }
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
 * The calendar's own join link while one set in Thread wins (D100): "Join (Teams)", in a new tab. Like every Join, the
 * link itself is never shown as text: it may hold a passcode.
 */
@Composable
private fun CalendarLink(meeting: Meeting) {
    val join = meeting.calendarJoin
    if (join == null) {
        None("No join link")
        return
    }
    val label = joinLabel(join.service?.label)
    A(join.url, {
        classes("lnk")
        target(ATarget.Blank)
        attr("rel", "noopener noreferrer")
        attr("aria-label", "$label, from the calendar")
    }) { Text(label) }
}

/**
 * One of the two fields of the Meeting card's Edit. It saves when it loses focus; Enter saves too. [onSave] returns false
 * when the text was refused and the field goes back to [value], the saved text. While [error] shows, the field keeps
 * what was typed. Otherwise it follows [value], so a change from another device shows.
 */
@Composable
private fun AlternateInput(
    id: String,
    value: String,
    placeholder: String,
    maxLength: Int,
    link: Boolean,
    error: String?,
    focus: Boolean,
    onElement: (HTMLInputElement?) -> Unit,
    onSave: (String) -> Boolean,
) {
    var element by remember { mutableStateOf<HTMLInputElement?>(null) }
    Input(if (link) InputType.Url else InputType.Text) {
        classes("input")
        id(id)
        placeholder(placeholder)
        maxLength(maxLength)
        fieldAttrs(id, error)
        attr("autocomplete", "off")
        if (link) {
            attr("inputmode", "url")
            attr("spellcheck", "false")
            attr("autocapitalize", "off")
        }
        ref {
            it.value = value
            element = it
            onElement(it)
            onDispose {
                element = null
                onElement(null)
            }
        }
        onKeyDown { event ->
            if (event.key == "Enter") {
                event.preventDefault()
                element?.blur()
            }
        }
        onChange { event ->
            if (!onSave(event.value)) element?.value = value
        }
    }
    SideEffect {
        element?.let { if (error == null && document.activeElement != it && it.value != value) it.value = value }
    }
    // The fields show only while editing, so they appear after Edit: the cursor goes to the end of the title.
    if (focus) {
        LaunchedEffect(element) {
            element?.let {
                it.focus()
                it.setSelectionRange(it.value.length, it.value.length)
            }
        }
    }
}

/** Why a title or link set in Thread was not saved. */
internal fun alternateProblemText(problem: EditProblem): String =
    when (problem) {
        EditProblem.BadLink -> "Use a full link that starts with https://, or leave it empty."
        EditProblem.TextTooLong -> "A title can have at most $MEETING_TEXT_MAX_LENGTH characters."
        EditProblem.NotReady -> "Your workspace is still loading. Try again in a moment."
        else -> "That change could not be made."
    }

private const val TITLE_ID = "dm-title"
private const val LINK_ID = "dm-link"
private const val LINK_HINT = "Any https link. Shown instead of the calendar's link. Leave empty to use it."

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
