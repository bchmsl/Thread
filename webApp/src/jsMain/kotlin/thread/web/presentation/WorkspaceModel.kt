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
import thread.domain.task.firstOpenTasks
import thread.domain.task.inboxTasks
import thread.domain.task.liveTasks
import thread.domain.task.newTask
import thread.domain.task.progressOf
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.StatusId
import thread.domain.topic.TOPIC_NAME_MAX_LENGTH
import thread.domain.topic.Topic
import thread.domain.topic.TopicId
import thread.domain.topic.TopicStatus
import thread.domain.topic.newTopic
import thread.domain.topic.topicsInOrder
import thread.domain.topic.visibleStatuses
import thread.domain.trash.TrashEntry
import thread.domain.trash.deleteForeverWrites
import thread.domain.trash.restoreReminder
import thread.domain.trash.restoreTask
import thread.domain.trash.restoreTopic
import thread.domain.trash.trashEntries
import thread.domain.trash.visibleWorkspace
import thread.domain.workspace.EditProblem
import thread.domain.workspace.EditResult
import thread.domain.workspace.ExternalProvider
import thread.domain.workspace.ExternalReference
import thread.domain.workspace.RefusedChange
import thread.domain.workspace.TrashKind
import thread.domain.workspace.WorkspaceData
import thread.domain.workspace.WorkspaceEdit
import thread.domain.workspace.WorkspacePrefs
import thread.domain.workspace.WorkspaceStore
import thread.domain.workspace.WorkspaceWrite
import thread.domain.workspace.copyNoteLinks
import thread.domain.workspace.createNoteWrites
import thread.domain.workspace.createReminderWrite
import thread.domain.workspace.createTaskWrite
import thread.domain.workspace.createTopicLinkedTo
import thread.domain.workspace.discardNote
import thread.domain.workspace.discardTask
import thread.domain.workspace.dismissReminder
import thread.domain.workspace.linkExternal
import thread.domain.workspace.linkedTopicIdsOf
import thread.domain.workspace.referencesOf
import thread.domain.workspace.savePrefs
import thread.domain.workspace.seedWrites
import thread.domain.workspace.setExternalLinks
import thread.domain.workspace.unlinkExternal
import thread.domain.workspace.workspaceSaveStates
import thread.domain.note.inboxNotes as inboxNotesOf
import thread.domain.trash.trashTopic as trashTopicEdit

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
 * adr/ADR-011-notes-in-the-workspace.md, adr/ADR-012-trash-and-search.md). A Note's text is not here: an open Note
 * syncs through its own NoteSyncer. State comes from [WorkspaceStore.observe]; screens change it only through writes, so
 * what they show is always what the device stored. Screens see [data]: a Topic in Trash is not in it, nor what went to
 * Trash with it. Only Trash and the Topic page read [stored].
 */
