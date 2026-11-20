package thread.web.presentation.external

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.ButtonType
import org.jetbrains.compose.web.attributes.type
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import org.w3c.dom.HTMLElement
import thread.domain.external.ExternalProblem
import thread.domain.external.ExternalView
import thread.domain.external.JiraBoardCard
import thread.domain.external.JiraBoardColumn
import thread.domain.external.JiraColumnsNote
import thread.domain.external.JiraMySprints
import thread.domain.external.JiraSprint
import thread.domain.external.JiraSprintBoard
import thread.domain.external.JiraSprintId
import thread.domain.external.JiraSprintList
import thread.domain.external.boardColumns
import thread.web.platform.mediaMatches
import thread.web.presentation.CardItem
import thread.web.presentation.CardType
import thread.web.presentation.CheckBox
import thread.web.presentation.EmptyState
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import thread.web.presentation.JiraTypeName
import thread.web.presentation.LocalCards
import thread.web.presentation.Place
import thread.web.presentation.SelectBox
import thread.web.presentation.withCard
import kotlin.js.Date
import kotlin.math.roundToLong

private const val SPRINT_PICK_ID = "jb-sprint-pick"
private const val ONLY_MINE_ID = "jb-mine"
private const val DAY_MILLIS = 86_400_000.0

/** What Jira answers when it refuses a sprint's query (HTTP 400), such as a sprint that no longer exists. */
private const val SPRINT_REFUSED = "http-400"

/** What the Board tab shows (D103 B): the account's active sprints, the sprint on screen and its board. */
internal class BoardShown(
    val sprintsView: ExternalView<JiraSprintList>?,
    val sprintId: JiraSprintId?,
    val boardView: ExternalView<JiraSprintBoard>?,
) {
    val sprints: List<JiraSprint>? get() = sprintsView?.savedOrNull?.value?.sprints

    val sprint: JiraSprint? get() = sprints?.firstOrNull { it.id == sprintId }

    /** The views the page banner speaks for, the board first. */
    val views: List<ExternalView<*>?> get() = listOf(boardView, sprintsView)
}

/**
 * Loads the account's active sprints and the board of the sprint on screen (D103 B): [wanted] (from the address),
 * else the one this device showed last, else the one with most of the account's tickets. The board waits for the
 * sprint list, which names the sprint's board. Both load on open, on Refresh and every five minutes while the page is
 * visible, like the other Jira lists (ADR-013).
 */
@Composable
internal fun rememberBoard(
    jira: JiraModel,
    wanted: JiraSprintId?,
    refresh: Int,
): BoardShown {
    val auto = rememberAutoRefresh(JiraMySprints) { jira.problem == ExternalProblem.Unauthorized }
    rememberListLoads(jira.mySprints, JiraMySprints, refresh, auto, jira.version)
    val sprintsView = jira.mySprints.view(JiraMySprints)
    val sprintId = pickSprint(sprintsView?.savedOrNull?.value?.sprints, wanted ?: jira.chosenSprint)
    if (sprintId != null) rememberListLoads(jira.sprintBoards, sprintId, refresh, auto, jira.version)
    // The sprint on screen is the one this device opens next time (Q4: kept on this device only).
    LaunchedEffect(sprintId) {
        if (sprintId != null && sprintId != jira.chosenSprint) jira.chooseSprint(sprintId)
    }
    return BoardShown(sprintsView, sprintId, sprintId?.let { jira.sprintBoards.view(it) })
}

/** Null while the sprint list is unknown; [wanted] when it is in the list; else the first sprint. */
private fun pickSprint(
    sprints: List<JiraSprint>?,
    wanted: JiraSprintId?,
): JiraSprintId? =
    when {
        sprints == null -> null
        wanted != null && sprints.any { it.id == wanted } -> wanted
        else -> sprints.firstOrNull()?.id
    }

/**
 * The Board tab (D103 B), Jira's active sprint board: the sprint's head, "Only my issues", then the board's columns
 * with the tickets in Jira's rank order. Read-only: a ticket opens as a card on top (D70 exception for the board, Q3).
 */
