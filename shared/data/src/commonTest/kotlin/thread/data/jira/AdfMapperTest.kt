package thread.data.jira

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import thread.domain.external.BlockTableCell
import thread.domain.external.BlockTableRow
import thread.domain.external.ExternalBlock
import thread.domain.external.ExternalBlock.BlockTable
import thread.domain.external.ExternalBlock.Code
import thread.domain.external.ExternalBlock.Expand
import thread.domain.external.ExternalBlock.Heading
import thread.domain.external.ExternalBlock.MarkdownList
import thread.domain.external.ExternalBlock.Panel
import thread.domain.external.ExternalBlock.Paragraph
import thread.domain.external.ExternalBlock.Quote
import thread.domain.external.ExternalBlock.Rule
import thread.domain.external.ExternalBlock.Unsupported
import thread.domain.external.ExternalDocument
import thread.domain.external.ExternalInline
import thread.domain.external.ExternalInline.HardBreak
import thread.domain.external.ExternalInline.Status
import thread.domain.external.ExternalInline.Text
import thread.domain.external.ExternalListItem
import thread.domain.external.PanelTone
import thread.domain.external.StatusColor
import thread.domain.note.LinkHref
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SYNTHETIC ADF only. */
class AdfMapperTest {
    private fun map(json: String): ExternalDocument = AdfMapper.document(Json.parseToJsonElement(json))

    private fun doc(vararg blocks: String): String = """{"type":"doc","version":1,"content":[${blocks.joinToString(",")}]}"""

    private fun p(vararg inlines: ExternalInline) = Paragraph(inlines.toList())

    private fun p(text: String) = """{"type":"paragraph","content":[{"type":"text","text":"$text"}]}"""

    private fun item(vararg blocks: ExternalBlock) = ExternalListItem(null, blocks.toList())

    private fun cell(
        text: String,
        header: Boolean = false,
        colspan: Int = 1,
        rowspan: Int = 1,
    ) = BlockTableCell(header, colspan, rowspan, listOf(p(Text(text))))

    @Test
    fun noDescriptionIsAnEmptyDocument() {
        assertEquals(ExternalDocument.EMPTY, AdfMapper.document(null))
        assertEquals(ExternalDocument.EMPTY, map("null"))
        assertEquals(ExternalDocument(listOf(Unsupported("This content"))), map("""{"type":"paragraph"}"""))
    }

