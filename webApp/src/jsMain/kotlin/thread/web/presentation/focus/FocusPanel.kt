package thread.web.presentation.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.OptGroup
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import thread.domain.focus.FOCUS_RECENT_SHOWN
import thread.domain.focus.FocusOutcome
import thread.domain.focus.FocusPhase
import thread.domain.focus.FocusRun
import thread.domain.focus.FocusSettings
import thread.domain.focus.FocusTarget
import thread.domain.focus.FocusTargetKind
import thread.domain.focus.NO_FOCUS_TARGET
import thread.domain.focus.focusTargetLabel
import thread.web.platform.WallClock
import thread.web.presentation.Icon
import thread.web.presentation.IconButton
import thread.web.presentation.IconView
import thread.web.presentation.LocalNow
import thread.web.presentation.ModalDialog
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.whenText

/**
 * The status line's Focus tool (v3 `tool--focus`, adr/ADR-019-focus-timer.md): "Focus", or the time left with the
 * filled `is-running` style while a period runs or is paused. It is not a live region, so a screen reader is not told
 * every second; its name carries the time.
 */
@Composable
internal fun FocusTool(
    focus: FocusModel,
    onOpen: () -> Unit,
) {
    val run = focus.run
    val left = minutesAndSeconds(focus.remainingMillis())
    Button({
        type(ButtonType.Button)
        classes("tool", "tool--focus")
        if (run != null) classes("is-running")
        attr("aria-label", focusToolLabel(run, left))
        attr("title", "Focus timer")
        onClick { onOpen() }
    }) {
        IconView(Icon.Timer, 18)
        Span({
            classes("tool__label")
            id("focus-chip-text")
        }) { Text(if (run == null) "Focus" else left) }
    }
}

/**
 * The v3 Focus panel (`dlg--pop`), without the prototype's concept badge and demo note: time and phase, the Task or
 * Topic, the controls, the lengths, what to attach and the last sessions (F3 A).
 */
@Composable
internal fun FocusPanel(
    focus: FocusModel,
    workspace: WorkspaceModel,
    onClose: () -> Unit,
) {
    val run = focus.run
    val stored = workspace.stored
    val labelOf = { target: FocusTarget? -> focusTargetLabel(target, stored?.tasks.orEmpty(), stored?.topics.orEmpty()) }
    ModalDialog(classes = listOf("dlg--pop", "dlg--focus"), onClose = onClose) { titleId ->
        Div({ classes("focus") }) {
            Header({ classes("focus__head") }) {
                H2({
                    classes("focus__title")
                    id(titleId)
                }) {
                    IconView(Icon.Timer, 18)
                    Text(" Focus")
                }
                IconButton(Icon.X, "Close focus timer") { onClose() }
            }
            P({ classes("focus__time") }) {
                Span({ id("focus-big") }) { Text(minutesAndSeconds(focus.remainingMillis())) }
                Span({ classes("focus__phase") }) { Text(phaseText(run)) }
            }
            P({ classes("focus__for") }) {
                Text("For: ")
                B { Text(labelOf(focus.target)) }
            }
            Controls(focus, run)
            val settings = workspace.prefs.focusSettings
            Div({ classes("fgrid") }) {
                val idle = run == null
                MinutesField("Focus min", settings.workMinutes, FocusSettings.WORK_MINUTES, idle, FocusSettings::workMinutesFrom) {
                    workspace.setPrefs(workspace.prefs.copy(focusWorkMinutes = it))
                }
                MinutesField("Break min", settings.breakMinutes, FocusSettings.BREAK_MINUTES, idle, FocusSettings::breakMinutesFrom) {
                    workspace.setPrefs(workspace.prefs.copy(focusBreakMinutes = it))
                }
            }
            Label(attrs = { classes("field") }) {
                Span({ classes("field__label") }) { Text("Attach to (optional)") }
                TargetSelect(focus.target, labelOf(focus.target), workspace, focus::choose)
            }
            H3({ classes("focus__h") }) { Text("Recent sessions") }
            val recent = stored?.focusSessions.orEmpty().take(FOCUS_RECENT_SHOWN)
            if (recent.isEmpty()) {
                P({ classes("focus__none") }) { Text("No sessions yet.") }
            } else {
                val now = LocalNow.current
                val zone = workspace.prefs.timeZone
                Ul({ classes("focus__hist") }) {
                    recent.forEach { session ->
                        Li {
                            Span { Text(whenText(WallClock.wallTimeAt(session.endedAtMillis.toDouble(), zone), now.day)) }
                            Span {
                                Text("${session.shownMinutes} min" + if (session.outcome == FocusOutcome.Stopped) " (stopped)" else "")
                            }
                            Span({ classes("focus__hl") }) { Text(labelOf(session.target)) }
                        }
                    }
                }
            }
            P({ classes("focus__note") }) { Text("Timer uses saved timestamps, so it keeps running across pages.") }
        }
    }
}

