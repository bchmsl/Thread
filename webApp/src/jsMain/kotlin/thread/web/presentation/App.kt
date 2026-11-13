package thread.web.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLElement
import thread.domain.account.AccountId
import thread.domain.account.AccountSession
import thread.domain.account.AccountState
import thread.domain.note.NewRevision
import thread.domain.note.NoteId
import thread.domain.note.RichTextDocument
import thread.domain.note.UNTITLED_NOTE
import thread.domain.reminder.ReminderTarget
import thread.domain.reminder.TargetKind
import thread.domain.search.searchWorkspace
import thread.domain.task.Task
import thread.domain.topic.TopicId
import thread.domain.topic.statusOf
import thread.domain.workspace.WorkspaceStore
import thread.web.platform.installCaptureShortcut
import thread.web.platform.installSearchShortcut
import thread.web.presentation.notes.NOTE_PARAM
import thread.web.presentation.notes.NoteServices
import thread.web.presentation.notes.NotesScreen
import thread.web.presentation.notes.launchNoteWrite
import thread.web.presentation.notes.notePlace
import thread.web.presentation.settings.PreferencesBlock
import thread.web.presentation.settings.SettingsScreen
import thread.web.presentation.settings.StatusesBlock
import thread.web.presentation.shell.CaptureDestination
import thread.web.presentation.shell.CaptureState
import thread.web.presentation.shell.CaptureType
import thread.web.presentation.shell.SearchGroup
import thread.web.presentation.shell.SearchOption
import thread.web.presentation.shell.SearchPalette
import thread.web.presentation.shell.StatusLine
import thread.web.presentation.shell.SyncPanel
import thread.web.presentation.shell.ViewsSheet
import thread.web.presentation.shell.focusStatusLine
import thread.web.presentation.tasks.InboxScreen
import thread.web.presentation.tasks.ReminderBanners
import thread.web.presentation.tasks.TaskSheet
import thread.web.presentation.tasks.TodayScreen
import thread.web.presentation.topics.HexSize
import thread.web.presentation.topics.TopicHex
import thread.web.presentation.topics.TopicScreen
import thread.web.presentation.topics.TopicsScreen
import thread.web.presentation.topics.topicPlace
import thread.web.presentation.topics.topicProblemText
import thread.web.presentation.trash.TRASH_PARAM
import thread.web.presentation.trash.TrashScreen

/** What the app reads from the composition root (Main.kt). */
class AppServices(
    val account: AccountSession,
    val usesEmulators: Boolean,
    /** The workspace store for one signed-in account. */
    val workspaceStore: (AccountId) -> WorkspaceStore,
    /** A new random id with this prefix, for entities made on this device. */
    val newId: (prefix: String) -> String,
    /** Note drafts on this device and Note sync with the server. */
    val notes: NoteServices,
)

@Composable
fun App(services: AppServices) {
    val accountState by services.account.state.collectAsState()
    val toasts = remember { ToastState() }
    val dialogs = remember { DialogStack() }
    val signInShown = accountState == AccountState.SignedOut
    SideEffect {
        // v3 styles the first-run page through body[data-page="index"].
        if (signInShown) document.body?.setAttribute("data-page", "index") else document.body?.removeAttribute("data-page")
    }
    CompositionLocalProvider(LocalToasts provides toasts, LocalDialogStack provides dialogs) {
        when (val state = accountState) {
            // Firebase restores the session from this device in a moment; nothing is drawn meanwhile.
            AccountState.Checking -> Unit
            AccountState.SignedOut -> {
                LaunchedEffect(Unit) { document.title = "Sign in | Thread" }
                SignInScreen(services.account, services.usesEmulators)
                if (dialogs.isEmpty) ToastHost(toasts)
            }
            // Everything below belongs to one account, so another account starts from scratch.
            is AccountState.SignedIn -> key(state.accountId) { Workspace(services, state) }
        }
    }
}

