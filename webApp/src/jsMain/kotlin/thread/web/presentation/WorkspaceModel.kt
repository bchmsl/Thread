package thread.web.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import thread.domain.note.NoteId
import thread.domain.note.NoteSaveState
import thread.domain.note.NoteSummary
import thread.domain.note.NoteTopicLink
import thread.domain.note.UNTITLED_NOTE
import thread.domain.note.linkedTopicIds
import thread.domain.note.liveNotes
import thread.domain.note.newNote
import thread.domain.note.notesForTopic
import thread.domain.note.notesInOrder
import thread.domain.reminder.Reminder
import thread.domain.reminder.ReminderId
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.reminder.liveReminders
import thread.domain.reminder.newReminder
import thread.domain.task.TASK_TITLE_MAX_LENGTH
import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.task.TaskProgress
import thread.domain.task.inboxTasks
import thread.domain.task.liveTasks
import thread.domain.task.newTask
import thread.domain.task.progressOf
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.StatusId
import thread.domain.topic.Topic
import thread.domain.topic.TopicId
import thread.domain.topic.TopicStatus
import thread.domain.topic.newTopic
import thread.domain.topic.topicsInOrder
import thread.domain.topic.visibleStatuses
import thread.domain.workspace.EditProblem
import thread.domain.workspace.EditResult
import thread.domain.workspace.WorkspaceData
import thread.domain.workspace.WorkspaceEdit
import thread.domain.workspace.WorkspacePrefs
import thread.domain.workspace.WorkspaceStore
import thread.domain.workspace.WorkspaceWrite
import thread.domain.workspace.copyNoteLinks
import thread.domain.workspace.createNoteWrites
import thread.domain.workspace.createReminderWrite
import thread.domain.workspace.createTaskWrite
import thread.domain.workspace.discardNote
import thread.domain.workspace.discardTask
import thread.domain.workspace.dismissReminder
import thread.domain.workspace.savePrefs
import thread.domain.workspace.seedWrites
import thread.domain.workspace.workspaceSaveStates
import thread.web.presentation.notes.notePlace
import thread.web.presentation.topics.topicPlace
import thread.domain.note.inboxNotes as inboxNotesOf

internal sealed interface TopicCreation {
    data class Created(
        val topic: Topic,
    ) : TopicCreation

    data class Refused(
        val problem: EditProblem,
    ) : TopicCreation
}

internal sealed interface TaskCreation {
    data class Created(
        val task: Task,
    ) : TaskCreation

    data class Refused(
        val problem: EditProblem,
    ) : TaskCreation
}

/**
 * The signed-in account's Topics, statuses, Tasks, Reminders, Note listings and preferences for the screens
 * (adr/ADR-009-workspace-entities-and-sync.md, adr/ADR-010-tasks-reminders-and-dates.md,
 * adr/ADR-011-notes-in-the-workspace.md). A Note's text is not here: an open Note syncs through its own NoteSyncer.
 * State comes from [WorkspaceStore.observe]; screens change it only through writes, so what they show is always what
 * the device stored.
 */
