package thread.web.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import thread.domain.calendar.MeetingTitleFit
import thread.domain.topic.StatusCategory
import thread.domain.workspace.CaptureKind
import thread.domain.workspace.InboxGroupBy
import thread.domain.workspace.MeetingsLayout
import thread.domain.workspace.MyPrsGroupBy
import thread.domain.workspace.PAST_MEETINGS_MAX
import thread.domain.workspace.ReviewPrsGroupBy
import thread.domain.workspace.TodayGroupBy
import thread.domain.workspace.TodayHideableSection
import thread.domain.workspace.TopicHideableSection
import thread.domain.workspace.TopicsView
import thread.domain.workspace.todayHiddenWhenEmpty
import thread.domain.workspace.todayTopicsLayout
import thread.web.presentation.CheckBox
import thread.web.presentation.LocalToasts
import thread.web.presentation.Place
import thread.web.presentation.SelectBox
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.external.JiraModel
import thread.web.presentation.shell.CaptureState
import thread.web.presentation.shell.CaptureType
import thread.web.presentation.tasks.TODAY_EDIT_PARAM
import thread.web.presentation.tasks.deviceTodayTopicsView
import thread.web.presentation.tasks.focusNow
import thread.web.presentation.tasks.rememberPhoneLayout
import thread.web.presentation.topics.categoryLabel

/*
 * The preferences, one block per screen (owner change #25, D84). Every one syncs to the workspace (Q6).
 */

/**
 * Settings > Today, in the owner's order (D84, D86, D89, D90, D97): Topics open as, Show completed tasks, Hide empty
 * sections, Meetings layout, Narrow meeting titles, Group tasks by, Show past meetings, Topics shown; then Today layout
 * (D98).
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
            hint = "The Topics card on Today. You can also switch it there.",
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
        // D97. Always shown, as Show past meetings is: the hint says which layout it is for.
        ChoicePref(
            labelId = "mtf-l",
            label = "Narrow meeting titles",
            hint = NARROW_TITLES_HINT,
            options = MeetingTitleFit.entries.map { it to titleFitLabel(it) },
            picked = prefs.meetingTitleFit,
        ) { workspace.setPrefs(workspace.prefs.copy(meetingTitleFit = it)) }
        ChoicePref(
            labelId = "tg-l",
            label = "Group tasks by",
            hint = GROUP_TASKS_HINT,
            options = TodayGroupBy.entries.map { it to groupByLabel(it) },
            picked = prefs.todayGroupBy,
        ) { workspace.setPrefs(workspace.prefs.copy(todayGroupBy = it)) }
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
        TopicsShownPref(workspace)
        TodayLayoutPref(workspace)
    }
}

/**
 * Today layout (D98): "Edit layout" opens Today in edit mode, and the reset button resets the layout this window uses, as
 * edit mode's does: "Reset to default" at 760 px and wider, "Match desktop" at 759 px and narrower (D-9). Like every row
 * here it saves at once, with Undo. It is disabled while that layout is not saved.
 */