/**
 * Start focus, Pause or Resume, then Stop (v3). The first button stays the same element through every phase, so
 * keyboard focus stays on it when it turns from Start to Pause to Resume; Stop hands focus back to it.
 */
@Composable
private fun Controls(
    focus: FocusModel,
    run: FocusRun?,
) {
    var first by remember { mutableStateOf<HTMLElement?>(null) }
    Div({ classes("focus__ctl") }) {
        val pausing = run != null && run.running
        Button({
            type(ButtonType.Button)
            classes("btn", if (pausing) "btn--line" else "btn--primary")
            ref {
                first = it
                onDispose { first = null }
            }
            onClick {
                when {
                    run == null -> focus.start()
                    run.running -> focus.pause(run)
                    else -> focus.resume(run)
                }
            }
        }) {
            if (pausing) {
                IconView(Icon.Pause, 16)
                Text(" Pause")
            } else {
                Text(if (run == null) "Start focus" else "Resume")
            }
        }
        if (run != null) {
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--line")
                onClick {
                    first?.focus()
                    focus.stop(run)
                }
            }) {
                IconView(Icon.Stop, 16)
                Text(" Stop")
            }
        }
    }
}

/**
 * A length in whole minutes, saved to the account when the person leaves the field (FD1). Text that is not a number
 * reads as the default, and a number outside [range] as the nearest limit (v3). The field shows a change from another
 * device unless the person is typing in it.
 */
@Composable
private fun MinutesField(
    label: String,
    value: Int,
    range: IntRange,
    enabled: Boolean,
    parse: (String) -> Int,
    onCommit: (Int) -> Unit,
) {
    var element by remember { mutableStateOf<HTMLInputElement?>(null) }
    Label(attrs = { classes("field") }) {
        Span({ classes("field__label") }) { Text(label) }
        Input(InputType.Number) {
            classes("input")
            attr("min", range.first.toString())
            attr("max", range.last.toString())
            attr("inputmode", "numeric")
            if (!enabled) disabled()
            ref {
                it.value = value.toString()
                element = it
                onDispose { element = null }
            }
            onChange { event ->
                val minutes = parse(event.target.value)
                event.target.value = minutes.toString()
                onCommit(minutes)
            }
        }
    }
    LaunchedEffect(value, element) {
        element?.let { if (it != document.activeElement && it.value != value.toString()) it.value = value.toString() }
    }
}

/**
 * Attach to: no Task or Topic, then the Topics, then the open Tasks, nothing in Trash (v3). A Task or Topic picked
 * earlier that is no longer in those lists (done, in Trash or deleted) stays shown as the choice, under its name.
 */
@Composable
private fun TargetSelect(
    target: FocusTarget?,
    targetLabel: String,
    workspace: WorkspaceModel,
    onPick: (FocusTarget?) -> Unit,
) {
    var element by remember { mutableStateOf<HTMLSelectElement?>(null) }
    val topics = workspace.topics
    val openTasks = workspace.tasks.filter { !it.done }
    val value = target?.let { optionValue(it.kind, it.id) }.orEmpty()
    val listed =
        target == null ||
            when (target.kind) {
                FocusTargetKind.Topic -> topics.any { it.id.value == target.id }
                FocusTargetKind.Task -> openTasks.any { it.id.value == target.id }
            }
    Select({
        classes("input")
        ref {
            element = it
            onDispose { element = null }
        }
        onChange { event -> event.value?.let { onPick(targetOf(it)) } }
    }) {
        Option("") { Text(NO_FOCUS_TARGET) }
        if (!listed) Option(value) { Text(targetLabel) }
        OptGroup("Topics") { topics.forEach { Option(optionValue(FocusTargetKind.Topic, it.id.value)) { Text(it.name) } } }
        OptGroup("Open tasks") { openTasks.forEach { Option(optionValue(FocusTargetKind.Task, it.id.value)) { Text(it.title) } } }
    }
    SideEffect { element?.let { if (it.value != value) it.value = value } }
}

/** Ready, Focusing, Paused, Break or Break paused (v3). */
private fun phaseText(run: FocusRun?): String =
    when {
        run == null -> "Ready"
        run.phase == FocusPhase.Work -> if (run.running) "Focusing" else "Paused"
        else -> if (run.running) "Break" else "Break paused"
    }

private fun optionValue(
    kind: FocusTargetKind,
    id: String,
): String = "${kind.id}:$id"

private fun targetOf(value: String): FocusTarget? {
    val kind = FocusTargetKind.fromId(value.substringBefore(':', "")) ?: return null
    val id = value.substringAfter(':', "")
    return if (id.isBlank() || id.length > FocusTarget.MAX_ID_LENGTH) null else FocusTarget(kind, id)
}
