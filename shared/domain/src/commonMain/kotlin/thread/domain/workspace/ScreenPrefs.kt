package thread.domain.workspace

import thread.domain.topic.StatusCategory

// The choices of the per-screen preferences in Settings (D83) and the readers of those that start from an older
// value. Each [id] is what is stored and synced, so it never changes.

/** How Today groups its Tasks. [DueDate] is the default. */
enum class TodayGroupBy(
    val id: String,
) {
    None("none"),
    Topic("topic"),
    DueDate("due"),
    ;

    companion object {
        fun fromId(id: String?): TodayGroupBy? = entries.firstOrNull { it.id == id }
    }
}

/** How Inbox groups what waits in it. [None], one list, is the default. */
enum class InboxGroupBy(
    val id: String,
) {
    None("none"),
    Type("type"),
    DueDate("due"),
    ;

    companion object {
        fun fromId(id: String?): InboxGroupBy? = entries.firstOrNull { it.id == id }
    }
}

/** How the Pull requests page groups "My pull requests". [Repository] is the default (D62). */
enum class MyPrsGroupBy(
    val id: String,
) {
    Repository("repo"),
    Topic("topic"),
    ReviewStatus("review"),
    ;

    companion object {
        fun fromId(id: String?): MyPrsGroupBy? = entries.firstOrNull { it.id == id }
    }
}

/** How the Pull requests page groups "Review requested". [Repository] is the default (D62). */
enum class ReviewPrsGroupBy(
    val id: String,
) {
    Repository("repo"),
    Author("author"),
    ReviewStatus("review"),
    ;

    companion object {
        fun fromId(id: String?): ReviewPrsGroupBy? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A Today card that can hide while it has nothing to show, in Today's card order. Inside the Tasks card an empty task
 * group always hides. [Tasks] is kept apart from the others ([WorkspacePrefs.todayTasksHideEmpty], D101), so its id is
 * never stored in [WorkspacePrefs.todayHideEmpty].
 */
enum class TodayHideableSection(
    val id: String,
) {
    Meetings("meetings"),
    Tasks("tasks"),
    Reminders("reminders"),
    ReviewRequested("review"),
    Topics("topics"),
    ;

    companion object {
        fun fromId(id: String?): TodayHideableSection? = entries.firstOrNull { it.id == id }
    }
}

/** A Topic Details section that can hide while it has nothing to show. */
enum class TopicHideableSection(
    val id: String,
) {
    Notes("notes"),
    Jira("jira"),
    PullRequests("prs"),
    Meetings("meetings"),
    Info("info"),
    ;

    companion object {
        fun fromId(id: String?): TopicHideableSection? = entries.firstOrNull { it.id == id }
    }
}

/** The most past meetings Today's Vertical card can be set to show. */
const val PAST_MEETINGS_MAX: Int = 20

/** The status categories whose Topics Today shows until the owner picks others (D59). */
val DEFAULT_TODAY_TOPIC_CATEGORIES: Set<StatusCategory> = setOf(StatusCategory.Active, StatusCategory.Paused)

/**
 * Today's Topics layout: the synced choice, else [onThisDevice], the layout this device kept before D83 (D59), else
 * List.
 */
fun WorkspacePrefs.todayTopicsLayout(onThisDevice: TopicsView?): TopicsView = todayTopicsView ?: onThisDevice ?: TopicsView.List

/**
 * The Today cards that hide while empty. Tasks follows [WorkspacePrefs.todayTasksHideEmpty], on unless turned off (D101;
 * D86, Q2 before). The others follow [WorkspacePrefs.todayHideEmpty]; until it is picked, the one switch from before
 * D83 (D77): on hides Reminders and Review requested, off hides none.
 */
val WorkspacePrefs.todayHiddenWhenEmpty: Set<TodayHideableSection>
    get() {
        val listed =
            todayHideEmpty
                ?: if (hideEmptySections) setOf(TodayHideableSection.Reminders, TodayHideableSection.ReviewRequested) else emptySet()
        val others = listed - TodayHideableSection.Tasks
        return if (todayTasksHideEmpty) others + TodayHideableSection.Tasks else others
    }

/**
 * These prefs with [section] hiding while empty, or not ([hide]). Tasks changes only [WorkspacePrefs.todayTasksHideEmpty];
 * any other card writes the whole [WorkspacePrefs.todayHideEmpty], starting from what [todayHiddenWhenEmpty] reads, so
 * the first change keeps what the D77 switch hid and an empty list stays empty.
 */
fun WorkspacePrefs.withHiddenWhenEmpty(
    section: TodayHideableSection,
    hide: Boolean,
): WorkspacePrefs {
    if (section == TodayHideableSection.Tasks) return copy(todayTasksHideEmpty = hide)
    val others = todayHiddenWhenEmpty - TodayHideableSection.Tasks
    return copy(todayHideEmpty = if (hide) others + section else others - section)
}

/**
 * Jira "Current sprint only": the synced choice, else [onThisDevice], the choice this device kept before D83 (D50),
 * else off.
 */
fun WorkspacePrefs.currentSprintOnly(onThisDevice: Boolean?): Boolean = jiraCurrentSprintOnly ?: onThisDevice ?: false
