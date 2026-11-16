package thread.data.github

import thread.domain.external.CellAlign
import thread.domain.external.ExternalBlock
import thread.domain.external.ExternalBlock.Code
import thread.domain.external.ExternalBlock.Heading
import thread.domain.external.ExternalBlock.MarkdownList
import thread.domain.external.ExternalBlock.Paragraph
import thread.domain.external.ExternalBlock.Quote
import thread.domain.external.ExternalBlock.Rule
import thread.domain.external.ExternalBlock.Table
import thread.domain.external.ExternalBlock.Unsupported
import thread.domain.external.ExternalDocument
import thread.domain.external.ExternalInline
import thread.domain.external.ExternalInline.HardBreak
import thread.domain.external.ExternalListItem
import thread.domain.external.TableCell
import thread.domain.note.LinkHref
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** SYNTHETIC Markdown only (D57). */
class GitHubMarkdownTest {
    private fun md(text: String?): ExternalDocument = GitHubMarkdown.document(text, "synthetic-org", "synthetic-app")

    private fun blocks(text: String): List<ExternalBlock> = md(text).blocks

    private fun t(
        text: String,
        bold: Boolean = false,
        italic: Boolean = false,
        strike: Boolean = false,
        code: Boolean = false,
        link: String? = null,
    ) = ExternalInline.Text(text, bold, italic, strike, code, link?.let { LinkHref.parse(it)!! })

    private fun p(vararg inlines: ExternalInline) = Paragraph(inlines.toList())

    private fun p(text: String) = Paragraph(listOf(t(text)))

    private fun item(
        vararg blocks: ExternalBlock,
        checked: Boolean? = null,
    ) = ExternalListItem(checked, blocks.toList())

    private fun cell(vararg inlines: ExternalInline) = TableCell(inlines.toList())

    private fun cell(text: String) = if (text.isEmpty()) TableCell(emptyList()) else TableCell(listOf(t(text)))

    /** Every inline in the document, in reading order. */
    private fun ExternalDocument.inlines(): List<ExternalInline> = blocks.flatMap(::inlines)

    private fun inlines(block: ExternalBlock): List<ExternalInline> =
        when (block) {
            is Paragraph -> block.content
            is Heading -> block.content
            is MarkdownList -> block.items.flatMap { it.blocks.flatMap(::inlines) }
            is Quote -> block.blocks.flatMap(::inlines)
            is Table -> (block.header + block.rows.flatten()).flatMap { it.content }
            is Code -> listOf(ExternalInline.Text(block.text))
            is Unsupported -> listOf(ExternalInline.Text(block.kind))
            is ExternalBlock.Rich, Rule -> emptyList()
        }

    private fun ExternalDocument.text(): String = inlines().joinToString("") { if (it is ExternalInline.Text) it.text else "\n" }

    private fun ExternalDocument.links(): List<String> = inlines().mapNotNull { (it as? ExternalInline.Text)?.link?.value }

    @Test
    fun noDescriptionIsAnEmptyDocument() {
        assertEquals(ExternalDocument.EMPTY, md(null))
        assertEquals(ExternalDocument.EMPTY, md(""))
        assertEquals(ExternalDocument.EMPTY, md("  \n\n\t\n"))
        assertEquals(ExternalDocument.EMPTY, md("<!-- Only the template's hint. -->"))
    }

    @Test
    fun headingsAtxAndSetext() {
        val markdown =
            """
            # One
            ## Two
            ### Three
            #### Four
            ##### Five
            ###### Six
            ####### Seven
            #NoSpace

            Setext one
            ===
            Setext two
            ---
            ## Closing ##
            """.trimIndent()
        assertEquals(
            listOf(
                Heading(1, listOf(t("One"))),
                Heading(2, listOf(t("Two"))),
                Heading(3, listOf(t("Three"))),
                Heading(4, listOf(t("Four"))),
                Heading(5, listOf(t("Five"))),
                Heading(6, listOf(t("Six"))),
                p(t("####### Seven"), HardBreak, t("#NoSpace")),
                Heading(1, listOf(t("Setext one"))),
                Heading(2, listOf(t("Setext two"))),
                Heading(2, listOf(t("Closing"))),
            ),
            blocks(markdown),
        )
    }

