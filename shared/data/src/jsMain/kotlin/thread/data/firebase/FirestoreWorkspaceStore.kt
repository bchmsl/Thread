package thread.data.firebase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import thread.data.external.InMemoryKeyValueStore
import thread.data.external.KeyValueStore
import thread.data.local.newJsObject
import thread.data.workspace.WorkspaceOutbox
import thread.domain.account.AccountId
import thread.domain.note.LinkHref
import thread.domain.note.NoteId
import thread.domain.note.NoteSummary
import thread.domain.note.NoteTopicLink
import thread.domain.reminder.Reminder
import thread.domain.reminder.ReminderId
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.task.Deadline
import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.time.DEFAULT_WORKSPACE_ZONE
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.StatusCategory
import thread.domain.topic.StatusId
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import thread.domain.topic.TopicStatus
import thread.domain.workspace.CaptureKind
import thread.domain.workspace.ExternalProvider
import thread.domain.workspace.ExternalReference
import thread.domain.workspace.RefusedChange
import thread.domain.workspace.TopicExternalLink
import thread.domain.workspace.TopicsView
import thread.domain.workspace.TrashKind
import thread.domain.workspace.WorkspaceData
import thread.domain.workspace.WorkspacePrefs
import thread.domain.workspace.WorkspaceStore
import thread.domain.workspace.WorkspaceWrite
import kotlin.js.Date

/** Listening to the workspace failed, for example because the account was signed out. */
class WorkspaceReadException(
    message: String,
) : Exception(message)

/**
 * [WorkspaceStore] on Cloud Firestore for one account (adr/ADR-009-workspace-entities-and-sync.md,
 * adr/ADR-010-tasks-reminders-and-dates.md).
 *
 * - `users/{uid}/statuses/{statusId}`: `{name, category, sortOrder, deletedAt, updatedAt, schemaVersion: 1}`.
 * - `users/{uid}/topics/{topicId}`: `{name, color, statusId, goal, leftOff, nextStep, createdAt, updatedAt,
 *   schemaVersion: 1}`.
 * - `users/{uid}/tasks/{taskId}`: `{title, topicId, done, completedOn, plannedDay, deadline, inbox, createdAt,
 *   updatedAt, deletedAt, discarded, schemaVersion: 1}`. Days and deadlines are `YYYY-MM-DD` or `YYYY-MM-DDTHH:MM`
 *   strings in the workspace time zone, never instants.
 * - `users/{uid}/reminders/{reminderId}`: `{at, zone, text, targetKind, targetId, dismissedAt, createdAt, updatedAt,
 *   deletedAt, schemaVersion: 1}`.
 * - `users/{uid}/prefs/main`: `{topicsView, captureType, keepCompleted, timeZone, updatedAt, schemaVersion: 1}`.
 * - `users/{uid}/notes/{noteId}`: read as the Notes listing (adr/ADR-011-notes-in-the-workspace.md). This store writes
 *   only `{inbox, discarded, createdAt, updatedAt, schemaVersion}`; the title, preview, head and Trash state belong to
 *   [FirestoreNoteSyncRemote], and the merge keeps them.
 * - `users/{uid}/topicNoteLinks/{topicId}_{noteId}`: `{topicId, noteId, linked, updatedAt, schemaVersion: 1}`.
 *   Unlinking writes `linked: false`; the document stays.
 * - Topics also carry `deletedAt` while in Trash (adr/ADR-012-trash-and-search.md).
 * - `users/{uid}/externalRefs/{provider~instance~key}`: `{provider, instance, key, url, updatedAt, schemaVersion: 1}`.
 *   Ids and the canonical URL only, never a title or other content (adr/ADR-013-jira-in-the-workspace.md).
 * - `users/{uid}/topicExternalLinks/{topicId}_{refId}`: `{topicId, refId, linked, updatedAt, schemaVersion: 1}`.
 *   Unlinking writes `linked: false`; nothing is deleted and the external record is never changed.
 * - Delete forever replaces a Topic, Task, Reminder or Note document with the tombstone `{purgedAt, schemaVersion: 1}`.
 *   The rules refuse every later write to it, so a stale edit from another device cannot bring it back. Tombstones
 *   are left out of [WorkspaceData]; purged Notes are listed in [WorkspaceData.purgedNotes] so their versions can be
 *   deleted too.
 *
 * The Firestore persistent cache is the device copy and the outbox. A write goes into it at once, also offline, and
 * the snapshot metadata says whether the server acknowledged it. A refused write is dropped from the cache by the SDK.
 * [WorkspaceOutbox] records each change in [outboxStorage] until the server answers, so a refused one stays listed in
 * [refused], also after a reload, until the person retries or discards it. A change sent before a reload has no
 * answer on the next page: once the server has answered every pending write, the changes the stored data does not
 * show are listed as unconfirmed (owner-review-queue Q14).
 *
 * A write with a `before` value sets only the fields that changed (a merge), so edits to different fields of one
 * document on two devices both survive. Edits to the same field: the last one the server receives wins.
 *
 * [rejectWritesForTesting] is a test hook: when it returns true, writes carry a field the rules refuse.
 */