@Composable
internal fun BoardPanel(
    board: BoardShown,
    jira: JiraModel,
    place: Place,
    onPick: (JiraSprintId) -> Unit,
    onRefresh: () -> Unit,
) {
    val sprintsView = board.sprintsView
    val list = sprintsView?.savedOrNull?.value
    val sprint = board.sprint
    when {
        sprintsView is ExternalView.Missing ->
            JiraListProblem(sprintsView.problem, jira, "Your sprints load when you are back online.", onRefresh)
        sprintsView == null || list == null -> P({ classes("muted") }) { Text("Loading your sprints…") }
        !list.siteHasSprints -> {
            CacheBar(sprintsView, "Refresh sprints", onRefresh)
            EmptyState(Icon.Jira, "This site has no sprints", "Sprints come with Jira Software. Assigned to me and Filters still work.")
        }
        list.sprints.isEmpty() || sprint == null -> {
            CacheBar(sprintsView, "Refresh sprints", onRefresh)
            EmptyState(
                Icon.Jira,
                "No active sprint with your tickets",
                "When one of your tickets is in an active sprint, that sprint's board shows here.",
            )
        }
        else -> {
            val boardView = board.boardView
            val data = boardView?.savedOrNull?.value
            SprintHead(sprint, list.sprints, data?.config?.name, onPick)
            when {
                boardView is ExternalView.Missing ->
                    if (boardView.problem == ExternalProblem.Failed(SPRINT_REFUSED)) {
                        EmptyState(
                            Icon.Alert,
                            "Jira could not show this sprint",
                            "It may have closed, or moved to a board you cannot see. Refresh to look again.",
                        ) { RetryButton(onRefresh) }
                    } else {
                        JiraListProblem(boardView.problem, jira, "This sprint's board loads when you are back online.", onRefresh)
                    }
                boardView == null || data == null -> P({ classes("muted") }) { Text("Loading the board…") }
                else -> {
                    BoardTools(jira, boardView, onRefresh)
                    SprintBoard(data, sprint, jira.boardOnlyMine, place)
                }
            }
        }
    }
}

/** The sprint's name, board, dates, time left and goal, with the sprint picker when there are several. */
@Composable
private fun SprintHead(
    sprint: JiraSprint,
    sprints: List<JiraSprint>,
    boardName: String?,
    onPick: (JiraSprintId) -> Unit,
) {
    val now = Times.now()
    Div({ classes("jb-head") }) {
        Div({ classes("jb-head__main") }) {
            H2({ classes("jb-title") }) { Text(sprint.name) }
            P({ classes("jb-meta") }) {
                if (!boardName.isNullOrBlank()) Span({ classes("jb-meta__board") }) { Text(boardName) }
                val dates = sprintDates(sprint, now)
                if (dates != null) {
                    Span({ classes("jb-meta__dates") }) {
                        IconView(Icon.Calendar, 15)
                        Text(dates)
                    }
                }
                sprint.endMillis?.let { end ->
                    Span({
                        classes("jb-left")
                        if (end < now) classes("is-late")
                    }) { Text(timeLeftText(end, now)) }
                }
            }
            sprint.goal?.takeIf { it.isNotBlank() }?.let { goal ->
                P({
                    classes("jb-goal")
                    title(goal)
                }) {
                    Span({ classes("jb-goal__k") }) { Text("Goal") }
                    Text(" $goal")
                }
            }
        }
        if (sprints.size > 1) {
            Div({ classes("field", "jb-pick") }) {
                Label(SPRINT_PICK_ID, { classes("field__label") }) { Text("Sprint") }
                SelectBox(
                    value = sprint.id.value,
                    options = sprints.map { it.id.value to sprintOption(it) },
                    id = SPRINT_PICK_ID,
                    classes = listOf("input"),
                ) { raw -> JiraSprintId.parse(raw)?.let(onPick) }
            }
        }
    }
    sprintProgress(sprint, now)?.let { done ->
        Div({
            classes("jb-time")
            attr("aria-hidden", "true")
        }) {
            Span({
                classes("jb-time__done")
                style { property("width", "${(done * 1000).toInt() / 10.0}%") }
            })
        }
    }
}

