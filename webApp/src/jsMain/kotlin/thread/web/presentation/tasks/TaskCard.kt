package thread.web.presentation.tasks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.maxLength
import org.jetbrains.compose.web.attributes.rows
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Fieldset
import org.jetbrains.compose.web.dom.Footer
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Legend
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.TagElement
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.task.Deadline
import thread.domain.task.DueGroup
import thread.domain.task.TASK_TITLE_MAX_LENGTH
import thread.domain.task.Task
import thread.domain.task.dueGroupOf
import thread.domain.task.firstDayOf
import thread.domain.task.showsInToday
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.time.clockText
import thread.domain.time.parseClock
import thread.domain.topic.TopicId
import thread.domain.workspace.EditResult
import thread.domain.workspace.TodayGroupBy
import thread.domain.workspace.editTask
import thread.domain.workspace.planTask
import thread.domain.workspace.trashReminder
import thread.domain.workspace.trashTask
import thread.web.presentation.CardItem
import thread.web.presentation.CardKind
import thread.web.presentation.CardType
import thread.web.presentation.CheckBox
import thread.web.presentation.Icon
import thread.web.presentation.IconButton
import thread.web.presentation.IconView
import thread.web.presentation.ItemCard
import thread.web.presentation.LocalCards
import thread.web.presentation.LocalNow
import thread.web.presentation.LocalToasts
import thread.web.presentation.Place
import thread.web.presentation.SelectBox
import thread.web.presentation.StoredValueInput
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.cardShownByPage
import thread.web.presentation.dayText
import thread.web.presentation.deadlineText
import thread.web.presentation.onPlainClick
import thread.web.presentation.taskProblemText
import thread.web.presentation.topics.TopicTag
import thread.web.presentation.topics.topicPlace
import thread.web.presentation.whenText

/**
 * The Task card (D70, owner requests #2 and #3), opened by `?task=` on any page. It opens for viewing; Edit or E shows
 * the fields. Every field saves on its own; the card shows what the store holds, so a change from another device
 * appears here too. A Task has no tab of its own, so the card has no "Open full page".
 *
 * [page] is the page under the card: "Open Topic" is left out when that page is the Task's Topic. [startEditing] is
 * for a Task made a moment ago. [backTo] names the card under this one.
 */
