package thread.web.presentation

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import thread.domain.note.NoteId
import thread.domain.task.TaskId
import thread.domain.topic.TopicId
import thread.web.presentation.notes.notePlace
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Item cards in the browser's history (D70): a card opened from another screen has an entry of its own, so Back and
 * Close return to the card or page under it; a card from a link has none; an item's own tab shows its full view. The
 * test browser's real history is used. All ids are synthetic.
 */
class CardOpenerTest {
    private val task = CardItem(CardType.Task, "t1")
    private val note = CardItem(CardType.Note, "n1")
    private val topic = CardItem(CardType.Topic, "tp1")
    private val today = View.Today.place

    @Test
    fun anItemFromAnotherScreenOpensAsACardOverThePage() =
        historyTest(today) { router, cards ->
            cards.openTask(TaskId("t1"))

            assertEquals(today.withCard(task).hash, window.location.hash)
            assertEquals(task, cards.shown)
            assertEquals(1, router.cardDepth)
            assertNull(router.previousCard)
            assertNull(cards.editOnOpen)

            cards.close()

            awaitHash(router, today)
            assertNull(cards.shown)
            assertEquals(0, router.cardDepth)
        }

    @Test
    fun aCardOpenedFromACardGoesBackToIt() =
        historyTest(today) { router, cards ->
            cards.openTask(TaskId("t1"))
            cards.openNote(NoteId("n1"))

            assertEquals(today.withCard(note).hash, window.location.hash)
            assertEquals(2, router.cardDepth)
            assertEquals(task, router.previousCard)

            cards.back()

            awaitHash(router, today.withCard(task))
            assertEquals(task, cards.shown)
            assertEquals(1, router.cardDepth)
            assertNull(router.previousCard)
        }

    @Test
    fun openingTheCardUnderThisOneGoesBackInsteadOfAddingIt() =
        historyTest(today) { router, cards ->
            cards.openTask(TaskId("t1"))
            cards.openNote(NoteId("n1"))

            cards.open(task)

            awaitHash(router, today.withCard(task))
            assertEquals(1, router.cardDepth)

            cards.back()

            awaitHash(router, today)
            assertEquals(0, router.cardDepth)
        }

    @Test
    fun closingANestedCardReturnsToThePage() =
        historyTest(today) { router, cards ->
            cards.openTask(TaskId("t1"))
            cards.openNote(NoteId("n1"))

            cards.close()

            awaitHash(router, today)
            assertNull(cards.shown)
            assertEquals(0, router.cardDepth)
        }

    @Test
    fun browserBackClosesTheCardAndForwardOpensItAgain() =
        historyTest(today) { router, cards ->
            cards.openTask(TaskId("t1"))

            window.history.back()

            awaitHash(router, today)
            assertNull(cards.shown)

            window.history.forward()

            awaitHash(router, today.withCard(task))
            assertEquals(task, cards.shown)
            assertEquals(1, router.cardDepth)
        }

    @Test
    fun aCardFromALinkIsReplacedByTheNextCardAndClosesInPlace() =
        historyTest(today.withCard(task)) { router, cards ->
            assertEquals(task, cards.shown)
            assertEquals(0, router.cardDepth)

            cards.openNote(NoteId("n1"))

            assertEquals(today.withCard(note).hash, window.location.hash)
            assertEquals(note, cards.shown)
            assertEquals(0, router.cardDepth)
            assertNull(router.previousCard)

            cards.close()

            assertEquals(today.hash, window.location.hash)
            assertNull(cards.shown)

            // Neither the card nor closing it added an entry: Back leaves the linked page.
            window.history.back()

            awaitHash(router, FLOOR)
        }

    @Test
    fun aTopicLinkInTheTopicsTabOpensTheTopicsPage() =
        historyTest(View.Topics.place) { router, cards ->
            cards.openTopic(TopicId("tp1"))

            awaitHash(router, Place.of("topic", "tp1"))
            assertNull(cards.shown)
            assertEquals(0, router.cardDepth)

            window.history.back()

            awaitHash(router, View.Topics.place)
        }

    @Test
    fun aTopicLinkOnAnotherScreenOpensACard() =
        historyTest(today) { router, cards ->
            cards.openTopic(TopicId("tp1"))

            assertEquals(today.withCard(topic).hash, window.location.hash)
            assertEquals(topic, cards.shown)
            assertEquals(1, router.cardDepth)
        }

    @Test
    fun anItemThePageShowsOpensNoCardAndClosesACardOverIt() =
        historyTest(notePlace(NoteId("n1"))) { router, cards ->
            val page = notePlace(NoteId("n1"))

            cards.openNote(NoteId("n1"))

            assertEquals(page.hash, window.location.hash)
            assertNull(cards.shown)
            assertEquals(0, router.cardDepth)

            cards.openTask(TaskId("t1"))
            assertEquals(page.withCard(task).hash, window.location.hash)

            cards.openNote(NoteId("n1"))

            awaitHash(router, page)
            assertNull(cards.shown)
        }

    @Test
    fun onlyANewItemOpensForEditing() =
        historyTest(today) { router, cards ->
            cards.openTask(TaskId("t1"), edit = true)

            assertEquals(task, cards.editOnOpen)

            // The card on screen opened again: nothing changes.
            cards.openTask(TaskId("t1"))

            assertEquals(task, cards.editOnOpen)
            assertEquals(1, router.cardDepth)
            assertEquals(today.withCard(task).hash, window.location.hash)

            cards.openNote(NoteId("n1"))

            assertNull(cards.editOnOpen)
            assertEquals(2, router.cardDepth)
        }

    /**
     * Runs [body] with a started [Router] on [start]. A floor entry goes under [start], so a test can show that Back
     * leaves the page, and so no Back in a test can leave this document. The test page's own address comes back after.
     */
    private fun historyTest(
        start: Place,
        body: suspend (Router, CardOpener) -> Unit,
    ): TestResult =
        runTest {
            val original = window.location.href
            window.history.pushState(null, "", FLOOR.hash)
            window.history.pushState(null, "", start.hash)
            val router = Router()
            val stop = router.start()
            try {
                body(router, CardOpener(router))
            } finally {
                stop()
                window.history.replaceState(null, "", original)
            }
        }

    /** Back, forward and go(-n) finish after a popstate event, not at once: waits until the page and router show [place]. */
    private suspend fun awaitHash(
        router: Router,
        place: Place,
    ) {
        repeat(200) {
            if (window.location.hash == place.hash && router.place == place) return
            Promise<Unit> { resolve, _ -> window.setTimeout({ resolve(Unit) }, 5) }.await()
        }
        fail("Expected ${place.hash}, the page shows ${window.location.hash} and the router ${router.place.hash}")
    }

    private companion object {
        /** The entry under each test's first page. */
        val FLOOR = View.Inbox.place
    }
}
