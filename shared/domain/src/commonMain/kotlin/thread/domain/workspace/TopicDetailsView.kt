package thread.domain.workspace

import thread.domain.task.DueGroup
import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.task.groupByDue
import thread.domain.time.WallTime

// What Topic Details shows (owner change #25, D92), by Settings > Topic Details: its Tasks with "Show completed tasks"
// and "Group tasks by due date", grouped by the shared task-group rule (`task/TaskGroups`, D90), and which of its
// sections "Hide empty sections" leaves out. The Topic's page and the Topic card (D70) show the same.

/**
 * One part of a Topic's Tasks list. [due] is null for the open Tasks with grouping off: one list without a heading, as
 * before D92. [DueGroup.Completed] is the Completed list, closed at first, in both modes (A8). Any other [due] is a
 * due-date group.
 */
data class TopicTaskPart(
    val due: DueGroup?,
    val tasks: List<Task>,
)

/**
 * The Topic's [tasks] (`topicTasks`: its live Tasks, oldest first) as Topic Details lists them (D92). An empty part is
 * left out (Q2).
 *
 * - [groupByDue] off: the open Tasks, oldest first, then Completed, as before D92.
 * - [groupByDue] on: the shared rule's groups, in its order and with its order inside each ([groupByDue]: Completed,
 *   Today, Tomorrow, Upcoming). Completed holds every completed Task of the Topic, not only today's.
 *
 * Completed is left out while [showCompleted] is off (D1). [remindAt] holds each Task's next open reminder, whose day
 * counts as a date (D54).
 */
fun topicTaskParts(
    tasks: List<Task>,
    showCompleted: Boolean,
    groupByDue: Boolean,
    now: WallTime,
    remindAt: Map<TaskId, WallTime> = emptyMap(),
): List<TopicTaskPart> {
    val shown = if (showCompleted) tasks else tasks.filter { !it.done }
    val parts =
        if (groupByDue) {
            groupByDue(shown, now, remindAt).map { TopicTaskPart(it.group, it.tasks) }
        } else {
            val (done, open) = shown.partition { it.done }
            listOf(TopicTaskPart(null, open), TopicTaskPart(DueGroup.Completed, done))
        }
    return parts.filter { it.tasks.isNotEmpty() }
}

/**
 * What each Topic Details section that can hide holds now (D92). [jira] and [prs] count the linked tickets and pull
 * requests, [jiraSuggested] and [prsSuggested] the suggestions their sections show (Q7). [meetings] counts the linked
 * meeting series. [info] is the Additional information text.
 */
data class TopicSectionItems(
    val notes: Int = 0,
    val jira: Int = 0,
    val jiraSuggested: Int = 0,
    val prs: Int = 0,
    val prsSuggested: Int = 0,
    val meetings: Int = 0,
    val info: String = "",
)

/**
 * The sections Topic Details leaves out (D92): the ones checked in [hideEmpty] that hold nothing in [items]. Jira and
 * Pull requests keep showing while they show a suggestion (owner answer Q7). Additional information is empty when its
 * text is blank; while it is hidden, Edit Topic has a field for it (A9).
 */
fun hiddenTopicSections(
    hideEmpty: Set<TopicHideableSection>,
    items: TopicSectionItems,
): Set<TopicHideableSection> =
    hideEmpty.filterTo(mutableSetOf()) { section ->
        when (section) {
            TopicHideableSection.Notes -> items.notes == 0
            TopicHideableSection.Jira -> items.jira == 0 && items.jiraSuggested == 0
            TopicHideableSection.PullRequests -> items.prs == 0 && items.prsSuggested == 0
            TopicHideableSection.Meetings -> items.meetings == 0
            TopicHideableSection.Info -> items.info.isBlank()
        }
    }