class FirestoreWorkspaceStore internal constructor(
    private val firestore: Firestore,
    private val accountId: AccountId,
    outboxStorage: KeyValueStore,
    scope: CoroutineScope,
    private val rejectWritesForTesting: () -> Boolean,
) : WorkspaceStore {
    /**
     * [outboxStorage] must be this account's device storage (IndexedDB) for refused changes to survive a reload. The
     * in-memory default is for tests.
     */
    constructor(
        backend: FirebaseBackend,
        accountId: AccountId,
        outboxStorage: KeyValueStore = InMemoryKeyValueStore(),
        scope: CoroutineScope = MainScope(),
        rejectWritesForTesting: () -> Boolean = { false },
    ) : this(backend.firestore, accountId, outboxStorage, scope, rejectWritesForTesting)

    private val outbox = WorkspaceOutbox(outboxStorage, scope, now = ::nowMillis)

    override val refused: StateFlow<List<RefusedChange>> = outbox.refused

    init {
        scope.launch { checkLeftovers() }
    }

    /** Lists the changes sent before a reload that the server's answer did not keep (see the class comment). */
    private suspend fun checkLeftovers() {
        val leftovers = outbox.load()
        if (leftovers.isEmpty()) return
        try {
            waitForPendingWrites(firestore).await()
            outbox.resolve(leftovers, observe().first())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Signed out, or the device copy failed: the leftovers stay recorded for the next page.
        }
    }

    override fun retry(change: RefusedChange) {
        write(change.writes)
        outbox.discard(change)
    }

    override fun discard(change: RefusedChange) = outbox.discard(change)

    override fun keep(change: RefusedChange) = outbox.keep(change)

    override fun observe(): Flow<WorkspaceData> =
        callbackFlow {
            var statuses: QuerySnapshot? = null
            var topics: QuerySnapshot? = null
            var tasks: QuerySnapshot? = null
            var reminders: QuerySnapshot? = null
            var notes: QuerySnapshot? = null
            var links: QuerySnapshot? = null
            var externalRefs: QuerySnapshot? = null
            var externalLinks: QuerySnapshot? = null
            var prefs: DocumentSnapshot? = null
            val emitWhenComplete = {
                val collections = listOf(statuses, topics, tasks, reminders, notes, links, externalRefs, externalLinks)
                val p = prefs
                if (p != null && collections.all { it != null }) {
                    val snapshots =
                        Snapshots(statuses!!, topics!!, tasks!!, reminders!!, notes!!, links!!, externalRefs!!, externalLinks!!, p)
                    trySend(toWorkspaceData(snapshots))
                }
            }
            val stop = { error: FirestoreError ->
                close(WorkspaceReadException("Listening failed (${error.code})"))
                Unit
            }
            val options = newJsObject()
            options.includeMetadataChanges = true
            val stopStatuses =
                onCollectionSnapshot(collection(firestore, USERS, accountId.value, STATUSES), options, {
                    statuses = it
                    emitWhenComplete()
                }, stop)
            val stopTopics =
                onCollectionSnapshot(collection(firestore, USERS, accountId.value, TOPICS), options, {
                    topics = it
                    emitWhenComplete()
                }, stop)
            val stopTasks =
                onCollectionSnapshot(collection(firestore, USERS, accountId.value, TASKS), options, {
                    tasks = it
                    emitWhenComplete()
                }, stop)
            val stopReminders =
                onCollectionSnapshot(collection(firestore, USERS, accountId.value, REMINDERS), options, {
                    reminders = it
                    emitWhenComplete()
                }, stop)
            val stopNotes =
                onCollectionSnapshot(collection(firestore, USERS, accountId.value, NOTES), options, {
                    notes = it
                    emitWhenComplete()
                }, stop)
            val stopLinks =
                onCollectionSnapshot(collection(firestore, USERS, accountId.value, LINKS), options, {
                    links = it
                    emitWhenComplete()
                }, stop)
            val stopExternalRefs =
                onCollectionSnapshot(collection(firestore, USERS, accountId.value, EXTERNAL_REFS), options, {
                    externalRefs = it
                    emitWhenComplete()
                }, stop)
            val stopExternalLinks =
                onCollectionSnapshot(collection(firestore, USERS, accountId.value, EXTERNAL_LINKS), options, {
                    externalLinks = it
                    emitWhenComplete()
                }, stop)
            val stopPrefs =
                onDocumentSnapshot(prefsRef(), options, {
                    prefs = it
                    emitWhenComplete()
                }, stop)
            awaitClose {
                stopStatuses()
                stopTopics()
                stopTasks()
                stopReminders()
                stopNotes()
                stopLinks()
                stopExternalRefs()
                stopExternalLinks()
                stopPrefs()
            }
        }.conflate()

    override fun write(writes: List<WorkspaceWrite>) {
        if (writes.isEmpty()) return
        val batch = writeBatch(firestore)
        val reject = rejectWritesForTesting()
        writes.forEach { write ->
            val data: dynamic =
                when (write) {
                    is WorkspaceWrite.PutStatus -> statusData(write.status, write.before)
                    is WorkspaceWrite.PutTopic -> topicData(write.topic, write.isNew, write.before)
                    is WorkspaceWrite.PutPrefs -> prefsData(write.prefs, write.before)
                    is WorkspaceWrite.PutTask -> taskData(write.task, write.isNew, write.before)
                    is WorkspaceWrite.PutReminder -> reminderData(write.reminder, write.isNew, write.before)
                    is WorkspaceWrite.PutNote -> noteData(write.note, write.isNew, write.before)
                    is WorkspaceWrite.PutNoteLink -> linkData(write.link)
                    is WorkspaceWrite.PutExternalRef -> externalRefData(write.reference)
                    is WorkspaceWrite.PutExternalLink -> externalLinkData(write.link)
                    is WorkspaceWrite.DeleteForever -> tombstone()
                }
            if (reject) data.rejectedForTesting = true
            // A tombstone replaces the whole document, so none of its content stays.
            if (write is WorkspaceWrite.DeleteForever) batch.set(reference(write), data) else batch.set(reference(write), data, merge())
        }
        // The cache has the writes now. The promise settles when the server answers, which waits while offline.
        val id = outbox.sent(writes)
        batch.commit().then({ outbox.accepted(id) }, { outbox.refused(id, writes) })
    }

    private fun reference(write: WorkspaceWrite): DocumentReference =
        when (write) {
            is WorkspaceWrite.PutStatus -> doc(firestore, USERS, accountId.value, STATUSES, write.status.id.value)
            is WorkspaceWrite.PutTopic -> doc(firestore, USERS, accountId.value, TOPICS, write.topic.id.value)
            is WorkspaceWrite.PutPrefs -> prefsRef()
            is WorkspaceWrite.PutTask -> doc(firestore, USERS, accountId.value, TASKS, write.task.id.value)
            is WorkspaceWrite.PutReminder -> doc(firestore, USERS, accountId.value, REMINDERS, write.reminder.id.value)
            is WorkspaceWrite.PutNote -> doc(firestore, USERS, accountId.value, NOTES, write.note.id.value)
            is WorkspaceWrite.PutNoteLink -> doc(firestore, USERS, accountId.value, LINKS, write.link.id)
            is WorkspaceWrite.PutExternalRef -> doc(firestore, USERS, accountId.value, EXTERNAL_REFS, write.reference.id)
            is WorkspaceWrite.PutExternalLink -> doc(firestore, USERS, accountId.value, EXTERNAL_LINKS, write.link.id)
            is WorkspaceWrite.DeleteForever -> {
                val collection =
                    when (write.kind) {
                        TrashKind.Topic -> TOPICS
                        TrashKind.Task -> TASKS
                        TrashKind.Reminder -> REMINDERS
                        TrashKind.Note -> NOTES
                    }
                doc(firestore, USERS, accountId.value, collection, write.id)
            }
        }

    private fun prefsRef() = doc(firestore, USERS, accountId.value, PREFS, PREFS_ID)

    // With a `before` value, only the fields that differ are written; the merge keeps the rest as stored.

    private fun statusData(
        status: TopicStatus,
        before: TopicStatus?,
    ): dynamic {
        val data = newJsObject()
        if (before == null || before.name != status.name) data.name = status.name
        if (before == null || before.category != status.category) data.category = status.category.id
        if (before == null || before.sortOrder != status.sortOrder) data.sortOrder = status.sortOrder
        if (before == null || before.deleted != status.deleted) data.deletedAt = if (status.deleted) serverTimestamp() else null
        data.updatedAt = serverTimestamp()
        data.schemaVersion = SCHEMA_VERSION
        return data
    }

    private fun topicData(
        topic: Topic,
        isNew: Boolean,
        before: Topic?,
    ): dynamic {
        val data = newJsObject()
        val base = before.takeUnless { isNew }
        if (base == null || base.name != topic.name) data.name = topic.name
        if (base == null || base.color != topic.color) data.color = topic.color.id
        if (base == null || base.statusId != topic.statusId) data.statusId = topic.statusId.value
        if (base == null || base.goal != topic.goal) data.goal = topic.goal
        if (base == null || base.leftOff != topic.leftOff) data.leftOff = topic.leftOff
        if (base == null || base.nextStep != topic.nextStep) data.nextStep = topic.nextStep
        if ((base == null && topic.deleted) || (base != null && base.deleted != topic.deleted)) {
            data.deletedAt = if (topic.deleted) serverTimestamp() else null
        }
        if (isNew) data.createdAt = serverTimestamp()
        data.updatedAt = serverTimestamp()
        data.schemaVersion = SCHEMA_VERSION
        return data
    }

    private fun prefsData(
        prefs: WorkspacePrefs,
        before: WorkspacePrefs?,
    ): dynamic {
        val data = newJsObject()
        if (before == null || before.topicsView != prefs.topicsView) data.topicsView = prefs.topicsView.id
        if (before == null || before.captureType != prefs.captureType) data.captureType = prefs.captureType.id
        if (before == null || before.keepCompleted != prefs.keepCompleted) data.keepCompleted = prefs.keepCompleted
        if (before == null || before.timeZone != prefs.timeZone) data.timeZone = prefs.timeZone
        data.updatedAt = serverTimestamp()
        data.schemaVersion = SCHEMA_VERSION
        return data
    }

    private fun taskData(
        task: Task,
        isNew: Boolean,
        before: Task?,
    ): dynamic {
        val data = newJsObject()
        val base = before.takeUnless { isNew }
        if (base == null || base.title != task.title) data.title = task.title
        if (base == null || base.topicId != task.topicId) data.topicId = task.topicId?.value
        if (base == null || base.done != task.done) data.done = task.done
        if (base == null || base.completedOn != task.completedOn) data.completedOn = task.completedOn?.toString()
        if (base == null || base.plannedDay != task.plannedDay) data.plannedDay = task.plannedDay?.toString()
        if (base == null || base.deadline != task.deadline) data.deadline = task.deadline?.toString()
        if (base == null || base.inbox != task.inbox) data.inbox = task.inbox
        if (base == null || base.deleted != task.deleted) data.deletedAt = if (task.deleted) serverTimestamp() else null
        if ((base == null && task.discarded) || (base != null && base.discarded != task.discarded)) data.discarded = task.discarded
        if (isNew) data.createdAt = serverTimestamp()
        data.updatedAt = serverTimestamp()
        data.schemaVersion = SCHEMA_VERSION
        return data
    }

    private fun reminderData(
        reminder: Reminder,
        isNew: Boolean,
        before: Reminder?,
    ): dynamic {
        val data = newJsObject()
        val base = before.takeUnless { isNew }
        if (base == null || base.at != reminder.at) data.at = reminder.at.toString()
        if (base == null || base.zone != reminder.zone) data.zone = reminder.zone
        if (base == null || base.text != reminder.text) data.text = reminder.text
        if (base == null || base.target != reminder.target) {
            data.targetKind = reminder.target.kind.id
            data.targetId = reminder.target.id
        }
        if (base == null || base.dismissed != reminder.dismissed) data.dismissedAt = if (reminder.dismissed) serverTimestamp() else null
        if (base == null || base.deleted != reminder.deleted) data.deletedAt = if (reminder.deleted) serverTimestamp() else null
        if (isNew) data.createdAt = serverTimestamp()
        data.updatedAt = serverTimestamp()
        data.schemaVersion = SCHEMA_VERSION
        return data
    }

    /** Only the filing fields. The title, preview, head and Trash state are the note sync's, and the merge keeps them. */
    private fun noteData(
        note: NoteSummary,
        isNew: Boolean,
        before: NoteSummary?,
    ): dynamic {
        val data = newJsObject()
        val base = before.takeUnless { isNew }
        if (base == null || base.inbox != note.inbox) data.inbox = note.inbox
        if ((base == null && note.discarded) || (base != null && base.discarded != note.discarded)) data.discarded = note.discarded
        if (isNew) data.createdAt = serverTimestamp()
        data.updatedAt = serverTimestamp()
        data.schemaVersion = SCHEMA_VERSION
        return data
    }

    private fun tombstone(): dynamic {
        val data = newJsObject()
        data.purgedAt = serverTimestamp()
        data.schemaVersion = SCHEMA_VERSION
        return data
    }

    private fun linkData(link: NoteTopicLink): dynamic {
        val data = newJsObject()
        data.topicId = link.topicId.value
        data.noteId = link.noteId.value
        data.linked = link.linked
        data.updatedAt = serverTimestamp()
        data.schemaVersion = SCHEMA_VERSION
        return data
    }

    /** Ids and the canonical URL only (owner-review-queue Q6). Titles stay in the device cache. */
    private fun externalRefData(reference: ExternalReference): dynamic {
        val data = newJsObject()
        data.provider = reference.provider.id
        data.instance = reference.instance
        data.key = reference.key
        data.url = reference.url.value
        data.updatedAt = serverTimestamp()
        data.schemaVersion = SCHEMA_VERSION
        return data
    }

    private fun externalLinkData(link: TopicExternalLink): dynamic {
        val data = newJsObject()
        data.topicId = link.topicId.value
        data.refId = link.referenceId
        data.linked = link.linked
        data.updatedAt = serverTimestamp()
        data.schemaVersion = SCHEMA_VERSION
        return data
    }

    private fun merge(): dynamic {
        val options = newJsObject()
        options.merge = true
        return options
    }

    private companion object {
        const val USERS = "users"
        const val STATUSES = "statuses"
        const val TOPICS = "topics"
        const val PREFS = "prefs"
        const val TASKS = "tasks"
        const val REMINDERS = "reminders"
        const val NOTES = "notes"
        const val LINKS = "topicNoteLinks"
        const val EXTERNAL_REFS = "externalRefs"
        const val EXTERNAL_LINKS = "topicExternalLinks"
        const val PREFS_ID = "main"
        const val SCHEMA_VERSION = 1
    }
}

