package thread.web.presentation.tasks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.maxLength
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.attributes.required
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Aside
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.reminder.TodayReminder
import thread.domain.reminder.todayReminders
import thread.domain.task.DueGroup
import thread.domain.task.DueTaskGroup
import thread.domain.task.TASK_TITLE_MAX_LENGTH
import thread.domain.task.Task
import thread.domain.task.dueGroupOf
import thread.domain.task.firstDayOf
import thread.domain.task.groupByDue
import thread.domain.task.groupByTopic
import thread.domain.task.todayTasks
import thread.domain.time.clockText
import thread.domain.topic.StatusCategory
import thread.domain.topic.Topic
import thread.domain.topic.TopicGroup
import thread.domain.topic.TopicLine
import thread.domain.topic.TopicStatus
import thread.domain.topic.statusOf
import thread.domain.topic.todayStatuses
import thread.domain.topic.todayTopicGroups
import thread.domain.workspace.MeetingsLayout
import thread.domain.workspace.TodayCard
import thread.domain.workspace.TodayGroupBy
import thread.domain.workspace.TodayLayoutEdit
import thread.domain.workspace.TodayLayoutKind
import thread.domain.workspace.TodayZone
import thread.domain.workspace.TopicsView
import thread.domain.workspace.todayDesktopLayout
import thread.domain.workspace.todayHiddenWhenEmpty
import thread.domain.workspace.todayPhoneOrder
import thread.domain.workspace.todayTopicsLayout
import thread.web.platform.readDeviceText
import thread.web.platform.writeDeviceText
import thread.web.presentation.CardKind
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalCards
import thread.web.presentation.LocalNow
import thread.web.presentation.LocalToasts
import thread.web.presentation.PageHeader
import thread.web.presentation.Place
import thread.web.presentation.SectionCard
import thread.web.presentation.SelectBox
import thread.web.presentation.TaskCreation
import thread.web.presentation.ToastState
import thread.web.presentation.View
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.calendar.MeetingsModel
import thread.web.presentation.calendar.MeetingsRail
import thread.web.presentation.calendar.MeetingsTimeline
import thread.web.presentation.calendar.todayMeetingsView
import thread.web.presentation.cardKind
import thread.web.presentation.dayText
import thread.web.presentation.external.GitHubModel
import thread.web.presentation.external.ReviewRequestedRail
import thread.web.presentation.external.rememberReviewRequested
import thread.web.presentation.itemCard
import thread.web.presentation.longDate
import thread.web.presentation.onPlainClick
import thread.web.presentation.taskProblemText
import thread.web.presentation.topics.HexSize
import thread.web.presentation.topics.StatusLabel
import thread.web.presentation.topics.TopicHex
import thread.web.presentation.topics.TopicHexEmpty
import thread.web.presentation.topics.TopicProgress
import thread.web.presentation.topics.topicColor
import thread.web.presentation.topics.topicPlace

/**
 * Today (v3 today.html): the date and the personal checklist lead, meetings, reminders and review requests sit on the rail,
 * the picked Topics below (D89). With "Meetings layout" set to Horizontal (D79), the meetings leave the rail for a
 * timeline across the page, under the heading and above both columns. The Tasks are grouped as Settings > Today
 * "Group tasks by" says (D90); see [TodayTaskGroups]. Every group opens and closes, and this device remembers it (D53).
 * An empty task group is left out; so are the Meetings, Reminders and Review requested sections Settings hides when
 * empty (D77, D86); see [TodaySections]. Since D95 the cards are placed by the desktop layout
 * ([thread.domain.workspace.todayDesktopLayout]); with none saved it is the layout described here. A window 759 px wide
 * or narrower shows them in one column, in the phone order ([thread.domain.workspace.todayPhoneOrder], D98).
 *
 * "Edit layout" opens edit mode (D98, [TodayEditView]): [startEditing] opens it at once (Settings > Today links here
 * with it), and [onEditingStarted] then drops that from the address.
 */