/** "Only my issues" and the board's cache bar. */
@Composable
private fun BoardTools(
    jira: JiraModel,
    view: ExternalView<JiraSprintBoard>,
    onRefresh: () -> Unit,
) {
    Div({ classes("jb-tools") }) {
        Label(ONLY_MINE_ID, { classes("jb-mine") }) {
            CheckBox(jira.boardOnlyMine, label = null, classes = listOf("switch"), id = ONLY_MINE_ID, switch = true) {
                jira.setBoardOnlyMine(it)
            }
            Text("Only my issues")
        }
        CacheBar(view, "Refresh the board", onRefresh)
    }
}

/** The notes about what the board leaves out, then the columns, with a chip row to jump between them when narrow. */
@Composable
private fun SprintBoard(
    data: JiraSprintBoard,
    sprint: JiraSprint,
    onlyMine: Boolean,
    place: Place,
) {
    val layout = boardColumns(data, sprint.myKeys, onlyMine)
    // With "Only my issues" a column says how many of all its tickets are shown.
    val all = if (onlyMine) boardColumns(data, sprint.myKeys, onlyMine = false) else layout
    val allCounts = all.columns.map { it.cards.size }
    BoardNotes(data, layout.byStatus, layout.hiddenCount)
    if (data.cards.isEmpty()) {
        EmptyState(Icon.Jira, "No tickets in this sprint", "Tickets added to the sprint in Jira show here.")
        return
    }
    Nav({
        classes("jb-chips")
        attr("aria-label", "Columns")
    }) {
        layout.columns.forEachIndexed { index, column ->
            Button({
                classes("jb-chip")
                type(ButtonType.Button)
                onClick { showLane(index) }
            }) {
                Text(column.name)
                Text(" ")
                Span({ classes("n") }) { Text(column.cards.size.toString()) }
            }
        }
    }
    Div({ classes("board", "jboard") }) {
        layout.columns.forEachIndexed { index, column ->
            key(index, column.name) {
                Lane(index, column, allCounts.getOrNull(index), onlyMine, place)
            }
        }
    }
}

/** Why the columns follow status, which tickets the board hides, and whether every ticket was fetched. */
@Composable
private fun BoardNotes(
    data: JiraSprintBoard,
    byStatus: Boolean,
    hiddenCount: Int,
) {
    if (byStatus && data.cards.isNotEmpty()) {
        BoardNote {
            if (data.columnsNote == JiraColumnsNote.NeedsScopes) {
                Text("Columns follow status. To see this board's own columns, the Jira token also needs to read boards. ")
                A(CONNECTIONS_PLACE.hash) { Text("Open Settings") }
            } else {
                Text("Columns follow status. Jira did not send this board's own columns.")
            }
        }
    }
    if (hiddenCount > 0) {
        BoardNote {
            Text(
                if (hiddenCount == 1) {
                    "1 ticket is hidden: its status is on no column of this board, as in Jira."
                } else {
                    "$hiddenCount tickets are hidden: their status is on no column of this board, as in Jira."
                },
            )
        }
    }
    if (!data.complete) BoardNote { Text("Showing the first ${data.cards.size} tickets of this sprint.") }
}

@Composable
private fun BoardNote(content: @Composable () -> Unit) {
    P({ classes("jb-note") }) {
        IconView(Icon.Info, 15)
        Span { content() }
    }
}

