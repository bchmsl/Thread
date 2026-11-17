package thread.web.presentation.tasks

import androidx.compose.runtime.Composable
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
import org.w3c.dom.HTMLElement
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.reminder.TodayReminder
import thread.domain.reminder.todayReminders
import thread.domain.task.TASK_TITLE_MAX_LENGTH
import thread.domain.task.Task
import thread.domain.task.firstDayOf
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
import thread.domain.workspace.TopicsView
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
import thread.web.presentation.View
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.calendar.MeetingsModel
import thread.web.presentation.calendar.MeetingsRail
import thread.web.presentation.calendar.MeetingsTimeline
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
import thread.web.presentation.topics.TopicProgress
import thread.web.presentation.topics.topicColor
import thread.web.presentation.topics.topicPlace

/**
 * Today (v3 today.html): the date and the personal checklist lead, meetings, reminders and review requests sit on the rail,
 * active and paused Topics below. With "Meetings layout" set to Horizontal (D79), the meetings leave the rail for a
 * timeline across the page, under the heading and above both columns. Each Task shows once, in the first group it
 * qualifies for (D37). Upcoming and No date follow Earlier planned and start closed; every group opens and closes, and
 * this device remembers it (D53). With "Hide empty sections in Today" on, an empty Today, Reminders or Review requested
 * section is left out too (D77); see [TodaySections].
 */
@Composable
internal fun TodayScreen(
    workspace: WorkspaceModel,
    gitHub: GitHubModel,
    meetings: MeetingsModel,
    zone: String,
    startAdding: Boolean,
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
    val groups = todayTasks(workspace.storedTasks, now, workspace.prefs.keepCompleted, workspace.taskReminderTimes)
    val open = groups.openCount
    val done = groups.doneCount

    LaunchedEffect(focusAddRequest) {
        if (focusAddRequest > 0) focusById(if (adding) "today-new" else "today-add")
    }

    PageHeader(
        title = longDate(now.day),
        sub =
            (if (open > 0) "$open ${if (open == 1) "open task" else "open tasks"}" else "No open tasks") +
                if (done > 0) ", $done done today" else "",
    ) {
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
    val sections =
        TodaySections(
            hideEmpty = workspace.prefs.hideEmptySections,
            tasks = groups,
            reminderRows = reminders.count { targetInfo(workspace, it.reminder.target) != null },
            reviewContent = reviewContent(gitHub.checked && gitHub.connected, reviews),
            meetingsShown = meetings.cardShown,
            meetingsLayout = workspace.prefs.meetingsLayout,
        )
    val cards = LocalCards.current
    // D79: Horizontal draws the meetings as a timeline across the page, above both columns.
    if (sections.timeline) MeetingsTimeline(meetings, zone, workspace::topicsOfSeries) { cards?.openMeeting(it.id) }
    if (sections.nothing) {
        NothingForToday()
    } else if (sections.main || sections.rail) {
        Div({
            classes("today")
            // A column with nothing to show is left out, and the other takes the width (D77).
            if (!sections.main || !sections.rail) classes("today--one")
        }) {
            if (sections.main) {
                Div({ classes("today__main") }) {
                    TaskGroup("overdue", "Overdue", Icon.Alert, groups.overdue, "Deadline passed", openByDefault = true) {
                        TaskList(groups.overdue, workspace, onOpenTask)
                    }
                    // D54: a reminder day counts as a date, so the hints name reminders too.
                    if (sections.today) {
                        TaskGroup("today", "Today", Icon.Today, groups.today, "Planned, due or reminded today", openByDefault = true) {
                            if (groups.today.isEmpty()) {
                                P({ classes("scard__empty") }) { Text("Nothing planned for today yet.") }
                            } else {
                                TaskList(groups.today, workspace, onOpenTask)
                            }
                        }
                    }
                    val earlierHint = "Planned before today, still open"
                    TaskGroup("earlier", "Earlier planned", Icon.Clock, groups.earlier, earlierHint, openByDefault = true) {
                        TaskList(groups.earlier, workspace, onOpenTask)
                    }
                    // D53: the open Tasks the groups above leave out. They start closed.
                    val upcomingHint = "Planned, due or reminded after today"
                    TaskGroup("upcoming", "Upcoming", Icon.ArrowRight, groups.upcoming, upcomingHint, openByDefault = false) {
                        UpcomingList(groups.upcoming, workspace, onOpenTask)
                    }
                    val noDateHint = "No planned day, deadline or reminder"
                    TaskGroup("nodate", "No date", Icon.Task, groups.noDate, noDateHint, openByDefault = false) {
                        ShortenedList(groups.noDate) { shown -> TaskList(shown, workspace, onOpenTask) }
                    }
                }
            }
            if (sections.rail) {
                Aside({
                    classes("today__rail")
                    attr("aria-label", if (sections.meetings) "Meetings, reminders and reviews" else "Reminders and reviews")
                }) {
                    if (sections.meetings) MeetingsRail(meetings, zone, workspace::topicsOfSeries) { cards?.openMeeting(it.id) }
                    if (sections.reminders) RemindersRail(workspace, reminders, onOpenTarget)
                    if (sections.review) ReviewRequestedRail(gitHub, reviews)
                }
            }
        }
    }
    ActiveTopics(workspace)
}

/** Where the empty state sends the person: Settings, at Today (D84). */
private val PREFERENCES_PLACE = Place.of("settings", "today")

/**
 * With "Hide empty sections in Today" on and every section it names empty (D77), one line says so in their place.
 * The page header, its add button and Active and paused Topics stay.
 */
@Composable
private fun NothingForToday() {
    EmptyState(Icon.Today, "Nothing for today", "No tasks, reminders or review requests. Empty sections are hidden.") {
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
 * One group of Today's main column: a Task section card (D68) whose [mark] says which group it is. Its heading is a
 * button that opens and closes it (D53), like a Topic page section; the open count stays in the heading when it is
 * closed. An empty group is not shown, except Today.
 */
@Composable
private fun TaskGroup(
    key: String,
    title: String,
    mark: Icon,
    tasks: List<Task>,
    hint: String,
    openByDefault: Boolean,
    body: @Composable () -> Unit,
) {
    if (tasks.isEmpty() && key != "today") return
    var open by remember { mutableStateOf(isGroupOpen(key, openByDefault)) }
    SectionCard(
        kind = CardKind.Task,
        headingId = "g-$key",
        bodyId = "gb-$key",
        title = title,
        count = tasks.count { !it.done }.toString(),
        open = open,
        onToggle = {
            open = !open
            keepGroupOpen(key, open)
        },
        hint = hint,
        mark = mark,
        attrs = {
            classes("tgroup--$key")
            if (key == "overdue") classes("scard--overdue")
        },
        body = body,
    )
}

@Composable
private fun TaskList(
    tasks: List<Task>,
    workspace: WorkspaceModel,
    onOpenTask: (Task) -> Unit,
) {
    val toasts = LocalToasts.current
    Ul({ classes("tlist") }) {
        tasks.forEach { task -> key(task.id.value) { TaskRow(task, workspace, toasts, onOpenTask) } }
    }
}

/** Upcoming (D53): the first [LIST_PREVIEW] Tasks under small day headings ("Tomorrow", "Mon 17 Nov"). */
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
                if (day != null) H3({ classes("tgroup__day") }) { Text(dayText(day, today)) }
                TaskList(onDay, workspace, onOpenTask)
            }
        }
    }
}

