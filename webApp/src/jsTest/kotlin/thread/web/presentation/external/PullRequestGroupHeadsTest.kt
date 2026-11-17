package thread.web.presentation.external

import thread.domain.external.GitHubPerson
import thread.domain.external.PullRequestGroupKey
import thread.domain.external.ReviewState
import thread.domain.topic.StatusId
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D93: the open state each group keeps on this device, and what a row leaves to its group. SYNTHETIC names only. */
class PullRequestGroupHeadsTest {
    private val topic = Topic(TopicId("t-alpha"), "Synthetic Alpha", TopicColor.Green, StatusId("s-synthetic"))

    private val keys =
        listOf(
            PullRequestGroupKey.Repository("Synthetic-Org/Synthetic-App"),
            PullRequestGroupKey.OneTopic(topic),
            PullRequestGroupKey.SeveralTopics,
            PullRequestGroupKey.NoTopic,
            PullRequestGroupKey.Author(GitHubPerson("Synthetic-Teammate", null)),
            // Logins that spell the groups for the rest still get their own key.
            PullRequestGroupKey.Author(GitHubPerson("none", null)),
            PullRequestGroupKey.Author(GitHubPerson("noauthor", null)),
            PullRequestGroupKey.NoAuthor,
        ) + ReviewState.entries.map { PullRequestGroupKey.Review(it) } + PullRequestGroupKey.NotLoaded

    @Test
    fun aRepositoryKeepsTheKeyItHadBeforeAndNoTwoGroupsShareOne() {
        // The name from before D93, so a repository closed on this device stays closed.
        assertEquals("synthetic-org/synthetic-app", groupDeviceKey(PullRequestGroupKey.Repository("Synthetic-Org/Synthetic-App")))
        assertEquals("topic:t-alpha", groupDeviceKey(PullRequestGroupKey.OneTopic(topic)))
        assertEquals("author:synthetic-teammate", groupDeviceKey(PullRequestGroupKey.Author(GitHubPerson("Synthetic-Teammate", null))))
        assertEquals("severaltopics", groupDeviceKey(PullRequestGroupKey.SeveralTopics))
        assertEquals("noauthor", groupDeviceKey(PullRequestGroupKey.NoAuthor))
        assertEquals(keys.size, keys.map(::groupDeviceKey).toSet().size)
    }

    @Test
    fun aRowLeavesOutWhatItsGroupSays() {
        val repository = RowParts.of(PullRequestGroupKey.Repository("synthetic-org/synthetic-app"))
        assertEquals(RowParts(repo = false, author = true, topics = true), repository, "as before D93")
        assertEquals(RowParts(repo = true, author = true, topics = false), RowParts.of(PullRequestGroupKey.OneTopic(topic)))
        assertEquals(RowParts(repo = true, author = true, topics = false), RowParts.of(PullRequestGroupKey.NoTopic))
        assertTrue(RowParts.of(PullRequestGroupKey.SeveralTopics).topics, "Several Topics names them on each row")
        assertFalse(RowParts.of(PullRequestGroupKey.Author(GitHubPerson("synthetic-teammate", null))).author)
        assertFalse(RowParts.of(PullRequestGroupKey.NoAuthor).author)
        assertEquals(RowParts(repo = true, author = true, topics = true), RowParts.of(PullRequestGroupKey.NotLoaded))
    }
}
