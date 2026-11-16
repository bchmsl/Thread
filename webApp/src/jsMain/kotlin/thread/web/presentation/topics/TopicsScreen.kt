package thread.web.presentation.topics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import thread.domain.task.Task
import thread.domain.task.TaskProgress
import thread.domain.topic.StatusCategory
import thread.domain.topic.StatusId
import thread.domain.topic.Topic
import thread.domain.topic.TopicId
import thread.domain.topic.TopicStatus
import thread.domain.topic.categoryOf
import thread.domain.topic.statusOf
import thread.domain.workspace.TopicsView
import thread.domain.workspace.moveTopic
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.LocalToasts
import thread.web.presentation.PageHeader
import thread.web.presentation.Place
import thread.web.presentation.Router
import thread.web.presentation.SelectBox
import thread.web.presentation.WorkspaceModel
import thread.web.presentation.shell.CaptureState
import thread.web.presentation.shell.CaptureType

/**
 * Topics (v3 topics.html): List grouped by status, or Board with a lane per status. The view is remembered in the
 * workspace preferences; `?view=list|board` opens one and remembers it too.
 */
@Composable
internal fun TopicsScreen(
    workspace: WorkspaceModel,
    place: Place,
    router: Router,
    capture: CaptureState,
) {
    val urlView = TopicsView.fromId(place.param("view"))
    LaunchedEffect(urlView, workspace.loaded) {
        if (urlView != null && workspace.loaded) workspace.setPrefs(workspace.prefs.copy(topicsView = urlView))
    }
    val view = urlView ?: workspace.prefs.topicsView
    val statuses = workspace.statuses
    val topics = workspace.topics
    val active = topics.count { categoryOf(it, statuses) == StatusCategory.Active }

    PageHeader(
        "Topics",
        if (topics.isNotEmpty()) {
            "${topicCount(topics.size)}, $active active"
        } else {
            "A Topic holds the tasks, notes, tickets and PRs for one piece of work."
        },
    ) {
        Div({
            classes("seg")
            attr("role", "radiogroup")
            attr("aria-label", "Topics view")
        }) {
            TopicsView.entries.forEach { option ->
                Button({
                    type(ButtonType.Button)
                    attr("role", "radio")
                    attr("aria-checked", (option == view).toString())
                    onClick {
                        workspace.setPrefs(workspace.prefs.copy(topicsView = option))
                        router.replace(place.with("view", option.id))
                    }
                }) {
                    IconView(if (option == TopicsView.List) Icon.List else Icon.Topics, 16)
                    Text(if (option == TopicsView.List) " List" else " Board")
                }
            }
        }
        Button({
            type(ButtonType.Button)
            classes("btn", "btn--primary")
            onClick { capture.requestFocus(CaptureType.Topic) }
        }) { Text("New topic") }
    }
    when {
        workspace.readFailed && !workspace.loaded ->
            EmptyState(Icon.Alert, "Topics could not load", "Reload the page to try again.")
        // Nothing is drawn for the moment before the device cache answers.
        !workspace.loaded -> Unit
        topics.isEmpty() -> EmptyState(Icon.Topics, "No Topics yet", "Create one with New topic. It starts as Planned.")
        view == TopicsView.Board -> TopicsBoard(workspace, statuses, topics)
        else -> TopicsList(workspace, statuses, topics)
    }
}

/** Topics whose status is [status]. A Topic whose status is gone shows under the first status, as everywhere else. */
private fun topicsIn(
    status: TopicStatus,
    topics: List<Topic>,
    statuses: List<TopicStatus>,
): List<Topic> = topics.filter { statusOf(it, statuses).id == status.id }

