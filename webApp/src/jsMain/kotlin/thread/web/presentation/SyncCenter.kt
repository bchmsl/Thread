package thread.web.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import thread.domain.note.NoteSaveState
import thread.domain.note.overallSaveState
import thread.domain.workspace.RefusedChange
import thread.web.platform.isOnline
import thread.web.platform.onOnlineChange

/** Something the person has to act on, shown under "Needs attention" in the sync panel. */
internal data class SyncProblem(
    val label: String,
    val state: NoteSaveState,
    val place: Place?,
    /** Set for a change the server refused: the panel then offers Retry, Copy and Discard. */
    val refused: RefusedItem? = null,
)

/** A refused change in the sync panel: [subject] names it in the buttons' accessible names, [copyText] is what Copy copies. */
internal data class RefusedItem(
    val change: RefusedChange,
    val subject: String,
    val copyText: String,
)

/** What one part of the app (Notes, Topics and Tasks, ...) reports about its saving and syncing. */
internal data class SyncReport(
    val states: List<NoteSaveState>,
    val problems: List<SyncProblem> = emptyList(),
)

/** Collects the reports for the status line chip and the sync panel. Each part reports under its own key. */
internal class SyncCenter {
    private val reports = mutableStateMapOf<String, SyncReport>()

    fun report(
        source: String,
        report: SyncReport?,
    ) {
        if (report == null) reports.remove(source) else reports[source] = report
    }

    fun overall(online: Boolean): NoteSaveState = overallSaveState(reports.values.flatMap { it.states }, online)

    val problems: List<SyncProblem> get() = reports.values.flatMap { it.problems }
}

/** The v3 chip text for each state: icon, words and tone (shell.js SYNC_TEXT). */
internal class SyncText(
    val icon: Icon,
    val text: String,
    val tone: String,
)

internal fun syncText(state: NoteSaveState): SyncText =
    when (state) {
        NoteSaveState.Synced -> SyncText(Icon.CloudCheck, "All synced", "ok")
        NoteSaveState.Syncing -> SyncText(Icon.CloudUp, "Syncing", "busy")
        NoteSaveState.Saving -> SyncText(Icon.Device, "Saving locally", "busy")
        NoteSaveState.Offline -> SyncText(Icon.CloudOff, "Saved locally, offline", "warn")
        NoteSaveState.SyncFailed -> SyncText(Icon.Alert, "Sync failed", "bad")
        NoteSaveState.LocalFailed -> SyncText(Icon.Alert, "Not saved locally", "bad")
        NoteSaveState.Conflict -> SyncText(Icon.Compare, "Conflict to resolve", "bad")
        NoteSaveState.LocalOnly -> SyncText(Icon.Device, "Local only", "warn")
    }

/** Whether the browser reports a network, updated on the online and offline events. */
@Composable
internal fun rememberOnline(): State<Boolean> {
    val online = remember { mutableStateOf(isOnline()) }
    DisposableEffect(Unit) {
        val stop = onOnlineChange { online.value = it }
        onDispose { stop() }
    }
    return online
}
