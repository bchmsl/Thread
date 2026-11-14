package thread.web.presentation.topics

import thread.domain.external.JiraIssueKey
import thread.domain.external.PullRequestRef
import thread.domain.suggest.SuggestionReason
import thread.domain.suggest.TopicTextField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** SYNTHETIC keys and repositories only. */
class SuggestionReasonTextTest {
    @Test
    fun eachReasonSaysWhereTheItemWasFound() {
        assertEquals("Mentioned in the Topic name", reasonText(SuggestionReason.Written(TopicTextField.Name)))
        assertEquals("Mentioned in Additional information", reasonText(SuggestionReason.Written(TopicTextField.Info)))
        assertEquals("Mentioned in a Note", reasonText(SuggestionReason.Written(TopicTextField.Note)))
        val pull = assertNotNull(PullRequestRef.parse("synthetic-org/synthetic-app#12"))
        assertEquals("Mentioned in pull request #12", reasonText(SuggestionReason.MentionedByPullRequest(pull)))
        val linked = assertNotNull(JiraIssueKey.parse("SYN-101"))
        assertEquals("SYN-101 is blocked by this", reasonText(SuggestionReason.IssueLink(linked, "is blocked by")))
    }
}
