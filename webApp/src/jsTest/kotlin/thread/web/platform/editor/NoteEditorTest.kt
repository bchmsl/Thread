package thread.web.platform.editor

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.w3c.dom.DataTransfer
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.clipboard.ClipboardEvent
import org.w3c.dom.clipboard.ClipboardEventInit
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import thread.domain.note.LinkHref
import thread.domain.note.ListItem
import thread.domain.note.RichTextBlock
import thread.domain.note.RichTextDocument
import thread.domain.note.RichTextInline
import thread.domain.note.TaskItem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the real Tiptap editor in headless Chrome. All content is synthetic. */
class NoteEditorTest {
    private val mounts = mutableListOf<HTMLElement>()
    private val editors = mutableListOf<NoteEditor>()
    private val changes = mutableListOf<RichTextDocument>()
    private val failures = mutableListOf<Throwable>()

    @AfterTest
    fun cleanUp() {
        editors.forEach { it.destroy() }
        mounts.forEach { it.remove() }
        assertTrue(failures.isEmpty(), "Editor failures: $failures")
    }

    private fun createEditor(
        initial: RichTextDocument,
        editable: Boolean = true,
        elementId: String = "editor",
    ): NoteEditor {
        val mount = document.createElement("div") as HTMLElement
        document.body!!.appendChild(mount)
        mounts += mount
        return NoteEditor(
            mount,
            initial,
            editable = editable,
            elementId = elementId,
            onChange = { changes += it },
            onFormatting = {},
            onFailure = { failures += it },
        ).also { editors += it }
    }

    private fun NoteEditor.dom(): HTMLElement = mounts[editors.indexOf(this)].querySelector(".ProseMirror") as HTMLElement

    private fun href(value: String) = requireNotNull(LinkHref.parse(value))

    private fun paragraph(vararg inlines: RichTextInline) = RichTextBlock.Paragraph(inlines.toList())

    private fun text(value: String) = RichTextInline.Text(value)

    @Test
    fun everyRichTextV1ConstructSurvivesTheEditorUnchanged() {
        val note =
            RichTextDocument(
                listOf(
                    RichTextBlock.Heading(1, listOf(text("One"))),
                    RichTextBlock.Heading(2, listOf(text("Two"))),
                    RichTextBlock.Heading(3, listOf(text("Three"))),
                    paragraph(
                        text("plain "),
                        RichTextInline.Text("bold", bold = true),
                        RichTextInline.Text("both", bold = true, italic = true),
                        text(" "),
                        RichTextInline.Text("link", link = href("https://example.com/a?b=1#c")),
                        RichTextInline.HardBreak,
                        RichTextInline.Text("mail", italic = true, link = href("mailto:someone@example.com")),
                    ),
                    RichTextBlock.BulletList(
                        listOf(
                            ListItem(
                                listOf(
                                    paragraph(text("item")),
                                    RichTextBlock.OrderedList(3, listOf(ListItem(listOf(paragraph(text("nested")))))),
                                ),
                            ),
                        ),
                    ),
                    RichTextBlock.TaskList(
                        listOf(
                            TaskItem(checked = true, blocks = listOf(paragraph(text("done")))),
                            TaskItem(
                                checked = false,
                                blocks =
                                    listOf(
                                        RichTextBlock.Paragraph(emptyList()),
                                        RichTextBlock.BulletList(listOf(ListItem(listOf(paragraph(text("under a checklist item")))))),
                                    ),
                            ),
                        ),
                    ),
                    paragraph(text("ქართული ტექსტი")),
                    RichTextBlock.Paragraph(emptyList()),
                ),
            )

        assertEquals(note, createEditor(note).document)
    }

    @Test
    fun editorsTrailingParagraphIsNotStoredAndOpeningIsNotAnEdit() {
        val note =
            RichTextDocument(
                listOf(
                    paragraph(text("intro")),
                    RichTextBlock.TaskList(listOf(TaskItem(checked = false, blocks = listOf(paragraph(text("last thing")))))),
                ),
            )
        val editor = createEditor(note)

        // Any transaction lets Tiptap's TrailingNode add its empty paragraph after the checklist.
        editor.focus()

        val lastBlock = editor.dom().lastElementChild
        assertEquals("P", lastBlock?.tagName, "The editor shows an empty paragraph after the checklist")
        assertEquals("", lastBlock?.textContent)
        assertEquals(note, editor.document)
        assertTrue(changes.all { it == note }, "Changes: $changes")
    }