@Composable
private fun Lane(
    index: Int,
    column: JiraBoardColumn,
    allCount: Int?,
    onlyMine: Boolean,
    place: Place,
) {
    Section({
        classes("lane", "jlane")
        // Like Jira: over the maximum is red, under the minimum yellow.
        if (column.isOverMax) {
            classes("is-overmax")
        } else if (column.isUnderMin) {
            classes("is-undermin")
        }
        id("jb-lane-$index")
        attr("aria-labelledby", "jb-ln-$index")
    }) {
        H3({
            classes("lane__head", "jlane__head")
            id("jb-ln-$index")
            tabIndex(-1)
        }) {
            // The spaces keep the heading's spoken name apart ("In Progress 4 Max 3"); a flex row does not show them.
            Span({ classes("jlane__name") }) { Text(column.name) }
            Text(" ")
            Span({ classes("n") }) {
                Text(if (onlyMine && allCount != null) "${column.cards.size} of $allCount" else column.cards.size.toString())
            }
            column.min?.let {
                Text(" ")
                LimitBadge("Min $it", "min", column.isUnderMin, ", under the limit")
            }
            column.max?.let {
                Text(" ")
                LimitBadge("Max $it", "max", column.isOverMax, ", over the limit")
            }
        }
        if (column.cards.isEmpty()) {
            P({ classes("lane__empty") }) { Text(if (onlyMine) "None of yours" else "No tickets") }
        } else {
            Ul({ classes("lane__list") }) {
                column.cards.forEach { card ->
                    key(card.key.value) { Li { BoardCard(card, column.isDone, place) } }
                }
            }
        }
    }
}

@Composable
private fun LimitBadge(
    text: String,
    kind: String,
    broken: Boolean,
    brokenText: String,
) {
    Span({
        classes("jlane__lim", "jlane__lim--$kind")
        if (broken) classes("is-broken")
    }) {
        Text(text)
        if (broken) Span({ classes("vh") }) { Text(brokenText) }
    }
}

/**
 * A ticket on the board: its type, title, parent, key, priority, points and assignee, as Jira's board card shows
 * them. A link to the ticket as a card on top of the board (Q3); the stub and the tear line still say Jira (D63).
 */
@Composable
private fun BoardCard(
    card: JiraBoardCard,
    done: Boolean,
    place: Place,
) {
    val cards = LocalCards.current
    A(place.withCard(CardItem(CardType.Jira, card.key.value)).hash, {
        classes("jbcard")
        if (done) classes("is-done")
        attr("data-key", card.key.value)
        onClick { event ->
            if (cards != null) {
                event.preventDefault()
                cards.openJira(card.key)
            }
        }
    }) {
        Span({
            classes("jbcard__stub")
            attr("aria-hidden", "true")
            card.type?.let { title(it) }
        }) { JiraTypeIcon(card.type, 16, decorative = true) }
        Span({ classes("jbcard__body") }) {
            JiraTypeName(card.type)
            Span({ classes("jbcard__title") }) { Text(card.title) }
            card.parentKey?.let { parent ->
                Span({
                    classes("jbcard__parent")
                    title(listOfNotNull(parent.value, card.parentTitle).joinToString(" "))
                }) {
                    Span({ classes("vh") }) { Text("Parent ") }
                    Span({ classes("jbcard__pkey") }) { Text(parent.value) }
                    card.parentTitle?.let { Text(" $it") }
                }
            }
            Span({ classes("jbcard__foot") }) {
                Span({ classes("jbcard__key") }) {
                    Text(card.key.value)
                    if (done) Span({ classes("vh") }) { Text(", done") }
                }
                Span({ classes("jbcard__sp") })
                card.priority?.let { PriorityIcon(it) }
                card.storyPoints?.let { points ->
                    Span({
                        classes("jbcard__pts")
                        title("Story points")
                    }) {
                        Span({ classes("vh") }) { Text("Story points ") }
                        Text(pointsText(points))
                    }
                }
                Assignee(card.assignee)
            }
        }
    }
}

/** Jira's priority arrows, drawn from constants only; the priority's name goes in attributes. */
private enum class PriorityLevel(
    val css: String,
    val path: String,
) {
    Highest("hi", "M4 8.6l4-3.6 4 3.6M4 12.6l4-3.6 4 3.6"),
    High("hi", "M4 10.3l4-4 4 4"),
    Medium("mid", "M3.6 6.2h8.8M3.6 9.8h8.8"),
    Low("lo", "M4 5.7l4 4 4-4"),
    Lowest("lo", "M4 3.4l4 3.6 4-3.6M4 7.4l4 3.6 4-3.6"),
}

private fun priorityLevel(name: String): PriorityLevel? =
    when (name.trim().lowercase()) {
        "highest", "blocker", "critical" -> PriorityLevel.Highest
        "high", "major" -> PriorityLevel.High
        "medium", "normal" -> PriorityLevel.Medium
        "low", "minor" -> PriorityLevel.Low
        "lowest", "trivial" -> PriorityLevel.Lowest
        else -> null
    }

