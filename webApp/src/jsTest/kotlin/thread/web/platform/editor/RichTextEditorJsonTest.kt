package thread.web.platform.editor

import thread.domain.note.LinkHref
import thread.domain.note.ListItem
import thread.domain.note.RichTextBlock
import thread.domain.note.RichTextDocument
import thread.domain.note.RichTextInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RichTextEditorJsonTest {
    private fun editorJson(json: String): dynamic = JSON.parse<dynamic>(json)

    private fun paragraphDoc(vararg inlines: String): dynamic =
        editorJson("""{"type":"doc","content":[{"type":"paragraph","content":[${inlines.joinToString(",")}]}]}""")

    @Test
    fun linkWithAnAddressNotesDoNotAllowKeepsItsTextOnly() {
        val doc =
            paragraphDoc(
                """{"type":"text","text":"bad","marks":[{"type":"link","attrs":{"href":"javascript:alert(1)"}}]}""",
                """{"type":"text","text":" relative","marks":[{"type":"link","attrs":{"href":"/x"}}]}""",
                """{"type":"text","text":" good","marks":[{"type":"link","attrs":{"href":"https://example.com"}}]}""",
            )

        assertEquals(
            RichTextDocument(
                listOf(
                    RichTextBlock.Paragraph(
                        listOf(
                            RichTextInline.Text("bad relative"),
                            RichTextInline.Text(" good", link = LinkHref.parse("https://example.com")),
                        ),
                    ),
                ),
            ),
            RichTextEditorJson.fromEditor(doc),
        )
    }

    @Test
    fun adjacentRunsWithTheSameFormattingMerge() {
        val doc =
            paragraphDoc(
                """{"type":"text","text":"a","marks":[{"type":"bold"}]}""",
                """{"type":"text","text":"b","marks":[{"type":"bold"}]}""",
                """{"type":"hardBreak"}""",
                """{"type":"text","text":"c"}""",
            )

        assertEquals(
            RichTextBlock.Paragraph(listOf(RichTextInline.Text("ab", bold = true), RichTextInline.HardBreak, RichTextInline.Text("c"))),
            RichTextEditorJson.fromEditor(doc).blocks.single(),
        )
    }

    @Test
    fun orderedListStartThatIsNotAWholeNumberBecomesOne() {
        for (start in listOf("2.5", "null", "\"3\"")) {
            val doc =
                editorJson(
                    """{"type":"doc","content":[{"type":"orderedList","attrs":{"start":$start},"content":[""" +
                        """{"type":"listItem","content":[{"type":"paragraph"}]}]}]}""",
                )

            val list = RichTextEditorJson.fromEditor(doc).blocks.single() as RichTextBlock.OrderedList
            assertEquals(1, list.start, "start $start")
        }
    }

    @Test
    fun emptyEditorDocumentIsOneEmptyParagraph() {
        assertEquals(RichTextDocument.EMPTY, RichTextEditorJson.fromEditor(editorJson("""{"type":"doc"}""")))
    }

    @Test
    fun trailingEmptyParagraphAfterABlockThatIsNotAParagraphIsLeftOut() {
        val item = """{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"x"}]}]}"""
        val lastBlocks =
            mapOf(
                "heading" to """{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"x"}]}""",
                "bullet list" to """{"type":"bulletList","content":[$item]}""",
                "ordered list" to """{"type":"orderedList","attrs":{"start":1},"content":[$item]}""",
                "checklist" to
                    """{"type":"taskList","content":[{"type":"taskItem","attrs":{"checked":false},""" +
                    """"content":[{"type":"paragraph","content":[{"type":"text","text":"x"}]}]}]}""",
            )

        for ((name, block) in lastBlocks) {
            val withTrailing = editorJson("""{"type":"doc","content":[$block,{"type":"paragraph"}]}""")
            val without = editorJson("""{"type":"doc","content":[$block]}""")

            assertEquals(RichTextEditorJson.fromEditor(without), RichTextEditorJson.fromEditor(withTrailing), name)
            assertEquals(1, RichTextEditorJson.fromEditor(withTrailing).blocks.size, name)
        }
    }

    @Test
    fun emptyParagraphsTheUserKeepsAreNotLeftOut() {
        val afterAParagraph =
            editorJson("""{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"x"}]},{"type":"paragraph"}]}""")
        val inTheMiddle =
            editorJson(
                """{"type":"doc","content":[{"type":"heading","attrs":{"level":1}},{"type":"paragraph"},""" +
                    """{"type":"paragraph","content":[{"type":"text","text":"x"}]}]}""",
            )
        val afterAListWithText =
            editorJson(
                """{"type":"doc","content":[{"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph"}]}]},""" +
                    """{"type":"paragraph","content":[{"type":"text","text":"x"}]}]}""",
            )

        assertEquals(
            listOf(RichTextBlock.Paragraph(listOf(RichTextInline.Text("x"))), RichTextBlock.Paragraph(emptyList())),
            RichTextEditorJson.fromEditor(afterAParagraph).blocks,
        )
        assertEquals(3, RichTextEditorJson.fromEditor(inTheMiddle).blocks.size)
        assertEquals(2, RichTextEditorJson.fromEditor(afterAListWithText).blocks.size)
        val onlyAnEmptyParagraph = editorJson("""{"type":"doc","content":[{"type":"paragraph"}]}""")
        assertEquals(RichTextDocument.EMPTY, RichTextEditorJson.fromEditor(onlyAnEmptyParagraph))
    }

    @Test
    fun nodesAndMarksOutsideRichTextV1AreRefused() {
        val unknownBlock = editorJson("""{"type":"doc","content":[{"type":"blockquote","content":[{"type":"paragraph"}]}]}""")
        val unknownInline = paragraphDoc("""{"type":"image","attrs":{"src":"https://example.com/x.png"}}""")
        val unknownMark = paragraphDoc("""{"type":"text","text":"x","marks":[{"type":"underline"}]}""")
        val headingLevel4 = editorJson("""{"type":"doc","content":[{"type":"heading","attrs":{"level":4}}]}""")

        for (doc in listOf(unknownBlock, unknownInline, unknownMark, headingLevel4)) {
            assertFailsWith<IllegalStateException> { RichTextEditorJson.fromEditor(doc) }
        }
    }

    @Test
    fun editorJsonLeavesOutEmptyContentAndMarks() {
        val document =
            RichTextDocument(
                listOf(
                    RichTextBlock.Paragraph(emptyList()),
                    RichTextBlock.BulletList(listOf(ListItem(listOf(RichTextBlock.Paragraph(listOf(RichTextInline.Text("x"))))))),
                ),
            )

        assertEquals(
            """{"type":"doc","content":[{"type":"paragraph"},{"type":"bulletList","content":[{"type":"listItem",""" +
                """"content":[{"type":"paragraph","content":[{"type":"text","text":"x"}]}]}]}]}""",
            JSON.stringify(RichTextEditorJson.toEditor(document)),
        )
    }
}