    @Test
    fun replacingTheContentShowsItWithoutReportingAnEdit() {
        val editor = createEditor(RichTextDocument(listOf(paragraph(text("synthetic text from this device")))))
        val other =
            RichTextDocument(
                listOf(
                    paragraph(text("synthetic text from another device")),
                    RichTextBlock.TaskList(listOf(TaskItem(checked = true, blocks = listOf(paragraph(text("done elsewhere")))))),
                ),
            )

        editor.replaceContent(other)
        editor.focus()

        assertEquals(other, editor.document)
        assertTrue("synthetic text from another device" in editor.text)
        assertTrue(changes.all { it == other }, "Changes: $changes")
    }

    @Test
    fun pasteFromAWebPageKeepsOnlySchemaContent() =
        runTest {
            val editor = createEditor(RichTextDocument.EMPTY)
            val html =
                """<meta charset="utf-8"><h1>Title</h1><h4>Small heading</h4>""" +
                    """<p style="color:red" class="x">Some <b>bold</b>, <em>italic</em>, <u>underlined</u>, <s>struck</s> and """ +
                    """<code>code</code> text with """ +
                    """<a href="https://example.com/a" title="t" class="c" target="_self" rel="x">a link</a>, """ +
                    """<a href="javascript:alert(1)">bad link</a> and <a href="/relative">relative</a>.</p>""" +
                    """<img src="https://example.invalid/x.png" onerror="window.__threadPasteImgError = true">""" +
                    """<script>window.__threadPasteScript = true</script>""" +
                    """<blockquote><p>Quoted</p></blockquote><ol type="a" start="3"><li>Third</li></ol>""" +
                    """<table><tr><td>Cell</td></tr></table><pre><code>let x = 1</code></pre><hr>""" +
                    """<ul data-type="taskList"><li data-type="taskItem" data-checked="true"><p>Done</p></li></ul>"""

            paste(editor.dom(), html, plain = "synthetic plain text")
            // Give a pasted <img onerror> time to fire, if it was ever loaded.
            withContext(Dispatchers.Default) { delay(300) }

            val result = editor.document
            assertEquals(listOf("https://example.com/a"), result.texts().mapNotNull { it.link?.value }.distinct())
            for (kept in listOf(
                "Title",
                "Small heading",
                "bold",
                "italic",
                "underlined",
                "struck",
                "code",
                "a link",
                "bad link",
                "relative",
                "Quoted",
                "Third",
                "Cell",
                "let x = 1",
                "Done",
            )) {
                assertTrue(kept in editor.text, "\"$kept\" missing from: ${editor.text}")
            }
            assertEquals(listOf(1), result.blocks.filterIsInstance<RichTextBlock.Heading>().map { it.level })
            assertEquals(listOf(3), result.blocks.filterIsInstance<RichTextBlock.OrderedList>().map { it.start })
            assertEquals(
                listOf(true),
                result.blocks.filterIsInstance<RichTextBlock.TaskList>().flatMap { list ->
                    list.items.map { it.checked }
                },
            )
            // The checklist node view styles its own hidden label, so only pasted blocks are checked for a style.
            assertNull(
                editor.dom().querySelector(
                    "img, script, blockquote, table, u, s, code, pre, hr, h4, ol[type], a[title], a[class], p[style]",
                ),
            )
            assertTrue(window.asDynamic().__threadPasteScript == undefined, "Pasted script ran")
            assertTrue(window.asDynamic().__threadPasteImgError == undefined, "Pasted image handler ran")
            assertEquals(result, changes.last())
        }