@Composable
internal fun TodayScreen(
    workspace: WorkspaceModel,
    gitHub: GitHubModel,
    meetings: MeetingsModel,
    zone: String,
    startAdding: Boolean,
    startEditing: Boolean,
    onEditingStarted: () -> Unit,
    onOpenTask: (Task) -> Unit,
    onOpenTarget: (ReminderTarget) -> Unit,
) {
    if (!workspace.loaded) {
        if (workspace.readFailed) EmptyState(Icon.Alert, "Today could not load", "Reload the page to try again.")
        return
    }
    val now = LocalNow.current
    var adding by remember { mutableStateOf(startAdding) }
    var focusAddRequest by remember { mutableStateOf(0) }
    val remindAt = workspace.taskReminderTimes
    val tasks = todayTasks(workspace.storedTasks, now, workspace.prefs.keepCompleted, remindAt)
    // The header counts what is for today, as before D90: later Tasks are not counted (D53).
    val open = tasks.count { dueGroupOf(it, now, remindAt[it.id]) == DueGroup.Today }
    val done = tasks.count { it.done }

    LaunchedEffect(focusAddRequest) {
        if (focusAddRequest > 0) focusById(if (adding) "today-new" else "today-add")
    }

    // Edit mode (D98): the draft, or null while Today is shown. It edits the layout this window used when it opened (D-9,
    // A25), also if the window then crosses 759/760 px.
    val toasts = LocalToasts.current
    val phone = rememberPhoneLayout()
    var edit by remember { mutableStateOf<TodayLayoutEdit?>(null) }
    var focusTarget by remember { mutableStateOf<FocusTarget?>(null) }
    LaunchedEffect(focusTarget) { focusTarget?.let { focusById(it.id) } }
    val openEdit = {
        if (edit == null) {
            edit = TodayLayoutEdit.start(workspace.prefs, if (phone) TodayLayoutKind.Phone else TodayLayoutKind.Desktop)
            focusTarget = FocusTarget(EDIT_TITLE_ID)
        }
    }
    val closeEdit = { save: Boolean ->
        edit?.let { draft ->
            edit = null
            when {
                save -> saveLayout(workspace, toasts, draft)
                draft.done(workspace.prefs) != workspace.prefs -> toasts.show("Changes undone")
            }
            focusTarget = FocusTarget(EDIT_BUTTON_ID)
        }
    }
    LaunchedEffect(startEditing) {
        if (startEditing) {
            openEdit()
            onEditingStarted()
        }
    }
    // Leaving Today while editing saves, like Done (section 4), quietly when nothing changed. A reload or a closed tab
    // drops the draft. It reads the state itself, not a copy from the last composition: Cancel and then a page change
    // before the next frame must not save the draft Cancel dropped.
    DisposableEffect(Unit) {
        onDispose { edit?.let { if (workspace.loaded) saveLayout(workspace, toasts, it, leaving = true) } }
    }
    if (edit != null) {
        DisposableEffect(Unit) {
            val stop = onEscape { closeEdit(false) }
            onDispose { stop() }
        }
    }

    PageHeader(
        title = longDate(now.day),
        sub =
            (if (open > 0) "$open ${if (open == 1) "open task" else "open tasks"}" else "No open tasks") +
                if (done > 0) ", $done done today" else "",
    ) {
        // While editing, the bar takes this button's part.
        if (edit == null) {
            Button({
                type(ButtonType.Button)
                id(EDIT_BUTTON_ID)
                classes("btn", "btn--line")
                onClick { openEdit() }
            }) { Text("Edit layout") }
        }
        Button({
            type(ButtonType.Button)
            id("today-add")
            classes("btn", "btn--primary")
            onClick {
                adding = true
                focusAddRequest++
            }
        }) { Text("Add task for today") }
    }
    if (adding) {
        AddForTodayForm(workspace) {
            adding = false
            focusAddRequest++
        }
    }
    val reminders = todayReminders(workspace.reminders, now)
    val reviews = rememberReviewRequested(gitHub)
    val layout = workspace.prefs.meetingsLayout
    // D86: "Show past meetings" is for the Vertical card only; the timeline keeps the whole day.
    val pastMeetings = if (layout == MeetingsLayout.Vertical) workspace.prefs.todayPastMeetings else null
    val meetingsView = todayMeetingsView(meetings.state, now, zone, pastMeetings)
    val draft = edit
    // In edit mode the draft's hide choices say which cards Today would draw, for the "not shown" tags.
    val sections =
        TodaySections(
            hidden = draft?.hidden ?: workspace.prefs.todayHiddenWhenEmpty,
            hasTasks = tasks.isNotEmpty(),
            reminderRows = reminders.count { targetInfo(workspace, it.reminder.target) != null },
            reviewContent = reviewContent(gitHub.checked && gitHub.connected, reviews),
            meetingsShown = meetings.cardShown,
            meetingsContent = meetingsView.hasContent,
            meetingsLayout = layout,
            topicsShown = (draft?.categories ?: workspace.prefs.todayTopicCategories).isNotEmpty(),
        )
    if (draft != null) {
        TodayEditBar(
            draft,
            onReset = {
                edit = draft.resetLayout()
                val what = if (draft.kind == TodayLayoutKind.Phone) "Order matches the desktop" else "Layout back to default"
                toasts.show("$what. Press Done to save.")
                // Reset is disabled now: focus goes on to Done.
                focusTarget = FocusTarget(EDIT_DONE_ID)
            },
            onCancel = { closeEdit(false) },
            onDone = { closeEdit(true) },
        )
        TodayEditView(draft, sections) { change ->
            edit = change.edit
            toasts.show(change.announcement)
            focusTarget = FocusTarget(change.focusId)
        }
        return
    }
    val cards = LocalCards.current
    // D95: each card Today draws, where the desktop layout puts it. A place left with no card takes no space.
    val places = workspace.prefs.todayDesktopLayout.only(sections.drawn)
    val drawCard: @Composable (TodayCard) -> Unit = { card ->
        when (card) {
            // D79 in every place: Horizontal draws the meetings as a timeline, Vertical as the Meetings card.
            TodayCard.Meetings ->
                if (layout == MeetingsLayout.Horizontal) {
                    MeetingsTimeline(meetingsView, workspace.prefs.meetingTitleFit, workspace::topicsOfSeries) { cards?.openMeeting(it.id) }
                } else {
                    MeetingsRail(meetingsView, workspace::topicsOfSeries) { cards?.openMeeting(it.id) }
                }
            // Every task group in one card, which moves as one (D-1).
            TodayCard.Tasks -> Div({ classes("today__tasks") }) { TodayTaskGroups(tasks, workspace, onOpenTask) }
            TodayCard.Reminders -> RemindersRail(workspace, reminders, onOpenTarget)
            TodayCard.ReviewRequested -> ReviewRequestedRail(gitHub, reviews)
            TodayCard.Topics -> ActiveTopics(workspace)
        }
    }
    if (sections.nothing) NothingForToday()
    if (phone) {
        PhoneColumn(workspace.prefs.todayPhoneOrder.filter { it in sections.drawn }, drawCard)
        return
    }
    // Top and Bottom run across the page, above and below the two columns. Each place is a zone (`tzone`), and each card
    // in it sizes its forms by its own width (D96, css/cards.css).
    WideZone(TodayZone.Top, places.top, drawCard)
    if (places.left.isNotEmpty() || places.right.isNotEmpty()) {
        Div({
            classes("today")
            // A column with nothing to show is left out, and the other takes the width (D77).
            if (places.oneSide) classes("today--one")
        }) {
            if (places.left.isNotEmpty()) {
                Div({
                    classes("today__main", "tzone")
                    attr("data-zone", TodayZone.Left.id)
                }) { places.left.forEach { key(it) { drawCard(it) } } }
            }
            if (places.right.isNotEmpty()) {
                Aside({
                    classes("today__rail", "tzone")
                    attr("data-zone", TodayZone.Right.id)
                    attr("aria-label", sideColumnLabel(places.right))
                }) { places.right.forEach { key(it) { drawCard(it) } } }
            }
        }
    }
    WideZone(TodayZone.Bottom, places.bottom, drawCard)
}

