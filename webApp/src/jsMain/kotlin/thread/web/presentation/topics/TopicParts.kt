package thread.web.presentation.topics

import androidx.compose.runtime.Composable
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.ElementBuilder
import org.jetbrains.compose.web.dom.ElementScope
import org.jetbrains.compose.web.dom.I
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.TagElement
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.Element
import thread.domain.task.TaskProgress
import thread.domain.topic.StatusCategory
import thread.domain.topic.Topic
import thread.domain.topic.TopicColor
import thread.domain.topic.TopicStatus
import thread.domain.topic.monogram
import thread.web.presentation.LocalCards
import thread.web.presentation.Place
import thread.web.presentation.onPlainClick

/** The v3 sticker sizes (shell.js HEX_PX). */
internal enum class HexSize(
    val id: String,
    val px: Int,
) {
    Xs("xs", 16),
    Sm("sm", 24),
    Md("md", 34),
    Lg("lg", 84),
}

/**
 * A Topic's hex sticker with its monogram (v3 T.hex). Decorative: the Topic name is always next to it.
 *
 * The `<svg>` is a real child of the parent, not wrapped, because app.css styles it with child selectors such as
 * `.trow2 > .hex`. The monogram is a text node, so a Topic name is never read as markup.
 */
@Composable
internal fun TopicHex(
    name: String,
    color: TopicColor,
    size: HexSize,
) {
    SvgTag("svg", {
        attr("class", "hex hex--${size.id}")
        attr("viewBox", "-12 -12 124 124")
        attr("width", size.px.toString())
        attr("height", size.px.toString())
        attr("aria-hidden", "true")
        attr("focusable", "false")
        style { property("--hc", "var(--topic-${color.id})") }
    }) {
        if (size == HexSize.Md || size == HexSize.Lg) {
            SvgTag("polygon", {
                attr("class", "hex__cut")
                attr("points", HEX_POINTS)
            })
        }
        SvgTag("polygon", {
            attr("class", "hex__fill")
            attr("points", HEX_POINTS)
        })
        if (size != HexSize.Xs) {
            SvgTag("text", {
                attr("class", "hex__t")
                attr("x", "50")
                attr("y", "50")
                attr("dy", "0.36em")
                attr("text-anchor", "middle")
            }) { Text(monogram(name)) }
        }
    }
}

/** The category pattern swatch (v3 T.swatch). Done carries a check mark, so the category never depends on color. */
@Composable
internal fun Swatch(category: StatusCategory) {
    Span({
        classes("sw", "sw--${category.id}")
        attr("aria-hidden", "true")
    }) {
        if (category == StatusCategory.Done) {
            SvgTag("svg", {
                attr("viewBox", "0 0 20 12")
                attr("width", "20")
                attr("height", "12")
            }) {
                SvgTag("path", {
                    attr("d", "M6.5 6.2l2.4 2.3 4.6-4.9")
                    attr("fill", "none")
                    attr("stroke", "currentColor")
                    attr("stroke-width", "1.9")
                    attr("stroke-linecap", "round")
                    attr("stroke-linejoin", "round")
                })
            }
        }
    }
}

/** A status with its swatch (v3 T.statusLabel). A custom name also tells screen readers its category. */
@Composable
internal fun StatusLabel(status: TopicStatus) {
    Span({ classes("st", "st--${status.category.id}") }) {
        Swatch(status.category)
        Span({ classes("st__name") }) { Text(status.name) }
        if (!status.name.equals(categoryLabel(status.category), ignoreCase = true)) {
            Span({ classes("vh") }) { Text(", ${categoryLabel(status.category)} category") }
        }
    }
}

/** An empty sticker, for "No Topic" (v3 T.hexEmpty). */
@Composable
internal fun TopicHexEmpty(size: HexSize) {
    SvgTag("svg", {
        attr("class", "hex hex--${size.id} hex--none")
        attr("viewBox", "-12 -12 124 124")
        attr("width", size.px.toString())
        attr("height", size.px.toString())
        attr("aria-hidden", "true")
        attr("focusable", "false")
    }) {
        SvgTag("polygon", {
            attr("class", "hex__fill")
            attr("points", HEX_POINTS)
        })
    }
}

/**
 * A Topic's tag (v3 T.topicTag): a link to the Topic, or [noneLabel] for a Task without one. A plain click opens the
 * Topic as the Topics tab does: its page there, a card anywhere else (D70).
 */
