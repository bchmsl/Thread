package thread.web.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import thread.domain.account.AccountId
import thread.domain.calendar.MeetingsState
import thread.domain.external.JiraIssueKey
import thread.domain.external.PullRequestRef
import thread.domain.note.NoteId
import thread.domain.note.NoteSummary
import thread.domain.note.UNTITLED_NOTE
import thread.web.presentation.calendar.MeetingCard
import thread.web.presentation.calendar.MeetingsModel
import thread.web.presentation.external.GitHubModel
import thread.web.presentation.external.JiraCard
import thread.web.presentation.external.JiraModel
import thread.web.presentation.external.PrCard
import thread.web.presentation.notes.NoteCard
import thread.web.presentation.notes.NoteServices
import thread.web.presentation.tasks.TaskCard
import thread.web.presentation.topics.TopicCard

/**
 * The card on top of the page (D70): the one item the place names with `?task=`, `?note=`, `?jira=`, `?pr=`,
 * `?topic=` or `?meeting=`, in one native dialog. Opening another item from a card swaps what the dialog shows and keeps the dialog,
 * so when the last card closes, focus returns to the row that opened the first one ([ModalDialog]).
 *
 * A link to an item that is not here shows a toast and the page. Closing a Note that is not saved on this device asks
 * first. Inside the dialog, element ids carry the card's scope ([LocalIdScope]), so they never repeat the page's.
 */
@Composable
internal fun CardHost(
    item: CardItem,
    workspace: WorkspaceModel,
    jira: JiraModel,
    gitHub: GitHubModel,
    router: Router,
    cards: CardOpener,
    notes: NoteServices,
    accountId: AccountId,
    syncCenter: SyncCenter,
    meetings: MeetingsModel,
) {
    val toasts = LocalToasts.current
    // Closing waits for the browser to go back through the card's history entries. Meanwhile the card is gone, and an
    // item it just moved to Trash is not reported as missing.
    var closing by remember(item) { mutableStateOf(false) }
    var noteUnsaved by remember(item) { mutableStateOf(false) }
    var confirming by remember(item) { mutableStateOf(false) }
    if (closing) return
    // Once only. When the browser forces a dialog closed on Escape (a cancel the page cannot stop, as after an earlier
    // Escape with no click since), ModalDialog calls onDismiss and then onClose, and going back twice would leave the
    // page under the card.
    val close = {
        if (!closing) {
            closing = true
            router.closeCard()
        }
    }
    val page = router.place.withoutCards()
    // A link that names the item the page itself shows: show the page, so one Note never has two editors.
    if (cardShownByPage(page, item)) {
        SideEffect { router.replace(page) }
        return
    }
    // A meeting link waits for the first read, so it is not reported missing while the meetings load.
    if (item.type == CardType.Meeting && meetings.state == MeetingsState.Loading) return
    val missing = missingText(item, workspace, meetings)
    if (missing != null) {
        LaunchedEffect(item) {
            toasts.show(missing)
            close()
        }
        return
    }
    // A Task or Note made a moment ago opens for editing (D70). The card keeps its mode from here on.
    val startEditing = cards.editOnOpen == item
    SideEffect { if (cards.editOnOpen == item) cards.editOnOpen = null }
    val backTo = router.previousCard?.let { cardTitle(it, workspace, meetings) }
    val requestClose = { if (item.type == CardType.Note && noteUnsaved) confirming = true else close() }
    ModalDialog(
        classes = listOf("dlg--card"),
        label = "${kindLabel(item.type)}: ${cardTitle(item, workspace, meetings)}",
        onDismiss = requestClose,
        onClose = close,
    ) {
        CompositionLocalProvider(LocalIdScope provides CARD_ID_SCOPE) {
            key(item) {
                when (item.type) {
                    CardType.Task ->
                        workspace.task(item.id)?.let { task ->
                            TaskCard(workspace, task, page, startEditing, backTo, cards::back, close)
                        }
                    CardType.Note ->
                        NoteCard(
                            workspace = workspace,
                            router = router,
                            services = notes,
                            accountId = accountId,
                            noteId = NoteId(item.id),
                            listing = noteListing(workspace, item.id),
                            syncCenter = syncCenter,
                            startEditing = startEditing,
                            backTo = backTo,
                            onBack = cards::back,
                            onUnsaved = { noteUnsaved = it },
                            onClose = requestClose,
                            onCloseNow = close,
                        )
                    CardType.Topic ->
                        workspace.topic(item.id)?.let { topic ->
                            TopicCard(workspace, jira, gitHub, meetings, topic, cards, backTo, cards::back, close)
                        }
                    CardType.Jira ->
                        JiraIssueKey.parse(item.id)?.let { key ->
                            JiraCard(jira, workspace, router, cards, key, backTo, cards::back, close)
                        }
                    CardType.Pr ->
                        PullRequestRef.parse(item.id)?.let { ref ->
                            PrCard(gitHub, workspace, router, ref, backTo, cards::back, close)
                        }
                    CardType.Meeting ->
                        meetings.meeting(item.id)?.let { meeting ->
                            val repeats = meetings.meetings.count { it.seriesKey == meeting.seriesKey } > 1
                            val overCalendar = View.of(page) == View.Calendar
                            MeetingCard(workspace, meeting, repeats, backTo, cards::back, close, showInCalendar = !overCalendar)
                        }
                }
            }
        }
    }
    if (confirming) {
        ConfirmDialog(
            title = "Close this note?",
            body = "It is not saved on this device. If you close it now, your latest changes can be lost.",
            confirmLabel = "Close anyway",
            danger = true,
        ) { closeAnyway ->
            confirming = false
            if (closeAnyway) close()
        }
    }
}