    @Test
    fun emphasisStrikeAndInlineCode() {
        val markdown = "*italic* _also_ **bold** __also__ ***both*** ~~strike~~ ~one~ `code` snake_case_name 2 * 3 * 4 **unclosed"
        assertEquals(
            listOf(
                p(
                    t("italic", italic = true),
                    t(" "),
                    t("also", italic = true),
                    t(" "),
                    t("bold", bold = true),
                    t(" "),
                    t("also", bold = true),
                    t(" "),
                    t("both", bold = true, italic = true),
                    t(" "),
                    t("strike", strike = true),
                    t(" "),
                    t("one", strike = true),
                    t(" "),
                    t("code", code = true),
                    t(" snake_case_name 2 * 3 * 4 **unclosed"),
                ),
            ),
            blocks(markdown),
        )
    }

    @Test
    fun emphasisFollowsCommonMarkRules() {
        assertEquals(listOf(p(t("*"), t("foo", italic = true))), blocks("**foo*"))
        assertEquals(
            listOf(p(t("foo", italic = true), t("bar", bold = true, italic = true), t("baz", italic = true))),
            blocks("*foo**bar**baz*"),
        )
        assertEquals(listOf(p(t("a "), t("b", bold = true, strike = true), t(" c"))), blocks("a **~~b~~** c"))
        assertEquals(listOf(p("a ~~~three~~~ b")), blocks("a ~~~three~~~ b"))
        assertEquals(listOf(p("~~not closed~")), blocks("~~not closed~"))
    }

    @Test
    fun codeSpans() {
        assertEquals(
            listOf(p(t("a ` b", code = true), t(" and "), t("x", code = true), t(" and `unclosed"))),
            blocks("`` a ` b `` and ` x ` and `unclosed"),
        )
        // Nothing inside a code span is Markdown.
        assertEquals(
            listOf(p(t("**not bold** [no](https://example.com) <br>", code = true))),
            blocks("`**not bold** [no](https://example.com) <br>`"),
        )
    }

    @Test
    fun fencedAndIndentedCode() {
        val markdown =
            """
            ```kotlin
            val x = 1 < 2
              indented
            ```

            ~~~~
            tilde ~~~ inside
            ~~~~

                four spaces
                more

            text
            """.trimIndent()
        assertEquals(
            listOf(Code("val x = 1 < 2\n  indented"), Code("tilde ~~~ inside"), Code("four spaces\nmore"), p("text")),
            blocks(markdown),
        )
        assertEquals(listOf(Code("open to the end\n# not a heading")), blocks("```\nopen to the end\n# not a heading"))
        assertEquals(listOf(Code("tab")), blocks("\ttab"))
    }

    @Test
    fun listsNestedOrderedAndTasks() {
        val markdown =
            """
            - one
            - two
              - nested
                1. deep
            - [x] done
            - [ ] open
            * other marker

            3. three
            4. four

            1) paren
            """.trimIndent()
        assertEquals(
            listOf(
                MarkdownList(
                    null,
                    listOf(
                        item(p("one")),
                        item(p("two"), MarkdownList(null, listOf(item(p("nested"), MarkdownList(1, listOf(item(p("deep")))))))),
                        item(p("done"), checked = true),
                        item(p("open"), checked = false),
                    ),
                ),
                MarkdownList(null, listOf(item(p("other marker")))),
                MarkdownList(3, listOf(item(p("three")), item(p("four")))),
                MarkdownList(1, listOf(item(p("paren")))),
            ),
            blocks(markdown),
        )
    }

    @Test
    fun listItemsKeepLazyLinesBlankLinesAndCode() {
        assertEquals(
            listOf(MarkdownList(null, listOf(item(p(t("item"), HardBreak, t("lazy line"))))), p("after")),
            blocks("- item\nlazy line\n\nafter"),
        )
        assertEquals(
            listOf(MarkdownList(null, listOf(item(p("a")), item(p("b"))))),
            blocks("- a\n\n- b"),
        )
        assertEquals(
            listOf(MarkdownList(1, listOf(item(p("Run:"), Code("./gradlew check"))))),
            blocks("1. Run:\n   ```\n   ./gradlew check\n   ```"),
        )
        // A list that does not start at 1 does not interrupt a paragraph.
        assertEquals(listOf(p(t("Version"), HardBreak, t("2. is out"))), blocks("Version\n2. is out"))
    }