/** A control edit mode focuses; a new one each time, so the same id is focused again. */
private class FocusTarget(
    val id: String,
)

private const val EDIT_BUTTON_ID = "today-edit"

/**
 * Done, or [leaving] Today while editing: saves what changed in edit mode (D98), and says so. Leaving with nothing
 * changed says nothing.
 */
private fun saveLayout(
    workspace: WorkspaceModel,
    toasts: ToastState,
    draft: TodayLayoutEdit,
    leaving: Boolean = false,
) {
    val next = draft.done(workspace.prefs)
    when {
        next != workspace.prefs -> {
            workspace.setPrefs(next)
            toasts.show("Layout saved")
        }
        !leaving -> toasts.show("No changes")
    }
}

/**
 * Calls [onCancel] on Esc while edit mode is open. Esc in a dialog (an open card), in the add form or during an IME
 * composition belongs there. Returns a function that removes the listener.
 */
private fun onEscape(onCancel: () -> Unit): () -> Unit {
    val listener: (Event) -> Unit = { event ->
        val target = event.target as? Element
        val ours =
            event is KeyboardEvent &&
                event.key == "Escape" &&
                !event.isComposing &&
                !event.defaultPrevented &&
                document.querySelector("dialog[open]") == null &&
                target?.closest("form") == null
        if (ours) {
            event.preventDefault()
            onCancel()
        }
    }
    document.addEventListener("keydown", listener)
    return { document.removeEventListener("keydown", listener) }
}

/** Top or Bottom with its [cards], across the page; nothing when it has none (D96). */
@Composable
private fun WideZone(
    zone: TodayZone,
    cards: List<TodayCard>,
    drawCard: @Composable (TodayCard) -> Unit,
) {
    if (cards.isEmpty()) return
    Div({
        classes("tzone", "tzone--wide", "tzone--${zone.id}")
        attr("data-zone", zone.id)
    }) { cards.forEach { key(it) { drawCard(it) } } }
}