private class Snapshots(
    val statuses: QuerySnapshot,
    val topics: QuerySnapshot,
    val tasks: QuerySnapshot,
    val reminders: QuerySnapshot,
    val notes: QuerySnapshot,
    val links: QuerySnapshot,
    val externalRefs: QuerySnapshot,
    val externalLinks: QuerySnapshot,
    val prefs: DocumentSnapshot,
)

private fun toWorkspaceData(snapshots: Snapshots): WorkspaceData {
    val statuses = snapshots.statuses
    val prefs = snapshots.prefs
    val decodedStatuses = statuses.docs.map(::decodeStatus)
    val decodedTopics = snapshots.topics.kept().map(::decodeTopic)
    val decodedTasks = snapshots.tasks.kept().map(::decodeTask)
    val decodedReminders = snapshots.reminders.kept().map(::decodeReminder)
    val decodedNotes = snapshots.notes.kept().map(::decodeNote)
    val decodedLinks = snapshots.links.docs.map(::decodeLink)
    val decodedRefs = snapshots.externalRefs.docs.map(::decodeExternalRef)
    val decodedExternalLinks = snapshots.externalLinks.docs.map(::decodeExternalLink)
    val all =
        listOf(
            statuses,
            snapshots.topics,
            snapshots.tasks,
            snapshots.reminders,
            snapshots.notes,
            snapshots.links,
            snapshots.externalRefs,
            snapshots.externalLinks,
        )
    val decoded =
        listOf(
            decodedStatuses,
            decodedTopics,
            decodedTasks,
            decodedReminders,
            decodedNotes,
            decodedLinks,
            decodedRefs,
            decodedExternalLinks,
        )
    return WorkspaceData(
        statuses = decodedStatuses.filterNotNull(),
        topics = decodedTopics.filterNotNull(),
        tasks = decodedTasks.filterNotNull(),
        reminders = decodedReminders.filterNotNull(),
        notes = decodedNotes.filterNotNull(),
        noteLinks = decodedLinks.filterNotNull(),
        externalRefs = decodedRefs.filterNotNull(),
        externalLinks = decodedExternalLinks.filterNotNull(),
        purgedNotes =
            snapshots.notes.docs
                .filter(::isTombstone)
                .map { NoteId(it.id) },
        prefs = decodePrefs(prefs),
        prefsStored = prefs.exists(),
        // Only the server can say that an account has no status at all. The cache may just not have them yet.
        needsSeed = !statuses.metadata.fromCache && statuses.docs.isEmpty(),
        pendingWrites = all.any { it.metadata.hasPendingWrites } || prefs.metadata.hasPendingWrites,
        unreadable = decoded.sumOf { list -> list.count { it == null } },
    )
}

