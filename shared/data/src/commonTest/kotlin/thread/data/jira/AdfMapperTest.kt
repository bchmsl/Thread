package thread.data.jira

import kotlinx.serialization.json.Json
import thread.domain.external.ExternalBlock
import thread.domain.external.ExternalDocument
import thread.domain.note.LinkHref
import thread.domain.note.ListItem
import thread.domain.note.RichTextBlock
import thread.domain.note.RichTextBlock.BulletList
import thread.domain.note.RichTextBlock.Heading
import thread.domain.note.RichTextBlock.OrderedList
import thread.domain.note.RichTextBlock.Paragraph
import thread.domain.note.RichTextBlock.TaskList
import thread.domain.note.RichTextInline.HardBreak
import thread.domain.note.RichTextInline.Text
import thread.domain.note.TaskItem
import kotlin.test.Test
import kotlin.test.assertEquals

/** SYNTHETIC ADF only. */
class AdfMapperTest {
    private fun map(json: String): ExternalDocument = AdfMapper.document(Json.parseToJsonElement(json))

    private fun doc(vararg blocks: String): String = """{"type":"doc","version":1,"content":[${blocks.joinToString(",")}]}"""

    private fun rich(block: RichTextBlock) = ExternalBlock.Rich(block)

    private fun p(vararg inlines: thread.domain.note.RichTextInline) = Paragraph(inlines.toList())

    @Test
    fun noDescriptionIsAnEmptyDocument() {
        assertEquals(ExternalDocument.EMPTY, AdfMapper.document(null))
        assertEquals(ExternalDocument.EMPTY, map("null"))
        assertEquals(ExternalDocument(listOf(ExternalBlock.Unsupported("This content"))), map("""{"type":"paragraph"}"""))
    }

    @Test
    fun textMarksKeepBoldItalicAndSafeLinksOnly() {
        val document =
            map(
                doc(
                    """
                    {"type":"paragraph","content":[
                      {"type":"text","text":"b","marks":[{"type":"strong"},{"type":"em"}]},
                      {"type":"text","text":"ok","marks":[{"type":"link","attrs":{"href":"https://example.test/a"}}]},
                      {"type":"text","text":"bad","marks":[{"type":"link","attrs":{"href":"javascript:alert(1)"}}]},
                      {"type":"text","text":"code","marks":[{"type":"code"},{"type":"strike"},{"type":"textColor","attrs":{"color":"#f00"}}]},
                      {"type":"text","text":""},
                      {"type":"hardBreak"}
                    ]}
                    """,
                ),
            )

        val expected =
            p(
                Text("b", bold = true, italic = true),
                Text("ok", link = LinkHref.parse("https://example.test/a")),
                Text("bad"),
                Text("code"),
                HardBreak,
            )
        assertEquals(listOf(rich(expected)), document.blocks)
    }

    @Test
    fun inlineNodesBecomeText() {
        val document =
            map(
                doc(
                    """
                    {"type":"paragraph","content":[
                      {"type":"mention","attrs":{"text":"@Synthetic A"}},
                      {"type":"mention","attrs":{}},
                      {"type":"emoji","attrs":{"shortName":":x:"}},
                      {"type":"date","attrs":{"timestamp":"1794873600000"}},
                      {"type":"status","attrs":{"text":"DONE"}},
                      {"type":"inlineCard","attrs":{"url":"https://example.test/card"}},
                      {"type":"placeholder","attrs":{"text":"hint"}},
                      {"type":"mediaInline","attrs":{}},
                      {"type":"somethingNew"}
                    ]}
                    """,
                ),
            )

        val expected =
            p(
                Text("@Synthetic A"),
                Text("@someone"),
                Text(":x:"),
                Text("2026-11-17"),
                Text("DONE"),
                Text("https://example.test/card", link = LinkHref.parse("https://example.test/card")),
                Text("[attachment]", italic = true),
                Text("[not shown]", italic = true),
            )
        assertEquals(listOf(rich(expected)), document.blocks)
    }

    @Test
    fun headingsAboveLevelThreeShowAsLevelThree() {
        val document =
            map(
                doc(
                    """{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"One"}]}""",
                    """{"type":"heading","attrs":{"level":6},"content":[{"type":"text","text":"Six"}]}""",
                ),
            )

        assertEquals(listOf(rich(Heading(1, listOf(Text("One")))), rich(Heading(3, listOf(Text("Six"))))), document.blocks)
    }