@Composable
internal fun TaskCard(
    workspace: WorkspaceModel,
    task: Task,
    page: Place,
    startEditing: Boolean,
    backTo: String?,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    val now = LocalNow.current
    val toasts = LocalToasts.current
    val cards = LocalCards.current
    var addingReminder by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(startEditing) }
    val id = task.id.value

    fun apply(result: EditResult) {
        if (result is EditResult.Refused) toasts.show(taskProblemText(result.problem)) else workspace.apply(result)
    }

    ItemCard(
        kind = CardKind.Task,
        label = "Task",
        classes = listOf("dcard--task"),
        editing = editing,
        onEditing = { editing = it },
        backTo = backTo,
        onBack = onBack,
        fullPage = null,
        onClose = onClose,
    ) {
        Div({ classes("dcard__body", "dtask-body") }) {
            Div({ classes("dtask") }) {
                CheckBox(
                    task.done,
                    label = if (task.done) "Reopen task" else "Complete task",
                    classes = listOf("check", "check--lg"),
                ) { toggleWithToast(workspace, task, now.day, toasts) }
                if (editing) {
                    TitleArea(task) { text ->
                        val current = workspace.task(id) ?: return@TitleArea false
                        val result = editTask(current, title = text)
                        apply(result)
                        result !is EditResult.Refused
                    }
                } else {
                    H2({
                        classes("dtask__t")
                        if (task.done) classes("is-done")
                    }) { Text(task.title) }
                }
            }
            Button({
                type(ButtonType.Button)
                // Done is the band's main button while editing, so Complete steps back then.
                classes("btn", if (editing) "btn--line" else "btn--primary", "dtask__done")
                onClick { toggleWithToast(workspace, task, now.day, toasts) }
            }) { Text(if (task.done) "Reopen task" else "Complete task") }
            P({ classes("ts-effect") }) {
                IconView(Icon.Today, 16)
                Span {
                    val prefs = workspace.prefs
                    Text(todayEffect(task, now, prefs.keepCompleted, workspace.taskReminderTimes[task.id], prefs.todayGroupBy))
                }
            }
            if (task.inbox) {
                P({ classes("ts-effect") }) {
                    IconView(Icon.Inbox, 16)
                    Span { Text("Also in Inbox until you file it or keep it standalone.") }
                }
            }
            if (editing) {
                Div({ classes("field") }) {
                    Label("ts-topic", { classes("field__label") }) { Text("Topic") }
                    SelectBox(
                        value = task.topicId?.value.orEmpty(),
                        options = listOf("" to "No Topic (standalone)") + workspace.topics.map { it.id.value to it.name },
                        id = "ts-topic",
                        classes = listOf("input"),
                    ) { picked ->
                        workspace.task(id)?.let { apply(editTask(it, topicId = picked.takeIf(String::isNotEmpty)?.let(::TopicId))) }
                    }
                    P({ classes("field__hint") }) { Text("A task belongs to one Topic or none.") }
                }
                Fieldset({ classes("ts-dates") }) {
                    Legend({ classes("field__label") }) { Text("Planned day, deadline and reminder are separate and optional") }
                    PlannedDayField(task, now.day) { day -> workspace.task(id)?.let { apply(planTask(it, day)) } }
                    DeadlineField(task, now.day) { deadline -> workspace.task(id)?.let { apply(editTask(it, deadline = deadline)) } }
                    TaskRemindersField(workspace, task, now.day) { addingReminder = true }
                }
            } else {
                TaskFacts(
                    workspace = workspace,
                    task = task,
                    today = now.day,
                    onPlan = { day -> workspace.task(id)?.let { apply(planTask(it, day)) } },
                ) { addingReminder = true }
            }
        }
        Footer({ classes("dcard__foot") }) {
            val topic = task.topicId?.let { workspace.topic(it.value) }
            if (topic != null && !cardShownByPage(page, CardItem(CardType.Topic, topic.id.value))) {
                A(topicPlace(topic).hash, {
                    classes("btn", "btn--text", "btn--sm", "dcard__foot-start")
                    if (cards != null) onPlainClick { cards.openTopicCard(topic.id) }
                }) {
                    IconView(Icon.Topics, 15)
                    Text(" Open Topic")
                }
            }
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text", "btn--danger-text", "btn--sm")
                onClick {
                    val current = workspace.task(id) ?: return@onClick
                    val edit = workspace.apply(trashTask(current)) ?: return@onClick
                    onClose()
                    toasts.show("Moved to Trash: ${current.title}", undo = { workspace.undo(edit) })
                }
            }) {
                IconView(Icon.Trash, 15)
                Text(" Move to Trash")
            }
        }
    }
    if (addingReminder) {
        ReminderDialog(workspace, ReminderTarget(TargetKind.Task, id), task.title) { addingReminder = false }
    }
}

/**
 * The Task's Topic, dates and reminders, for viewing. The Today and Tomorrow chips still plan the Task (D70, owner
 * answer Q5), and reminders can still be added and deleted: they are nudges about the Task, not its content.
 */
@Composable
private fun TaskFacts(
    workspace: WorkspaceModel,
    task: Task,
    today: LocalDay,
    onPlan: (LocalDay) -> Unit,
    onAddReminder: () -> Unit,
) {
    val topic = task.topicId?.let { workspace.topic(it.value) }
    TagElement<HTMLElement>("dl", { classes("facts") }) {
        Fact(Icon.Topics, "Topic") {
            if (topic != null) TopicTag(topic) else None("No Topic (standalone)")
        }
        Fact(Icon.Today, "Planned day") {
            val day = task.plannedDay
            if (day != null) Span { Text(dayText(day, today)) } else None("Not planned")
            // A chip shows only when it would change the day, so the value never reads "Today Today".
            if (day != today) Chip("Today", label = "Plan for today") { onPlan(today) }
            if (day != today.plusDays(1)) Chip("Tomorrow", label = "Plan for tomorrow") { onPlan(today.plusDays(1)) }
        }
        Fact(Icon.Clock, "Deadline") {
            val deadline = task.deadline
            if (deadline != null) Span { Text(deadlineText(deadline, today)) } else None("No deadline")
        }
        Fact(Icon.Bell, "Remind me") {
            val reminders = workspace.remindersOf(task)
            if (reminders.isNotEmpty()) TaskReminderList(workspace, task, today) else None("No reminders")
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text", "btn--sm", "facts__add")
                onClick { onAddReminder() }
            }) {
                IconView(Icon.Plus, 15)
                Text(" Add reminder")
            }
        }
    }
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