internal class WorkspaceModel(
    private val store: WorkspaceStore,
    private val newId: (prefix: String) -> String,
) {
    /** Everything the device stored, Topics in Trash included. Null until the first answer from the cache or the server. */
    var stored: WorkspaceData? by mutableStateOf(null)
        private set

    /** What the views show: [stored] without the Topics in Trash and what went to Trash with them. */
    val data: WorkspaceData?
        get() {
            val current = stored
            if (current !== visibleFrom) {
                visibleCache = current?.let(::visibleWorkspace)
                visibleFrom = current
            }
            return visibleCache
        }

    private var visibleFrom: WorkspaceData? = null
    private var visibleCache: WorkspaceData? = null

    var readFailed: Boolean by mutableStateOf(false)
        private set

    /** Changes the server refused, kept on this device until retried or discarded (owner-review-queue Q14). */
    var refused: List<RefusedChange> by mutableStateOf(emptyList())
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

    /** The stored Topic [id], also when it is in Trash. Null once it is deleted forever. */
    fun storedTopic(id: String): Topic? = stored?.topics?.firstOrNull { it.id.value == id }

    /** Every Task, Trash and undone creations included, but not those of a Topic in Trash: the input of the Task rules. */
    val storedTasks: List<Task> get() = data?.tasks.orEmpty()

    /** Tasks not in Trash. */
    val tasks: List<Task> get() = derived().tasks

    fun task(id: String): Task? = derived().tasksById[id]

    /** Reminders not in Trash whose Task, Topic or Note is still here. */
    val reminders: List<Reminder> get() = derived().reminders

    /** A Task's reminders, oldest first. */
    fun remindersOf(task: Task): List<Reminder> = derived().remindersByTask[task.id.value].orEmpty()

    val inbox: List<Task> get() = derived().inbox

    /** Every Note listing, Trash and undone creations included: the input of the Note edits. */
    val storedNotes: List<NoteSummary> get() = data?.notes.orEmpty()

    /** Every Topic link, unlinked ones included, but not those to a Topic in Trash. Edits leave those links alone. */
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

    /** The Task a Topic row shows as "Next": its oldest open Task, the first one in Topic Details (D52). */
    fun nextTask(topicId: TopicId): Task? = derived().nextTasks[topicId]

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
        val nextTasks = firstOpenTasks(data?.tasks.orEmpty())
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
                    stored = next
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
        val slot = maxOf(stored?.topics?.size ?: 0, lastColorSlot + 1)
        val topic =
            newTopic(TopicId(newId("t-")), name, statuses, slot)
                ?: return TopicCreation.Refused(if (name.isBlank()) EditProblem.BlankName else EditProblem.NameTooLong)
        lastColorSlot = slot
        store.write(listOf(WorkspaceWrite.PutTopic(topic, isNew = true)))
        return TopicCreation.Created(topic)
    }

    /**
     * v3 "Create Topic from ticket": a Topic named after the ticket (cut to the name limit), "Created from KEY." in its
     * Additional information (D52), linked to [reference] in the same write.
     */
    fun createTopicLinkedTo(
        title: String,
        reference: ExternalReference,
    ): TopicCreation {
        val current = data
        if (current == null || statuses.isEmpty()) return TopicCreation.Refused(EditProblem.NotReady)
        val slot = maxOf(stored?.topics?.size ?: 0, lastColorSlot + 1)
        val topic =
            newTopic(TopicId(newId("t-")), title.trim().take(TOPIC_NAME_MAX_LENGTH), statuses, slot)
                ?.copy(info = "Created from ${recordLabel(reference)}.")
                ?: return TopicCreation.Refused(EditProblem.BlankName)
        lastColorSlot = slot
        store.write(createTopicLinkedTo(topic, reference, current))
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

    /** Links the Note made from a conflict to the Topics of the Note it came from (D41), those in Trash too. */
    fun copyLinks(
        from: NoteId,
        to: NoteId,
    ) {
        copyNoteLinks(from, to, stored?.noteLinks.orEmpty()).takeIf { it.isNotEmpty() }?.let(store::write)
    }

    /** The records of [provider] linked to [topicId], in link order. Nothing while the Topic is in Trash. */
    fun externalsOf(
        topicId: TopicId,
        provider: ExternalProvider,
    ): List<ExternalReference> = data?.let { referencesOf(topicId, provider, it) }.orEmpty()

    /** The Topics [referenceId] is linked to, in Topic order. Topics in Trash are left out. */
    fun topicsLinkedTo(referenceId: String): List<Topic> {
        val ids = linkedTopicIdsOf(referenceId, data?.externalLinks.orEmpty()).toSet()
        return if (ids.isEmpty()) emptyList() else topics.filter { it.id in ids }
    }

    /**
     * Makes [topicIds] the Topics of [reference] (v3 "Link to Topic"). Links to Topics in Trash stay as they are.
     * Returns the edit for Undo, or null when nothing changed.
     */
    fun setTopicsOf(
        reference: ExternalReference,
        topicIds: List<TopicId>,
    ): WorkspaceEdit? = data?.let { apply(setExternalLinks(reference, topicIds, it)) }

    fun linkToTopic(
        reference: ExternalReference,
        topicId: TopicId,
    ): WorkspaceEdit? = data?.let { apply(linkExternal(reference, topicId, it)) }

    fun unlinkFromTopic(
        reference: ExternalReference,
        topicId: TopicId,
    ): WorkspaceEdit? = data?.let { apply(unlinkExternal(reference, topicId, it)) }

    /** Everything in Trash, newest deletion first. */
    val trash: List<TrashEntry>
        get() {
            val current = stored
            if (current !== trashFrom) {
                trashCache = current?.let(::trashEntries).orEmpty()
                trashFrom = current
            }
            return trashCache
        }

    private var trashFrom: WorkspaceData? = null
    private var trashCache: List<TrashEntry> = emptyList()

    /** Moves [topic] to Trash with its Tasks, Reminders and Note links. Returns the edit, for Undo. */
    fun trashTopic(topic: Topic): WorkspaceEdit? = apply(trashTopicEdit(topic))

    /**
     * Restores a Topic, Task or Reminder from Trash. Returns the edit, or null when nothing changed. A Note is restored
     * through its note sync instead, so [entry] must not be a [TrashEntry.NoteEntry].
     */
    fun restore(entry: TrashEntry): WorkspaceEdit? =
        when (entry) {
            is TrashEntry.TopicEntry -> apply(restoreTopic(entry.topic, storedStatuses))
            is TrashEntry.TaskEntry -> stored?.let { apply(restoreTask(entry.task, it)) }
            is TrashEntry.ReminderEntry -> apply(restoreReminder(entry.reminder))
            is TrashEntry.NoteEntry -> error("A Note is restored through its note sync")
        }

    /** How many reminders Delete forever takes with [entry]. */
    fun remindersGoingWith(entry: TrashEntry): Int =
        stored
            ?.let { deleteForeverWrites(entry, it) }
            .orEmpty()
            .count { it is WorkspaceWrite.DeleteForever && it.kind == TrashKind.Reminder }

    /** Deletes [entry] forever, with what went to Trash with it (D43). There is no Undo. */
    fun deleteForever(entry: TrashEntry) {
        val current = stored ?: return
        store.write(deleteForeverWrites(entry, current))
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

    /** Sync panel Retry: sends a refused change again. */
    fun retryRefused(change: RefusedChange) = store.retry(change)

    /** Sync panel Discard: forgets a refused change on this device. */
    fun discardRefused(change: RefusedChange) = store.discard(change)

    /** The Undo of [discardRefused]. */
    fun keepRefused(change: RefusedChange) = store.keep(change)

    /** What the status line chip and the sync panel show for the workspace. */
    fun syncReport(online: Boolean): SyncReport {
        val current = stored
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

/** How a "where I left off" line names a record: the Jira key, or `owner/repo#number` for a pull request. */
internal fun recordLabel(reference: ExternalReference): String =
    if (reference.provider == ExternalProvider.GitHub) "${reference.instance}#${reference.key}" else reference.key
