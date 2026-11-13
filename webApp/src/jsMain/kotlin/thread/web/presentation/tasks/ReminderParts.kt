package thread.web.presentation.tasks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.maxLength
import org.jetbrains.compose.web.attributes.onSubmit
import org.jetbrains.compose.web.attributes.required
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.DateInput
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextInput
import org.jetbrains.compose.web.dom.TimeInput
import thread.domain.reminder.REMINDER_TEXT_MAX_LENGTH
import thread.domain.reminder.Reminder
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.reminder.dueReminders
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.time.clockText
import thread.domain.time.parseClock
import thread.domain.topic.Topic
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalNow
import thread.web.presentation.LocalToasts
import thread.web.presentation.ModalDialog
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.topics.TopicTag
import thread.web.presentation.whenText

/** What a reminder points at, as the screens name it (v3 S.targetOf). Null when the target is gone. */
internal class ReminderTargetInfo(
    val kind: TargetKind,
    val label: String,
    val topic: Topic?,
) {
    val kindWord: String get() = kind.id
}

internal fun targetInfo(
    workspace: WorkspaceModel,
    target: ReminderTarget,
): ReminderTargetInfo? =
    when (target.kind) {
        TargetKind.Task ->
            workspace.task(target.id)?.let { task ->
                ReminderTargetInfo(TargetKind.Task, task.title, task.topicId?.let { workspace.topic(it.value) })
            }
        TargetKind.Topic -> workspace.topic(target.id)?.let { ReminderTargetInfo(TargetKind.Topic, it.name, it) }
        TargetKind.Note -> null
    }

/** The v3 "New reminder" dialog for a Task or Topic: day, time (15:00 by default) and text. */
@Composable
internal fun ReminderDialog(
    workspace: WorkspaceModel,
    target: ReminderTarget,
    defaultText: String,
    onClose: () -> Unit,
) {
    val now = LocalNow.current
    val toasts = LocalToasts.current
    var day by remember { mutableStateOf(now.day.toString()) }
    var time by remember { mutableStateOf("15:00") }
    var text by remember { mutableStateOf(defaultText.take(REMINDER_TEXT_MAX_LENGTH)) }
    ModalDialog(onClose = onClose) { titleId ->
        Form(attrs = {
            classes("dlg__in")
            attr("autocomplete", "off")
            onSubmit { event ->
                event.preventDefault()
                val pickedDay = LocalDay.parse(day)
                val minute = parseClock(time)
                if (pickedDay == null || minute == null) {
                    toasts.show("Pick a day and a time")
                    return@onSubmit
                }
                val at = WallTime(pickedDay, minute)
                if (workspace.createReminder(at, text, target) == null) {
                    toasts.show("Reminder text can have at most $REMINDER_TEXT_MAX_LENGTH characters")
                } else {
                    onClose()
                    toasts.show("Reminder set for ${whenText(at, now.day, mid = true)}")
                }
            }
        }) {
            H2({
                classes("dlg__title")
                id(titleId)
            }) { Text("New reminder") }
            P({ classes("dlg__text") }) {
                Text("Shows in Today and as an in-app banner at that time. Delivery while Thread is closed is not part of v1.")
            }
            Div({ classes("fgrid") }) {
                Label(attrs = { classes("field") }) {
                    Span({ classes("field__label") }) { Text("Day") }
                    DateInput(day) {
                        classes("input")
                        required()
                        onInput { day = it.value }
                    }
                }
                Label(attrs = { classes("field") }) {
                    Span({ classes("field__label") }) { Text("Time") }
                    TimeInput(time) {
                        classes("input")
                        required()
                        attr("autofocus", "")
                        onInput { time = it.value }
                    }
                }
            }
            Label(attrs = { classes("field") }) {
                Span({ classes("field__label") }) { Text("Text") }
                TextInput(text) {
                    classes("input")
                    required()
                    maxLength(REMINDER_TEXT_MAX_LENGTH)
                    onInput { text = it.value }
                }
            }
            Div({ classes("dlg__acts") }) {
                Button({
                    type(ButtonType.Button)
                    classes("btn", "btn--line")
                    onClick { onClose() }
                }) { Text("Cancel") }
                Button({
                    type(ButtonType.Submit)
                    classes("btn", "btn--primary")
                }) { Text("Add reminder") }
            }
        }
    }
}

/**
 * The in-app reminder banners (v3 renderReminderBanner). A reminder shows from its minute until it is dismissed, so
 * one that came due while Thread was closed shows when Thread opens. Thread cannot reach a closed tab (v1).
 */
@Composable
internal fun ReminderBanners(
    workspace: WorkspaceModel,
    onOpenTarget: (ReminderTarget) -> Unit,
) {
    val now = LocalNow.current
    dueReminders(workspace.reminders, now).forEach { reminder ->
        val info = targetInfo(workspace, reminder.target) ?: return@forEach
        key(reminder.id.value) { ReminderBanner(reminder, info, now, workspace, onOpenTarget) }
    }
}

@Composable
private fun ReminderBanner(
    reminder: Reminder,
    info: ReminderTargetInfo,
    now: WallTime,
    workspace: WorkspaceModel,
    onOpenTarget: (ReminderTarget) -> Unit,
) {
    Div({
        classes("rbanner")
        attr("role", "status")
    }) {
        Span({ classes("rbanner__time") }) {
            Text(if (reminder.at.day == now.day) clockText(reminder.at.minuteOfDay) else whenText(reminder.at, now.day))
        }
        Div({ classes("rbanner__body") }) {
            P({ classes("rbanner__text") }) {
                IconView(Icon.Bell, 16)
                Text(" ${reminder.text}")
            }
            P({ classes("rbanner__meta") }) {
                Text("Reminder for ${info.kindWord} ")
                if (info.kind != TargetKind.Topic) {
                    B { Text(info.label) }
                    Text(" ")
                }
                info.topic?.let { TopicTag(it) }
            }
        }
        Div({ classes("rbanner__acts") }) {
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--line")
                onClick { onOpenTarget(reminder.target) }
            }) { Text("Open ${info.kindWord}") }
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text")
                onClick { workspace.dismiss(reminder) }
            }) { Text("Dismiss") }
        }
    }
}