@Composable
private fun Workspace(
    services: AppServices,
    signedIn: AccountState.SignedIn,
) {
    val toasts = LocalToasts.current
    val dialogs = LocalDialogStack.current
    val router = remember { Router() }
    val syncCenter = remember { SyncCenter() }
    val capture = remember { CaptureState(CaptureType.Task) }
    val workspace = remember { WorkspaceModel(services.workspaceStore(signedIn.accountId), services.newId) }
    // Wall-clock "now" in the workspace zone, refreshed each minute (ADR-010).
    val now by rememberNow(workspace.prefs.timeZone)
    val online by rememberOnline()
    var searchOpen by remember { mutableStateOf(false) }
    var syncOpen by remember { mutableStateOf(false) }
    var viewsOpen by remember { mutableStateOf(false) }

    DisposableEffect(router) {
        val stopRouter = router.start()
        // ⌘K toggles search everywhere, also inside the editor (D40).
        val stopSearch = installSearchShortcut { searchOpen = !searchOpen }
        val stopCapture = installCaptureShortcut { capture.requestFocus() }
        onDispose {
            stopRouter()
            stopSearch()
            stopCapture()
        }
    }

    val place = router.place
    if (place.path.isEmpty()) {
        SideEffect { router.replace(View.Today.place) }
    }
    val view = View.of(place)
    // The Task sheet rides on any page as `?task=`, so the page under it is the place without that parameter.
    val page = place.with(TASK_PARAM, null)
    val placeTopicId = if (place.head == "topic") place.segment(1) else null
    // Opening another Note or Trash entry keeps the view; the Notes and Trash screens move focus themselves.
    val focusPlace =
        when (view) {
            View.Notes -> page.with(NOTE_PARAM, null)
            View.Trash -> page.with(TRASH_PARAM, null)
            else -> page
        }
    val title = if (place.head == "topic") placeTopicId?.let(workspace::topic)?.name ?: "Topic" else view.label
    LaunchedEffect(title) { document.title = "$title | Thread" }
    var firstPlace by remember { mutableStateOf(true) }
    LaunchedEffect(focusPlace) {
        // Announce a new view by moving focus to its title, but not on first load.
        if (firstPlace) {
            firstPlace = false
        } else if (!searchOpen && !viewsOpen) {
            (document.querySelector("main h1") as? HTMLElement)?.focus()
        }
    }
    LaunchedEffect(workspace) { workspace.run() }
    SweepPurgedNotes(workspace, services.notes, signedIn.accountId, online)
    val workspaceReport = workspace.syncReport(online)
    SideEffect { syncCenter.report("workspace", workspaceReport) }
    // Capture starts as the type in Preferences when the app opens (v3); later picks are remembered there.
    var captureTypeReady by remember { mutableStateOf(false) }
    LaunchedEffect(workspace.loaded) {
        if (workspace.loaded && !captureTypeReady) {
            capture.type = CaptureType.of(workspace.prefs.captureType)
            captureTypeReady = true
        }
    }
    LaunchedEffect(capture.type, captureTypeReady) {
        if (captureTypeReady) workspace.setPrefs(workspace.prefs.copy(captureType = capture.type.kind))
    }
    val syncState = syncCenter.overall(online)
    val openTask: (Task) -> Unit = { task -> router.replace(taskPlace(router.place, task.id)) }
    val openNote: (NoteId) -> Unit = { id -> router.go(notePlace(id)) }
    val openTarget: (ReminderTarget) -> Unit = { target ->
        when (target.kind) {
            TargetKind.Task -> workspace.task(target.id)?.let(openTask)
            TargetKind.Topic -> workspace.topic(target.id)?.let { router.go(topicPlace(it)) }
            TargetKind.Note -> router.go(notePlace(NoteId(target.id)))
        }
    }

    CompositionLocalProvider(LocalNow provides now) {
        A("#line", {
            classes("skip")
            onClick { event ->
                event.preventDefault()
                focusStatusLine()
            }
        }) { Text("Skip to workspace bar") }
        Div({ classes("app") }) {
            Div({ id("banner") }) { ReminderBanners(workspace, openTarget) }
            Main({
                id("main")
                tabIndex(-1)
            }) {
                when {
                    place.head == "topic" -> TopicScreen(workspace, placeTopicId, openTask, openTarget, openNote)
                    view == View.Today -> TodayScreen(workspace, place.param("add") == "1", openTask, openTarget)
                    view == View.Inbox -> InboxScreen(workspace, router, capture, openTask, openNote)
                    view == View.Notes ->
                        NotesScreen(workspace, router, services.notes, signedIn.accountId, place.param(NOTE_PARAM), syncCenter)
                    view == View.Topics -> TopicsScreen(workspace, place, router, capture)
                    view == View.Trash ->
                        TrashScreen(
                            workspace,
                            router,
                            services.notes,
                            signedIn.accountId,
                            place.param(TRASH_PARAM),
                            openTask,
                            openTarget,
                        )
                    view == View.Settings ->
                        SettingsScreen(signedIn, services.account, services.usesEmulators, place.segment(1)) {
                            StatusesBlock(workspace)
                            PreferencesBlock(workspace, capture)
                        }
                    else -> NotBuiltYet(view)
                }
            }
        }
        StatusLine(
            current = view,
            inboxCount = workspace.inboxCount,
            sync = syncText(syncState),
            capture = capture,
            destinations = workspace.topics.map { CaptureDestination(it.id.value, it.name) },
            // On a Topic's page, capture files into that Topic unless the person picks another place (v3).
            defaultDestination = placeTopicId,
            onCapture = { type, title, destination ->
                when {
                    title.isEmpty() -> {
                        capture.requestFocus(flash = false)
                        toasts.show("Type a title first.")
                    }
                    type == CaptureType.Topic ->
                        when (val created = workspace.createTopic(title)) {
                            is TopicCreation.Refused -> toasts.show(topicProblemText(created.problem))
                            is TopicCreation.Created -> {
                                capture.title = ""
                                capture.requestFocus(flash = false)
                                toasts.show("Topic added to Topics", open = { router.go(topicPlace(created.topic)) })
                            }
                        }
                    type == CaptureType.Task ->
                        when (val created = workspace.createTask(title, destination?.let(::TopicId))) {
                            is TaskCreation.Refused -> toasts.show(taskProblemText(created.problem))
                            is TaskCreation.Created -> {
                                capture.title = ""
                                capture.requestFocus(flash = false)
                                val topic = created.task.topicId?.let { workspace.topic(it.value) }
                                toasts.show(
                                    if (topic != null) "Task added to ${topic.name}" else "Task added to Inbox",
                                    open = { openTask(created.task) },
                                )
                            }
                        }
                    type == CaptureType.Note -> {
                        val topicId = destination?.let(::TopicId)
                        val note = workspace.createNote(topicId)
                        if (note == null) {
                            toasts.show("Your workspace is still loading. Try again in a moment.")
                        } else {
                            capture.title = ""
                            capture.requestFocus(flash = false)
                            // The title is the Note's first version; the Note opens with it on every device.
                            val first =
                                NewRevision(services.notes.newRevisionId(), null, title, RichTextDocument.EMPTY, services.notes.deviceId)
                            launchNoteWrite(toasts, "The note “$title” was not saved. The server refused it.") {
                                services.notes.syncRemote(signedIn.accountId).push(note.id, first)
                            }
                            val topic = topicId?.let { workspace.topic(it.value) }
                            toasts.show(
                                if (topic != null) "Note added to ${topic.name}" else "Note added to Inbox",
                                open = { openNote(note.id) },
                            )
                        }
                    }
                }
            },
            onOpenViews = { viewsOpen = true },
            onOpenSearch = { searchOpen = true },
            onOpenSync = { syncOpen = true },
        )
        if (dialogs.isEmpty) ToastHost(toasts)

        val sheetTaskId = place.param(TASK_PARAM)
        if (sheetTaskId != null && workspace.loaded) {
            val sheetTask = workspace.task(sheetTaskId)
            if (sheetTask == null) {
                // A link to a Task that was trashed, discarded or never synced here.
                LaunchedEffect(sheetTaskId) {
                    toasts.show("That task is not here any more. It may be in Trash.")
                    router.replace(router.place.with(TASK_PARAM, null))
                }
            } else {
                key(sheetTask.id.value) {
                    TaskSheet(workspace, sheetTask, placeTopicId) { router.replace(router.place.with(TASK_PARAM, null)) }
                }
            }
        }
        if (viewsOpen) {
            ViewsSheet(
                current = view,
                inboxCount = workspace.inboxCount,
                onSearch = {
                    viewsOpen = false
                    searchOpen = true
                },
                onSync = {
                    viewsOpen = false
                    syncOpen = true
                },
                onClose = { viewsOpen = false },
            )
        }
        if (syncOpen) {
            SyncPanel(syncState, syncCenter.problems, services.usesEmulators) { syncOpen = false }
        }
        if (searchOpen) {
            SearchPalette(
                initialQuery = "",
                goTo =
                    View.entries.map { target ->
                        SearchOption(target.label, "", { IconView(target.icon, 18) }) {
                            searchOpen = false
                            router.go(target.place)
                        }
                    },
                search = { query ->
                    val q = query.trim().lowercase()
                    searchGroups(query, workspace, openTask = { task ->
                        searchOpen = false
                        openTask(task)
                    }) { to ->
                        searchOpen = false
                        router.go(to)
                    } +
                        SearchGroup(
                            "Go to",
                            View.entries.filter { it.label.lowercase().contains(q) }.map { target ->
                                SearchOption(target.label, "", { IconView(target.icon, 18) }) {
                                    searchOpen = false
                                    router.go(target.place)
                                }
                            },
                        )
                },
                noResults = { query -> NoSearchResults(query) },
                onQueryChange = {},
                onClose = { searchOpen = false },
            )
        }
    }
}