/** Where the empty state sends the person: Settings, at Today (D84). */
private val PREFERENCES_PLACE = Place.of("settings", "today")

/**
 * No task to show and every section Settings hides when empty has nothing (D77, D86): one line says so in their place.
 * The page header, its add button and the Topics card stay.
 */
@Composable
private fun NothingForToday() {
    EmptyState(Icon.Today, "Nothing for today", "No tasks, meetings, reminders or review requests. Empty sections are hidden.") {
        A(PREFERENCES_PLACE.hash, { classes("btn", "btn--line") }) { Text("Change in Settings") }
    }
}

/** The v3 add row: planned for today, and in Inbox too when it has no Topic (D38). */
@Composable
private fun AddForTodayForm(
    workspace: WorkspaceModel,
    onDone: () -> Unit,
) {
    val now = LocalNow.current
    val toasts = LocalToasts.current
    var title by remember { mutableStateOf("") }
    var topicPick by remember { mutableStateOf("") }
    val statuses = workspace.statuses
    val topics = workspace.topics.filter { statusOf(it, statuses).category != StatusCategory.Done }
    LaunchedEffect(Unit) { focusById("today-new") }
    Form(attrs = {
        classes("addrow")
        attr("autocomplete", "off")
        onSubmit { event ->
            event.preventDefault()
            val topic = topicPick.takeIf(String::isNotEmpty)?.let { workspace.topic(it) }
            when (val created = workspace.createTask(title, topic?.id, plannedDay = now.day)) {
                is TaskCreation.Refused -> toasts.show(taskProblemText(created.problem))
                is TaskCreation.Created -> {
                    toasts.show(
                        if (topic != null) "Added to Today and ${topic.name}" else "Added to Today and Inbox",
                        undo = { workspace.discard(created.task) },
                    )
                    title = ""
                    focusNow("today-new")
                }
            }
        }
    }) {
        Label("today-new", { classes("vh") }) { Text("New task for today") }
        TextInput(title) {
            id("today-new")
            classes("input")
            placeholder("What do you need to do today?")
            required()
            maxLength(TASK_TITLE_MAX_LENGTH)
            onInput { title = it.value }
        }
        Label("today-topic", { classes("vh") }) { Text("Topic (optional)") }
        SelectBox(
            value = topicPick,
            options = listOf("" to "No Topic") + topics.map { it.id.value to it.name },
            id = "today-topic",
            classes = listOf("input"),
        ) { topicPick = it }
        Button({
            type(ButtonType.Submit)
            classes("btn", "btn--line")
        }) { Text("Add") }
        Button({
            type(ButtonType.Button)
            classes("btn", "btn--text")
            onClick { onDone() }
        }) { Text("Done") }
        P({ classes("addrow__note") }) { Text("Planned for today. Without a Topic it also waits in Inbox until you file it.") }
    }
}

/**
 * Today's Tasks ([todayTasks]), grouped as Settings > Today "Group tasks by" says (D90), with the shared task-group rule:
 *
 * - Due date, the default: Completed (only while "Show completed tasks" is on), Today, Tomorrow and Upcoming, in the
 *   owner's order (Q1). Completed and Upcoming start closed (D53).
 * - Topic: a group per Topic, the one with the most urgent open Task first, "No Topic" last (A4). Each starts open.
 * - None: one list, in the same order.
 *
 * Only the headings change between them: the same Tasks show in the same order (A4). An empty group is not drawn (Q2).
 * Without headings for the day, the rows say when a Task is planned, today included.
 */