@Composable
private fun TodayLayoutPref(workspace: WorkspaceModel) {
    val toasts = LocalToasts.current
    val phone = rememberPhoneLayout()
    val prefs = workspace.prefs
    val saved = if (phone) prefs.todayPhoneLayout != null else prefs.todayLayout != null
    Div({ classes("pref", "pref--choice") }) {
        Div {
            P({
                classes("pref__label")
                id("tl-l")
            }) { Text("Today layout") }
            P({
                classes("pref__hint")
                id("tl-hint")
            }) {
                Text(
                    when {
                        !phone -> "Where each card sits on Today in a wide window. Phones and narrow windows have their own order."
                        saved -> "The order of Today's cards on a phone or narrow window, apart from the wide layout."
                        else -> "The order of Today's cards on a phone or narrow window. It follows the wide layout until you change it."
                    },
                )
            }
        }
        Div({
            classes("pref__acts")
            attr("role", "group")
            attr("aria-labelledby", "tl-l")
            attr("aria-describedby", "tl-hint")
        }) {
            A(Place.of("today", params = mapOf(TODAY_EDIT_PARAM to "1")).hash, {
                classes("btn", "btn--line")
                id(LAYOUT_EDIT_ID)
            }) { Text("Edit layout") }
            Button({
                type(ButtonType.Button)
                classes("btn", "btn--text")
                id("tl-reset")
                if (!saved) disabled()
                onClick {
                    if (phone) {
                        val before = workspace.prefs.todayPhoneLayout
                        workspace.setPrefs(workspace.prefs.copy(todayPhoneLayout = null))
                        toasts.show("Order matches the desktop", undo = {
                            workspace.setPrefs(workspace.prefs.copy(todayPhoneLayout = before))
                        })
                    } else {
                        val before = workspace.prefs.todayLayout
                        workspace.setPrefs(workspace.prefs.copy(todayLayout = null))
                        toasts.show("Layout back to default", undo = { workspace.setPrefs(workspace.prefs.copy(todayLayout = before)) })
                    }
                    // The button is disabled now.
                    focusNow(LAYOUT_EDIT_ID)
                }
            }) { Text(if (phone) "Match desktop" else "Reset to default") }
        }
    }
}

private const val LAYOUT_EDIT_ID = "tl-edit"

/**
 * Topics shown (owner change #7, D89): a checkbox per status category. Today's Topics card shows the Topics whose status
 * is in a checked one, and is left out with none checked. Each change saves the whole set, so none checked stays none.
 */
@Composable
private fun TopicsShownPref(workspace: WorkspaceModel) {
    val shown = workspace.prefs.todayTopicCategories
    Div({ classes("pref", "pref--checks") }) {
        Div {
            P({
                classes("pref__label")
                id("tc-l")
            }) { Text("Topics shown") }
            P({
                classes("pref__hint")
                id("tc-hint")
            }) { Text(TOPICS_SHOWN_HINT) }
        }
        Div({
            classes("pref-checks")
            attr("role", "group")
            attr("aria-labelledby", "tc-l")
            attr("aria-describedby", "tc-hint")
        }) {
            StatusCategory.entries.forEach { category ->
                val id = "tc-${category.id}"
                Span({ classes("pref-check") }) {
                    CheckBox(category in shown, label = null, id = id) { on ->
                        val now = workspace.prefs.todayTopicCategories
                        workspace.setPrefs(workspace.prefs.copy(todayTopicCategories = if (on) now + category else now - category))
                    }
                    Label(id) { Text(categoryLabel(category)) }
                }
            }
        }
    }
}

private const val TOPICS_SHOWN_HINT =
    "Today shows the Topics whose status is in a checked category, grouped by status. With none checked, it leaves Topics out."

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

private const val GROUP_TASKS_HINT =
    "Due date: Completed (when completed tasks show), Today, Tomorrow and Upcoming. Empty groups hide."

private fun groupByLabel(groupBy: TodayGroupBy): String =
    when (groupBy) {
        TodayGroupBy.None -> "None"
        TodayGroupBy.Topic -> "Topic"
        TodayGroupBy.DueDate -> "Due date"
    }

private const val NARROW_TITLES_HINT =
    "On the Horizontal timeline, how a meeting too short for its title shows it. The shortest meetings are always flags."

private fun titleFitLabel(titles: MeetingTitleFit): String =
    when (titles) {
        MeetingTitleFit.Diagonal -> "Diagonal"
        MeetingTitleFit.Vertical -> "Vertical"
        MeetingTitleFit.Flags -> "Dots with flags"
        MeetingTitleFit.Wrap -> "Increase height"
    }

private const val PAST_MEETINGS_HINT = "How many ended meetings the Vertical layout keeps, the latest ones. Horizontal shows the whole day."