/** The documents that are not tombstones. A tombstone is neither shown nor counted as unreadable. */
private fun QuerySnapshot.kept(): List<DocumentSnapshot> = docs.filterNot(::isTombstone)

/** True for a document deleted forever: `{purgedAt, schemaVersion}`. A pending tombstone counts at once. */
private fun isTombstone(snapshot: DocumentSnapshot): Boolean {
    val data: dynamic = snapshot.data(estimatedTimestamps())
    return data != null && data.purgedAt != null
}

/** The status in [snapshot], or null when this app cannot read it (another schema version, or damaged). */
private fun decodeStatus(snapshot: DocumentSnapshot): TopicStatus? {
    val data: dynamic = snapshot.data(estimatedTimestamps())
    if (data == null || data.schemaVersion != 1) return null
    val name = stringOrNull(data.name)?.takeIf { it.isNotBlank() } ?: return null
    val category = stringOrNull(data.category)?.let(StatusCategory::fromId) ?: return null
    val sortOrder: dynamic = data.sortOrder
    if (jsTypeOf(sortOrder) != "number") return null
    return TopicStatus(
        id = StatusId(snapshot.id),
        name = name,
        category = category,
        sortOrder = sortOrder.unsafeCast<Double>().toInt(),
        deleted = data.deletedAt != null,
    )
}