internal class WorkspaceModel(
    private val store: WorkspaceStore,
    private val newId: (prefix: String) -> String,
) {
    /** Null until the first answer from the device cache or the server. */
    var data: WorkspaceData? by mutableStateOf(null)
        private set

    var readFailed: Boolean by mutableStateOf(false)
        private set

    var refused: List<WorkspaceWrite> by mutableStateOf(emptyList())
        private set

    val loaded: Boolean get() = data != null

    /** Every stored status, deleted ones included: the input of the status edits. */
    val storedStatuses: List<TopicStatus> get() = data?.statuses.orEmpty()

    /** The statuses in workspace order. */
    val statuses: List<TopicStatus> get() = visibleStatuses(storedStatuses)

    /** The Topics in workspace order: by status, then oldest first. */
    val topics: List<Topic> get() = topicsInOrder(data?.topics.orEmpty(), statuses)

    val prefs: WorkspacePrefs get() = data?.prefs ?: WorkspacePrefs()

    fun topic(id: String): Topic? = data?.topics?.firstOrNull { it.id.value == id }

    /** Every stored Task, Trash and undone creations included: the input of the Task rules. */
    val storedTasks: List<Task> get() = data?.tasks.orEmpty()

    /** Tasks not in Trash. */
    val tasks: List<Task> get() = derived().tasks

    fun task(id: String): Task? = derived().tasksById[id]

    /** Reminders not in Trash whose Task, Topic or Note is still here. */
    val reminders: List<Reminder> get() = derived().reminders

    /** A Task's reminders, oldest first. */
    fun remindersOf(task: Task): List<Reminder> = derived().remindersByTask[task.id.value].orEmpty()

    val inbox: List<Task> get() = derived().inbox

    /** Every stored Note listing, Trash and undone creations included: the input of the Note edits. */
    val storedNotes: List<NoteSummary> get() = data?.notes.orEmpty()

    /** Every stored Topic link, unlinked ones included. */
    val noteLinks: List<NoteTopicLink> get() = data?.noteLinks.orEmpty()

    /** Notes not in Trash, newest change first. */
    val notes: List<NoteSummary> get() = derived().notes

    fun note(id: String): NoteSummary? = derived().notesById[id]

    /** The Topics [noteId] is linked to that still exist, in link order. */
    fun topicsOf(noteId: NoteId): List<Topic> = derived().topicsOf(noteId)

    /** The Notes linked to [topicId], newest change first. */
    fun notesFor(topicId: TopicId): List<NoteSummary> = notesForTopic(topicId, storedNotes, noteLinks)

    /** Notes waiting in Inbox, newest first. */
    val inboxNotes: List<NoteSummary> get() = derived().inboxNotes

    /** Tasks and Notes waiting in Inbox. */
    val inboxCount: Int get() = derived().inbox.size + derived().inboxNotes.size

    fun progress(topicId: TopicId): TaskProgress = derived().progress.getOrPut(topicId) { progressOf(topicId, storedTasks) }

    /** The lists screens read many times per frame, worked out once per store snapshot. */
    private class Derived(
        data: WorkspaceData?,
    ) {
        val tasks = liveTasks(data?.tasks.orEmpty())
        val tasksById = tasks.associateBy { it.id.value }
        val topicIds =
            data
                ?.topics
                .orEmpty()
                .map { it.id.value }
                .toSet()
        val notes = notesInOrder(liveNotes(data?.notes.orEmpty()))
        val notesById = notes.associateBy { it.id.value }
        val inboxNotes = inboxNotesOf(data?.notes.orEmpty())

        // A Note's Topics show in the order Topics have everywhere else, not in link order.
        private val orderedTopics = topicsInOrder(data?.topics.orEmpty(), visibleStatuses(data?.statuses.orEmpty()))
        private val links = data?.noteLinks.orEmpty()
        private val topicsByNote = mutableMapOf<NoteId, List<Topic>>()

        fun topicsOf(noteId: NoteId): List<Topic> =
            topicsByNote.getOrPut(noteId) {
                val linked = linkedTopicIds(noteId, links).toSet()
                orderedTopics.filter { it.id in linked }
            }

        val reminders =
            liveReminders(data?.reminders.orEmpty()) { target ->
                when (target.kind) {
                    TargetKind.Task -> target.id in tasksById
                    TargetKind.Topic -> target.id in topicIds
                    TargetKind.Note -> target.id in notesById
                }
            }
        val remindersByTask = reminders.filter { it.target.kind == TargetKind.Task }.groupBy { it.target.id }
        val inbox = inboxTasks(data?.tasks.orEmpty())
        val progress = mutableMapOf<TopicId, TaskProgress>()
    }

    private var derivedFrom: WorkspaceData? = null
    private var derivedCache = Derived(null)

    private fun derived(): Derived {
        val current = data
        if (current !== derivedFrom) {
            derivedCache = Derived(current)
            derivedFrom = current
        }
        return derivedCache
    }

    /** Follows the store until cancelled. Seeds the four default statuses once the server says there are none. */
    suspend fun run() {
        try {
            coroutineScope {
                launch { store.refused.collect { refused = it } }
                var seeded = false
                store.observe().collect { next ->
                    data = next
                    if (next.needsSeed && !seeded) {
                        seeded = true
                        store.write(seedWrites())
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            readFailed = true
        }
    }

    /** Writes [result] when it changes something. Returns the edit, for Undo. */
    fun apply(result: EditResult): WorkspaceEdit? {
        val edit = (result as? EditResult.Changed)?.edit ?: return null
        store.write(edit.writes)
        return edit
    }

    fun undo(edit: WorkspaceEdit) {
        store.write(edit.undo)
    }

    /** The color slot of the last Topic made here. Quick captures follow each other before the store shows them. */
    private var lastColorSlot = -1

    /** Creates a Topic named [name] in the first Planned status, or says why it cannot. */
    fun createTopic(name: String): TopicCreation {
        if (statuses.isEmpty()) return TopicCreation.Refused(EditProblem.NotReady)
        val slot = maxOf(data?.topics?.size ?: 0, lastColorSlot + 1)
        val topic =
            newTopic(TopicId(newId("t-")), name, statuses, slot)
                ?: return TopicCreation.Refused(if (name.isBlank()) EditProblem.BlankName else EditProblem.NameTooLong)
        lastColorSlot = slot
        store.write(listOf(WorkspaceWrite.PutTopic(topic, isNew = true)))
        return TopicCreation.Created(topic)
    }

    fun newStatusId(): StatusId = StatusId(newId("s-"))

    /** Creates a Task. Without a Topic it waits in Inbox (D38: also when it is planned for today). */
    fun createTask(
        title: String,
        topicId: TopicId?,
        plannedDay: LocalDay? = null,
    ): TaskCreation {
        if (!loaded) return TaskCreation.Refused(EditProblem.NotReady)
        val task =
            newTask(TaskId(newId("k-")), title, topicId, plannedDay)
                ?: return TaskCreation.Refused(if (title.isBlank()) EditProblem.BlankName else EditProblem.NameTooLong)
        store.write(listOf(createTaskWrite(task)))
        return TaskCreation.Created(task)
    }

    /** Takes back a Task made a moment ago (the Undo of its toast). The document stays, hidden even from Trash. */
    fun discard(task: Task) {
        val current = storedTasks.firstOrNull { it.id == task.id } ?: task
        store.write(discardTask(current))
    }

    /**
     * Stores a new Note's listing (D39: without [topicId] it waits in Inbox; with one it is linked there). Its text
     * is written by the note sync: the first revision, when there is a title or the first edit.
     */
    fun createNote(topicId: TopicId?): NoteSummary? {
        if (!loaded) return null
        val note = newNote(NoteId(newId("n-")), topicId)
        madeHere += note.id.value
        store.write(createNoteWrites(note, topicId))
        return note
    }

    /** Ids of the Notes made on this device while the app is open. */
    private val madeHere = mutableStateListOf<String>()

    /**
     * True for a Note made here since the app opened. It can be opened before the store shows its listing, and its
     * page opens with the title ready for typing.
     */
    fun isMadeHere(id: NoteId): Boolean = id.value in madeHere

    /** Takes back a Note made a moment ago (the Undo of its toast). The documents stay, hidden even from Trash. */
    fun discard(note: NoteSummary) {
        val current = storedNotes.firstOrNull { it.id == note.id } ?: note
        discardNote(current).takeIf { it.isNotEmpty() }?.let(store::write)
    }

    /** Links the Note made from a conflict to the Topics of the Note it came from (D41). */
    fun copyLinks(
        from: NoteId,
        to: NoteId,
    ) {
        copyNoteLinks(from, to, noteLinks).takeIf { it.isNotEmpty() }?.let(store::write)
    }

    /** Adds a reminder, or returns null when its text is too long. */
    fun createReminder(
        at: WallTime,
        text: String,
        target: ReminderTarget,
    ): Reminder? {
        val reminder = newReminder(ReminderId(newId("r-")), at, prefs.timeZone, text, target) ?: return null
        store.write(listOf(createReminderWrite(reminder)))
        return reminder
    }

    fun dismiss(reminder: Reminder) {
        dismissReminder(reminder).takeIf { it.isNotEmpty() }?.let(store::write)
    }

    fun setPrefs(next: WorkspacePrefs) {
        savePrefs(data, next)?.let { store.write(listOf(it)) }
    }

    /** What the status line chip and the sync panel show for the workspace. */
    fun syncReport(online: Boolean): SyncReport {
        val current = data
        val states = workspaceSaveStates(current, refused.size, online) + if (readFailed) listOf(NoteSaveState.SyncFailed) else emptyList()
        val problems =
            buildList {
                if (readFailed) {
                    add(
                        SyncProblem("Your workspace could not load. Reload the page to try again.", NoteSaveState.SyncFailed, null),
                    )
                }
                refused.forEach { add(refusedProblem(it)) }
                val unreadable = current?.unreadable ?: 0
                if (unreadable > 0) {
                    val what = if (unreadable == 1) "1 saved item" else "$unreadable saved items"
                    add(
                        SyncProblem(
                            "$what could not be read. A newer version of Thread may have saved them.",
                            NoteSaveState.SyncFailed,
                            null,
                        ),
                    )
                }
            }
        return SyncReport(states, problems)
    }

    private fun refusedProblem(write: WorkspaceWrite): SyncProblem =
        when (write) {
            is WorkspaceWrite.PutTopic ->
                SyncProblem(
                    "Topic “${write.topic.name}” was not saved. The server refused the change.",
                    NoteSaveState.SyncFailed,
                    topicPlace(write.topic),
                )
            is WorkspaceWrite.PutStatus ->
                SyncProblem(
                    "Status “${write.status.name}” was not saved. The server refused the change.",
                    NoteSaveState.SyncFailed,
                    Place.of("settings", "statuses"),
                )
            is WorkspaceWrite.PutPrefs ->
                SyncProblem(
                    "Preferences were not saved. The server refused the change.",
                    NoteSaveState.SyncFailed,
                    Place.of("settings", "prefs"),
                )
            is WorkspaceWrite.PutTask ->
                SyncProblem(
                    "Task “${write.task.title}” was not saved. The server refused the change.",
                    NoteSaveState.SyncFailed,
                    if (write.isNew) null else taskPlace(View.Today.place, write.task.id),
                )
            is WorkspaceWrite.PutReminder ->
                SyncProblem(
                    "Reminder “${write.reminder.text}” was not saved. The server refused the change.",
                    NoteSaveState.SyncFailed,
                    null,
                )
            is WorkspaceWrite.PutNote -> {
                val title = note(write.note.id.value)?.title?.ifBlank { null } ?: UNTITLED_NOTE
                SyncProblem(
                    "Where “$title” is filed was not saved. The server refused the change.",
                    NoteSaveState.SyncFailed,
                    if (write.isNew) null else notePlace(write.note.id),
                )
            }
            is WorkspaceWrite.PutNoteLink -> {
                val title = note(write.link.noteId.value)?.title?.ifBlank { null } ?: UNTITLED_NOTE
                val topic = topic(write.link.topicId.value)?.name ?: "a Topic"
                SyncProblem(
                    "The link between “$title” and $topic was not saved. The server refused the change.",
                    NoteSaveState.SyncFailed,
                    notePlace(write.link.noteId),
                )
            }
        }
}

/** [base] with the Task sheet open on [id] (v3 `?task=`). */
internal fun taskPlace(
    base: Place,
    id: TaskId,
): Place = base.with(TASK_PARAM, id.value)

internal const val TASK_PARAM = "task"

internal fun taskProblemText(problem: EditProblem): String =
    when (problem) {
        EditProblem.BlankName -> "A task needs a title"
        EditProblem.NameTooLong -> "A task title can have at most $TASK_TITLE_MAX_LENGTH characters"
        EditProblem.NotReady -> "Your workspace is still loading. Try again in a moment."
        else -> "That change could not be made"
    }