private fun hideableLabel(section: TodayHideableSection): String =
    when (section) {
        TodayHideableSection.Meetings -> "Meetings"
        TodayHideableSection.Reminders -> "Reminders"
        TodayHideableSection.ReviewRequested -> "Review requested"
    }

/**
 * Settings > Inbox (D91), in the owner's order: Show completed tasks, Group by, then Show notes, which the owner added
 * after the list. A hidden Task or Note still waits in Inbox, and the Inbox count leaves it out (Q8).
 */
@Composable
internal fun InboxPrefsBlock(workspace: WorkspaceModel) {
    SettingsBlock("inbox", "Inbox", null) {
        val prefs = workspace.prefs
        SwitchPref("pref-ibx-done", "Show completed tasks", INBOX_COMPLETED_HINT, prefs.inboxShowCompleted) { on ->
            workspace.setPrefs(workspace.prefs.copy(inboxShowCompleted = on))
        }
        ChoicePref(
            labelId = "ig-l",
            label = "Group by",
            hint = INBOX_GROUP_HINT,
            options = InboxGroupBy.entries.map { it to inboxGroupByLabel(it) },
            picked = prefs.inboxGroupBy,
        ) { workspace.setPrefs(workspace.prefs.copy(inboxGroupBy = it)) }
        SwitchPref("pref-ibx-notes", "Show notes", INBOX_NOTES_HINT, prefs.inboxShowNotes) { on ->
            workspace.setPrefs(workspace.prefs.copy(inboxShowNotes = on))
        }
    }
}

private const val INBOX_COMPLETED_HINT = "Struck through until you file them. Off hides them, and the Inbox count leaves them out."

private const val INBOX_GROUP_HINT =
    "Type: Tasks, then Notes. Due date: Completed, Today, Tomorrow and Upcoming, then Notes. Empty groups hide."

private const val INBOX_NOTES_HINT = "Off hides them here and from the Inbox count. They still wait in Inbox and show on the Notes page."