@Composable
private fun TodayTaskGroups(
    tasks: List<Task>,
    workspace: WorkspaceModel,
    onOpenTask: (Task) -> Unit,
) {
    val now = LocalNow.current
    val remindAt = workspace.taskReminderTimes
    // The Tasks for today, done or not, lead the order. They always show; "Show all" is for the later ones.
    val todayPart = { list: List<Task> -> list.count { dueGroupOf(it, now, remindAt[it.id]) in TODAY_PART } }
    when (workspace.prefs.todayGroupBy) {
        TodayGroupBy.DueDate ->
            groupByDue(tasks, now, remindAt).forEach { group ->
                key(group.group.name) { DueGroupCard(group, workspace, onOpenTask) }
            }
        TodayGroupBy.Topic ->
            groupByTopic(tasks, workspace.topics, now, remindAt).forEach { group ->
                val topic = group.topic
                val groupKey = if (topic == null) "notopic" else "topic-${topic.id.value}"
                val name = topic?.name ?: "No Topic"
                key(groupKey) {
                    TaskGroup(
                        key = groupKey,
                        kind = "topic",
                        title = name,
                        mark = Icon.Topics,
                        tasks = group.tasks,
                        hint = null,
                        openByDefault = true,
                        titleContent = {
                            Span({ classes("tgroup__topic") }) {
                                if (topic == null) TopicHexEmpty(HexSize.Xs) else TopicHex(topic.name, topic.color, HexSize.Xs)
                                Span { Text(name) }
                            }
                        },
                    ) {
                        ShortenedList(group.tasks, keep = todayPart(group.tasks)) { shown ->
                            TaskList(shown, workspace, onOpenTask, hideTopic = true, showPlanned = true)
                        }
                    }
                }
            }
        TodayGroupBy.None ->
            TaskGroup("all", "all", "Tasks", Icon.Task, tasks, NONE_HINT, openByDefault = true) {
                ShortenedList(tasks, keep = todayPart(tasks)) { shown -> TaskList(shown, workspace, onOpenTask, showPlanned = true) }
            }
    }
}

/** The groups whose Tasks always show, before "Show all": what is for today, open or completed. */
private val TODAY_PART = setOf(DueGroup.Today, DueGroup.Completed)

private const val NONE_HINT = "For today first, then done today, then later and with no date"

/**
 * One due-date group (D90, Q1), named by its [DueGroup] on this device. Today and Completed show every Task; Tomorrow
 * and Upcoming the first ones, then "Show all".
 */
@Composable
private fun DueGroupCard(
    group: DueTaskGroup,
    workspace: WorkspaceModel,
    onOpenTask: (Task) -> Unit,
) {
    val tasks = group.tasks
    val key = group.group.name.lowercase()
    val (mark, hint) = dueGroupLook(group.group)
    TaskGroup(key, key, dueGroupTitle(group.group), mark, tasks, hint, openByDefault = group.group in OPEN_DUE_GROUPS) {
        when (group.group) {
            DueGroup.Completed, DueGroup.Today -> TaskList(tasks, workspace, onOpenTask)
            DueGroup.Tomorrow -> ShortenedList(tasks) { shown -> TaskList(shown, workspace, onOpenTask) }
            DueGroup.Upcoming -> UpcomingList(tasks, workspace, onOpenTask)
        }
    }
}

/**
 * A due-date group's mark and hint (D90), on Today and in Inbox (D91). D54: a reminder day counts as a date, so the hints
 * name reminders too.
 */
internal fun dueGroupLook(group: DueGroup): Pair<Icon, String> =
    when (group) {
        DueGroup.Completed -> Icon.Check to "Done today. They leave when the day ends"
        DueGroup.Today -> Icon.Today to "Overdue, planned, due or reminded today, or planned earlier"
        DueGroup.Tomorrow -> Icon.Calendar to "Planned, due or reminded tomorrow"
        DueGroup.Upcoming -> Icon.ArrowRight to "Later dates, then no date"
    }

/** A due-date group's heading, also named by the Task sheet (D90). */
internal fun dueGroupTitle(group: DueGroup): String =
    when (group) {
        DueGroup.Completed -> "Completed"
        DueGroup.Today -> "Today"
        DueGroup.Tomorrow -> "Tomorrow"
        DueGroup.Upcoming -> "Upcoming"
    }

/** The due-date groups that start open. Completed starts closed, like Topic Details' Completed (A8); Upcoming too (D53). */
private val OPEN_DUE_GROUPS = setOf(DueGroup.Today, DueGroup.Tomorrow)

/**
 * One group of Today's main column: a Task section card (D68) whose [mark] says which group it is. Its heading is a
 * button that opens and closes it (D53), like a Topic page section; the open count stays in the heading when it is
 * closed, and the Completed group counts its Tasks. [key] names it on this device, [kind] in the page's classes. An
 * empty group is not shown (D86, Q2).
 */
