package thread.web.presentation.tasks

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import thread.domain.task.Task
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.time.clockText
import thread.domain.workspace.toggleTask
import thread.web.presentation.CheckBox
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalNow
import thread.web.presentation.ToastState
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.dayText
import thread.web.presentation.deadlineText
import thread.web.presentation.topics.TopicTag
import thread.web.presentation.whenText

/** A Task in a list (v3 T.taskRow): its checkbox, its title that opens the Task sheet, and its meta line. */
@Composable
internal fun TaskRow(
    task: Task,
    workspace: WorkspaceModel,
    toasts: ToastState,
    onOpen: (Task) -> Unit,
    hideTopic: Boolean = false,
    showPlanned: Boolean = false,
) {
    val now = LocalNow.current
    Li({
        classes("trow")
        if (task.done) classes("is-done")
    }) {
        CheckBox(task.done, label = "${if (task.done) "Reopen" else "Complete"}: ${task.title}") {
            toggleWithToast(workspace, task, now.day, toasts)
        }
        Div({ classes("trow__body") }) {
            Button({
                type(ButtonType.Button)
                classes("trow__title")
                onClick { onOpen(task) }
            }) { Text(task.title) }
            Div({ classes("trow__meta") }) { TaskMeta(task, workspace, now, hideTopic, showPlanned) }
        }
    }
}

/** Completes or reopens [task], with the v3 toast and Undo. */
internal fun toggleWithToast(
    workspace: WorkspaceModel,
    task: Task,
    today: LocalDay,
    toasts: ToastState,
) {
    val current = workspace.task(task.id.value) ?: return
    val edit = workspace.apply(toggleTask(current, today)) ?: return
    toasts.show(if (current.done) "Reopened: ${current.title}" else "Completed: ${current.title}", undo = { workspace.undo(edit) })
}

/** The meta line under a Task (v3 T.taskMeta): Topic, deadline, planned day, reminders, Inbox. */
@Composable
private fun TaskMeta(
    task: Task,
    workspace: WorkspaceModel,
    now: WallTime,
    hideTopic: Boolean,
    showPlanned: Boolean,
) {
    val today = now.day
    if (!hideTopic) TopicTag(task.topicId?.let { workspace.topic(it.value) })
    task.deadline?.let { deadline ->
        val over = !task.done && deadline.isPassed(now)
        val sameDay = deadline.day == today
        val text = if (sameDay) deadline.minuteOfDay?.let(::clockText) ?: "today" else deadlineText(deadline, today, mid = true)
        Span({
            classes("meta")
            if (over) classes("meta--over")
        }) {
            IconView(if (over) Icon.Alert else Icon.Clock, 14)
            Text(if (over) "Overdue, due $text" else "Due $text")
        }
    }
    task.plannedDay?.let { day ->
        if (showPlanned || day != today) {
            Span({ classes("meta") }) {
                IconView(Icon.Today, 14)
                Text("Planned ${dayText(day, today, mid = true)}")
            }
        }
    }
    // The next reminder that is not dismissed, and how many more there are (D54).
    val pending = workspace.remindersOf(task).filter { !it.dismissed }
    pending.firstOrNull()?.let { next ->
        Span({
            classes("meta", "meta--rem")
            if (pending.size > 1) title("Next of ${pending.size} reminders")
        }) {
            IconView(Icon.Bell, 14)
            Text(if (next.at.day == today) clockText(next.at.minuteOfDay) else whenText(next.at, today))
            if (pending.size > 1) Text(" +${pending.size - 1}")
        }
    }
    if (task.inbox) {
        Span({ classes("meta") }) {
            IconView(Icon.Inbox, 14)
            Text("In Inbox")
        }
    }
}
