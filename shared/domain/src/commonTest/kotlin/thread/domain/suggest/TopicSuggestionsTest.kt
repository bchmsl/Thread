package thread.domain.suggest

import thread.domain.external.CiState
import thread.domain.external.CiSummary
import thread.domain.external.ExternalDocument
import thread.domain.external.JiraIssue
import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraRelated
import thread.domain.external.JiraSite
import thread.domain.external.PullRequestDetail
import thread.domain.external.PullRequestRef
import thread.domain.external.ReviewState
import thread.domain.note.NoteId
import thread.domain.note.NoteSummary
import thread.domain.task.Task
import thread.domain.task.TaskId
import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// Every Topic, ticket and pull request here is SYNTHETIC test data.
class TopicSuggestionsTest {
    private val site = assertNotNull(JiraSite.parse("synthetic.atlassian.net"))
    private val topic =
        Topic(
            TopicId("t-alpha"),
            "Synthetic retries SYN-101",
            TopicColor.Mint,
            SEED_STATUSES[0].id,
            goal = "",
            leftOff = "Waiting on synthetic-org/synthetic-app#12",
            nextStep = "Check SYN-104",
        )

    private fun key(value: String): JiraIssueKey = assertNotNull(JiraIssueKey.parse(value))

    private fun pull(id: String): PullRequestRef = assertNotNull(PullRequestRef.parse(id))

    private fun open(updated: Long? = null) = SuggestionFacts(done = false, updatedAtMillis = updated)

    @Test
    fun topicTextsAreItsFieldsTasksAndNotesNotOtherTopicsOrTrash() {
        val tasks =
            listOf(
                Task(TaskId("k1"), "Task SYN-105", topicId = topic.id),
                Task(TaskId("k2"), "Done SYN-106", topicId = topic.id, done = true),
                Task(TaskId("k3"), "In Trash SYN-107", topicId = topic.id, deleted = true),
                Task(TaskId("k4"), "Other Topic SYN-108", topicId = TopicId("t-beta")),
            )
        val notes =
            listOf(
                NoteSummary(NoteId("n1"), title = "Note", preview = "Preview SYN-109"),
                NoteSummary(NoteId("n2"), title = "Trashed SYN-110", inTrash = true),
            )
        val texts = topicTextSources(topic, tasks, notes)
        assertEquals(
            listOf(
                TopicTextField.Name,
                TopicTextField.LeftOff,
                TopicTextField.NextStep,
                TopicTextField.Task,
                TopicTextField.Task,
                TopicTextField.Note,
            ),
            texts.map { it.field },
            "a blank goal is left out",
        )
        assertEquals(
            listOf("SYN-101", "SYN-104", "SYN-105", "SYN-106", "SYN-109"),
            writtenJiraKeys(texts, site).map { it.item.value },
        )
        assertEquals(listOf(pull("synthetic-org/synthetic-app#12")), writtenPullRequests(texts).map { it.item })
    }

    @Test
    fun issueLinksAndPullRequestMentionsAreCandidates() {
        val linked =
            JiraIssue(
                key = key("SYN-101"),
                type = "Story",
                title = "Synthetic",
                status = null,
                priority = null,
                assignee = null,
                reporter = null,
                sprint = null,
                labels = emptyList(),
                updatedAtMillis = null,
                description = ExternalDocument(emptyList()),
                comments = emptyList(),
                commentTotal = 0,
                webUrl = null,
                related = listOf(JiraRelated("is blocked by", key("SYN-104"), "Synthetic blocker", null)),
            )
        assertEquals(
            listOf(SuggestionCandidate(key("SYN-104"), SuggestionReason.IssueLink(key("SYN-101"), "is blocked by"))),
            issueLinkCandidates(listOf(linked)),
        )

        val ref = pull("synthetic-org/synthetic-app#12")
        val detail =
            PullRequestDetail(
                ref = ref,
                title = "SYNTHETIC: SYN-103 retries",
                author = null,
                isDraft = false,
                headBranch = "feature/SYN-105-banner",
                baseBranch = "main",
                body = "Also SYN-103 and https://other.atlassian.net/browse/OTH-1",
                updatedAtMillis = null,
                webUrl = null,
                reviewers = emptyList(),
                review = ReviewState.NoReviews,
                ci = CiSummary(CiState.None, statusesReadable = true, checksReadable = true),
            )
        assertEquals(
            listOf("SYN-103", "SYN-105"),
            pullRequestMentionCandidates(listOf(detail), site).map { it.item.value },
        )
        assertEquals(SuggestionReason.MentionedByPullRequest(ref), pullRequestMentionCandidates(listOf(detail), site).first().reason)
    }