/** The prefix of element ids inside a card. */
private const val CARD_ID_SCOPE = "c-"

private fun kindLabel(type: CardType): String =
    when (type) {
        CardType.Task -> "Task"
        CardType.Note -> "Note"
        CardType.Jira -> "Jira ticket"
        CardType.Pr -> "Pull request"
        CardType.Topic -> "Topic"
        CardType.Meeting -> "Meeting"
    }

/** The item's name, for the dialog's label and the next card's Back button. */
private fun cardTitle(
    item: CardItem,
    workspace: WorkspaceModel,
    meetings: MeetingsModel,
): String =
    when (item.type) {
        CardType.Task -> workspace.task(item.id)?.title ?: "Task"
        CardType.Note -> noteListing(workspace, item.id)?.title?.ifBlank { null } ?: UNTITLED_NOTE
        CardType.Topic -> workspace.topic(item.id)?.name ?: "Topic"
        CardType.Jira -> item.id
        CardType.Pr -> PullRequestRef.parse(item.id)?.let { "#${it.number}" } ?: "Pull request"
        CardType.Meeting -> meetings.meeting(item.id)?.displayTitle ?: "Meeting"
    }

private fun noteListing(
    workspace: WorkspaceModel,
    id: String,
): NoteSummary? = workspace.storedNotes.firstOrNull { it.id.value == id }?.takeUnless { it.discarded }

/** Why a card cannot show its item, or null when it can. */
private fun missingText(
    item: CardItem,
    workspace: WorkspaceModel,
    meetings: MeetingsModel,
): String? =
    when (item.type) {
        CardType.Task -> "That task is not here any more. It may be in Trash.".takeIf { workspace.task(item.id) == null }
        CardType.Note ->
            "That note is not here any more. It may be in Trash."
                .takeIf { noteListing(workspace, item.id) == null && !workspace.isMadeHere(NoteId(item.id)) }
        CardType.Topic -> "That Topic is not here any more. It may be in Trash.".takeIf { workspace.topic(item.id) == null }
        CardType.Jira -> "That link does not name a Jira ticket.".takeIf { JiraIssueKey.parse(item.id) == null }
        CardType.Pr -> "That link does not name a pull request.".takeIf { PullRequestRef.parse(item.id) == null }
        CardType.Meeting -> meetingMissingText(meetings.state, meetings.meeting(item.id) != null)
    }

/** Why a meeting card cannot show its meeting, or null when it can. */
private fun meetingMissingText(
    state: MeetingsState,
    found: Boolean,
): String? =
    when (state) {
        is MeetingsState.Ready -> "That meeting is no longer in Thread.".takeIf { !found }
        MeetingsState.NotSetUp, MeetingsState.Loading -> "That meeting is no longer in Thread."
        MeetingsState.NeedsUpdate -> "Update Thread to see meetings. Reload the page to get the latest version."
        is MeetingsState.Unavailable -> "Meetings are unavailable. Reload the page to try again."
    }