@Composable
internal fun TopicTag(
    topic: Topic?,
    noneLabel: String = "Standalone",
) {
    if (topic == null) {
        Span({ classes("ttag", "ttag--none") }) {
            TopicHexEmpty(HexSize.Xs)
            Span { Text(noneLabel) }
        }
        return
    }
    val cards = LocalCards.current
    A(topicPlace(topic).hash, {
        classes("ttag")
        topicColor(topic.color)
        if (cards != null) onPlainClick { cards.openTopic(topic.id) }
    }) {
        TopicHex(topic.name, topic.color, HexSize.Xs)
        Span { Text(topic.name) }
    }
}

/** A Topic's tag that is not a link, for use inside another link (v3 T.topicTag with plain). */
@Composable
internal fun PlainTopicTag(topic: Topic) {
    Span({
        classes("ttag")
        topicColor(topic.color)
    }) {
        TopicHex(topic.name, topic.color, HexSize.Xs)
        Span { Text(topic.name) }
    }
}

/**
 * The Topic's personal Task progress (v3 T.progress): one segment per Task, done ones first, or a bar above twelve.
 * Linked Jira tickets never count (D21).
 */
@Composable
internal fun TopicProgress(
    topic: Topic,
    progress: TaskProgress,
) {
    if (progress.total == 0) {
        Span({ classes("prog", "prog--none") }) { Text("No tasks yet") }
        return
    }
    Span({
        classes("prog")
        topicColor(topic.color)
    }) {
        Span({
            classes("prog__segs")
            attr("aria-hidden", "true")
        }) {
            if (progress.total <= PROGRESS_SEGMENTS_MAX) {
                repeat(progress.total) { index -> I({ if (index < progress.done) classes("on") }) }
            } else {
                B({ style { property("--p", "${progress.done.toDouble() / progress.total}") } })
            }
        }
        Span({ classes("prog__txt") }) {
            Text("${progress.done} of ${progress.total}")
            Span({ classes("vh") }) { Text(" personal tasks done") }
        }
    }
}

private const val PROGRESS_SEGMENTS_MAX = 12

internal fun categoryLabel(category: StatusCategory): String =
    when (category) {
        StatusCategory.Planned -> "Planned"
        StatusCategory.Active -> "Active"
        StatusCategory.Paused -> "Paused"
        StatusCategory.Done -> "Done"
    }

/** What each category means, for the Settings legend (v3 CAT_HINT). */
internal fun categoryHint(category: StatusCategory): String =
    when (category) {
        StatusCategory.Planned -> "not started"
        StatusCategory.Active -> "shows in Today"
        StatusCategory.Paused -> "on hold"
        StatusCategory.Done -> "finished"
    }

/**
 * The `--tc` custom property that tints a Topic's row, card or header (v3 T.topicVar). Set through the CSSOM, because
 * the strict CSP blocks style attributes (adr/ADR-015).
 */
internal fun AttrsScope<*>.topicColor(color: TopicColor) {
    style { property("--tc", "var(--topic-${color.id})") }
}

internal fun topicPlace(topic: Topic): Place = Place.of("topic", topic.id.value)

/** "1 Topic", "3 Topics". */
internal fun topicCount(count: Int): String = if (count == 1) "1 Topic" else "$count Topics"

private const val HEX_POINTS = "50,4 89.8,27 89.8,73 50,96 10.2,73 10.2,27"
private const val SVG_NS = "http://www.w3.org/2000/svg"

/** Makes SVG elements in the SVG namespace; Compose HTML's own builder makes HTML elements only. */
private class SvgElementBuilder(
    private val tag: String,
) : ElementBuilder<Element> {
    override fun create(): Element = document.createElementNS(SVG_NS, tag)
}

private val svgBuilders = mutableMapOf<String, ElementBuilder<Element>>()

/** One SVG element. Attributes are set with `attr`, also `class` and `style`, which work the same on SVG. */
@Composable
private fun SvgTag(
    tag: String,
    attrs: AttrsScope<Element>.() -> Unit,
    content: (@Composable ElementScope<Element>.() -> Unit)? = null,
) {
    TagElement(svgBuilders.getOrPut(tag) { SvgElementBuilder(tag) }, attrs, content)
}