    @Test
    fun pointsOfDifferentKindsAddUpAndTheSameKindCountsOnce() {
        val a = key("SYN-201")
        val b = key("SYN-202")
        val candidates =
            listOf(
                SuggestionCandidate(a, SuggestionReason.IssueLink(key("SYN-101"), "blocks")),
                SuggestionCandidate(b, SuggestionReason.Written(TopicTextField.Name)),
                SuggestionCandidate(b, SuggestionReason.Written(TopicTextField.NextStep)),
                SuggestionCandidate(a, SuggestionReason.MentionedByPullRequest(pull("synthetic-org/synthetic-app#12"))),
                SuggestionCandidate(a, SuggestionReason.Written(TopicTextField.Goal)),
            )
        val ranked = rankSuggestions(candidates, { open() }, linked = emptySet(), dismissed = emptySet())
        assertEquals(listOf(a, b), ranked.map { it.item })
        assertEquals(WRITTEN_POINTS + PULL_REQUEST_POINTS + ISSUE_LINK_POINTS, ranked[0].score)
        assertEquals(WRITTEN_POINTS, ranked[1].score, "written twice counts once")
        assertEquals(
            listOf(
                SuggestionReason.Written(TopicTextField.Goal),
                SuggestionReason.MentionedByPullRequest(pull("synthetic-org/synthetic-app#12")),
            ),
            ranked[0].reasons,
            "the best two reasons",
        )
    }

    @Test
    fun doneGoesLowerAndTiesGoToTheMostRecentlyUpdated() {
        val done = key("SYN-301")
        val old = key("SYN-302")
        val recent = key("SYN-303")
        val unknownTime = key("SYN-304")
        val candidates =
            listOf(
                done,
                unknownTime,
                old,
                recent,
            ).map { SuggestionCandidate(it, SuggestionReason.Written(TopicTextField.Name)) }
        val facts =
            mapOf(
                done to SuggestionFacts(done = true, updatedAtMillis = 900),
                old to open(100),
                recent to open(500),
                unknownTime to open(null),
            )
        val ranked = rankSuggestions(candidates, facts::get, linked = emptySet(), dismissed = emptySet())
        assertEquals(listOf(recent, old, unknownTime, done), ranked.map { it.item })
        assertEquals(WRITTEN_POINTS - DONE_PENALTY, ranked.last().score)
    }

    @Test
    fun linkedDismissedAndUnconfirmedItemsAreLeftOutAndTheLimitHolds() {
        val items = (1..14).map { key("SYN-${400 + it}") }
        val candidates = items.map { SuggestionCandidate(it, SuggestionReason.Written(TopicTextField.Task)) }
        val unconfirmed = items[2]
        val ranked =
            rankSuggestions(
                candidates,
                facts = { if (it == unconfirmed) null else open() },
                linked = setOf(items[0]),
                dismissed = setOf(items[1]),
            )
        assertEquals(SUGGESTIONS_MAX, ranked.size)
        assertEquals(items.drop(3).take(SUGGESTIONS_MAX), ranked.map { it.item }, "equal scores keep the order found")
        assertEquals(2, rankSuggestions(candidates, { open() }, emptySet(), emptySet(), limit = 2).size)
    }

    @Test
    fun keysOfKnownProjectsAreCheckedFirstAndNewProjectsAreCapped() {
        val unknown = listOf("UTF-8", "SYN-501", "SHA-256", "ABC-1", "SYN-502", "XYZ-9", "ABC-2", "SYN-501").map(::key)
        assertEquals(
            listOf("SYN-501", "SYN-502", "UTF-8", "SHA-256", "ABC-1"),
            keysToCheck(unknown, knownProjects = setOf("SYN")).map { it.value },
        )
        assertEquals(
            listOf("UTF-8", "SYN-501", "SYN-502"),
            keysToCheck(unknown, knownProjects = emptySet(), newProjects = 2).map { it.value },
            "only the first two new projects",
        )
        assertEquals(emptyList(), keysToCheck(emptyList(), setOf("SYN")))
        assertEquals("A1_B", projectOf(key("A1_B-9")))
    }
}