    @Test
    fun quotesNestAndTakeLazyLines() {
        assertEquals(
            listOf(
                Quote(listOf(p(t("quoted "), t("bold", bold = true), HardBreak, t("lazy")), Quote(listOf(p("nested"))))),
                p("after"),
            ),
            blocks("> quoted **bold**\nlazy\n> > nested\n\nafter"),
        )
    }

    @Test
    fun rules() {
        assertEquals(listOf(p("a"), Rule, Rule, Rule), blocks("a\n\n***\n\n- - -\n\n___"))
    }

    @Test
    fun tablesKeepAlignmentEscapedPipesAndRowShape() {
        val markdown =
            """
            Intro
            | Left | Center | Right | None |
            |:-----|:------:|------:|------|
            | `a\|b` | **bold** | 3 |
            | x | y | z | w | extra |

            a | b
            --|--
            1 | 2
            """.trimIndent()
        assertEquals(
            listOf(
                p("Intro"),
                Table(
                    listOf(CellAlign.Left, CellAlign.Center, CellAlign.Right, CellAlign.None),
                    listOf(cell("Left"), cell("Center"), cell("Right"), cell("None")),
                    listOf(
                        listOf(cell(t("a|b", code = true)), cell(t("bold", bold = true)), cell("3"), cell("")),
                        listOf(cell("x"), cell("y"), cell("z"), cell("w")),
                    ),
                ),
                Table(listOf(CellAlign.None, CellAlign.None), listOf(cell("a"), cell("b")), listOf(listOf(cell("1"), cell("2")))),
            ),
            blocks(markdown),
        )
        // The delimiter row must match the header's cell count.
        assertEquals(listOf(p(t("| a | b |"), HardBreak, t("|---|"))), blocks("| a | b |\n|---|"))
    }

    @Test
    fun linksAutolinksAndReferences() {
        val markdown =
            "[site](https://example.com \"title\") [mail](mailto:someone@example.com) [rel](/relative) [ref][r] [r] [collapsed][] " +
                "<https://auto.example.com> <someone@example.com> https://bare.example.com/path. (www.example.com/x)\n\n" +
                "[r]: https://ref.example.com"
        assertEquals(
            listOf(
                p(
                    t("site", link = "https://example.com"),
                    t(" "),
                    t("mail", link = "mailto:someone@example.com"),
                    t(" rel "),
                    t("ref", link = "https://ref.example.com"),
                    t(" "),
                    t("r", link = "https://ref.example.com"),
                    t(" [collapsed][] "),
                    t("https://auto.example.com", link = "https://auto.example.com"),
                    t(" "),
                    t("someone@example.com", link = "mailto:someone@example.com"),
                    t(" "),
                    t("https://bare.example.com/path", link = "https://bare.example.com/path"),
                    t(". ("),
                    t("www.example.com/x", link = "http://www.example.com/x"),
                    t(")"),
                ),
            ),
            blocks(markdown),
        )
        assertEquals(
            listOf(p(t("bold ", link = "https://example.com"), t("link", bold = true, link = "https://example.com"))),
            blocks("[bold **link**](https://example.com)"),
        )
    }

    @Test
    fun imagesBecomeLinksToTheImage() {
        val markdown =
            "![alt text](https://example.com/a.png) ![](https://example.com/b.png) ![rel](img/c.png) " +
                "<img src=\"https://example.com/d.png\" alt=\"Diagram\" width=\"300\"> <img src=x>"
        assertEquals(
            listOf(
                p(
                    t("Image: alt text", link = "https://example.com/a.png"),
                    t(" "),
                    t("Image", link = "https://example.com/b.png"),
                    t(" Image: rel "),
                    t("Image: Diagram", link = "https://example.com/d.png"),
                    t(" Image"),
                ),
            ),
            blocks(markdown),
        )
        assertEquals(
            listOf(p(t("Image: Screenshot", link = "https://example.com/full"))),
            blocks("[![Screenshot](https://example.com/thumb.png)](https://example.com/full)"),
        )
    }