/** The first [LIST_PREVIEW] of [tasks], then a button that shows all of them, and fewer again. */
@Composable
private fun ShortenedList(
    tasks: List<Task>,
    content: @Composable (List<Task>) -> Unit,
) {
    var all by remember { mutableStateOf(false) }
    content(if (all) tasks else tasks.take(LIST_PREVIEW))
    if (tasks.size > LIST_PREVIEW) {
        Button({
            type(ButtonType.Button)
            classes("btn", "btn--text", "btn--sm", "tgroup__more")
            onClick { all = !all }
        }) { Text(if (all) "Show fewer" else "Show all (${tasks.size})") }
    }
}

/** Rows Upcoming and No date show before "Show all" (D53). */
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
 * Active and paused Topics (owner change #1): only Topics whose status is in the Active or Paused category, grouped by
 * that status in Settings order. List: a heading per status that opens and closes, with the Topics as rows. Board: a
 * lane per status. With one such status there is nothing to group, so the list has no heading and the board is the
 * card grid.
 */
@Composable
private fun ActiveTopics(workspace: WorkspaceModel) {
    val statuses = workspace.statuses
    val shownStatuses = todayStatuses(statuses)
    val groups = todayTopicGroups(workspace.topics, statuses)
    val count = groups.sumOf { it.topics.size }
    val grouped = shownStatuses.size > 1
    // List or Board syncs with Settings > Today (D84).
    val onThisDevice = remember { deviceTodayTopicsView() }
    val layout = workspace.prefs.todayTopicsLayout(onThisDevice)
    // Grouped, the headings name the statuses, so the hint would only repeat them.
    val hint =
        if (grouped) {
            null
        } else {
            val names = shownStatuses.joinToString(" and ") { it.name }
            if (names.isEmpty()) "No status uses the Active or Paused category" else "Statuses in the Active or Paused category: $names"
        }
    SectionCard(
        kind = CardKind.Topic,
        headingId = "at-h",
        bodyId = "at-b",
        title = "Active and paused Topics",
        count = count.toString(),
        hint = hint,
        attrs = { classes("atopics") },
        actions =
            if (count > 0) {
                {
                    LayoutSwitch(layout) { picked -> workspace.setPrefs(workspace.prefs.copy(todayTopicsView = picked)) }
                }
            } else {
                null
            },
    ) {
        if (count == 0) {
            val why = "Topics show here when their status is in the Active or Paused category."
            EmptyState(Icon.Topics, "No active or paused Topics", why) {
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
    layout: TopicsView,
    onPick: (TopicsView) -> Unit,
) {
    Div({
        classes("seg")
        attr("role", "radiogroup")
        attr("aria-label", "Active and paused Topics layout")
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
 * The small board: a lane per Active or Paused status, in Settings order, so a status keeps its place. A lane no Topic uses
 * says so; on a phone, where lanes stack, it is left out.
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
 * its goal, at most two lines (css `.tabout`). Today shows no Done Topics, so "Finished." is there for completeness.
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
