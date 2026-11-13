package thread.data.firebase

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import thread.data.local.newJsObject
import thread.domain.account.AccountId
import thread.domain.topic.StatusCategory
import thread.domain.topic.StatusId
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import thread.domain.topic.TopicStatus
import thread.domain.workspace.CaptureKind
import thread.domain.workspace.TopicsView
import thread.domain.workspace.WorkspaceData
import thread.domain.workspace.WorkspacePrefs
import thread.domain.workspace.WorkspaceStore
import thread.domain.workspace.WorkspaceWrite

/** Listening to the workspace failed, for example because the account was signed out. */
class WorkspaceReadException(
    message: String,
) : Exception(message)

/**
 * [WorkspaceStore] on Cloud Firestore for one account (adr/ADR-009-workspace-entities-and-sync.md).
 *
 * - `users/{uid}/statuses/{statusId}`: `{name, category, sortOrder, deletedAt, updatedAt, schemaVersion: 1}`.
 * - `users/{uid}/topics/{topicId}`: `{name, color, statusId, goal, leftOff, nextStep, createdAt, updatedAt,
 *   schemaVersion: 1}`.
 * - `users/{uid}/prefs/main`: `{topicsView, captureType, updatedAt, schemaVersion: 1}`.
 *
 * The Firestore persistent cache is the device copy and the outbox. A write goes into it at once, also offline, and
 * the snapshot metadata says whether the server acknowledged it. A refused write is dropped from the cache by the SDK;
 * it is kept in [refused] so the app can say what was not saved.
 *
 * A write with a `before` value sets only the fields that changed (a merge), so edits to different fields of one
 * document on two devices both survive. Edits to the same field: the last one the server receives wins.
 *
 * [rejectWritesForTesting] is a test hook: when it returns true, writes carry a field the rules refuse.
 */
class FirestoreWorkspaceStore internal constructor(
    private val firestore: Firestore,
    private val accountId: AccountId,
    private val rejectWritesForTesting: () -> Boolean,
) : WorkspaceStore {
    constructor(
        backend: FirebaseBackend,
        accountId: AccountId,
        rejectWritesForTesting: () -> Boolean = { false },
    ) : this(backend.firestore, accountId, rejectWritesForTesting)

    private val refusedWrites = MutableStateFlow<List<WorkspaceWrite>>(emptyList())

    override val refused: StateFlow<List<WorkspaceWrite>> = refusedWrites.asStateFlow()

    override fun observe(): Flow<WorkspaceData> =
        callbackFlow {
            var statuses: QuerySnapshot? = null
            var topics: QuerySnapshot? = null
            var prefs: DocumentSnapshot? = null
            val emitWhenComplete = {
                val s = statuses
                val t = topics
                val p = prefs
                if (s != null && t != null && p != null) trySend(toWorkspaceData(s, t, p))
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
            val stopPrefs =
                onDocumentSnapshot(prefsRef(), options, {
                    prefs = it
                    emitWhenComplete()
                }, stop)
            awaitClose {
                stopStatuses()
                stopTopics()
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
                }
            if (reject) data.rejectedForTesting = true
            batch.set(reference(write), data, merge())
        }
        // The cache has the writes now. The promise settles when the server answers, which waits while offline.
        batch.commit().catch { refusedWrites.update { it + writes } }
    }

    private fun reference(write: WorkspaceWrite): DocumentReference =
        when (write) {
            is WorkspaceWrite.PutStatus -> doc(firestore, USERS, accountId.value, STATUSES, write.status.id.value)
            is WorkspaceWrite.PutTopic -> doc(firestore, USERS, accountId.value, TOPICS, write.topic.id.value)
            is WorkspaceWrite.PutPrefs -> prefsRef()
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
        const val PREFS_ID = "main"
        const val SCHEMA_VERSION = 1
    }
}

private fun toWorkspaceData(
    statuses: QuerySnapshot,
    topics: QuerySnapshot,
    prefs: DocumentSnapshot,
): WorkspaceData {
    val decodedStatuses = statuses.docs.map(::decodeStatus)
    val decodedTopics = topics.docs.map(::decodeTopic)
    return WorkspaceData(
        statuses = decodedStatuses.filterNotNull(),
        topics = decodedTopics.filterNotNull(),
        prefs = decodePrefs(prefs),
        prefsStored = prefs.exists(),
        // Only the server can say that an account has no status at all. The cache may just not have them yet.
        needsSeed = !statuses.metadata.fromCache && statuses.docs.isEmpty(),
        pendingWrites = statuses.metadata.hasPendingWrites || topics.metadata.hasPendingWrites || prefs.metadata.hasPendingWrites,
        unreadable = decodedStatuses.count { it == null } + decodedTopics.count { it == null },
    )
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
    )
}

/** Missing or unknown values fall back to the defaults, field by field. */
private fun decodePrefs(snapshot: DocumentSnapshot): WorkspacePrefs {
    val data: dynamic = if (snapshot.exists()) snapshot.data(estimatedTimestamps()) else null
    if (data == null) return WorkspacePrefs()
    return WorkspacePrefs(
        topicsView = TopicsView.fromId(stringOrNull(data.topicsView)) ?: TopicsView.List,
        captureType = CaptureKind.fromId(stringOrNull(data.captureType)) ?: CaptureKind.Task,
    )
}

private fun stringOrNull(value: dynamic): String? = if (jsTypeOf(value) == "string") value.unsafeCast<String>() else null

/** Pending server timestamps read as the device's estimate instead of null, so order shows at once. */
private fun estimatedTimestamps(): dynamic {
    val options = newJsObject()
    options.serverTimestamps = "estimate"
    return options
}