    @Test
    fun commentsHideAndBreakTagsBreakOtherHtmlStaysText() {
        val markdown =
            """
            line one<br>line two<br/>three<BR />four
            <!-- hidden comment -->
            <kbd>Ctrl</kbd> <details><summary>More</summary>

            <!--
            A template hint
            over lines
            --> Visible after
            before <!-- inline --> after
            """.trimIndent()
        assertEquals(
            listOf(
                p(t("line one"), HardBreak, t("line two"), HardBreak, t("three"), HardBreak, t("four")),
                p("<kbd>Ctrl</kbd> <details><summary>More</summary>"),
                p("Visible after"),
                p("before  after"),
            ),
            blocks(markdown),
        )
    }

    @Test
    fun entitiesEscapesAndLineBreaks() {
        assertEquals(
            listOf(p("& <b> © # 😀 &unknown; � AT&T")),
            blocks("&amp; &lt;b&gt; &copy; &#35; &#x1F600; &unknown; &#0; AT&T"),
        )
        assertEquals(listOf(p("*not italic* # \\ \\a")), blocks("\\*not italic\\* \\# \\\\ \\a"))
        assertEquals(listOf(p(t("a"), HardBreak, t("b"))), blocks("a\\\nb"))
        assertEquals(listOf(p(t("one"), HardBreak, t("two"))), blocks("one\ntwo"))
        assertEquals(listOf(p(t("a"), HardBreak, t("b�c"))), blocks("a\r\nb\u0000c"))
    }

    @Test
    fun mentionsAndIssueNumbersLinkToGitHub() {
        assertEquals(
            listOf(
                p(
                    t("Thanks "),
                    t("@synthetic-user", link = "https://github.com/synthetic-user"),
                    t(" and @org/team, mail a@b.co, see "),
                    t("#12", link = "https://github.com/synthetic-org/synthetic-app/issues/12"),
                    t(", #0, x#5, #12a and ("),
                    t("#7", link = "https://github.com/synthetic-org/synthetic-app/issues/7"),
                    t(")."),
                ),
            ),
            blocks("Thanks @synthetic-user and @org/team, mail a@b.co, see #12, #0, x#5, #12a and (#7)."),
        )
        // Not inside code.
        assertEquals(listOf(Code("@synthetic-user #12")), blocks("```\n@synthetic-user #12\n```"))
    }

    @Test
    fun hostileMarkdownRendersAsHarmlessText() {
        val markdown =
            """
            <script>alert('x')</script>
            [click](javascript:alert(1)) [two](JAVASCRIPT:alert(1)) [three](  javascript:alert(1)) [data](data:text/html;base64,PHNjcmlwdD4=)
            [vb](vbscript:msgbox) [entity](jav&#x61;script:alert(1)) [escaped](java\script:alert(1)) [file](file:///etc/hosts)
            <javascript:alert(1)> <a href="javascript:alert(1)">a</a> <img src="javascript:alert(1)" onerror="alert(1)">
            <iframe src="https://evil.example"></iframe> <style>body{display:none}</style> <svg onload=alert(1)>
            ![x](javascript:alert(1)) <img src=data:image/png;base64,AAAA alt="pixel">

            [ref]: javascript:alert(1)

            [x][ref] and [y][ref]
            """.trimIndent()
        val document = md(markdown)
        assertEquals(emptyList(), document.links(), "no link survives")
        val text = document.text()
        for (visible in listOf(
            "<script>alert('x')</script>",
            "<a href=\"javascript:alert(1)\">a</a>",
            "<iframe src=\"https://evil.example\"></iframe>",
            "<style>body{display:none}</style>",
            "<svg onload=alert(1)>",
            "click two three data",
            "javascript:alert(1)",
            "Image: pixel",
            "x and y",
        )) {
            assertTrue(visible in text, "\"$visible\" stays visible text in: $text")
        }
        assertTrue(document.blocks.all { it is Paragraph }, "only paragraphs: ${document.blocks}")
    }