/**
 * The title box. It saves when it loses focus; Enter saves too, because a title is one line. [onSave] returns false
 * when the title was refused, and the box then shows the stored title again.
 */
@Composable
private fun TitleArea(
    task: Task,
    onSave: (String) -> Boolean,
) {
    var element by remember { mutableStateOf<HTMLTextAreaElement?>(null) }
    TextArea(attrs = {
        classes("ts-title__input")
        if (task.done) classes("is-done")
        rows(1)
        maxLength(TASK_TITLE_MAX_LENGTH)
        attr("aria-label", "Task title")
        attr("autofocus", "")
        ref {
            it.value = task.title
            fitHeight(it)
            element = it
            onDispose { element = null }
        }
        // One line for a short title, more as it wraps: the box is as tall as the title it holds.
        onInput { event -> (event.target as? HTMLTextAreaElement)?.let(::fitHeight) }
        onKeyDown { event ->
            if (event.key == "Enter") {
                event.preventDefault()
                element?.blur()
            }
        }
        onChange { event ->
            if (!onSave(event.value)) element?.value = task.title
        }
    })
    SideEffect {
        element?.let {
            if (document.activeElement != it && it.value != task.title) {
                it.value = task.title
                fitHeight(it)
            }
        }
    }
    // The box shows only while editing, so it appears after Edit: the cursor goes to the end of the title.
    LaunchedEffect(element) {
        element?.let {
            it.focus()
            it.setSelectionRange(it.value.length, it.value.length)
        }
    }
}

@Composable
private fun PlannedDayField(
    task: Task,
    today: LocalDay,
    onPlan: (LocalDay?) -> Unit,
) {
    Div({ classes("field") }) {
        Label("ts-plan", { classes("field__label", "field__label--sub") }) {
            IconView(Icon.Today, 15)
            Text(" Planned day ")
            Span({ classes("field__aside") }) { Text("the day you mean to work on it") }
        }
        Div({ classes("row") }) {
            StoredValueInput(InputType.Date, task.plannedDay?.toString().orEmpty(), listOf("input"), id = "ts-plan") { value ->
                onPlan(LocalDay.parse(value))
            }
            Chip("Today") { onPlan(today) }
            Chip("Tomorrow") { onPlan(today.plusDays(1)) }
            if (task.plannedDay != null) Chip("Clear", quiet = true) { onPlan(null) }
        }
    }
}

/**
 * Deadline day and an optional time. Without a time the deadline lasts the whole day (ADR-010). A time picked
 * before any day applies to today.
 */
@Composable
private fun DeadlineField(
    task: Task,
    today: LocalDay,
    onDeadline: (Deadline?) -> Unit,
) {
    val deadline = task.deadline
    Div({ classes("field") }) {
        Label("ts-dl-day", { classes("field__label", "field__label--sub") }) {
            IconView(Icon.Clock, 15)
            Text(" Deadline ")
            Span({ classes("field__aside") }) { Text("when it must be done") }
        }
        Div({ classes("row") }) {
            StoredValueInput(InputType.Date, deadline?.day?.toString().orEmpty(), listOf("input"), id = "ts-dl-day") { value ->
                onDeadline(LocalDay.parse(value)?.let { Deadline(it, deadline?.minuteOfDay) })
            }
            StoredValueInput(
                InputType.Time,
                deadline?.minuteOfDay?.let(::clockText).orEmpty(),
                listOf("input", "input--time"),
                label = "Deadline time",
            ) { value ->
                onDeadline(Deadline(deadline?.day ?: today, parseClock(value)))
            }
            if (deadline != null) Chip("Clear", quiet = true) { onDeadline(null) }
        }
    }
}

