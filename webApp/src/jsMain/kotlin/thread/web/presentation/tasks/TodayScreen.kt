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
import org.jetbrains.compose.web.css.fontSize
import org.jetbrains.compose.web.css.padding
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Aside
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H2
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
import thread.domain.reminder.todayReminders
import thread.domain.task.TASK_TITLE_MAX_LENGTH
import thread.domain.task.Task
import thread.domain.task.firstDayOf
import thread.domain.task.todayTasks
import thread.domain.time.clockText
import thread.domain.topic.StatusCategory
import thread.domain.topic.statusOf
import thread.web.platform.readDeviceText
import thread.web.platform.writeDeviceText
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalNow
import thread.web.presentation.LocalToasts
import thread.web.presentation.PageHeader
import thread.web.presentation.SelectBox
import thread.web.presentation.TaskCreation
import thread.web.presentation.View
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.dayText
import thread.web.presentation.external.GitHubModel
import thread.web.presentation.external.ReviewRequestedRail
import thread.web.presentation.longDate
import thread.web.presentation.taskProblemText
import thread.web.presentation.topics.HexSize
import thread.web.presentation.topics.StatusLabel
import thread.web.presentation.topics.TopicHex
import thread.web.presentation.topics.TopicProgress
import thread.web.presentation.topics.topicColor
import thread.web.presentation.topics.topicPlace

/**
 * Today (v3 today.html): the date and the personal checklist lead, reminders and review requests sit on the rail,
 * Active Topics below. Each Task shows once, in the first group it qualifies for (D37). Upcoming and No date follow
 * Earlier planned and start closed; every group opens and closes, and this device remembers it (D53).
 */