    @Test
    fun textKeepsBoldItalicStrikeCodeAndSafeLinksOnly() {
        val document =
            map(
                doc(
                    """
                    {"type":"paragraph","content":[
                      {"type":"text","text":"b","marks":[{"type":"strong"},{"type":"em"}]},
                      {"type":"text","text":"ok","marks":[{"type":"link","attrs":{"href":"https://example.test/a"}}]},
                      {"type":"text","text":"bad","marks":[{"type":"link","attrs":{"href":"javascript:alert(1)"}}]},
                      {"type":"text","text":"code","marks":[{"type":"code"},{"type":"strike"},{"type":"textColor","attrs":{"color":"#f00"}}]},
                      {"type":"text","text":"u","marks":[{"type":"underline"},{"type":"subsup","attrs":{"type":"sub"}}]},
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
                Text("code", strike = true, code = true),
                Text("u"),
                HardBreak,
            )
        assertEquals(listOf(expected), document.blocks)
    }

    @Test
    fun inlineNodesBecomeTextAndStatusesKeepTheirColor() {
        val document =
            map(
                doc(
                    """
                    {"type":"paragraph","content":[
                      {"type":"mention","attrs":{"id":"synthetic-account","text":"@Synthetic A"}},
                      {"type":"mention","attrs":{}},
                      {"type":"emoji","attrs":{"shortName":":x:"}},
                      {"type":"date","attrs":{"timestamp":"1794873600000"}},
                      {"type":"status","attrs":{"text":"DONE"}},
                      {"type":"status","attrs":{"text":" Live ","color":"green"}},
                      {"type":"status","attrs":{"text":"Odd","color":"magenta"}},
                      {"type":"status","attrs":{"text":"  ","color":"red"}},
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
                Status("DONE", StatusColor.Neutral),
                Status("Live", StatusColor.Green),
                Status("Odd", StatusColor.Neutral),
                Text("https://example.test/card", link = LinkHref.parse("https://example.test/card")),
                Text("[attachment]", italic = true),
                Text("[not shown]", italic = true),
            )
        assertEquals(listOf(expected), document.blocks)
    }

    @Test
    fun everyStatusColorMaps() {
        val colors = listOf("neutral", "purple", "blue", "red", "yellow", "green")
        val inlines = colors.joinToString(",") { """{"type":"status","attrs":{"text":"$it","color":"$it"}}""" }

        val document = map(doc("""{"type":"paragraph","content":[$inlines]}"""))

        val expected = colors.zip(StatusColor.entries).map { (text, color) -> Status(text, color) }
        assertEquals(listOf(Paragraph(expected)), document.blocks)
    }

    @Test
    fun headingsKeepLevelsOneToSix() {
        val document =
            map(
                doc(
                    """{"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"One"}]}""",
                    """{"type":"heading","attrs":{"level":6},"content":[{"type":"text","text":"Six"}]}""",
                    """{"type":"heading","attrs":{"level":9},"content":[{"type":"text","text":"Nine"}]}""",
                    """{"type":"heading","content":[{"type":"text","text":"None"}]}""",
                ),
            )

        assertEquals(
            listOf(
                Heading(1, listOf(Text("One"))),
                Heading(6, listOf(Text("Six"))),
                Heading(6, listOf(Text("Nine"))),
                Heading(1, listOf(Text("None"))),
            ),
            document.blocks,
        )
    }

    @Test
    fun listItemsHoldAnyBlock() {
        val document =
            map(
                doc(
                    """
                    {"type":"orderedList","attrs":{"order":3},"content":[
                      {"type":"listItem","content":[${p("Three")},
                        {"type":"bulletList","content":[{"type":"listItem","content":[${p("Nested")}]}]}]},
                      {"type":"listItem","content":[{"type":"codeBlock","attrs":{"language":"kotlin"},"content":[{"type":"text","text":"a\nb"}]}]},
                      {"type":"listItem","content":[{"type":"table","content":[]}]},
                      ${p("Not an item")}
                    ]}
                    """,
                    """{"type":"bulletList","content":[]}""",
                ),
            )

        val nested = MarkdownList(null, listOf(item(p(Text("Nested")))))
        val expected =
            MarkdownList(
                3,
                listOf(
                    item(p(Text("Three")), nested),
                    item(Code("a\nb", "kotlin")),
                    item(),
                    item(p(Text("Not an item"))),
                ),
            )
        assertEquals(listOf(expected), document.blocks)
    }

    @Test
    fun checklistsKeepTheirStateAndDecisionsAreMarked() {
        val document =
            map(
                doc(
                    """
                    {"type":"taskList","content":[
                      {"type":"taskList","content":[{"type":"taskItem","attrs":{"state":"TODO"},"content":[{"type":"text","text":"First nested"}]}]},
                      {"type":"taskItem","attrs":{"state":"DONE"},"content":[{"type":"text","text":"Done"}]},
                      {"type":"taskList","content":[{"type":"taskItem","attrs":{"state":"TODO"},"content":[{"type":"text","text":"Nested"}]}]},
                      {"type":"taskItem","attrs":{"state":"TODO"},"content":[${p("Wrapped")}]},
                      {"type":"blockTaskItem","attrs":{"state":"DONE"},"content":[${p("Block")},${p("Second")}]}
                    ]}
                    """,
                    """{"type":"decisionList","content":[{"type":"decisionItem","content":[{"type":"text","text":"Decided"}]},{"type":"rule"}]}""",
                    """{"type":"taskList","content":[]}""",
                ),
            )

        val firstNested = MarkdownList(null, listOf(ExternalListItem(false, listOf(p(Text("First nested"))))))
        val nested = MarkdownList(null, listOf(ExternalListItem(false, listOf(p(Text("Nested"))))))
        val tasks =
            MarkdownList(
                null,
                listOf(
                    item(firstNested),
                    ExternalListItem(true, listOf(p(Text("Done")), nested)),
                    ExternalListItem(false, listOf(p(Text("Wrapped")))),
                    ExternalListItem(true, listOf(p(Text("Block")), p(Text("Second")))),
                ),
            )
        val decisions = MarkdownList(null, listOf(ExternalListItem(null, listOf(p(Text("Decided"))), decision = true)))
        assertEquals(listOf(tasks, decisions), document.blocks)
    }

    @Test
    fun panelsKeepTheirToneAndContent() {
        val types = listOf("info", "note", "success", "tip", "warning", "error", "custom", "")
        val document = map(doc(*types.map { """{"type":"panel","attrs":{"panelType":"$it"},"content":[${p("Text")}]}""" }.toTypedArray()))

        val tones =
            listOf(
                PanelTone.Info,
                PanelTone.Note,
                PanelTone.Success,
                PanelTone.Success,
                PanelTone.Warning,
                PanelTone.Error,
                PanelTone.Neutral,
                PanelTone.Neutral,
            )
        assertEquals(tones.map { Panel(it, listOf(p(Text("Text")))) }, document.blocks)
    }

    @Test
    fun expandsAndLayoutsShowTheirContent() {
        val document =
            map(
                doc(
                    """
                    {"type":"expand","attrs":{"title":"  More  "},"content":[${p("Inside")},
                      {"type":"nestedExpand","attrs":{"title":"Deeper"},"content":[${p("Deepest")}]}]}
                    """,
                    """{"type":"expand","attrs":{"title":" "},"content":[${p("Untitled")}]}""",
                    """
                    {"type":"layoutSection","content":[
                      {"type":"layoutColumn","attrs":{"width":50},"content":[${p("Left")}]},
                      {"type":"layoutColumn","attrs":{"width":50},"content":[${p("Right")}]}]}
                    """,
                    """{"type":"blockquote","content":[${p("Quote")}]}""",
                    """{"type":"rule"}""",
                ),
            )

        val expected =
            listOf(
                Expand("More", listOf(p(Text("Inside")), Expand("Deeper", listOf(p(Text("Deepest")))))),
                Expand(null, listOf(p(Text("Untitled")))),
                p(Text("Left")),
                p(Text("Right")),
                Quote(listOf(p(Text("Quote")))),
                Rule,
            )
        assertEquals(expected, document.blocks)
    }

    @Test
    fun codeKeepsItsLinesAndASafeLanguageName() {
        val document =
            map(
                doc(
                    """{"type":"codeBlock","content":[{"type":"text","text":"x = 1"},{"type":"hardBreak"},{"type":"text","text":"y"}]}""",
                    """{"type":"codeBlock","attrs":{"language":"c++"},"content":[{"type":"text","text":"int x;"}]}""",
                    """{"type":"codeBlock","attrs":{"language":"<script>"},"content":[{"type":"text","text":"z"}]}""",
                ),
            )

        assertEquals(listOf(Code("x = 1\ny"), Code("int x;", "c++"), Code("z")), document.blocks)
    }

    @Test
    fun tablesKeepHeadersSpansBlocksAndRowNumbers() {
        val document =
            map(
                doc(
                    """
                    {"type":"table","attrs":{"isNumberColumnEnabled":true,"layout":"wide"},"content":[
                      {"type":"tableRow","content":[
                        {"type":"tableHeader","attrs":{"colwidth":[100],"background":"#fff"},"content":[${p("Area")}]},
                        {"type":"tableHeader","attrs":{"colspan":2},"content":[${p("Owner")}]}]},
                      {"type":"tableRow","content":[
                        {"type":"tableHeader","attrs":{"rowspan":2},"content":[${p("Mapper")}]},
                        {"type":"tableCell","attrs":{"colspan":0,"rowspan":0},"content":[
                          {"type":"bulletList","content":[{"type":"listItem","content":[${p("Point")}]}]}]},
                        {"type":"paragraph","content":[]},
                        {"type":"tableCell","content":[]}]},
                      {"type":"tableRow","content":[]},
                      {"type":"paragraph","content":[]}
                    ]}
                    """,
                    """{"type":"table","content":[{"type":"tableRow","content":[]}]}""",
                ),
            )

        val expected =
            BlockTable(
                listOf(
                    BlockTableRow(listOf(cell("Area", header = true), cell("Owner", header = true, colspan = 2))),
                    BlockTableRow(
                        listOf(
                            cell("Mapper", header = true, rowspan = 2),
                            BlockTableCell(false, 1, 1, listOf(MarkdownList(null, listOf(item(p(Text("Point"))))))),
                            BlockTableCell(false, 1, 1, emptyList()),
                        ),
                    ),
                ),
                numbered = true,
            )
        assertEquals(listOf(expected), document.blocks)
    }

    @Test
    fun bigTablesAreCutAndSaySo() {
        val row = """{"type":"tableRow","content":[{"type":"tableCell","content":[${p("x")}]}]}"""
        val tall = """{"type":"table","content":[${List(AdfMapper.MAX_TABLE_ROWS + 1) { row }.joinToString(",")}]}"""
        val wideCells = List(AdfMapper.MAX_TABLE_COLUMNS + 5) { """{"type":"tableCell","content":[]}""" }.joinToString(",")
        val wide = """{"type":"table","content":[{"type":"tableRow","content":[$wideCells]}]}"""
        val spans =
            """
            {"type":"table","content":[{"type":"tableRow","content":[
              {"type":"tableCell","attrs":{"colspan":19},"content":[]},
              {"type":"tableCell","attrs":{"colspan":5,"rowspan":500},"content":[]}]}]}
            """

        val blocks = map(doc(tall, wide, spans)).blocks

        assertEquals(6, blocks.size)
        assertEquals(AdfMapper.MAX_TABLE_ROWS, assertIs<BlockTable>(blocks[0]).rows.size)
        assertEquals(Unsupported(AdfMapper.REST_OF_TABLE), blocks[1])
        assertEquals(
            AdfMapper.MAX_TABLE_COLUMNS,
            assertIs<BlockTable>(blocks[2])
                .rows
                .single()
                .cells.size,
        )
        assertEquals(Unsupported(AdfMapper.REST_OF_TABLE), blocks[3])
        val clamped = assertIs<BlockTable>(blocks[4]).rows.single().cells
        assertEquals(listOf(19 to 1, 1 to AdfMapper.MAX_TABLE_ROWS), clamped.map { it.colspan to it.rowspan })
        assertEquals(Unsupported(AdfMapper.REST_OF_TABLE), blocks[5])
    }

    @Test
    fun picturesAndFilesArePlaceholdersNamedByTheirAltText() {
        val longName = "n".repeat(200)
        val document =
            map(
                doc(
                    """{"type":"mediaSingle","content":[{"type":"media","attrs":{"id":"m1","type":"file","alt":" screen.png "}}]}""",
                    """
                    {"type":"mediaGroup","content":[
                      {"type":"media","attrs":{"id":"m2","type":"file","alt":"spec.pdf"}},
                      {"type":"media","attrs":{"id":"m3","type":"file"}}]}
                    """,
                    """{"type":"mediaSingle","content":[]}""",
                    """{"type":"mediaSingle","content":[{"type":"media","attrs":{"alt":"$longName"}}]}""",
                ),
            )

        assertEquals(
            listOf(
                Unsupported("Image “screen.png”"),
                Unsupported("Attachment “spec.pdf”"),
                Unsupported("Attachment"),
                Unsupported("Image"),
                Unsupported("Image “${"n".repeat(120)}”"),
            ),
            document.blocks,
        )
    }

    @Test
    fun appContentCardsAndUnknownNodesArePlaceholders() {
        val document =
            map(
                doc(
                    """{"type":"extension","attrs":{"extensionKey":"x"}}""",
                    """{"type":"bodiedExtension","content":[]}""",
                    """{"type":"blockCard","attrs":{"url":"https://example.test/x"}}""",
                    """{"type":"embedCard","attrs":{"url":"javascript:x"}}""",
                    """{"type":"syncBlock"}""",
                    """{"type":"<img src=x>"}""",
                ),
            )

        val link = LinkHref.parse("https://example.test/x")
        val expected =
            listOf(
                Unsupported("App content"),
                Unsupported("App content"),
                p(Text("https://example.test/x", link = link)),
                Unsupported("Link card"),
                Unsupported("Jira content \"syncBlock\""),
                Unsupported("This content"),
            )
        assertEquals(expected, document.blocks)
    }

    @Test
    fun veryDeepNestingStopsWithAPlaceholder() {
        var node = p("deep")
        repeat(30) { node = """{"type":"blockquote","content":[$node]}""" }

        var blocks = map(doc(node)).blocks
        var quotes = 0
        while (blocks.singleOrNull() is Quote) {
            blocks = (blocks.single() as Quote).blocks
            quotes++
        }

        assertEquals(20, quotes)
        assertEquals(listOf(Unsupported(AdfMapper.DEEPLY_NESTED)), blocks)
    }

    @Test
    fun theStoredCopyHasNoMentionAccountIds() {
        val adf =
            doc(
                """{"type":"paragraph","content":[{"type":"mention","attrs":{"id":"synthetic-account-1","text":"@Synthetic A","accessLevel":""}}]}""",
                """
                {"type":"table","content":[{"type":"tableRow","content":[{"type":"tableCell","content":[
                  {"type":"paragraph","content":[{"type":"mention","attrs":{"id":"synthetic-account-2","text":"@Synthetic B"}}]}]}]}]}
                """,
                """{"type":"taskList","attrs":{"localId":"synthetic-list"},"content":[]}""",
            )

        val stored = AdfMapper.forStorage(Json.parseToJsonElement(adf)).toString()

        assertFalse("synthetic-account" in stored, stored)
        assertTrue("@Synthetic A" in stored && "@Synthetic B" in stored && "synthetic-list" in stored, stored)
        assertEquals(map(adf), AdfMapper.document(Json.parseToJsonElement(stored)))
        assertNull(AdfMapper.forStorage(null))
        assertIs<JsonObject>(AdfMapper.forStorage(Json.parseToJsonElement(adf)))
    }
}