@Composable
private fun TaskRemindersField(
    workspace: WorkspaceModel,
    task: Task,
    today: LocalDay,
    onAdd: () -> Unit,
) {
    Div({ classes("field") }) {
        Span({ classes("field__label", "field__label--sub") }) {
            IconView(Icon.Bell, 15)
            Text(" Remind me ")
            Span({ classes("field__aside") }) { Text("an in-app nudge at a time") }
        }
        TaskReminderList(workspace, task, today)
        Button({
            type(ButtonType.Button)
            classes("btn", "btn--line", "btn--sm")
            onClick { onAdd() }
        }) {
            IconView(Icon.Plus, 16)
            Text(" Add reminder")
        }
    }
}

/** The Task's reminders, each with Delete (and Undo in the toast). */
@Composable
private fun TaskReminderList(
    workspace: WorkspaceModel,
    task: Task,
    today: LocalDay,
) {
    val toasts = LocalToasts.current
    Ul({ classes("ts-rems") }) {
        workspace.remindersOf(task).forEach { reminder ->
            key(reminder.id.value) {
                val time = whenText(reminder.at, today)
                Li({ classes("ts-rem") }) {
                    Span({ classes("ts-rem__time") }) { Text(time) }
                    Span({ classes("ts-rem__text") }) { Text(reminder.text) }
                    IconButton(Icon.Trash, "Delete reminder $time", small = true) {
                        val edit = workspace.apply(trashReminder(reminder))
                        if (edit != null) toasts.show("Reminder moved to Trash", undo = { workspace.undo(edit) })
                    }
                }
            }
        }
    }
}

private fun fitHeight(area: HTMLTextAreaElement) {
    area.style.height = "auto"
    area.style.height = "${area.scrollHeight}px"
}

/** A small button. [label] is its accessible name when the text alone does not say what it does. */
@Composable
private fun Chip(
    text: String,
    quiet: Boolean = false,
    label: String? = null,
    onClick: () -> Unit,
) {
    Button({
        type(ButtonType.Button)
        classes("chip")
        if (quiet) classes("chip--quiet")
        if (label != null) attr("aria-label", label)
        onClick { onClick() }
    }) { Text(text) }
}

/**
 * Why the Task is or is not in Today (v3 todayEffect), so the person never has to guess, and where Today lists it with
 * [groupBy] (D90). [remindAt] is the Task's next reminder that is not dismissed (D54).
 */
internal fun todayEffect(
    task: Task,
    now: WallTime,
    keepCompleted: Boolean,
    remindAt: WallTime? = null,
    groupBy: TodayGroupBy = TodayGroupBy.DueDate,
): String {
    val group = dueGroupOf(task, now, remindAt)
    val under =
        when (groupBy) {
            TodayGroupBy.DueDate -> " under ${dueGroupTitle(group)}"
            TodayGroupBy.Topic -> if (task.topicId == null) " under No Topic" else " under its Topic"
            TodayGroupBy.None -> ""
        }
    if (task.done) {
        val stays = showsInToday(task, now, keepCompleted, remindAt)
        return if (stays) "Completed. It stays in Today$under until tomorrow, then leaves." else "Completed. It does not show in Today."
    }
    val byDate = groupBy == TodayGroupBy.DueDate
    val today = now.day
    return when {
        group == DueGroup.Today -> {
            val why =
                when {
                    task.deadline?.isPassed(now) == true -> "the deadline passed"
                    task.plannedDay == today -> "it is planned for today"
                    task.deadline?.day == today -> "it is due today"
                    remindAt != null && remindAt.day <= today -> "it has a reminder today"
                    else -> "it was planned for an earlier day. It is not overdue; nothing moves it automatically"
                }
            "Shows in Today$under because $why."
        }
        group == DueGroup.Tomorrow ->
            if (byDate) {
                "Shows in Today under Tomorrow. Tomorrow it moves to Today."
            } else {
                "Shows in Today$under. Its first date is tomorrow."
            }
        firstDayOf(task, remindAt) != null ->
            if (byDate) {
                "Shows in Today under Upcoming. The day before its first date it moves to Tomorrow."
            } else {
                "Shows in Today$under. Its first date is after tomorrow."
            }
        task.inbox -> "Not in Today while it waits in Inbox. Plan a day, set a deadline or add a reminder to see it there."
        else -> "Shows in Today$under. It has no date: plan a day, set a deadline or add a reminder to give it one."
    }
}