/**
 * The search groups of v3 openSearch for what is on this device: Topics, Tasks and Notes, nothing in Trash
 * (adr/ADR-012-trash-and-search.md). Jira and pull requests join in later phases.
 */
private fun searchGroups(
    query: String,
    workspace: WorkspaceModel,
    openTask: (Task) -> Unit,
    go: (Place) -> Unit,
): List<SearchGroup> {
    val found = searchWorkspace(query, workspace.topics, workspace.tasks, workspace.notes) ?: return emptyList()
    val statuses = workspace.statuses
    return listOf(
        SearchGroup(
            "Topics",
            found.topics.map { topic ->
                SearchOption(topic.name, statusOf(topic, statuses).name, { TopicHex(topic.name, topic.color, HexSize.Sm) }) {
                    go(topicPlace(topic))
                }
            },
        ),
        SearchGroup(
            "Tasks",
            found.tasks.map { task ->
                val sub = if (task.done) "Completed" else task.topicId?.let { workspace.topic(it.value)?.name } ?: "Standalone"
                SearchOption(task.title, sub, { IconView(Icon.Task, 18) }) { openTask(task) }
            },
        ),
        SearchGroup(
            "Notes",
            found.notes.map { note ->
                val sub = workspace.topicsOf(note.id).joinToString(", ") { it.name }.ifEmpty { "Standalone" }
                SearchOption(note.title.ifBlank { UNTITLED_NOTE }, sub, { IconView(Icon.Notes, 18) }) { go(notePlace(note.id)) }
            },
        ),
    )
}