    @Test
    fun aHugeTableIsCappedAndSaysSo() {
        val header = (1..30).joinToString("|", "|", "|") { "H$it" }
        val delimiter = (1..30).joinToString("|", "|", "|") { "---" }
        // 150 rows by 30 columns, small enough to stay under the description cap.
        val rows = (1..150).joinToString("\n") { row -> "|<script>$row</script>" + "|x".repeat(29) + "|" }
        val blocks = blocks("$header\n$delimiter\n$rows")
        assertEquals(2, blocks.size)
        val table = blocks[0] as Table
        assertEquals(GitHubMarkdown.MAX_TABLE_COLUMNS, table.align.size)
        assertEquals(GitHubMarkdown.MAX_TABLE_ROWS, table.rows.size)
        assertEquals(cell("<script>1</script>"), table.rows[0][0])
        assertEquals(Unsupported(GitHubMarkdown.REST_OF_TABLE), blocks[1])
    }

    @Test
    fun aLongDescriptionIsCutAndSaysSo() {
        val cut = blocks("a".repeat(70_000))
        assertEquals(listOf(p("a".repeat(GitHubMarkdown.MAX_CHARS)), Unsupported(GitHubMarkdown.REST_OF_DESCRIPTION)), cut)
        // The cut never splits an emoji.
        val emoji = blocks("a".repeat(GitHubMarkdown.MAX_CHARS - 1) + "😀b")
        assertEquals(listOf(p("a".repeat(GitHubMarkdown.MAX_CHARS - 1)), Unsupported(GitHubMarkdown.REST_OF_DESCRIPTION)), emoji)
    }

    @Test
    fun theBlockBudgetStopsParsingAndSaysSo() {
        val blocks = blocks("x\n\n".repeat(2_500))
        assertEquals(GitHubMarkdown.MAX_BLOCKS + 1, blocks.size)
        assertEquals(Unsupported(GitHubMarkdown.REST_OF_DESCRIPTION), blocks.last())
    }

    @Test
    fun deepNestingIsCapped() {
        var block = blocks(">".repeat(100) + " deep").single()
        repeat(GitHubMarkdown.MAX_DEPTH) { block = (block as Quote).blocks.single() }
        assertEquals(Unsupported(GitHubMarkdown.DEEPLY_NESTED), block)

        // A list at the deepest level still shows; its item says what it does not show.
        var list = blocks("- ".repeat(100) + "x").single()
        repeat(GitHubMarkdown.MAX_DEPTH + 1) {
            list =
                (list as MarkdownList)
                    .items
                    .single()
                    .blocks
                    .single()
        }
        assertEquals(Unsupported(GitHubMarkdown.DEEPLY_NESTED), list)
    }

    @Test
    fun pathologicalInputFinishesQuickly() {
        val inputs =
            mapOf(
                "emphasis" to "*a".repeat(30_000),
                "strong" to "a**b".repeat(15_000),
                "mixed delimiters" to "*_~".repeat(20_000),
                "unmatched openers" to "**a ".repeat(15_000),
                "code runs" to (1..300).joinToString("a") { "`".repeat(it) },
                "brackets" to "[".repeat(60_000),
                "empty destinations" to "[](".repeat(20_000),
                "nested links" to "[".repeat(1_000) + "a" + "](https://example.com)".repeat(1_000),
                "references" to "[a]: https://example.com\n\n" + "[".repeat(30_000) + "]".repeat(30_000),
                "ampersands" to "&".repeat(65_000),
                "comment openers" to "<!--".repeat(16_000),
                "comments in links" to "[<!--](https://example.com)".repeat(2_500) + "-->",
                "image tags" to "<img \"".repeat(10_000),
                "angles" to "<a".repeat(30_000),
                "quotes" to "> ".repeat(30_000) + "x",
                "lists" to "- ".repeat(30_000) + "x",
                "lines" to "a\n".repeat(30_000),
                "wide table" to "|a".repeat(10_000) + "\n" + "|-".repeat(10_000),
                "bare links" to "www.a ".repeat(10_000),
                "link ends" to "https://a.example/" + ")".repeat(2_000),
                "mentions" to "@a ".repeat(20_000),
                "issues" to "#1 ".repeat(20_000),
            )
        for ((name, input) in inputs) {
            val start = TimeSource.Monotonic.markNow()
            md(input)
            val ms = start.elapsedNow().inWholeMilliseconds
            assertTrue(ms < 1_500, "$name took $ms ms")
        }
    }
}
