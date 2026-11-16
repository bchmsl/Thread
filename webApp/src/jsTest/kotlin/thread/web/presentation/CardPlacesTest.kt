package thread.web.presentation

import thread.domain.external.JiraIssueKey
import thread.domain.external.PullRequestRef
import thread.domain.note.NoteId
import thread.web.presentation.external.jiraPlace
import thread.web.presentation.external.prPlace
import thread.web.presentation.notes.notePlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Item cards in the URL (D70). All ids and keys are synthetic. */
class CardPlacesTest {
    private val key = requireNotNull(JiraIssueKey.parse("ABC-12"))
    private val ref = requireNotNull(PullRequestRef.parse("octo-org/thread-demo#7"))

    @Test
    fun aPlaceShowsTheCardItsParameterNames() {
        assertEquals(CardItem(CardType.Task, "t1"), Place.parse("#/today?task=t1").card)
        assertEquals(CardItem(CardType.Note, "n1"), Place.parse("#/inbox?note=n1").card)
        assertEquals(CardItem(CardType.Jira, "ABC-12"), Place.parse("#/topic/t9?jira=ABC-12").card)
        assertEquals(CardItem(CardType.Topic, "t2"), Place.parse("#/notes?id=n1&topic=t2").card)
        assertNull(Place.parse("#/today").card)
        assertNull(Place.parse("#/today?task=").card)
        assertFalse(Place.parse("#/notes?id=n1").hasCard)
    }

    @Test
    fun aLinkThatNamesSeveralCardsShowsOne() {
        assertEquals(CardItem(CardType.Task, "t1"), Place.parse("#/today?note=n1&task=t1&pr=x").card)
    }

    @Test
    fun aCardKeepsThePageUnderItAndReplacesAnyOtherCard() {
        val page = Place.parse("#/prs?tab=review&note=n1")

        val withJira = page.withCard(CardItem(CardType.Jira, key.value))

        assertEquals(mapOf("tab" to "review", "jira" to "ABC-12"), withJira.params)
        assertEquals(Place.parse("#/prs?tab=review"), withJira.withoutCards())
        assertEquals(listOf("prs"), withJira.path)
    }

    @Test
    fun aPullRequestCardSurvivesTheAddressBar() {
        val place = View.Today.place.withCard(CardItem(CardType.Pr, ref.id))

        assertEquals(CardItem(CardType.Pr, "octo-org/thread-demo#7"), Place.parse(place.hash).card)
    }

    @Test
    fun aTopicLinkOpensItsPageOnlyInTheTopicsTab() {
        assertTrue(topicOpensAsPage(View.Topics.place))
        assertTrue(topicOpensAsPage(Place.of("topic", "t1")))
        assertFalse(topicOpensAsPage(Place.of("topic", "t1").withCard(CardItem(CardType.Task, "t3"))))
        assertFalse(topicOpensAsPage(View.Today.place))
        assertFalse(topicOpensAsPage(View.Notes.place))
        assertFalse(topicOpensAsPage(View.Jira.place))
    }

    @Test
    fun aCardForWhatThePageAlreadyShowsIsThePage() {
        assertTrue(cardShownByPage(notePlace(NoteId("n1")), CardItem(CardType.Note, "n1")))
        assertFalse(cardShownByPage(notePlace(NoteId("n1")), CardItem(CardType.Note, "n2")))
        assertFalse(cardShownByPage(View.Today.place, CardItem(CardType.Note, "n1")))
        assertTrue(cardShownByPage(Place.of("topic", "t1"), CardItem(CardType.Topic, "t1")))
        assertFalse(cardShownByPage(Place.of("topic", "t1"), CardItem(CardType.Topic, "t2")))
        assertTrue(cardShownByPage(jiraPlace(key), CardItem(CardType.Jira, "ABC-12")))
        assertFalse(cardShownByPage(View.Jira.place, CardItem(CardType.Jira, "ABC-12")))
        assertTrue(cardShownByPage(prPlace(ref, review = true), CardItem(CardType.Pr, ref.id)))
        assertFalse(cardShownByPage(Place.of("topic", "t1"), CardItem(CardType.Task, "t1")))
    }

    @Test
    fun aLinkToAnItemOpensThatItemAsACard() {
        assertEquals(CardItem(CardType.Topic, "t1"), cardFor(Place.of("topic", "t1")))
        assertEquals(CardItem(CardType.Note, "n1"), cardFor(notePlace(NoteId("n1"))))
        assertEquals(CardItem(CardType.Jira, "ABC-12"), cardFor(View.Jira.place.with("key", "abc-12")))
        assertEquals(CardItem(CardType.Pr, ref.id), cardFor(prPlace(ref, review = false)))
        assertEquals(CardItem(CardType.Task, "t3"), cardFor(Place.of("topic", "t1").withCard(CardItem(CardType.Task, "t3"))))
        assertNull(cardFor(View.Notes.place))
        assertNull(cardFor(View.Jira.place.with("key", "not a key")))
        assertNull(cardFor(View.Today.place))
        assertNull(cardFor(Place.of("settings", "connections")))
    }

    @Test
    fun anOldItemPageLinkOpensTheItemInItsTab() {
        assertEquals(jiraPlace(key), legacyItemPlace(Place.of("item", "jira", "ABC-12")) { false })
        assertEquals(View.Jira.place, legacyItemPlace(Place.of("item", "jira", "not-a-key")) { false })
        assertEquals(
            prPlace(ref, review = true),
            legacyItemPlace(Place.of("item", "pr", "octo-org", "thread-demo", "7")) { it == ref },
        )
        assertEquals(
            prPlace(ref, review = false),
            legacyItemPlace(Place.of("item", "pr", "octo-org", "thread-demo", "7")) { false },
        )
        assertEquals(View.Prs.place, legacyItemPlace(Place.of("item", "pr", "octo-org", "thread-demo")) { true })
        assertEquals(View.Prs.place, legacyItemPlace(Place.of("item", "pr", "octo-org", "thread-demo", "7", "x")) { true })
        assertEquals(View.Today.place, legacyItemPlace(Place.of("item", "other")) { true })
        assertNull(legacyItemPlace(View.Jira.place) { true })
    }
}
