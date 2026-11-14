package thread.domain.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** SYNTHETIC keys, sites and repositories only. */
class MentionsTest {
    private val site = assertNotNull(JiraSite.parse("synthetic.atlassian.net"))

    private fun keys(
        text: String,
        jiraSite: JiraSite? = site,
    ): List<String> = jiraKeysIn(text, jiraSite).map { it.value }

    private fun pulls(text: String): List<String> = pullRequestRefsIn(text).map { it.id }

    @Test
    fun jiraKeysOnWordBoundariesInOrderWithoutRepeats() {
        assertEquals(listOf("SYN-101", "SYN-104"), keys("Fix SYN-101, then SYN-104 (and SYN-101 again)."))
        assertEquals(listOf("SYN-101"), keys("SYN-101"))
        assertEquals(listOf("SYN-101", "SYN-102"), keys("feature/SYN-101-retry and SYN-102_cleanup"))
        assertEquals(listOf("A1_B-9"), keys("[A1_B-9]"))
    }

    @Test
    fun lowercaseAndGluedTextAreNotKeys() {
        assertEquals(emptyList(), keys("syn-101 Syn-101 xSYN-101 SYN-101x SYN-0 SYN- 1SYN-2 _SYN-3"))
        assertEquals(emptyList(), keys("SYN-12345678901"), "longer than a key number")
    }

    @Test
    fun keyShapedTextIsKeptForJiraToAnswer() {
        assertEquals(listOf("UTF-8", "SHA-256"), keys("UTF-8 text, SHA-256 sums"))
    }

    @Test
    fun linksToThisSiteCountAndOtherJiraSitesDoNot() {
        assertEquals(listOf("SYN-105"), keys("https://synthetic.atlassian.net/browse/SYN-105"))
        assertEquals(
            listOf("SYN-106"),
            keys("https://Synthetic.atlassian.net/jira/software/projects/SYN/boards/1?selectedIssue=SYN-106"),
        )
        assertEquals(emptyList(), keys("https://other-company.atlassian.net/browse/OTH-1 and http://x.atlassian.net/browse/OTH-2"))
        assertEquals(
            emptyList(),
            keys("https://synthetic.atlassian.net/browse/SYN-1", jiraSite = null),
            "no site: every Jira link is another site",
        )
        assertEquals(listOf("SYN-7"), keys("https://example.test/notes/SYN-7"), "other links read like text")
    }

    @Test
    fun pullRequestLinksAndShortForms() {
        assertEquals(
            listOf("synthetic-org/synthetic-app#12", "synthetic-org/other.repo#3"),
            pulls("See https://github.com/synthetic-org/synthetic-app/pull/12/files and synthetic-org/other.repo#3."),
        )
        assertEquals(
            listOf("synthetic-org/synthetic-app#12"),
            pulls("synthetic-org/synthetic-app#12 https://www.github.com/synthetic-org/synthetic-app/pull/12"),
        )
    }

    @Test
    fun otherGitHubLinksAndPathsAreNotPullRequests() {
        assertEquals(
            emptyList(),
            pulls(
                "https://github.com/synthetic-org/synthetic-app/issues/4 " +
                    "https://github.com/synthetic-org/synthetic-app/commit/abc " +
                    "https://example.test/a/b#5 path/a/b#6 synthetic-org/app#0 org/app#12x",
            ),
        )
    }
}