/** The Topic in [snapshot], or null when this app cannot read it. */
private fun decodeTopic(snapshot: DocumentSnapshot): Topic? {
    val data: dynamic = snapshot.data(estimatedTimestamps())
    if (data == null || data.schemaVersion != 1) return null
    val name = stringOrNull(data.name)?.takeIf { it.isNotBlank() } ?: return null
    val color = stringOrNull(data.color)?.let(TopicColor::fromId) ?: return null
    val statusId = stringOrNull(data.statusId)?.takeIf { it.isNotBlank() } ?: return null
    val createdAt: dynamic = data.createdAt
    return Topic(
        id = TopicId(snapshot.id),
        name = name,
        color = color,
        statusId = StatusId(statusId),
        goal = stringOrNull(data.goal).orEmpty(),
        leftOff = stringOrNull(data.leftOff).orEmpty(),
        nextStep = stringOrNull(data.nextStep).orEmpty(),
        createdAtMillis = if (createdAt == null) null else createdAt.unsafeCast<Timestamp>().toMillis().toLong(),
        deleted = data.deletedAt != null,
        deletedAtMillis = millisOrNull(data.deletedAt),
    )
}

/** The Task in [snapshot], or null when this app cannot read it. Days that do not parse make it unreadable. */
private fun decodeTask(snapshot: DocumentSnapshot): Task? {
    val data: dynamic = snapshot.data(estimatedTimestamps())
    if (data == null || data.schemaVersion != 1) return null
    val title = stringOrNull(data.title)?.takeIf { it.isNotBlank() } ?: return null
    val done = booleanOrNull(data.done) ?: return null
    val inbox = booleanOrNull(data.inbox) ?: return null
    val completedOn = optional(data.completedOn, LocalDay::parse) ?: return null
    val plannedDay = optional(data.plannedDay, LocalDay::parse) ?: return null
    val deadline = optional(data.deadline, Deadline::parse) ?: return null
    return Task(
        id = TaskId(snapshot.id),
        title = title,
        topicId = stringOrNull(data.topicId)?.takeIf { it.isNotBlank() }?.let(::TopicId),
        done = done,
        completedOn = completedOn.value,
        plannedDay = plannedDay.value,
        deadline = deadline.value,
        inbox = inbox,
        createdAtMillis = millisOrNull(data.createdAt),
        deleted = data.deletedAt != null,
        discarded = data.discarded == true,
        deletedAtMillis = millisOrNull(data.deletedAt),
    )
}