    @Test
    fun commandEnterTicksTheChecklistItemAtTheCursor() {
        val editor =
            createEditor(
                RichTextDocument(
                    listOf(
                        RichTextBlock.TaskList(
                            listOf(
                                TaskItem(checked = false, blocks = listOf(paragraph(text("first")))),
                                TaskItem(checked = false, blocks = listOf(paragraph(text("second")))),
                            ),
                        ),
                        paragraph(text("after")),
                    ),
                ),
            )

        // The cursor starts at the beginning of the document, in the first item.
        val event =
            KeyboardEvent("keydown", KeyboardEventInit(key = "Enter", code = "Enter", metaKey = true, bubbles = true, cancelable = true))
        editor.dom().dispatchEvent(event)

        val list = editor.document.blocks.first() as RichTextBlock.TaskList
        assertEquals(listOf(true, false), list.items.map { it.checked })
        assertTrue(event.defaultPrevented)
    }

    @Test
    fun addingALinkWithNothingSelectedInsertsTheAddressAsLinkedText() {
        val editor = createEditor(RichTextDocument.EMPTY)

        editor.applyLink(href("https://example.com/x"))

        assertEquals(
            RichTextDocument(listOf(paragraph(RichTextInline.Text("https://example.com/x", link = href("https://example.com/x"))))),
            editor.document,
        )
    }

    @Test
    fun aViewedNoteTakesNoTypingUntilEditTurnsItOn() {
        val note = RichTextDocument(listOf(paragraph(text("synthetic note"))))
        val editor = createEditor(note, editable = false)
        assertEquals("false", editor.dom().getAttribute("contenteditable"))

        editor.setEditable(true)

        assertEquals("true", editor.dom().getAttribute("contenteditable"))
        assertEquals(note, editor.document)
        assertTrue(changes.all { it == note }, "Changes: $changes")
    }

    @Test
    fun aChecklistItemTicksWhileTheNoteIsViewed() {
        val editor =
            createEditor(
                RichTextDocument(
                    listOf(
                        RichTextBlock.TaskList(
                            listOf(
                                TaskItem(checked = false, blocks = listOf(paragraph(text("first")))),
                                TaskItem(checked = false, blocks = listOf(paragraph(text("second")))),
                            ),
                        ),
                    ),
                ),
                editable = false,
            )

        (editor.dom().querySelectorAll("input[type=checkbox]").item(1) as HTMLInputElement).click()

        val list = editor.document.blocks.first() as RichTextBlock.TaskList
        assertEquals(listOf(false, true), list.items.map { it.checked })
        assertEquals(editor.document, changes.last())
        assertEquals("false", editor.dom().getAttribute("contenteditable"))
    }

    @Test
    fun theTextTakesTheIdItIsGiven() {
        // A Note card gives its own id, so the toolbar's aria-controls finds the card's text, not the page's (D70).
        val editor = createEditor(RichTextDocument.EMPTY, elementId = "c-editor")

        assertEquals("c-editor", editor.dom().id)
    }

    @Test
    fun destroyRemovesTheEditorFromThePage() {
        val editor = createEditor(RichTextDocument.EMPTY)
        val mount = mounts.last()
        assertEquals(1, mount.childElementCount)

        editor.destroy()

        assertTrue(editor.isDestroyed)
        assertEquals(0, mount.childElementCount)
    }

    private fun paste(
        target: HTMLElement,
        html: String,
        plain: String,
    ) {
        val data = js("new DataTransfer()").unsafeCast<DataTransfer>()
        data.setData("text/html", html)
        data.setData("text/plain", plain)
        target.dispatchEvent(ClipboardEvent("paste", ClipboardEventInit(clipboardData = data, bubbles = true, cancelable = true)))
    }

    private fun RichTextDocument.texts(): List<RichTextInline.Text> = blocks.flatMap { it.texts() }

    private fun RichTextBlock.texts(): List<RichTextInline.Text> =
        when (this) {
            is RichTextBlock.Paragraph -> content.filterIsInstance<RichTextInline.Text>()
            is RichTextBlock.Heading -> content.filterIsInstance<RichTextInline.Text>()
            is RichTextBlock.BulletList -> items.flatMap { item -> item.blocks.flatMap { it.texts() } }
            is RichTextBlock.OrderedList -> items.flatMap { item -> item.blocks.flatMap { it.texts() } }
            is RichTextBlock.TaskList -> items.flatMap { item -> item.blocks.flatMap { it.texts() } }
        }
}
