package thread.domain.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** SYNTHETIC ids and values only. */
class ExternalIdsTest {
    @Test
    fun issueKeys() {
        assertEquals("SYN-12", JiraIssueKey.parse(" syn-12 ")?.value)
        assertEquals("A1_B-9", JiraIssueKey.parse("A1_B-9")?.value)
        listOf("", "SYN", "SYN-", "SYN-0", "1SYN-2", "SYN-12a", "SYN 12", "SYN-12/../x", "SYN-12?x=1").forEach {
            assertNull(JiraIssueKey.parse(it), "Expected \"$it\" to be refused")
        }
    }

    @Test
    fun jiraSites() {
        assertEquals("synthetic-site.atlassian.net", JiraSite.parse(" https://Synthetic-Site.atlassian.net/ ")?.host)
        val refused =
            listOf(
                "",
                "atlassian.net",
                "example.com",
                "x.atlassian.net.example.com",
                "http://x.atlassian.net",
                "x.atlassian.net/path",
                "a b.atlassian.net",
            )
        refused.forEach { assertNull(JiraSite.parse(it), "Expected \"$it\" to be refused") }
    }

    @Test
    fun cloudIds() {
        assertEquals("00000000-0000-4000-8000-000000000000", JiraCloudId.parse("00000000-0000-4000-8000-000000000000 ")?.value)
        listOf("", "not-a-uuid", "00000000-0000-4000-8000-00000000000", "00000000-0000-4000-8000-0000000000000g").forEach {
            assertNull(JiraCloudId.parse(it), "Expected \"$it\" to be refused")
        }
    }

    @Test
    fun pullRequestRefs() {
        assertEquals(PullRequestRef("synthetic-org", "synthetic.repo", 7), PullRequestRef.parse("synthetic-org/synthetic.repo#7"))
        listOf("", "a/b", "a#1", "a/b#0", "a/b#x", "a/b/c#1", "a b/c#1").forEach {
            assertNull(PullRequestRef.parse(it), "Expected \"$it\" to be refused")
        }
    }

    @Test
    fun secretsNeverPrintTheirValue() {
        val secret = SecretText("synthetic-secret-value")
        val connection =
            JiraConnection(
                site = requireNotNull(JiraSite.parse("synthetic-site.atlassian.net")),
                cloudId = requireNotNull(JiraCloudId.parse("00000000-0000-4000-8000-000000000000")),
                email = "synthetic@example.test",
                token = secret,
            )

        assertFalse("synthetic-secret-value" in secret.toString())
        assertFalse("synthetic-secret-value" in connection.toString())
        assertFalse("synthetic-secret-value" in GitHubConnection(secret).toString())
        assertEquals("synthetic-secret-value", secret.reveal())
    }
}