@Composable
private fun TaskGroup(
    key: String,
    kind: String,
    title: String,
    mark: Icon,
    tasks: List<Task>,
    hint: String?,
    openByDefault: Boolean,
    titleContent: (@Composable () -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    if (tasks.isEmpty()) return
    var open by remember(key) { mutableStateOf(isGroupOpen(key, openByDefault)) }
    SectionCard(
        kind = CardKind.Task,
        headingId = "g-$key",
        bodyId = "gb-$key",
        title = title,
        count = (if (kind == "completed") tasks.size else tasks.count { !it.done }).toString(),
        open = open,
        onToggle = {
            open = !open
            keepGroupOpen(key, open)
        },
        hint = hint,
        mark = mark,
        attrs = { classes("tgroup--$kind") },
        titleContent = titleContent,
        body = body,
    )
}

@Composable
private fun TaskList(
    tasks: List<Task>,
    workspace: WorkspaceModel,
    onOpenTask: (Task) -> Unit,
    hideTopic: Boolean = false,
    showPlanned: Boolean = false,
) {
    val toasts = LocalToasts.current
    Ul({ classes("tlist") }) {
        tasks.forEach { task -> key(task.id.value) { TaskRow(task, workspace, toasts, onOpenTask, hideTopic, showPlanned) } }
    }
}

/**
 * Upcoming (D53, D90): the first [LIST_PREVIEW] Tasks under small day headings ("Mon 17 Nov"), the ones with no date
 * last, under "No date".
 */
@Composable
private fun UpcomingList(
    tasks: List<Task>,
    workspace: WorkspaceModel,
    onOpenTask: (Task) -> Unit,
) {
    val today = LocalNow.current.day
    ShortenedList(tasks) { shown ->
        shown.groupBy { firstDayOf(it, workspace.taskReminderTimes[it.id]) }.forEach { (day, onDay) ->
            key(day.toString()) {
                H3({ classes("tgroup__day") }) { Text(if (day == null) "No date" else dayText(day, today)) }
                TaskList(onDay, workspace, onOpenTask)
            }
        }
    }
}

/**
 * The first [keep] Tasks and [LIST_PREVIEW] more, then a button that shows all of them, and fewer again. [keep] is for
 * the Tasks that must always show.
 */
@Composable
private fun ShortenedList(
    tasks: List<Task>,
    keep: Int = 0,
    content: @Composable (List<Task>) -> Unit,
) {
    var all by remember { mutableStateOf(false) }
    val preview = keep + LIST_PREVIEW
    content(if (all) tasks else tasks.take(preview))
    if (tasks.size > preview) {
        Button({
            type(ButtonType.Button)
            classes("btn", "btn--text", "btn--sm", "tgroup__more")
            onClick { all = !all }
        }) { Text(if (all) "Show fewer" else "Show all (${tasks.size})") }
    }
}

/** Rows a shortened list shows before "Show all" (D53), after the ones it always shows. */
private const val LIST_PREVIEW = 10

/** Where this device keeps whether a Today group is open: `1` or `0`, per group. Not synced (D53). */
internal const val GROUP_OPEN_NAME = "thread.today.open"

/** Whether the group is open on this device: what was last chosen here, else [default], also when storage is blocked. */
internal fun isGroupOpen(
    key: String,
    default: Boolean,
): Boolean =
    when (readDeviceText("$GROUP_OPEN_NAME.$key")) {
        "1" -> true
        "0" -> false
        else -> default
    }

internal fun keepGroupOpen(
    key: String,
    open: Boolean,
) = writeDeviceText("$GROUP_OPEN_NAME.$key", if (open) "1" else "0")

@Composable
private fun RemindersRail(
    workspace: WorkspaceModel,
    list: List<TodayReminder>,
    onOpenTarget: (ReminderTarget) -> Unit,
) {
    SectionCard(
        kind = CardKind.Reminder,
        headingId = "rem-h",
        bodyId = "rem-b",
        title = "Reminders",
        count = list.size.toString(),
        attrs = { classes("scard--rem") },
    ) {
        if (list.isEmpty()) {
            P({ classes("scard__empty") }) { Text("No reminders today. Add one from a Task or Note.") }
            return@SectionCard
        }
        Ul({ classes("tlist") }) {
            list.forEach { entry ->
                val reminder = entry.reminder
                val info = targetInfo(workspace, reminder.target) ?: return@forEach
                key(reminder.id.value) {
                    Li {
                        Button({
                            type(ButtonType.Button)
                            itemCard(CardKind.Reminder)
                            classes("rem")
                            if (entry.past) classes("is-past")
                            onClick { onOpenTarget(reminder.target) }
                        }) {
                            Span({ classes("rem__time") }) { Text(clockText(reminder.at.minuteOfDay)) }
                            Span({ classes("rem__text") }) {
                                Text(reminder.text)
                                if (entry.past) Span({ classes("vh") }) { Text(" (past)") }
                                if (entry.next) Span({ classes("rem__next") }) { Text("Next") }
                            }
                            Span({ classes("rem__for") }) {
                                IconView(if (info.kind == TargetKind.Task) Icon.Task else Icon.Topics, 14)
                                Span { Text(if (info.kind == TargetKind.Task) "Task: ${info.label}" else info.label) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Where this device kept Today's Topics layout before it synced (owner change #1, D84). Only read now: until the
 * synced choice is first set, this device's old pick is the starting value.
 */
internal const val ACTIVE_LAYOUT_NAME = "thread.today.activeLayout"

/** The layout picked on this device before D84, or null when none was picked, it is unknown or storage is blocked. */
internal fun deviceTodayTopicsView(): TopicsView? = TopicsView.fromId(readDeviceText(ACTIVE_LAYOUT_NAME))

/**
 * Today's Topics (owner change #1, D89): only Topics whose status is in a category Settings > Today "Topics shown" picks,
 * Active and Paused by default, grouped by that status in Settings order. With no category picked the card is left
 * out. The title names the picked categories. List: a heading per status that opens and closes, with the Topics as
 * rows. Board: a lane per status. With one such status there is nothing to group, so the list has no heading and the
 * board is the card grid.
 */
@Composable
private fun ActiveTopics(workspace: WorkspaceModel) {
    val categories = workspace.prefs.todayTopicCategories
    if (categories.isEmpty()) return
    val statuses = workspace.statuses
    val shownStatuses = todayStatuses(statuses, categories)
    val groups = todayTopicGroups(workspace.topics, statuses, categories)
    val count = groups.sumOf { it.topics.size }
    val grouped = shownStatuses.size > 1
    val title = todayTopicsTitle(categories)
    // List or Board syncs with Settings > Today (D84).
    val onThisDevice = remember { deviceTodayTopicsView() }
    val layout = workspace.prefs.todayTopicsLayout(onThisDevice)
    // Grouped, the headings name the statuses, so the hint would only repeat them.
    val hint =
        if (grouped) {
            null
        } else {
            val names = shownStatuses.joinToString(" and ") { it.name }
            val choice = todayCategoryChoice(categories)
            if (names.isEmpty()) "No status uses the $choice category" else "Statuses in the $choice category: $names"
        }
    SectionCard(
        kind = CardKind.Topic,
        headingId = "at-h",
        bodyId = "at-b",
        title = title,
        count = count.toString(),
        hint = hint,
        attrs = { classes("atopics") },
        actions =
            if (count > 0) {
                {
                    LayoutSwitch(title, layout) { picked -> workspace.setPrefs(workspace.prefs.copy(todayTopicsView = picked)) }
                }
            } else {
                null
            },
    ) {
        if (count == 0) {
            EmptyState(Icon.Topics, todayNoTopicsText(categories), todayNoTopicsWhy(categories)) {
                A(View.Topics.place.hash, { classes("btn", "btn--line") }) { Text("Open Topics") }
            }
            return@SectionCard
        }
        when {
            layout == TopicsView.List && grouped ->
                groups.forEach { group -> key(group.status.id.value) { ActiveGroup(group, workspace) } }
            layout == TopicsView.List -> ActiveRows(groups.single().topics, workspace)
            grouped -> ActiveBoard(shownStatuses, groups, workspace)
            else -> ActiveCards(groups.single().topics, workspace, showStatus = true)
        }
    }
}

@Composable
private fun LayoutSwitch(
    title: String,
    layout: TopicsView,
    onPick: (TopicsView) -> Unit,
) {
    Div({
        classes("seg")
        attr("role", "radiogroup")
        attr("aria-label", "$title layout")
    }) {
        TopicsView.entries.forEach { option ->
            Button({
                type(ButtonType.Button)
                attr("role", "radio")
                attr("aria-checked", (option == layout).toString())
                onClick { onPick(option) }
            }) {
                IconView(if (option == TopicsView.List) Icon.List else Icon.Topics, 16)
                Text(if (option == TopicsView.List) " List" else " Board")
            }
        }
    }
}

/** One status's Topics in the list. Its heading opens and closes it, and this device remembers it (D53). */
@Composable
private fun ActiveGroup(
    group: TopicGroup,
    workspace: WorkspaceModel,
) {
    val key = "at-${group.status.id.value}"
    var open by remember(key) { mutableStateOf(isGroupOpen(key, true)) }
    Section({
        classes("csub")
        if (!open) classes("is-closed")
        attr("aria-labelledby", "g-$key")
    }) {
        H3({
            classes("csub__h")
            id("g-$key")
        }) {
            Button({
                type(ButtonType.Button)
                classes("csub__toggle")
                attr("aria-expanded", open.toString())
                attr("aria-controls", "gb-$key")
                onClick {
                    open = !open
                    keepGroupOpen(key, open)
                }
            }) {
                Span({ classes("chev") }) { IconView(Icon.ChevDown, 18) }
                StatusLabel(group.status)
                Span({ classes("n") }) { Text(group.topics.size.toString()) }
            }
        }
        Div({
            id("gb-$key")
            if (!open) attr("hidden", "")
        }) { if (open) ActiveRows(group.topics, workspace) }
    }
}

/** Topics as rows, like the Topics list: sticker, name, next Task, progress. */
@Composable
private fun ActiveRows(
    topics: List<Topic>,
    workspace: WorkspaceModel,
) {
    val cards = LocalCards.current
    Ul({ classes("tlist") }) {
        topics.forEach { topic ->
            key(topic.id.value) {
                Li {
                    // A Topic from Today opens as a card (D70, owner answer Q1); the address stays its page.
                    A(topicPlace(topic).hash, {
                        itemCard(CardKind.Topic)
                        classes("trow2")
                        topicColor(topic.color)
                        if (cards != null) onPlainClick { cards.openTopic(topic.id) }
                    }) {
                        TopicHex(topic.name, topic.color, HexSize.Md)
                        Span({ classes("trow2__name") }) { Text(topic.name) }
                        Span({ classes("trow2__next") }) { NextLine(workspace.nextLine(topic)) }
                        Span({ classes("trow2__side") }) { TopicProgress(topic, workspace.progress(topic.id)) }
                    }
                }
            }
        }
    }
}

/**
 * The small board: a lane per status in a picked category (D89), in Settings order, so a status keeps its place. A lane no
 * Topic uses says so; on a phone, where lanes stack, it is left out.
 */
@Composable
private fun ActiveBoard(
    shownStatuses: List<TopicStatus>,
    groups: List<TopicGroup>,
    workspace: WorkspaceModel,
) {
    Div({
        classes("aboard")
        // The board widens past the page only as far as its lanes need (owner change #14, D66).
        style { property("--lanes", shownStatuses.size.toString()) }
    }) {
        shownStatuses.forEach { status ->
            val topics = groups.firstOrNull { it.status.id == status.id }?.topics.orEmpty()
            key(status.id.value) {
                Section({
                    classes("lane", "alane")
                    if (topics.isEmpty()) classes("is-empty")
                    attr("aria-labelledby", "al-${status.id.value}")
                }) {
                    H3({
                        classes("lane__head")
                        id("al-${status.id.value}")
                    }) {
                        StatusLabel(status)
                        Text(" ")
                        Span({ classes("n") }) { Text(topics.size.toString()) }
                    }
                    if (topics.isEmpty()) {
                        P({ classes("lane__empty") }) { Text("No Topics") }
                    } else {
                        ActiveCards(topics, workspace, showStatus = false)
                    }
                }
            }
        }
    }
}

/** The v3 Today cards. In a lane the lane names the status, so the card leaves it out. */
@Composable
private fun ActiveCards(
    topics: List<Topic>,
    workspace: WorkspaceModel,
    showStatus: Boolean,
) {
    val statuses = workspace.statuses
    val cards = LocalCards.current
    Ul({ classes(if (showStatus) "agrid" else "lane__list") }) {
        topics.forEach { topic ->
            key(topic.id.value) {
                Li {
                    A(topicPlace(topic).hash, {
                        classes("acard")
                        cardKind(CardKind.Topic)
                        topicColor(topic.color)
                        if (cards != null) onPlainClick { cards.openTopic(topic.id) }
                    }) {
                        Span({ classes("acard__top") }) {
                            TopicHex(topic.name, topic.color, HexSize.Md)
                            Span({ classes("acard__name") }) { Text(topic.name) }
                        }
                        if (showStatus) StatusLabel(statusOf(topic, statuses))
                        Span({ classes("acard__next") }) { NextLine(workspace.nextLine(topic)) }
                        Span({ classes("acard__foot") }) { TopicProgress(topic, workspace.progress(topic.id)) }
                    }
                }
            }
        }
    }
}

/**
 * A Topic's line, as on the Topics list (D81, refining D52): its first open Task, else its Additional information, else
 * its goal, at most two lines (css `.tabout`). "Finished." shows only when "Topics shown" picks Done (D89).
 */
@Composable
private fun NextLine(line: TopicLine) {
    when (line) {
        is TopicLine.NextTask -> {
            B { Text("Next:") }
            Text(" ${line.title}")
        }
        is TopicLine.About -> Span({ classes("tabout") }) { Text(line.text) }
        TopicLine.Finished -> Span({ classes("muted") }) { Text("Finished.") }
        TopicLine.NoOpenTasks -> Span({ classes("muted") }) { Text("No open tasks.") }
    }
}

/** Focuses the element with [id] after the next frame, once Compose has drawn it. */
internal suspend fun focusById(id: String) {
    delay(16)
    focusNow(id)
}

internal fun focusNow(id: String) {
    (document.getElementById(id) as? HTMLElement)?.focus()
}
