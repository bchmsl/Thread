package thread.domain.note

import thread.domain.note.RichTextBlock.BulletList
import thread.domain.note.RichTextBlock.Heading
import thread.domain.note.RichTextBlock.OrderedList
import thread.domain.note.RichTextBlock.Paragraph
import thread.domain.note.RichTextBlock.TaskList
import thread.domain.note.RichTextInline.HardBreak
import thread.domain.note.RichTextInline.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RichTextJsonTest {
    private fun link(href: String) = requireNotNull(LinkHref.parse(href))

    private fun para(vararg inlines: RichTextInline) = Paragraph(inlines.toList())

    /** Synthetic sample that uses every node and mark of schema version 1. */
    private val everything =
        RichTextDocument(
            listOf(
                Heading(1, listOf(Text("Synthetic note"))),
                Heading(2, listOf(Text("Plan"))),
                Heading(3, emptyList()),
                para(
                    Text("Plain, "),
                    Text("bold", bold = true),
                    Text(" and "),
                    Text("italic", italic = true),
                    Text(" both", bold = true, italic = true),
                    HardBreak,
                    Text("example link", link = link("https://example.com/a?b=1#c")),
                    Text(" mail", italic = true, link = link("mailto:someone@example.com")),
                ),
                para(),
                BulletList(
                    listOf(
                        ListItem(listOf(para(Text("One")))),
                        ListItem(
                            listOf(
                                para(Text("Two")),
                                OrderedList(3, listOf(ListItem(listOf(para(Text("Nested")))))),
                            ),
                        ),
                    ),
                ),
                TaskList(
                    listOf(
                        TaskItem(checked = true, blocks = listOf(para(Text("Done item")))),
                        TaskItem(
                            checked = false,
                            blocks =
                                listOf(
                                    para(Text("Open item")),
                                    TaskList(listOf(TaskItem(checked = false, blocks = listOf(para(Text("Sub item")))))),
                                ),
                        ),
                    ),
                ),
                para(Text("ქართული ტექსტი 👋 \"quotes\" \\ back")),
            ),
        )

    @Test
    fun roundTripKeepsEveryNodeAndMark() {
        val json = RichTextJson.encode(everything)

        assertEquals(everything, RichTextJson.decode(json))
    }

    @Test
    fun encodingIsStable() {
        assertEquals(RichTextJson.encode(everything), RichTextJson.encode(RichTextJson.decode(RichTextJson.encode(everything))))
    }

    @Test
    fun encodesTheDocumentedFormat() {
        val doc =
            RichTextDocument(
                listOf(
                    Heading(2, listOf(Text("Plan"))),
                    para(Text("Read", bold = true, link = link("https://example.com")), HardBreak),
                    TaskList(listOf(TaskItem(checked = false, blocks = listOf(para())))),
                ),
            )

        val expected =
            """{"schemaVersion":1,"content":[""" +
                """{"type":"heading","level":2,"content":[{"type":"text","text":"Plan"}]},""" +
                """{"type":"paragraph","content":[{"type":"text","text":"Read","marks":[{"type":"bold"},""" +
                """{"type":"link","href":"https://example.com"}]},{"type":"hardBreak"}]},""" +
                """{"type":"taskList","content":[{"type":"taskItem","checked":false,""" +
                """"content":[{"type":"paragraph","content":[]}]}]}]}"""
        assertEquals(expected, RichTextJson.encode(doc))
    }

    @Test
    fun emptyDocumentIsOneEmptyParagraph() {
        assertEquals("""{"schemaVersion":1,"content":[{"type":"paragraph","content":[]}]}""", RichTextJson.encode(RichTextDocument.EMPTY))
        assertEquals(RichTextDocument.EMPTY, RichTextJson.decode(RichTextJson.encode(RichTextDocument.EMPTY)))
    }

    @Test
    fun marksInAnyOrderAreRead() {
        val json =
            """{"schemaVersion":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"x",""" +
                """"marks":[{"type":"link","href":"http://a.b"},{"type":"italic"},{"type":"bold"}]}]}]}"""

        assertEquals(
            RichTextDocument(listOf(para(Text("x", bold = true, italic = true, link = link("http://a.b"))))),
            RichTextJson.decode(json),
        )
    }

    @Test
    fun rejectsUnknownBlockType() {
        assertRejected("""{"schemaVersion":1,"content":[{"type":"blockquote","content":[]}]}""", "unknown block type")
    }

    @Test
    fun rejectsUnknownInlineType() {
        assertRejected(
            """{"schemaVersion":1,"content":[{"type":"paragraph","content":[{"type":"image","src":"x.png"}]}]}""",
            "unknown inline type",
        )
    }

    @Test
    fun rejectsUnknownMark() {
        assertRejected(
            """{"schemaVersion":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"x","marks":[{"type":"strike"}]}]}]}""",
            "unknown mark",
        )
    }

    @Test
    fun rejectsDuplicateMark() {
        assertRejected(
            """{"schemaVersion":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"x",""" +
                """"marks":[{"type":"bold"},{"type":"bold"}]}]}]}""",
            "appears twice",
        )
    }

    @Test
    fun rejectsUnknownKey() {
        assertRejected("""{"schemaVersion":1,"content":[{"type":"paragraph","content":[],"align":"center"}]}""", "unknown key")
        assertRejected("""{"schemaVersion":1,"content":[{"type":"paragraph","content":[]}],"type":"doc"}""", "unknown key")
    }

    @Test
    fun rejectsOtherSchemaVersions() {
        assertRejected("""{"schemaVersion":2,"content":[{"type":"paragraph","content":[]}]}""", "unsupported schema version 2")
        assertRejected("""{"schemaVersion":"1","content":[{"type":"paragraph","content":[]}]}""", "expected an integer")
        assertRejected("""{"content":[{"type":"paragraph","content":[]}]}""", "missing \"schemaVersion\"")
    }

    @Test
    fun rejectsEditorJsonWithoutConversion() {
        assertRejected("""{"type":"doc","content":[{"type":"paragraph"}]}""", "unknown key")
    }

    @Test
    fun rejectsHeadingLevelOutsideOneToThree() {
        assertRejected("""{"schemaVersion":1,"content":[{"type":"heading","level":4,"content":[]}]}""", "heading level 4")
        assertRejected("""{"schemaVersion":1,"content":[{"type":"heading","level":0,"content":[]}]}""", "heading level 0")
    }

    @Test
    fun rejectsLinksThatAreNotAllowed() {
        listOf("javascript:alert(1)", "data:text/html,x", "ftp://example.com", "https://", "https://a b", "/relative").forEach { href ->
            assertRejected(
                """{"schemaVersion":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"x",""" +
                    """"marks":[{"type":"link","href":"$href"}]}]}]}""",
                "link address is not allowed",
            )
        }
    }

    @Test
    fun rejectsBrokenStructure() {
        assertRejected("not json", "Not valid JSON")
        assertRejected("""[]""", "expected an object")
        assertRejected("""{"schemaVersion":1,"content":[]}""", "at least one block")
        assertRejected("""{"schemaVersion":1,"content":[{"type":"paragraph","content":[{"type":"text","text":""}]}]}""", "never empty")
        assertRejected("""{"schemaVersion":1,"content":[{"type":"bulletList","content":[]}]}""", "at least one item")
        assertRejected(
            """{"schemaVersion":1,"content":[{"type":"bulletList","content":[{"type":"taskItem","checked":true,"content":[]}]}]}""",
            "expected \"listItem\"",
        )
        assertRejected(
            """{"schemaVersion":1,"content":[{"type":"bulletList","content":[{"type":"listItem","content":[""" +
                """{"type":"heading","level":2,"content":[]}]}]}]}""",
            "starts with a paragraph",
        )
        assertRejected(
            """{"schemaVersion":1,"content":[{"type":"taskList","content":[{"type":"taskItem","checked":"yes",""" +
                """"content":[{"type":"paragraph","content":[]}]}]}]}""",
            "expected true or false",
        )
    }

    @Test
    fun errorNamesThePath() {
        val error =
            assertFailsWith<RichTextFormatException> {
                RichTextJson.decode(
                    """{"schemaVersion":1,"content":[{"type":"paragraph","content":[]},{"type":"bulletList","content":[""" +
                        """{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"video"}]}]}]}]}""",
                )
            }
        assertEquals("$.content[1].content[0].content[0].content[0].type: unknown inline type \"video\"", error.message)
    }

    private fun assertRejected(
        json: String,
        messagePart: String,
    ) {
        val error = assertFailsWith<RichTextFormatException> { RichTextJson.decode(json) }
        val message = error.message.orEmpty()
        assertTrue(messagePart in message, "Expected \"$messagePart\" in \"$message\" for $json")
    }
}