/** The Reminder in [snapshot], or null when this app cannot read it. */
private fun decodeReminder(snapshot: DocumentSnapshot): Reminder? {
    val data: dynamic = snapshot.data(estimatedTimestamps())
    if (data == null || data.schemaVersion != 1) return null
    val at = stringOrNull(data.at)?.let(WallTime::parse) ?: return null
    val zone = stringOrNull(data.zone)?.takeIf { it.isNotBlank() } ?: return null
    val text = stringOrNull(data.text)?.takeIf { it.isNotBlank() } ?: return null
    val kind = TargetKind.fromId(stringOrNull(data.targetKind)) ?: return null
    val targetId = stringOrNull(data.targetId)?.takeIf { it.isNotBlank() } ?: return null
    return Reminder(
        id = ReminderId(snapshot.id),
        at = at,
        zone = zone,
        text = text,
        target = ReminderTarget(kind, targetId),
        dismissed = data.dismissedAt != null,
        deleted = data.deletedAt != null,
        createdAtMillis = millisOrNull(data.createdAt),
        deletedAtMillis = millisOrNull(data.deletedAt),
    )
}

/**
 * The Note listing in [snapshot], or null when this app cannot read it. The note sync writes the title, preview and
 * head and the workspace writes the filing fields, so any of them may be missing for a moment; each has a default.
 */