    @Test
    fun listsKeepTheirShapeAndListItemsStartWithAParagraph() {
        val document =
            map(
                doc(
                    """
                    {"type":"orderedList","attrs":{"order":3},"content":[
                      {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Three"}]},
                        {"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Nested"}]}]}]}]},
                      {"type":"listItem","content":[{"type":"codeBlock","content":[{"type":"text","text":"a\nb"}]}]},
                      {"type":"listItem","content":[{"type":"table","content":[]}]}
                    ]}
                    """,
                    """{"type":"bulletList","content":[]}""",
                ),
            )

        val nested = BulletList(listOf(ListItem(listOf(p(Text("Nested"))))))
        val expected =
            OrderedList(
                3,
                listOf(
                    ListItem(listOf(p(Text("Three")), nested)),
                    ListItem(listOf(p(Text("a"), HardBreak, Text("b")))),
                    ListItem(listOf(p(Text("Table is not shown here.", italic = true)))),
                ),
            )
        assertEquals(listOf(rich(expected)), document.blocks)
    }

    @Test
    fun checklistsAndDecisions() {
        val document =
            map(
                doc(
                    """
                    {"type":"taskList","content":[
                      {"type":"taskItem","attrs":{"state":"DONE"},"content":[{"type":"text","text":"Done"}]},
                      {"type":"taskList","content":[{"type":"taskItem","attrs":{"state":"TODO"},"content":[{"type":"text","text":"Nested"}]}]},
                      {"type":"taskItem","attrs":{"state":"TODO"},"content":[{"type":"paragraph","content":[{"type":"text","text":"Wrapped"}]}]}
                    ]}
                    """,
                    """{"type":"decisionList","content":[{"type":"decisionItem","content":[{"type":"text","text":"Decided"}]}]}""",
                ),
            )

        val nested = TaskList(listOf(TaskItem(false, listOf(p(Text("Nested"))))))
        val tasks =
            TaskList(
                listOf(
                    TaskItem(true, listOf(p(Text("Done")), nested)),
                    TaskItem(false, listOf(p(Text("Wrapped")))),
                ),
            )
        val decisions = BulletList(listOf(ListItem(listOf(p(Text("Decided"))))))
        assertEquals(listOf(rich(tasks), rich(decisions)), document.blocks)
    }

    @Test
    fun containersShowTheirContentAndUnsupportedNodesArePlaceholders() {
        val document =
            map(
                doc(
                    """{"type":"blockquote","content":[{"type":"paragraph","content":[{"type":"text","text":"Quote"}]}]}""",
                    """{"type":"panel","content":[{"type":"paragraph","content":[{"type":"text","text":"Panel"}]}]}""",
                    """{"type":"expand","attrs":{"title":"More"},"content":[{"type":"paragraph","content":[{"type":"text","text":"Inside"}]}]}""",
                    """{"type":"codeBlock","content":[{"type":"text","text":"x = 1"},{"type":"hardBreak"},{"type":"text","text":"y"}]}""",
                    """{"type":"rule"}""",
                    """{"type":"table","content":[]}""",
                    """{"type":"mediaSingle","content":[]}""",
                    """{"type":"bodiedExtension","content":[]}""",
                    """{"type":"blockCard","attrs":{"url":"https://example.test/x"}}""",
                    """{"type":"blockCard","attrs":{"url":"javascript:x"}}""",
                    """{"type":"syncBlock"}""",
                    """{"type":"<img src=x>"}""",
                ),
            )

        val link = LinkHref.parse("https://example.test/x")
        val expected =
            listOf(
                rich(p(Text("Quote"))),
                rich(p(Text("Panel"))),
                rich(p(Text("More", bold = true))),
                rich(p(Text("Inside"))),
                ExternalBlock.Code("x = 1\ny"),
                ExternalBlock.Unsupported("Table"),
                ExternalBlock.Unsupported("Image or attachment"),
                ExternalBlock.Unsupported("App content"),
                rich(p(Text("https://example.test/x", link = link))),
                ExternalBlock.Unsupported("Link card"),
                ExternalBlock.Unsupported("Jira content \"syncBlock\""),
                ExternalBlock.Unsupported("This content"),
            )
        assertEquals(expected, document.blocks)
    }

    @Test
    fun veryDeepNestingStopsWithAPlaceholder() {
        var node = """{"type":"paragraph","content":[{"type":"text","text":"deep"}]}"""
        repeat(30) { node = """{"type":"blockquote","content":[$node]}""" }

        val document = map(doc(node))

        assertEquals(listOf(ExternalBlock.Unsupported("Deeply nested content")), document.blocks)
    }
}