/**
 * Deletes the saved versions of each Note deleted forever, here or on another device (adr/ADR-012-trash-and-search.md).
 * Each one is tried once per app start while online; one that fails is tried again on the next reconnect.
 */
@Composable
private fun SweepPurgedNotes(
    workspace: WorkspaceModel,
    notes: NoteServices,
    accountId: AccountId,
    online: Boolean,
) {
    val swept = remember { mutableSetOf<String>() }
    val purged = workspace.data?.purgedNotes.orEmpty()
    LaunchedEffect(purged, online) {
        if (!online) return@LaunchedEffect
        purged.filter { swept.add(it.value) }.forEach { id ->
            try {
                notes.syncRemote(accountId).deleteVersions(id)
            } catch (e: CancellationException) {
                swept.remove(id.value)
                throw e
            } catch (e: Throwable) {
                swept.remove(id.value)
            }
        }
    }
}

@Composable
private fun NoSearchResults(query: String) {
    Div({ classes("pal__none") }) {
        org.jetbrains.compose.web.dom.P {
            org.jetbrains.compose.web.dom
                .B { Text("No local results for “$query”.") }
        }
    }
}

/** A view whose phase has not been built yet in this run. Replaced phase by phase. */
@Composable
private fun NotBuiltYet(view: View) {
    PageHeader(view.label)
    EmptyState(Icon.Sparkle, "Not built yet", "This view comes in a later phase of the build.")
}