private fun decodeNote(snapshot: DocumentSnapshot): NoteSummary? {
    val data: dynamic = snapshot.data(estimatedTimestamps())
    if (data == null || data.schemaVersion != 1) return null
    return NoteSummary(
        id = NoteId(snapshot.id),
        title = stringOrNull(data.title).orEmpty(),
        preview = stringOrNull(data.preview).orEmpty(),
        inbox = data.inbox == true,
        inTrash = data.deletedAt != null,
        discarded = data.discarded == true,
        createdAtMillis = millisOrNull(data.createdAt),
        updatedAtMillis = millisOrNull(data.updatedAt),
        pendingWrites = snapshot.metadata.hasPendingWrites,
        fromConflict = data.copiedFrom != null,
        deletedAtMillis = millisOrNull(data.deletedAt),
    )
}

/** The Topic link in [snapshot], or null when this app cannot read it or its id does not match its pair. */
private fun decodeLink(snapshot: DocumentSnapshot): NoteTopicLink? {
    val data: dynamic = snapshot.data(estimatedTimestamps())
    if (data == null || data.schemaVersion != 1) return null
    val topicId = stringOrNull(data.topicId)?.takeIf { it.isNotBlank() } ?: return null
    val noteId = stringOrNull(data.noteId)?.takeIf { it.isNotBlank() } ?: return null
    val linked = booleanOrNull(data.linked) ?: return null
    return NoteTopicLink(TopicId(topicId), NoteId(noteId), linked).takeIf { it.id == snapshot.id }
}

