package thread.web.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import thread.domain.workspace.CaptureKind
import thread.domain.workspace.MeetingsLayout
import thread.domain.workspace.PAST_MEETINGS_MAX
import thread.domain.workspace.TodayHideableSection
import thread.domain.workspace.TopicsView
import thread.domain.workspace.todayHiddenWhenEmpty
import thread.domain.workspace.todayTopicsLayout
import thread.web.presentation.CheckBox
import thread.web.presentation.SelectBox
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.external.JiraModel
import thread.web.presentation.shell.CaptureState
import thread.web.presentation.shell.CaptureType
import thread.web.presentation.tasks.deviceTodayTopicsView

/*
 * The preferences, one block per screen (owner change #25, D84). Every one syncs to the workspace (Q6). Later phases
 * add Inbox, Topic Details and Pull requests blocks and more controls in these.
 */

/**
 * Settings > Today, in the owner's order (D84, D86): Topics open as, Show completed tasks, Hide empty sections, Meetings
 * layout, Show past meetings. Group tasks by and Topics shown take their places in later phases.
 */
@Composable
internal fun TodayPrefsBlock(workspace: WorkspaceModel) {
    // This device's layout from before D84: shown, and used, until the synced choice is first set.
    val topicsOnThisDevice = remember { deviceTodayTopicsView() }
    SettingsBlock("today", "Today", null) {
        val prefs = workspace.prefs
        ChoicePref(
            labelId = "ttv-l",
            label = "Topics open as",
            hint = "Active and paused Topics. You can also switch on Today.",
            options = TopicsView.entries.map { it to topicsViewLabel(it) },
            picked = prefs.todayTopicsLayout(topicsOnThisDevice),
        ) { workspace.setPrefs(workspace.prefs.copy(todayTopicsView = it)) }
        SwitchPref("pref-keep", "Show completed tasks", "They stay, struck through, until the end of the day.", prefs.keepCompleted) { on ->
            workspace.setPrefs(workspace.prefs.copy(keepCompleted = on))
        }
        HideEmptyPref(workspace)
        ChoicePref(
            labelId = "ml-l",
            label = "Meetings layout",
            hint = "Vertical lists today's meetings on the right. Horizontal draws a timeline across the top.",
            options = MeetingsLayout.entries.map { it to if (it == MeetingsLayout.Vertical) "Vertical" else "Horizontal" },
            picked = prefs.meetingsLayout,
        ) { workspace.setPrefs(workspace.prefs.copy(meetingsLayout = it)) }
        Div({ classes("pref") }) {
            Div {
                Label("pref-past", { classes("pref__label") }) { Text("Show past meetings") }
                P({ classes("pref__hint") }) { Text(PAST_MEETINGS_HINT) }
            }
            SelectBox(
                value = prefs.todayPastMeetings?.toString() ?: "",
                options = listOf("" to "All") + (0..PAST_MEETINGS_MAX).map { it.toString() to if (it == 0) "None" else it.toString() },
                id = "pref-past",
                classes = listOf("input"),
            ) { picked -> workspace.setPrefs(workspace.prefs.copy(todayPastMeetings = picked.toIntOrNull())) }
        }
    }
}

/**
 * Hide empty sections (D86): a checkbox per Today section that can hide when empty. Each change saves the whole list,
 * never the switch it replaces, so an empty list stays empty instead of falling back to that switch.
 */
@Composable
private fun HideEmptyPref(workspace: WorkspaceModel) {
    val hidden = workspace.prefs.todayHiddenWhenEmpty
    Div({ classes("pref", "pref--checks") }) {
        Div {
            P({
                classes("pref__label")
                id("he-l")
            }) { Text("Hide empty sections") }
            P({
                classes("pref__hint")
                id("he-hint")
            }) { Text(HIDE_EMPTY_HINT) }
        }
        Div({
            classes("pref-checks")
            attr("role", "group")
            attr("aria-labelledby", "he-l")
            attr("aria-describedby", "he-hint")
        }) {
            TodayHideableSection.entries.forEach { section ->
                val id = "he-${section.id}"
                Span({ classes("pref-check") }) {
                    CheckBox(section in hidden, label = null, id = id) { on ->
                        val now = workspace.prefs.todayHiddenWhenEmpty
                        workspace.setPrefs(workspace.prefs.copy(todayHideEmpty = if (on) now + section else now - section))
                    }
                    Label(id) { Text(hideableLabel(section)) }
                }
            }
        }
    }
}

