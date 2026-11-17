package thread.web.presentation.topics

import thread.domain.topic.TopicColor
import kotlin.test.Test
import kotlin.test.assertEquals

/** The sticker color names the Edit Topic picker reads out and shows: Chrome's tab group names (D71, D72). */
class TopicColorNameTest {
    @Test
    fun eachStickerColorHasChromesNameInChromesOrder() {
        assertEquals(
            listOf("Grey", "Blue", "Red", "Yellow", "Green", "Pink", "Purple", "Cyan", "Orange"),
            TopicColor.entries.map(::topicColorName),
        )
    }

    @Test
    fun theOldColorsShowTheirChromeNamesNotTheirStoredIds() {
        val shown = listOf("lavender", "mint", "sky", "peach", "butter").map { topicColorName(TopicColor.fromId(it)!!) }
        assertEquals(listOf("Purple", "Green", "Blue", "Orange", "Yellow"), shown)
    }

    @Test
    fun theColorTokenIsNamedLikeTheColorPeopleSee() {
        assertEquals("var(--topic-purple)", topicColorVar(TopicColor.Purple))
        assertEquals("var(--topic-grey)", topicColorVar(TopicColor.Grey))
    }
}
