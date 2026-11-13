package thread.domain.note

/**
 * The one save state a Note shows in its status line (02-ux-design.md, "Quiet autosave indicator"; v3 notes page).
 * Each state claims only what the device storage or the server confirmed.
 */
enum class NoteSaveState {
    /** The newest edit is not stored on this device yet. */
    Saving,

    /** Storing on this device failed. The text is only on the screen. */
    LocalFailed,

    /** Stored on this device. Not signed in, so nothing syncs. */
    LocalOnly,

    /** Stored on this device. Another device changed the Note too, and the person chooses what to keep. */
    Conflict,

    /** Stored on this device. The server refused it, or has versions this app cannot read. */
    SyncFailed,

    /** Stored on this device, and the server acknowledged exactly this content. */
    Synced,

    /** Stored on this device. Syncs when the device is online. */
    Offline,

    /** Stored on this device and on its way to the server. */
    Syncing,
}

/**
 * Combines the device save state and the sync state into the state a Note shows. [sync] is null when no account is
 * signed in. A device storage problem comes first: until it is solved, the text exists only on the screen.
 */
fun noteSaveState(
    local: LocalSaveStatus,
    sync: NoteSyncState?,
    online: Boolean,
): NoteSaveState =
    when {
        local == LocalSaveStatus.NeedsAttention -> NoteSaveState.LocalFailed
        local == LocalSaveStatus.Saving -> NoteSaveState.Saving
        sync == null -> NoteSaveState.LocalOnly
        sync.conflict != null -> NoteSaveState.Conflict
        sync.rejected || sync.unreadable -> NoteSaveState.SyncFailed
        sync.synced -> NoteSaveState.Synced
        !online -> NoteSaveState.Offline
        else -> NoteSaveState.Syncing
    }

/**
 * The one state the status line's sync chip shows for everything the app saves (v3 shell.js, `overall`). A problem
 * the person must act on comes first, then work still under way. With nothing pending, it is Synced when the device
 * is online and Offline when it is not.
 */
fun overallSaveState(
    states: Collection<NoteSaveState>,
    online: Boolean,
): NoteSaveState = OVERALL_ORDER.firstOrNull { it in states } ?: if (online) NoteSaveState.Synced else NoteSaveState.Offline

private val OVERALL_ORDER =
    listOf(
        NoteSaveState.Conflict,
        NoteSaveState.LocalFailed,
        NoteSaveState.SyncFailed,
        NoteSaveState.Saving,
        NoteSaveState.LocalOnly,
        NoteSaveState.Offline,
        NoteSaveState.Syncing,
    )
