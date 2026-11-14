package thread.domain.suggest

import thread.domain.external.JiraIssueKey
import thread.domain.external.JiraIssueSummary
import thread.domain.external.PullRequestMention
import thread.domain.external.PullRequestRef
import thread.domain.external.PullRequestSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// S4 and S5 (D49 Phase 2). Every ticket, repository and pull request here is SYNTHETIC test data.
class RelatedSuggestionsTest {
    private fun key(value: String): JiraIssueKey = assertNotNull(JiraIssueKey.parse(value))

    private fun keys(vararg values: String): List<JiraIssueKey> = values.map(::key)

    private fun pull(id: String): PullRequestRef = assertNotNull(PullRequestRef.parse(id))

    private fun row(
        value: String,
        parent: String? = null,
    ) = JiraIssueSummary(key(value), "Story", "SYNTHETIC $value", null, null, parent?.let(::key))

    private fun mention(
        id: String,
        vararg named: String,
        closed: Boolean = false,
    ) = PullRequestMention(PullRequestSummary(pull(id), "SYNTHETIC $id", null, false, null, null), closed, keys(*named))

    @Test
    fun familyGivesParentsChildrenAndSiblingsFromRowParentsOnly() {
        val rows =
            listOf(
                row("SYN-101", parent = "SYN-110"),
                row("SYN-103", parent = "SYN-110"),
                row("SYN-110"),
                row("SYN-109", parent = "SYN-101"),
                row("SYN-105", parent = "SYN-110"),
                row("SYN-120", parent = "SYN-999"),
                row("SYN-121"),
            )
        val found = familyCandidates(keys("SYN-101", "SYN-104"), rows)
        assertEquals(
            listOf(
                SuggestionCandidate(key("SYN-110"), SuggestionReason.Family(key("SYN-101"), FamilyRole.Parent)),
                SuggestionCandidate(key("SYN-109"), SuggestionReason.Family(key("SYN-101"), FamilyRole.Child)),
                SuggestionCandidate(key("SYN-103"), SuggestionReason.SameParent(key("SYN-101"))),
                SuggestionCandidate(key("SYN-105"), SuggestionReason.SameParent(key("SYN-101"))),
            ),
            found,
            "rows with another parent or none add nothing, and SYN-104 has no row",
        )
    }

    @Test
    fun linkedTicketsAreNeverTheirOwnFamilyAndSiblingsAreCapped() {
        val rows =
            listOf(row("SYN-101", parent = "SYN-110"), row("SYN-110")) +
                (1..8).map { row("SYN-${200 + it}", parent = "SYN-110") }
        val found = familyCandidates(keys("SYN-101", "SYN-110"), rows, siblingLimit = 3)
        assertEquals(
            (1..8).map { SuggestionCandidate(key("SYN-${200 + it}"), SuggestionReason.Family(key("SYN-110"), FamilyRole.Child)) },
            found,
            "a linked parent makes its other children children, not siblings, and both linked tickets are left out",
        )
        val siblings = familyCandidates(keys("SYN-101"), rows, siblingLimit = 3).filter { it.reason is SuggestionReason.SameParent }
        assertEquals(keys("SYN-201", "SYN-202", "SYN-203"), siblings.map { it.item }, "the first three in Jira's order")
    }

    @Test
    fun parentsToAskAreTheRowsParentsThatAreNotAskedAlready() {
        val rows = listOf(row("SYN-101", parent = "SYN-110"), row("SYN-103", parent = "SYN-110"), row("SYN-104", parent = "SYN-101"))
        assertEquals(keys("SYN-110"), familyParents(keys("SYN-101", "SYN-103", "SYN-104"), rows))
        assertEquals(emptyList(), familyParents(keys("SYN-101"), rows, limit = 0))
    }

    @Test
    fun searchKeysAreDistinctSortedAndCapped() {
        val many = (1..12).map { key("SYN-${300 - it}") }
        assertEquals(FAMILY_KEYS_MAX, familySearchKeys(many + many).size)
        assertEquals(familySearchKeys(many), familySearchKeys(many.reversed()), "the same keys in another order are the same search")
        assertEquals(MENTION_KEYS_MAX, mentionSearchKeys(many).size)
        assertEquals(keys("SYN-101", "SYN-104"), mentionSearchKeys(keys("SYN-104", "SYN-101", "SYN-104")))
    }

    @Test
    fun mentionReposComeFromLinkedPullRequestsElseKnownOnes() {
        val linked = listOf(pull("o/b#1"), pull("o/a#2"), pull("o/b#3"), pull("o/c#4"))
        val known = listOf(pull("k/x#1"))
        assertEquals(listOf("o/b", "o/a"), mentionSearchRepos(linked, known), "the most linked repository first, at most two")
        assertEquals(listOf("k/x"), mentionSearchRepos(emptyList(), known))
        assertEquals(emptyList(), mentionSearchRepos(emptyList(), emptyList()))
    }

    @Test
    fun mentionCandidatesKeepOnlyKeysStillLinked() {
        val pulls = listOf(mention("o/a#12", "SYN-103", "SYN-105"), mention("o/a#14", "SYN-101", closed = true))
        assertEquals(
            listOf(
                SuggestionCandidate(pull("o/a#12"), SuggestionReason.MentionsLinkedTicket(key("SYN-105"))),
                SuggestionCandidate(pull("o/a#14"), SuggestionReason.MentionsLinkedTicket(key("SYN-101"))),
            ),
            mentionCandidates(pulls, keys("SYN-101", "SYN-105")),
        )
    }

    @Test
    fun relatedReasonsRankBelowWrittenAndAddUpByKind() {
        val candidates =
            listOf(
                SuggestionCandidate(key("SYN-110"), SuggestionReason.Family(key("SYN-101"), FamilyRole.Parent)),
                SuggestionCandidate(key("SYN-103"), SuggestionReason.SameParent(key("SYN-101"))),
                SuggestionCandidate(key("SYN-103"), SuggestionReason.SameParent(key("SYN-105"))),
                SuggestionCandidate(key("SYN-106"), SuggestionReason.Written(TopicTextField.Task)),
                SuggestionCandidate(key("SYN-106"), SuggestionReason.SameParent(key("SYN-101"))),
            )
        val ranked = rankSuggestions(candidates, { SuggestionFacts(false, null) }, emptySet(), emptySet())
        assertEquals(
            listOf("SYN-106" to WRITTEN_POINTS + SIBLING_POINTS, "SYN-110" to FAMILY_POINTS, "SYN-103" to SIBLING_POINTS),
            ranked.map { it.item.value to it.score },
            "two siblings count once",
        )
        val pulls =
            rankSuggestions(
                listOf(SuggestionCandidate(pull("o/a#14"), SuggestionReason.MentionsLinkedTicket(key("SYN-101")))),
                { SuggestionFacts(done = true, updatedAtMillis = null) },
                emptySet(),
                emptySet(),
            )
        assertEquals(PULL_REQUEST_POINTS - DONE_PENALTY, pulls.single().score, "a closed pull request goes below open ones")
    }

    @Test
    fun aSavedAnswerIsFreshForTenMinutesAndNotFromTheFuture() {
        val saved = 1_800_000_000_000L
        assertTrue(isFresh(saved, saved))
        assertTrue(isFresh(saved, saved + RELATED_FRESH_MILLIS - 1))
        assertFalse(isFresh(saved, saved + RELATED_FRESH_MILLIS))
        assertFalse(isFresh(saved, saved - 1), "a save time after now, from a wrong clock, is not trusted")
    }
}
