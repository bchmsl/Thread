package thread.domain.note

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The local save state of one Note (02-ux-design.md, "Note editor"). Sync states come with sync. */
enum class LocalSaveStatus {
    /** The newest edit is not durably stored yet. */
    Saving,

    /** The newest edit is durably stored on this device. */
    Saved,

    /** The newest write attempt failed, and nothing newer is stored. */
    NeedsAttention,
}

/**
 * Saves the newest draft of one Note to [store]: after a pause in editing ([debounce]), at least every [maxWait]
 * while editing goes on, and at once on [flush] (page hidden or closed, route left).
 *
 * [status] never claims more than the store confirmed. It is [LocalSaveStatus.Saved] only after the store durably
 * committed the newest edit. After a failed write it stays [LocalSaveStatus.NeedsAttention] until a newer write
 * succeeds, even while later attempts are running.
 *
 * Each write stores the whole draft, so a successful newer write makes any older failure irrelevant.
 *
 * Not thread-safe: call it from one thread, with a [scope] on a single-threaded dispatcher (the UI thread).
 */
class DraftAutosaver(
    private val store: LocalDraftStore,
    private val scope: CoroutineScope,
    initial: LocalDraft,
    initialIsStored: Boolean,
    private val debounce: Duration = DEFAULT_DEBOUNCE,
    private val maxWait: Duration = DEFAULT_MAX_WAIT,
) {
    private var latest: LocalDraft = initial

    // Revisions count edits. 0 is the initial content.
    private var editRevision: Long = if (initialIsStored) 0 else 1
    private var issuedRevision: Long = 0
    private var durableRevision: Long = 0
    private var failedRevision: Long = 0

    private var debounceJob: Job? = null
    private var maxWaitJob: Job? = null

    private val mutableStatus = MutableStateFlow(computeStatus())
    val status: StateFlow<LocalSaveStatus> = mutableStatus.asStateFlow()

    /** The newest content, stored or not. */
    val current: LocalDraft get() = latest

    init {
        if (!initialIsStored) schedule()
    }

    /** Records a new version of the draft and schedules a write. Content equal to the newest version is ignored. */
    fun edit(draft: LocalDraft) {
        require(draft.noteId == latest.noteId) { "This autosaver saves note ${latest.noteId}" }
        if (draft == latest) return
        latest = draft
        editRevision++
        schedule()
        publish()
    }

    /** Starts writing the newest content now if it was not handed to the store yet. */
    fun flush() {
        cancelTimers()
        if (issuedRevision < editRevision) issue()
    }

    /** Writes the newest content again if it is not durably stored, for example after a failure. */
    fun retry() {
        cancelTimers()
        if (durableRevision < editRevision) issue()
    }

    /**
     * Records that the newest content now comes from [revisionId] (sync wrote or adopted that revision). This is not
     * an edit: the content and [status] stay as they are. A write that is still waiting stores the new base with it.
     * When nothing is waiting, the base is written now. A failure of that write changes nothing: the content was
     * stored before, and the next start finds the right base again by comparing content
     * (adr/ADR-004-note-revisions-and-conflicts.md).
     */
    fun rebase(revisionId: RevisionId?) {
        if (latest.baseRevisionId == revisionId) return
        latest = latest.copy(baseRevisionId = revisionId)
        if (issuedRevision < editRevision) return
        val revision = editRevision
        val draft = latest
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                store.write(draft)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return@launch
            }
            // The write holds the newest content too, so it also stores it after an earlier failure.
            durableRevision = maxOf(durableRevision, revision)
            publish()
        }
    }

    private fun schedule() {
        debounceJob?.cancel()
        debounceJob =
            scope.launch {
                delay(debounce)
                flush()
            }
        if (maxWaitJob == null) {
            maxWaitJob =
                scope.launch {
                    delay(maxWait)
                    flush()
                }
        }
    }

    private fun cancelTimers() {
        debounceJob?.cancel()
        maxWaitJob?.cancel()
        debounceJob = null
        maxWaitJob = null
    }

    private fun issue() {
        val revision = editRevision
        val draft = latest
        issuedRevision = revision
        publish()
        // UNDISPATCHED: the store starts its write before this function returns, also inside a pagehide handler.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val stored =
                try {
                    store.write(draft)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Any failure, expected or not, means the edit is not known to be stored.
                    false
                }
            if (stored) {
                durableRevision = maxOf(durableRevision, revision)
            } else {
                failedRevision = maxOf(failedRevision, revision)
            }
            publish()
        }
    }

    private fun publish() {
        mutableStatus.value = computeStatus()
    }

    private fun computeStatus(): LocalSaveStatus =
        when {
            failedRevision > durableRevision -> LocalSaveStatus.NeedsAttention
            editRevision > durableRevision -> LocalSaveStatus.Saving
            else -> LocalSaveStatus.Saved
        }

    companion object {
        val DEFAULT_DEBOUNCE: Duration = 300.milliseconds
        val DEFAULT_MAX_WAIT: Duration = 1.seconds
    }
}