@Composable
private fun PriorityIcon(name: String) {
    val level = priorityLevel(name)
    if (level == null) {
        // A priority Thread has no arrow for still reaches screen readers.
        Span({ classes("vh") }) { Text("Priority: $name") }
        return
    }
    key(level) {
        Span({
            classes("jb-prio", "jb-prio--${level.css}")
            attr("role", "img")
            attr("aria-label", "Priority: $name")
            title("Priority: $name")
            ref { element ->
                element.innerHTML =
                    """<svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" focusable="false" fill="none" """ +
                    """stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">""" +
                    """<path d="${level.path}"/></svg>"""
                onDispose { }
            }
        })
    }
}

/** The assignee's initials on a color picked from the name, or a dashed circle when no one has the ticket. */
@Composable
private fun Assignee(name: String?) {
    // Taken while the name may be null, so it is a person's initials ("Ada Lovelace" → "AL"), not a GitHub login's.
    val letters = initials(name)
    if (name.isNullOrBlank()) {
        Span({
            classes("jbav", "jbav--none")
            attr("role", "img")
            attr("aria-label", "Unassigned")
            title("Unassigned")
        }) { IconView(Icon.User, 14) }
        return
    }
    Span({
        classes("jbav")
        attr("role", "img")
        attr("aria-label", "Assignee: $name")
        title(name)
        style { property("--av-h", nameHue(name).toString()) }
    }) { Text(letters) }
}

/** A steady hue for a name, so a person keeps one color across the board. */
private fun nameHue(name: String): Int = name.fold(7) { hash, char -> (hash * 31 + char.code) and 0xFFFFFF } % 360

/** `3`, or `0.5` for a part point. */
private fun pointsText(points: Double): String =
    if (points == points.roundToLong().toDouble()) points.roundToLong().toString() else points.toString()

/** `Option name (3 of yours)`, for the sprint picker. */
private fun sprintOption(sprint: JiraSprint): String = "${sprint.name} (${sprint.myKeys.size} of yours)"

/** `Mon 16 Nov to Mon 30 Nov`, or one end when Jira gave only one. */
private fun sprintDates(
    sprint: JiraSprint,
    now: Long,
): String? {
    val start = sprint.startMillis?.let { Times.dateText(it, now) }
    val end = sprint.endMillis?.let { Times.dateText(it, now) }
    return when {
        start != null && end != null -> "$start to $end"
        start != null -> "Started $start"
        end != null -> "Ends $end"
        else -> null
    }
}

/** How many calendar days are left until [end], by this device's clock and time zone. */
private fun timeLeftText(
    end: Long,
    now: Long,
): String {
    val days = ((localMidnight(end) - localMidnight(now)) / DAY_MILLIS).roundToLong()
    return when {
        end < now || days < 0 -> "Past its end date"
        days == 0L -> "Ends today"
        days == 1L -> "1 day left"
        else -> "$days days left"
    }
}

private fun localMidnight(millis: Long): Double {
    val date = Date(millis.toDouble())
    return Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
}

/** How much of the sprint's time has passed, from 0 to 1, or null when its dates are unknown. */
private fun sprintProgress(
    sprint: JiraSprint,
    now: Long,
): Double? {
    val start = sprint.startMillis ?: return null
    val end = sprint.endMillis ?: return null
    if (end <= start) return null
    return ((now - start).toDouble() / (end - start)).coerceIn(0.0, 1.0)
}

/** Scrolls column [index] into view and moves focus to its heading, for the narrow chip row. */
private fun showLane(index: Int) {
    val lane = document.getElementById("jb-lane-$index") as? HTMLElement ?: return
    val options: dynamic = js("({})")
    options.block = "nearest"
    options.inline = "start"
    options.behavior = if (mediaMatches("(prefers-reduced-motion: reduce)")) "auto" else "smooth"
    lane.scrollIntoView(options)
    (document.getElementById("jb-ln-$index") as? HTMLElement)?.asDynamic()?.focus(js("({ preventScroll: true })"))
}
