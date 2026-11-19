package thread.web.presentation.calendar

import thread.domain.calendar.Meeting
import thread.domain.calendar.MeetingAlternate
import thread.domain.calendar.MeetingId
import thread.domain.calendar.MeetingResponse
import thread.domain.calendar.MeetingTime
import thread.domain.calendar.SeriesKey
import thread.domain.search.searchMeetings
import thread.domain.time.LocalDay
import thread.domain.time.WallTime
import thread.domain.topic.SEED_STATUSES
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicId
import thread.web.presentation.CardItem
import thread.web.presentation.CardKind
import thread.web.presentation.CardType
import thread.web.presentation.Place
import thread.web.presentation.View
import thread.web.presentation.card
import thread.web.presentation.withCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** D78 P5b: the Meetings group of global search. Every meeting, Topic and link here is SYNTHETIC. */
class MeetingSearchTest {
    private val today = LocalDay(2026, 11, 17)
    private val now = WallTime.of(today, 10, 48)
    private val standupSeries = SeriesKey("a".repeat(32))
    private val qr = Topic(TopicId("t-qr"), "Synthetic QR", TopicColor.Green, SEED_STATUSES[0].id)

    private val standup =
        Meeting(
            MeetingId("1".repeat(32)),
            standupSeries,
            "Synthetic standup",
            MeetingTime.Timed(WallTime.of(today, 11, 30), WallTime.of(today, 11, 45)),
            MeetingResponse.Accepted,
            joinUrl = "https://zoom.us/j/1234567890?pwd=SyntheticPasscode42",
        )
    private val offsite =
        Meeting(
            MeetingId("2".repeat(32)),
            SeriesKey("b".repeat(32)),
            "Synthetic offsite standup",
            MeetingTime.AllDay(today.plusDays(1), today.plusDays(2)),
            MeetingResponse.Accepted,
            location = "Synthetic hall",
        )

    private fun topicsOf(series: SeriesKey) = if (series == standupSeries) listOf(qr) else emptyList()

    @Test
    fun aResultShowsWhenItIsAndTheLinkedTopicsButNeverTheJoinLink() {
        val options = meetingSearchOptions(listOf(standup, offsite), ::topicsOf, now) {}

        assertEquals(listOf("Synthetic standup", "Synthetic offsite standup"), options.map { it.label })
        assertEquals(listOf("Today, 11:30 · Synthetic QR", "Tomorrow, all day"), options.map { it.sub })
        assertEquals(listOf(CardKind.Meeting, CardKind.Meeting), options.map { it.kind })
        options.forEach { option ->
            assertFalse("zoom" in option.label + option.sub || "pwd" in option.label + option.sub, "no part of the join link")
        }
    }

    @Test
    fun aTitleSetInThreadShowsAndFindsTheMeeting() {
        val own =
            standup.copy(
                alternate = MeetingAlternate(standupSeries, title = "Synthetic team sync", joinUrl = "https://meet.synthetic.example/r1"),
            )
        val found = searchMeetings("team sync", listOf(own, offsite), now).orEmpty()

        assertEquals(listOf(own), found)
        assertEquals(listOf("Synthetic team sync"), meetingSearchOptions(found, ::topicsOf, now) {}.map { it.label })
        assertEquals(emptyList<Meeting>(), searchMeetings("synthetic.example", listOf(own, offsite), now).orEmpty(), "never the link")
    }

    @Test
    fun pickingAResultWithEnterOpensTheMeetingCard() {
        var opened: MeetingId? = null
        val found = searchMeetings("standup", listOf(offsite, standup), now).orEmpty()
        val options = meetingSearchOptions(found, ::topicsOf, now) { opened = it }

        // The search palette calls onPick for Enter and for a click (ShellDialogs.SearchPalette).
        options.first().onPick()

        assertEquals(standup.id, opened, "the nearest match comes first and opens")
        val place = View.Today.place.withCard(CardItem(CardType.Meeting, standup.id.value))
        assertEquals(mapOf("meeting" to standup.id.value), place.params)
        assertEquals(CardItem(CardType.Meeting, standup.id.value), Place.parse(place.hash).card)
    }
}