/** The external record in [snapshot], or null when this app cannot read it or its id does not match its fields. */
private fun decodeExternalRef(snapshot: DocumentSnapshot): ExternalReference? {
    val data: dynamic = snapshot.data(estimatedTimestamps())
    if (data == null || data.schemaVersion != 1) return null
    val provider = ExternalProvider.fromId(stringOrNull(data.provider)) ?: return null
    val instance = stringOrNull(data.instance)?.takeIf { it.isNotBlank() } ?: return null
    val key = stringOrNull(data.key)?.takeIf { it.isNotBlank() } ?: return null
    val url = stringOrNull(data.url)?.let(LinkHref::parse) ?: return null
    return ExternalReference(provider, instance, key, url).takeIf { it.id == snapshot.id }
}

/** The Topic link to an external record in [snapshot], or null when this app cannot read it or its id does not match. */
private fun decodeExternalLink(snapshot: DocumentSnapshot): TopicExternalLink? {
    val data: dynamic = snapshot.data(estimatedTimestamps())
    if (data == null || data.schemaVersion != 1) return null
    val topicId = stringOrNull(data.topicId)?.takeIf { it.isNotBlank() } ?: return null
    val refId = stringOrNull(data.refId)?.takeIf { it.isNotBlank() } ?: return null
    val linked = booleanOrNull(data.linked) ?: return null
    return TopicExternalLink(TopicId(topicId), refId, linked).takeIf { it.id == snapshot.id }
}

/** Missing or unknown values fall back to the defaults, field by field. */
private fun decodePrefs(snapshot: DocumentSnapshot): WorkspacePrefs {
    val data: dynamic = if (snapshot.exists()) snapshot.data(estimatedTimestamps()) else null
    if (data == null) return WorkspacePrefs()
    return WorkspacePrefs(
        topicsView = TopicsView.fromId(stringOrNull(data.topicsView)) ?: TopicsView.List,
        captureType = CaptureKind.fromId(stringOrNull(data.captureType)) ?: CaptureKind.Task,
        keepCompleted = booleanOrNull(data.keepCompleted) ?: true,
        timeZone = stringOrNull(data.timeZone)?.takeIf { it.isNotBlank() } ?: DEFAULT_WORKSPACE_ZONE,
    )
}

/** A nullable stored value: [Optional] of null when absent or null, null when present but unreadable. */
private class Optional<T>(
    val value: T?,
)

private fun <T> optional(
    value: dynamic,
    parse: (String) -> T?,
): Optional<T>? {
    if (value == null) return Optional(null)
    val text = stringOrNull(value) ?: return null
    return parse(text)?.let { Optional(it) }
}

private fun booleanOrNull(value: dynamic): Boolean? = if (jsTypeOf(value) == "boolean") value.unsafeCast<Boolean>() else null

private fun millisOrNull(value: dynamic): Long? = if (value == null) null else value.unsafeCast<Timestamp>().toMillis().toLong()

private fun stringOrNull(value: dynamic): String? = if (jsTypeOf(value) == "string") value.unsafeCast<String>() else null

/** Pending server timestamps read as the device's estimate instead of null, so order shows at once. */
private fun estimatedTimestamps(): dynamic {
    val options = newJsObject()
    options.serverTimestamps = "estimate"
    return options
}

private fun nowMillis(): Long = Date.now().toLong()