@Composable
private fun TopicsList(
    workspace: WorkspaceModel,
    statuses: List<TopicStatus>,
    topics: List<Topic>,
) {
    statuses.forEach { status ->
        val inStatus = topicsIn(status, topics, statuses)
        if (inStatus.isEmpty()) return@forEach
        key(status.id) {
            Section({
                classes("tl-group")
                attr("aria-labelledby", "tl-${status.id}")
            }) {
                H2({
                    classes("tl-head")
                    id("tl-${status.id}")
                }) {
                    StatusLabel(status)
                    Text(" ")
                    Span({ classes("n") }) { Text(inStatus.size.toString()) }
                }
                Ul({ classes("tl-list") }) {
                    inStatus.forEach { topic ->
                        key(topic.id) {
                            Li {
                                A(topicPlace(topic).hash, {
                                    classes("trow2")
                                    topicColor(topic.color)
                                }) {
                                    TopicHex(topic.name, topic.color, HexSize.Md)
                                    Span({ classes("trow2__name") }) { Text(topic.name) }
                                    Span({ classes("trow2__next") }) { Text(nextLine(workspace.nextTask(topic.id), status)) }
                                    Span({ classes("trow2__side") }) { TopicProgress(topic, workspace.progress(topic.id)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A Topic row's second line (D52): its first open Task, the one Topic Details lists first. */
private fun nextLine(
    next: Task?,
    status: TopicStatus,
): String =
    when {
        next != null -> "Next: ${next.title}"
        status.category == StatusCategory.Done -> "Finished"
        else -> "No open tasks"
    }

/** The Board: drag a card by its handle to another lane, or use its "Move to" menu (keyboard and touch). */
@Composable
private fun TopicsBoard(
    workspace: WorkspaceModel,
    statuses: List<TopicStatus>,
    topics: List<Topic>,
) {
    val toasts = LocalToasts.current
    var dragging by remember { mutableStateOf<TopicId?>(null) }
    var overLane by remember { mutableStateOf<StatusId?>(null) }
    val move = { topic: Topic, to: StatusId ->
        val edit = workspace.apply(moveTopic(topic, to, statuses))
        val target = statuses.firstOrNull { it.id == to }
        if (edit != null && target != null) {
            toasts.show("Moved ${topic.name} to ${target.name}", undo = { workspace.undo(edit) })
        }
    }
    Div({
        classes("board")
        attr("aria-label", "Board. Drag a card by its handle, or use its Move to menu.")
        // The board widens past the page only as far as its lanes need (owner change #14, D66).
        style { property("--lanes", statuses.size.toString()) }
    }) {
        statuses.forEach { status ->
            val inStatus = topicsIn(status, topics, statuses)
            key(status.id) {
                Section({
                    classes("lane")
                    if (overLane == status.id) classes("is-over")
                    attr("data-lane", status.id.value)
                    attr("aria-labelledby", "ln-${status.id}")
                    onDragOver { event ->
                        if (dragging != null) {
                            event.preventDefault()
                            overLane = status.id
                        }
                    }
                    onDrop { event ->
                        val id = dragging ?: return@onDrop
                        event.preventDefault()
                        dragging = null
                        overLane = null
                        workspace.topic(id.value)?.let { move(it, status.id) }
                    }
                }) {
                    H2({
                        classes("lane__head")
                        id("ln-${status.id}")
                    }) {
                        StatusLabel(status)
                        Text(" ")
                        Span({ classes("n") }) { Text(inStatus.size.toString()) }
                    }
                    if (inStatus.isEmpty()) {
                        P({ classes("lane__empty") }) { Text("No Topics. Drop one here.") }
                    } else {
                        Ul({ classes("lane__list") }) {
                            inStatus.forEach { topic ->
                                key(topic.id) {
                                    BoardCard(
                                        topic = topic,
                                        next = workspace.nextTask(topic.id),
                                        progress = workspace.progress(topic.id),
                                        statuses = statuses,
                                        dragging = dragging == topic.id,
                                        onDragStart = { dragging = topic.id },
                                        onDragEnd = {
                                            dragging = null
                                            overLane = null
                                        },
                                        onMove = { to -> move(topic, to) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardCard(
    topic: Topic,
    next: Task?,
    progress: TaskProgress,
    statuses: List<TopicStatus>,
    dragging: Boolean,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onMove: (StatusId) -> Unit,
) {
    Li({
        classes("bcard")
        if (dragging) classes("is-dragging")
        attr("draggable", "true")
        attr("data-topic", topic.id.value)
        topicColor(topic.color)
        onDragStart { event ->
            event.dataTransfer?.effectAllowed = "move"
            event.dataTransfer?.setData("text/plain", topic.id.value)
            onDragStart()
        }
        onDragEnd { onDragEnd() }
    }) {
        Div({ classes("bcard__top") }) {
            TopicHex(topic.name, topic.color, HexSize.Sm)
            A(topicPlace(topic).hash, { classes("bcard__name") }) { Text(topic.name) }
            Span({
                classes("bcard__grip")
                attr("title", "Drag to move")
                attr("aria-hidden", "true")
            }) { IconView(Icon.Grip, 18) }
        }
        if (next != null) P({ classes("bcard__next") }) { Text("Next: ${next.title}") }
        Div({ classes("bcard__foot") }) {
            TopicProgress(topic, progress)
            Span({ classes("bcard__move") }) {
                Label("mv-${topic.id}", { classes("vh") }) { Text("Move ${topic.name} to") }
                SelectBox(
                    value = statusOf(topic, statuses).id.value,
                    options = statuses.map { it.id.value to it.name },
                    id = "mv-${topic.id}",
                    classes = listOf("input", "input--sm", "input--auto"),
                ) { picked -> onMove(StatusId(picked)) }
            }
        }
    }
}
