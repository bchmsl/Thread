package thread.domain.workspace

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import thread.domain.calendar.MeetingAlternate
import thread.domain.calendar.MeetingLink
import thread.domain.calendar.MeetingTitleFit
import thread.domain.focus.FocusSession
import thread.domain.focus.FocusSettings
import thread.domain.note.NoteId
import thread.domain.note.NoteSaveState
import thread.domain.note.NoteSummary
import thread.domain.note.NoteTopicLink
import thread.domain.reminder.Reminder
import thread.domain.task.Task
import thread.domain.time.DEFAULT_WORKSPACE_ZONE
import thread.domain.topic.StatusCategory
import thread.domain.topic.Topic
import thread.domain.topic.TopicStatus

/** How the Topics page opens (v3 Settings, "Topics opens as"). The page also remembers the last one picked. */
enum class TopicsView(
    val id: String,
) {
    List("list"),
    Board("board"),
    ;

    companion object {
        fun fromId(id: String?): TopicsView? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Where Today shows the meetings (D79, Settings "Meetings layout"): [Vertical], the Meetings card at the top of the right
 * column, or [Horizontal], a timeline across the page above both columns.
 */
enum class MeetingsLayout(
    val id: String,
) {
    Vertical("vertical"),
    Horizontal("horizontal"),
    ;

    companion object {
        fun fromId(id: String?): MeetingsLayout? = entries.firstOrNull { it.id == id }
    }
}

/** What quick capture makes (D35). */
enum class CaptureKind(
    val id: String,
) {
    Task("task"),
    Note("note"),
    Topic("topic"),
    ;

    companion object {
        fun fromId(id: String?): CaptureKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The workspace preferences stored with the account (04-data-and-sync.md, "Preferences"). The per-screen ones, from
 * [todayTopicsView] on (D83), each start with the behaviour from before them. A null one is not set yet: its reader in
 * ScreenPrefs.kt, or TodayLayout.kt for Today's layout (D95), says what that means.
 */
data class WorkspacePrefs(
    val topicsView: TopicsView = TopicsView.List,
    val captureType: CaptureKind = CaptureKind.Task,
    /** Settings, "Keep completed tasks in Today". On by default, as in v3. */
    val keepCompleted: Boolean = true,
    /** The IANA time zone that defines today, overdue and reminder times. No screen changes it in the pilot. */
    val timeZone: String = DEFAULT_WORKSPACE_ZONE,
    /** The Focus timer's focus length in minutes, 1 to 90 (FD1). */
    val focusWorkMinutes: Int = FocusSettings.DEFAULT_WORK_MINUTES,
    /** The Focus timer's break length in minutes, 1 to 30 (FD1). */
    val focusBreakMinutes: Int = FocusSettings.DEFAULT_BREAK_MINUTES,
    /**
     * Settings, "Hide empty sections in Today" (D77). Off by default. Since D83 it is also the starting value of
     * [todayHideEmpty]: see [todayHiddenWhenEmpty].
     */
    val hideEmptySections: Boolean = false,
    /** Settings, "Meetings layout" (D79). Vertical by default, as before D79. */
    val meetingsLayout: MeetingsLayout = MeetingsLayout.Vertical,
    /** Today, "Narrow meeting titles" (D97). Diagonal by default, as before D97. */
    val meetingTitleFit: MeetingTitleFit = MeetingTitleFit.Diagonal,
    /** Today, "Topics open as". Not set: see [todayTopicsLayout]. */
    val todayTopicsView: TopicsView? = null,
    /** Today, "Hide when empty". Not set: see [todayHiddenWhenEmpty]. */
    val todayHideEmpty: Set<TodayHideableSection>? = null,
    /** Today, "Group tasks by". Due date by default. */
    val todayGroupBy: TodayGroupBy = TodayGroupBy.DueDate,
    /** Today, past meetings the Vertical card shows, 0 to [PAST_MEETINGS_MAX]. Null shows them all, the default. */
    val todayPastMeetings: Int? = null,
    /** Today, the status categories whose Topics show. Active and Paused by default (D59). */
    val todayTopicCategories: Set<StatusCategory> = DEFAULT_TODAY_TOPIC_CATEGORIES,
    /** Inbox, "Show completed tasks". On by default. */
    val inboxShowCompleted: Boolean = true,
    /** Inbox, "Show notes". On by default. */
    val inboxShowNotes: Boolean = true,
    /** Inbox, "Group by". None by default. */
    val inboxGroupBy: InboxGroupBy = InboxGroupBy.None,
    /** Topic Details, "Show completed tasks". On by default. */
    val topicShowCompleted: Boolean = true,
    /** Topic Details, "Hide when empty". None by default. */
    val topicHideEmpty: Set<TopicHideableSection> = emptySet(),
    /** Topic Details, "Group tasks by due date". Off by default. */
    val topicGroupByDue: Boolean = false,
    /** Jira, "Current sprint only". Not set: see [currentSprintOnly]. */
    val jiraCurrentSprintOnly: Boolean? = null,
    /** Pull requests, how "My pull requests" is grouped. By repository by default (D62). */
    val prsMineGroupBy: MyPrsGroupBy = MyPrsGroupBy.Repository,
    /** Pull requests, how "Review requested" is grouped. By repository by default (D62). */
    val prsReviewGroupBy: ReviewPrsGroupBy = ReviewPrsGroupBy.Repository,
    /** Today's desktop layout as saved (#29, D95). Not set: see [todayDesktopLayout]. */
    val todayLayout: TodayLayout? = null,
    /** Today's one-column order as saved, for windows 759 px wide or narrower. Not set: see [todayPhoneOrder]. */
    val todayPhoneLayout: List<TodayCard>? = null,
) {
    /** The Focus timer's durations. A value outside its limits reads as kept inside them. */
    val focusSettings: FocusSettings
        get() =
            FocusSettings(
                focusWorkMinutes.coerceIn(FocusSettings.WORK_MINUTES),
                focusBreakMinutes.coerceIn(FocusSettings.BREAK_MINUTES),
            )
}

/**
 * One document change. A [WorkspaceStore] applies a list of them together or not at all.
 *
 * `before` is the value the change was made from. When it is set, the store writes only the fields that differ from
 * it, so a change made at the same time on another device to a different field is kept. When it is null, every field
 * is written (a new document, or the seed).
 */
sealed interface WorkspaceWrite {
    /** Saves [topic]. [isNew] marks its first write, which also stores when it was created. */
    data class PutTopic(
        val topic: Topic,
        val isNew: Boolean = false,
        val before: Topic? = null,
    ) : WorkspaceWrite

    /** Saves [status]. Deleting a status is a [TopicStatus.deleted] write, so Undo can bring it back. */
    data class PutStatus(
        val status: TopicStatus,
        val before: TopicStatus? = null,
    ) : WorkspaceWrite

    data class PutPrefs(
        val prefs: WorkspacePrefs,
        val before: WorkspacePrefs? = null,
    ) : WorkspaceWrite

    /** Saves [task]. [isNew] marks its first write, which also stores when it was created. */
    data class PutTask(
        val task: Task,
        val isNew: Boolean = false,
        val before: Task? = null,
    ) : WorkspaceWrite

    /** Saves [reminder]. [isNew] marks its first write, which also stores when it was created. */
    data class PutReminder(
        val reminder: Reminder,
        val isNew: Boolean = false,
        val before: Reminder? = null,
    ) : WorkspaceWrite

    /**
     * Saves where [note] is filed: its Inbox flag, and whether it was discarded. [isNew] marks its first write, which
     * also stores when it was made. The title, preview, versions and Trash state belong to Note sync (NoteSyncRemote)
     * and are never written here.
     */
    data class PutNote(
        val note: NoteSummary,
        val isNew: Boolean = false,
        val before: NoteSummary? = null,
    ) : WorkspaceWrite

    /** Saves one Topic link of a Note. Unlinking writes [NoteTopicLink.linked] false; nothing is deleted. */
    data class PutNoteLink(
        val link: NoteTopicLink,
    ) : WorkspaceWrite

    /** Saves a record that Topics link to: ids and the canonical URL only (adr/ADR-013-jira-in-the-workspace.md). */
    data class PutExternalRef(
        val reference: ExternalReference,
    ) : WorkspaceWrite

    /** Saves one Topic link to an external record. Unlinking writes [TopicExternalLink.linked] false. */
    data class PutExternalLink(
        val link: TopicExternalLink,
    ) : WorkspaceWrite

    /**
     * Saves one Topic link to a meeting series (D78, adr/ADR-020-calendar-meetings-sync.md). Unlinking writes
     * [MeetingLink.linked] false; nothing is deleted. A null [MeetingLink.title] leaves the stored snapshot as it is.
     */
    data class PutMeetingLink(
        val link: MeetingLink,
    ) : WorkspaceWrite

    /**
     * Saves the owner's alternate title and join link of one meeting series (D100). With a [before] value, only the
     * fields that differ are written; a field set back to null is stored as null. Nothing is deleted.
     */
    data class PutMeetingAlternate(
        val alternate: MeetingAlternate,
        val before: MeetingAlternate? = null,
    ) : WorkspaceWrite

    /**
     * Records one Focus session in the account's history (adr/ADR-019-focus-timer.md). A session never changes once
     * made, and its id comes from its run, so sending it again (Retry, or two tabs) stores it once.
     */
    data class PutFocusSession(
        val session: FocusSession,
    ) : WorkspaceWrite

    /**
     * Deletes one Topic, Task, Reminder or Note forever (D43, adr/ADR-012-trash-and-search.md). The document stays
     * as a tombstone that no later write can change, so a stale offline edit from another device cannot bring it back.
     * The store leaves tombstones out of [WorkspaceData].
     */
    data class DeleteForever(
        val kind: TrashKind,
        val id: String,
        /** Shown when the server refuses the change. */
        val title: String,
    ) : WorkspaceWrite
}

/** What can go to Trash. Jira tickets and pull requests never do. */
enum class TrashKind {
    Topic,
    Task,
    Reminder,
    Note,
}

/** What this device knows of the workspace: its own changes included, acknowledged or not. */
data class WorkspaceData(
    /** Every stored status, deleted ones included. Empty before the workspace is seeded. */
    val statuses: List<TopicStatus> = emptyList(),
    val topics: List<Topic> = emptyList(),
    /** Every stored Task, those in Trash and discarded ones included. */
    val tasks: List<Task> = emptyList(),
    /** Every stored Reminder, those in Trash included. */
    val reminders: List<Reminder> = emptyList(),
    /** Every stored Note listing, those in Trash and discarded ones included. */
    val notes: List<NoteSummary> = emptyList(),
    /** Every stored Note to Topic link, unlinked ones included. */
    val noteLinks: List<NoteTopicLink> = emptyList(),
    /** Every stored record that Topics link to. */
    val externalRefs: List<ExternalReference> = emptyList(),
    /** Every stored Topic link to an external record, unlinked ones included. */
    val externalLinks: List<TopicExternalLink> = emptyList(),
    /** Every stored Topic link to a meeting series, unlinked ones included. */
    val meetingLinks: List<MeetingLink> = emptyList(),
    /** Every stored alternate title and join link of a meeting series (D100), empty ones included. */
    val meetingAlternates: List<MeetingAlternate> = emptyList(),
    /** Notes deleted forever. Their documents are tombstones; their saved versions may still need deleting. */
    val purgedNotes: List<NoteId> = emptyList(),
    /** The newest Focus sessions, newest first, at most [FOCUS_HISTORY_LIMIT][thread.domain.focus.FOCUS_HISTORY_LIMIT]. */
    val focusSessions: List<FocusSession> = emptyList(),
    val prefs: WorkspacePrefs = WorkspacePrefs(),
    /** True when the account has a stored preferences document. Until then [prefs] are the defaults. */
    val prefsStored: Boolean = false,
    /** True only when the server confirmed that the account has no status at all: a new workspace to seed. */
    val needsSeed: Boolean = false,
    /**
     * True when every collection in it comes from the server, not only from this device's cache. Changes made from what
     * the data holds, such as the conversion of Topic reminders (D54), wait for it.
     */
    val fromServer: Boolean = false,
    /** True while a change made on this device is not acknowledged by the server. */
    val pendingWrites: Boolean = false,
    /** Stored documents this version of the app cannot read. They are left alone and not shown. */
    val unreadable: Int = 0,
)

/**
 * A change the server refused (owner-review-queue Q14). It stays on this device, also after a reload, until the person
 * retries or discards it: a failed sync never drops content silently.
 *
 * [unconfirmed] marks a change sent before a reload whose answer the reload lost, and that the stored data does not
 * show: the server refused it, or another device changed the same field since ([unconfirmedChanges]).
 */
data class RefusedChange(
    val id: String,
    val writes: List<WorkspaceWrite>,
    val unconfirmed: Boolean = false,
)

/**
 * Topics, statuses, Tasks, Reminders, Note listings and links, and preferences of one signed-in account
 * (adr/ADR-009-workspace-entities-and-sync.md, adr/ADR-010-tasks-reminders-and-dates.md,
 * adr/ADR-011-notes-in-the-workspace.md). Platform
 * code implements it (Firestore on the web). The store's local copy is this device's only copy and outbox: there is
 * no second queue. The store also remembers each change on this device until the server answers, only so a refusal
 * is never lost.
 */
interface WorkspaceStore {
    /** The device's copy. Emits again on every local change and every server change or acknowledgement. */
    fun observe(): Flow<WorkspaceData>

    /**
     * Applies [writes] together to the device's copy at once, also offline, and sends them when the device is online.
     * Never waits for the server. Writes the server refuses are taken back out of the copy and listed in [refused].
     */
    fun write(writes: List<WorkspaceWrite>)

    /** Changes the server refused, oldest first. They stay listed, also after a reload, until [retry] or [discard]. */
    val refused: StateFlow<List<RefusedChange>>

    /** Sends [change] again as a new write. If the server refuses it again, it is listed again. */
    fun retry(change: RefusedChange)

    /** Forgets [change] on this device, once the person copied it or does not need it. */
    fun discard(change: RefusedChange)

    /** Lists [change] again: the Undo of [discard]. */
    fun keep(change: RefusedChange)
}

/**
 * The save states the workspace adds to the status line chip (adr/ADR-009-workspace-entities-and-sync.md). A refused
 * write or an unreadable document is Sync failed. A change the server has not acknowledged is Syncing, or Saved
 * locally, offline. Nothing pending adds no state, so the chip shows All synced when everything else agrees.
 */
fun workspaceSaveStates(
    data: WorkspaceData?,
    refusedCount: Int,
    online: Boolean,
): List<NoteSaveState> =
    buildList {
        if (refusedCount > 0 || (data?.unreadable ?: 0) > 0) add(NoteSaveState.SyncFailed)
        if (data?.pendingWrites == true) add(if (online) NoteSaveState.Syncing else NoteSaveState.Offline)
    }