private fun inboxGroupByLabel(groupBy: InboxGroupBy): String =
    when (groupBy) {
        InboxGroupBy.None -> "None"
        InboxGroupBy.Type -> "Type"
        InboxGroupBy.DueDate -> "Due date"
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
 * Settings > Topic Details (D92), in the owner's order: Show completed tasks, Hide empty sections, Group tasks by due
 * date. The Topic's page and the Topic card (D70) follow them.
 */
@Composable
internal fun TopicDetailsPrefsBlock(workspace: WorkspaceModel) {
    SettingsBlock("topic-details", "Topic Details", null) {
        val prefs = workspace.prefs
        SwitchPref("pref-td-done", "Show completed tasks", TOPIC_COMPLETED_HINT, prefs.topicShowCompleted) { on ->
            workspace.setPrefs(workspace.prefs.copy(topicShowCompleted = on))
        }
        TopicHideEmptyPref(workspace)
        SwitchPref("pref-td-due", "Group tasks by due date", TOPIC_DUE_HINT, prefs.topicGroupByDue) { on ->
            workspace.setPrefs(workspace.prefs.copy(topicGroupByDue = on))
        }
    }
}

/** Hide empty sections in Topic Details (D92): a checkbox per section that can hide. Each change saves the whole set. */
@Composable
private fun TopicHideEmptyPref(workspace: WorkspaceModel) {
    val hidden = workspace.prefs.topicHideEmpty
    Div({ classes("pref", "pref--checks") }) {
        Div {
            P({
                classes("pref__label")
                id("tdh-l")
            }) { Text("Hide empty sections") }
            P({
                classes("pref__hint")
                id("tdh-hint")
            }) { Text(TOPIC_HIDE_EMPTY_HINT) }
        }
        Div({
            classes("pref-checks")
            attr("role", "group")
            attr("aria-labelledby", "tdh-l")
            attr("aria-describedby", "tdh-hint")
        }) {
            TopicHideableSection.entries.forEach { section ->
                val id = "tdh-${section.id}"
                Span({ classes("pref-check") }) {
                    CheckBox(section in hidden, label = null, id = id) { on ->
                        val now = workspace.prefs.topicHideEmpty
                        workspace.setPrefs(workspace.prefs.copy(topicHideEmpty = if (on) now + section else now - section))
                    }
                    Label(id) { Text(topicHideableLabel(section)) }
                }
            }
        }
    }
}

private const val TOPIC_COMPLETED_HINT = "Under Completed, closed at first. Off hides them; the open count stays."

private const val TOPIC_HIDE_EMPTY_HINT =
    "A checked section shows only when it has something. Jira and Pull requests also show for suggestions. " +
        "While Additional information hides, Edit Topic has a field for it."

private const val TOPIC_DUE_HINT = "Completed (when completed tasks show), Today, Tomorrow and Upcoming. Empty groups hide."

private fun topicHideableLabel(section: TopicHideableSection): String =
    when (section) {
        TopicHideableSection.Notes -> "Notes"
        TopicHideableSection.Jira -> "Jira"
        TopicHideableSection.PullRequests -> "Pull requests"
        TopicHideableSection.Meetings -> "Meetings"
        TopicHideableSection.Info -> "Additional information"
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

/**
 * Settings > Pull requests (D93): how each list groups, in the owner's order. Review status is what this device saved
 * when a pull request was opened (Q4); the rest show under Not loaded yet.
 */
@Composable
internal fun PrsPrefsBlock(workspace: WorkspaceModel) {
    SettingsBlock("pull-requests", "Pull requests", null) {
        val prefs = workspace.prefs
        ChoicePref(
            labelId = "prm-l",
            label = "Mine: group by",
            hint = PRS_MINE_HINT,
            options = MyPrsGroupBy.entries.map { it to myPrsGroupByLabel(it) },
            picked = prefs.prsMineGroupBy,
        ) { workspace.setPrefs(workspace.prefs.copy(prsMineGroupBy = it)) }
        ChoicePref(
            labelId = "prr-l",
            label = "Review requested: group by",
            hint = PRS_REVIEW_HINT,
            options = ReviewPrsGroupBy.entries.map { it to reviewPrsGroupByLabel(it) },
            picked = prefs.prsReviewGroupBy,
        ) { workspace.setPrefs(workspace.prefs.copy(prsReviewGroupBy = it)) }
    }
}

private const val PRS_REVIEW_HINT =
    "Review status comes from pull requests opened on this device. The rest show under Not loaded yet."

private const val PRS_MINE_HINT = "A pull request linked to more than one Topic shows once, under Several Topics. $PRS_REVIEW_HINT"

private fun myPrsGroupByLabel(groupBy: MyPrsGroupBy): String =
    when (groupBy) {
        MyPrsGroupBy.Repository -> "Repository"
        MyPrsGroupBy.Topic -> "Topic"
        MyPrsGroupBy.ReviewStatus -> "Review status"
    }

private fun reviewPrsGroupByLabel(groupBy: ReviewPrsGroupBy): String =
    when (groupBy) {
        ReviewPrsGroupBy.Repository -> "Repository"
        ReviewPrsGroupBy.Author -> "Author"
        ReviewPrsGroupBy.ReviewStatus -> "Review status"
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

/**
 * One preference with a few named choices, as a row of radio buttons. More than three (D97) mark the row, so the choices
 * stand in two columns where the row is too narrow for them (app.css, .pref--many).
 */
@Composable
private fun <T> ChoicePref(
    labelId: String,
    label: String,
    hint: String,
    options: List<Pair<T, String>>,
    picked: T,
    onPick: (T) -> Unit,
) {
    Div({
        classes("pref", "pref--choice")
        if (options.size > 3) classes("pref--many")
    }) {
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