private const val HIDE_EMPTY_HINT =
    "A checked section shows only when it has something. A meetings problem always shows. Empty task groups always hide."

private const val PAST_MEETINGS_HINT = "How many ended meetings the Vertical layout keeps, the latest ones. Horizontal shows the whole day."

private fun hideableLabel(section: TodayHideableSection): String =
    when (section) {
        TodayHideableSection.Meetings -> "Meetings"
        TodayHideableSection.Reminders -> "Reminders"
        TodayHideableSection.ReviewRequested -> "Review requested"
    }

/** Settings > Topics. The Topics page switch saves the same choice (D21). */
@Composable
internal fun TopicsPrefsBlock(workspace: WorkspaceModel) {
    SettingsBlock("topics", "Topics", null) {
        ChoicePref(
            labelId = "pv-l",
            label = "Topics open as",
            hint = "You can switch any time on the Topics page.",
            options = TopicsView.entries.map { it to topicsViewLabel(it) },
            picked = workspace.prefs.topicsView,
        ) { workspace.setPrefs(workspace.prefs.copy(topicsView = it)) }
    }
}

/**
 * Settings > Jira. "Current sprint only" is the Jira page checkbox (D50), synced since D84. It cannot change without a
 * connected Jira site that has sprints, as on the Jira page.
 */
@Composable
internal fun JiraPrefsBlock(jira: JiraModel) {
    // The same check as the Jira page: saved on this device, and asked of Jira at most once a day.
    LaunchedEffect(jira.version) { jira.checkSprints() }
    SettingsBlock("jira", "Jira", null) {
        val hint =
            when {
                jira.checked && !jira.connected -> "Connect Jira under Connections to use this."
                jira.sprints == false -> "Your Jira site has no sprints."
                else -> "Assigned to me on the Jira page shows only tickets in an open sprint."
            }
        val enabled = jira.connected && jira.sprints != false
        SwitchPref("pref-sprint", "Current sprint only", hint, jira.currentSprintOnly, enabled) { on -> jira.setCurrentSprintOnly(on) }
    }
}

/** Settings > General: what is not about one screen. */
@Composable
internal fun GeneralPrefsBlock(
    workspace: WorkspaceModel,
    capture: CaptureState,
) {
    SettingsBlock("general", "General", null) {
        Div({ classes("pref") }) {
            Div {
                Label("pref-cap", { classes("pref__label") }) { Text("Capture starts as") }
                P({ classes("pref__hint") }) { Text("The type picked in the bottom bar when a page opens.") }
            }
            SelectBox(
                value = workspace.prefs.captureType.id,
                options = CaptureKind.entries.map { it.id to it.id.replaceFirstChar(Char::uppercaseChar) },
                id = "pref-cap",
                classes = listOf("input"),
            ) { picked ->
                val kind = CaptureKind.fromId(picked) ?: return@SelectBox
                workspace.setPrefs(workspace.prefs.copy(captureType = kind))
                capture.type = CaptureType.of(kind)
            }
        }
    }
}

private fun topicsViewLabel(view: TopicsView): String = if (view == TopicsView.List) "List" else "Board"

/** One on or off preference: a label, a hint and a switch. */
@Composable
private fun SwitchPref(
    id: String,
    label: String,
    hint: String,
    on: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Div({ classes("pref") }) {
        Div {
            Label(id, { classes("pref__label") }) { Text(label) }
            P({
                classes("pref__hint")
                id("$id-hint")
            }) { Text(hint) }
        }
        CheckBox(on, label = null, classes = listOf("switch"), id = id, switch = true, enabled = enabled, describedBy = "$id-hint") {
            onChange(it)
        }
    }
}

/** One preference with a few named choices, as a row of radio buttons. */
@Composable
private fun <T> ChoicePref(
    labelId: String,
    label: String,
    hint: String,
    options: List<Pair<T, String>>,
    picked: T,
    onPick: (T) -> Unit,
) {
    Div({ classes("pref") }) {
        Div {
            P({
                classes("pref__label")
                id(labelId)
            }) { Text(label) }
            P({ classes("pref__hint") }) { Text(hint) }
        }
        Div({
            classes("seg")
            attr("role", "radiogroup")
            attr("aria-labelledby", labelId)
        }) {
            options.forEach { (option, text) ->
                Button({
                    type(ButtonType.Button)
                    attr("role", "radio")
                    attr("aria-checked", (option == picked).toString())
                    onClick { onPick(option) }
                }) { Text(text) }
            }
        }
    }
}