@Composable
internal fun TodayScreen(
    workspace: WorkspaceModel,
    gitHub: GitHubModel,
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
    Div({ classes("today") }) {
        Div({ classes("today__main") }) {
            TaskGroup("overdue", "Overdue", groups.overdue, "Deadline passed", openByDefault = true) {
                TaskList(groups.overdue, workspace, onOpenTask)
            }
            // D54: a reminder day counts as a date, so the hints name reminders too.
            TaskGroup("today", "Today", groups.today, "Planned, due or reminded today", openByDefault = true) {
                if (groups.today.isEmpty()) {
                    P({
                        classes("muted")
                        style { padding(12.px, 4.px) }
                    }) { Text("Nothing planned for today yet.") }
                } else {
                    TaskList(groups.today, workspace, onOpenTask)
                }
            }
            TaskGroup("earlier", "Earlier planned", groups.earlier, "Planned before today, still open", openByDefault = true) {
                TaskList(groups.earlier, workspace, onOpenTask)
            }
            // D53: the open Tasks the groups above leave out. They start closed.
            TaskGroup("upcoming", "Upcoming", groups.upcoming, "Planned, due or reminded after today", openByDefault = false) {
                UpcomingList(groups.upcoming, workspace, onOpenTask)
            }
            TaskGroup("nodate", "No date", groups.noDate, "No planned day, deadline or reminder", openByDefault = false) {
                ShortenedList(groups.noDate) { shown -> TaskList(shown, workspace, onOpenTask) }
            }
        }
        Aside({
            classes("today__rail")
            attr("aria-label", "Reminders and reviews")
        }) {
            RemindersRail(workspace, onOpenTarget)
            ReviewRequestedRail(gitHub)
        }
    }
    ActiveTopics(workspace)
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
 * One group of Today's main column. Its heading is a button that opens and closes it (D53), like a Topic page
 * section; the open count stays in the heading when it is closed. An empty group is not shown, except Today.
 */
@Composable
private fun TaskGroup(
    key: String,
    title: String,
    tasks: List<Task>,
    hint: String,
    openByDefault: Boolean,
    body: @Composable () -> Unit,
) {
    if (tasks.isEmpty() && key != "today") return
    var open by remember { mutableStateOf(isGroupOpen(key, openByDefault)) }
    Section({
        classes("tgroup", "tgroup--$key")
        if (!open) classes("is-closed")
        attr("aria-labelledby", "g-$key")
    }) {
        H2({
            classes("tgroup__head")
            id("g-$key")
        }) {
            Button({
                type(ButtonType.Button)
                classes("tgroup__toggle")
                attr("aria-expanded", open.toString())
                attr("aria-controls", "gb-$key")
                onClick {
                    open = !open
                    keepGroupOpen(key, open)
                }
            }) {
                Span({ classes("chev") }) { IconView(Icon.ChevDown, 18) }
                if (key == "overdue") IconView(Icon.Alert, 17)
                Text("$title ")
                Span({ classes("n") }) { Text(tasks.count { !it.done }.toString()) }
                Span({ classes("tgroup__hint") }) { Text(hint) }
            }
        }
        Div({
            classes("tgroup__body")
            id("gb-$key")
            if (!open) attr("hidden", "")
        }) { if (open) body() }
    }
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
    onOpenTarget: (ReminderTarget) -> Unit,
) {
    val now = LocalNow.current
    val list = todayReminders(workspace.reminders, now)
    Section({ attr("aria-labelledby", "rem-h") }) {
        Div({ classes("railsec__head") }) {
            H2({
                classes("h2")
                id("rem-h")
            }) {
                Text("Reminders ")
                Span({ classes("n") }) { Text(list.size.toString()) }
            }
        }
        if (list.isEmpty()) {
            P({ classes("muted") }) { Text("No reminders today. Add one from a Task or Note.") }
            return@Section
        }
        Ul({ classes("rems") }) {
            list.forEach { entry ->
                val reminder = entry.reminder
                val info = targetInfo(workspace, reminder.target) ?: return@forEach
                key(reminder.id.value) {
                    Li {
                        Button({
                            type(ButtonType.Button)
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

@Composable
private fun ActiveTopics(workspace: WorkspaceModel) {
    val statuses = workspace.statuses
    val active = workspace.topics.filter { statusOf(it, statuses).category == StatusCategory.Active }
    val names = statuses.filter { it.category == StatusCategory.Active }.joinToString(" and ") { it.name }
    Section({
        classes("atopics")
        attr("aria-labelledby", "at-h")
    }) {
        Div({ classes("atopics__head") }) {
            H2({
                classes("h2")
                id("at-h")
            }) {
                Text("Active Topics ")
                Span({ classes("n") }) { Text(active.size.toString()) }
            }
            Span({
                classes("muted")
                style { fontSize(13.px) }
            }) { Text(if (names.isNotEmpty()) "Statuses in the Active category: $names" else "No status uses the Active category") }
        }
        if (active.isEmpty()) {
            EmptyState(Icon.Topics, "No active Topics", "Topics show here when their status is in the Active category.") {
                A(View.Topics.place.hash, { classes("btn", "btn--line") }) { Text("Open Topics") }
            }
            return@Section
        }
        Ul({ classes("agrid") }) {
            active.forEach { topic ->
                key(topic.id.value) {
                    Li {
                        A(topicPlace(topic).hash, {
                            classes("acard")
                            topicColor(topic.color)
                        }) {
                            Span({ classes("acard__top") }) {
                                TopicHex(topic.name, topic.color, HexSize.Md)
                                Span({ classes("acard__name") }) { Text(topic.name) }
                            }
                            StatusLabel(statusOf(topic, statuses))
                            Span({ classes("acard__next") }) {
                                // D52: the Topic's first open Task, as on the Topics list.
                                val next = workspace.nextTask(topic.id)
                                if (next != null) {
                                    B { Text("Next:") }
                                    Text(" ${next.title}")
                                } else {
                                    Span({ classes("muted") }) { Text("No open tasks.") }
                                }
                            }
                            Span({ classes("acard__foot") }) { TopicProgress(topic, workspace.progress(topic.id)) }
                        }
                    }
                }
            }
        }
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
