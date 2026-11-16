package thread.web.platform.editor

import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import thread.domain.note.LinkHref
import thread.domain.note.RichTextDocument

/** The formatting at the editor selection, for the toolbar. */
internal data class EditorFormatting(
    val heading: Boolean = false,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val bulletList: Boolean = false,
    val orderedList: Boolean = false,
    val taskList: Boolean = false,
    /** The address of the link at the selection, or null when the selection is not in a link. */
    val link: String? = null,
)

/**
 * The rich-text editor of a Note body: Tiptap 3 limited to the RichText v1 schema (adr/ADR-003-editor-and-richtext.md).
 *
 * The editor mounts inside [mount] when created. Call [destroy] when the view goes away.
 *
 * - [onChange] gets the body after every content change.
 * - [onFormatting] gets the toolbar state after every transaction.
 * - [onFailure] is called when the editor refuses [initial] or produces content that does not map to RichText v1.
 *   The editor then stops accepting edits, so a broken body is never saved over a good one.
 *
 * Schema: paragraphs, headings 1 to 3, bold, italic, bullet, ordered and check lists, links and hard breaks.
 * Everything else, also in pasted content, is dropped by the schema. Links allow only http, https and mailto, and the
 * link mark keeps only its address. Links are added with the toolbar ([applyLink]), not by autolink or paste rules.
 * ⌘Enter or Ctrl+Enter in a checklist item ticks it, as in v3.
 *
 * [elementId] is the text's element id, for the toolbar's `aria-controls`; a Note card gives its own (D70).
 *
 * Not [editable] (view mode): the text cannot be typed into, links open like links, and checklist items still tick.
 * A tick is a normal change and goes to [onChange].
 */
internal class NoteEditor(
    private val mount: HTMLElement,
    initial: RichTextDocument,
    editable: Boolean = true,
    elementId: String = "editor",
    private val onChange: (RichTextDocument) -> Unit,
    private val onFormatting: (EditorFormatting) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private var failed = false
    private var contentError: Throwable? = null
    private val editor = TiptapEditor(options(mount, initial, editable, elementId))

    // Capture phase: runs before Tiptap's own listener on the checkbox, which only keeps the tick (onReadOnlyChecked).
    private val readOnlyTick: (Event) -> Unit = { event -> tickReadOnly(event) }

    init {
        contentError?.let(::fail)
        onFormatting(formatting(editor))
        mount.addEventListener("change", readOnlyTick, true)
    }

    /** Turns typing on or off. Stays off after a failure, so a broken body is never saved over a good one. */
    fun setEditable(editable: Boolean) {
        if (failed || editor.isDestroyed || editor.isEditable == editable) return
        editor.setEditable(editable, false)
    }

    /** Focuses the end of the text. */
    fun focusEnd() {
        if (editor.isDestroyed) return
        editor.chain().focus("end").run()
    }

    /** The plain text of the body, blocks separated by blank lines. */
    val text: String get() = editor.getText()

    /** The current body. Throws [IllegalStateException] when it does not map to RichText v1. */
    val document: RichTextDocument get() = RichTextEditorJson.fromEditor(editor.getJSON())

    val isDestroyed: Boolean get() = editor.isDestroyed

    fun toggleHeading() {
        // v3: the heading button turns any heading back into a paragraph, otherwise makes a level 2 heading.
        val chain = editor.chain().focus()
        if (editor.isActive("heading")) chain.setParagraph().run() else chain.setHeading(jsObjectOf("level" to 2)).run()
    }

    fun toggleBold() {
        editor
            .chain()
            .focus()
            .toggleBold()
            .run()
    }

    fun toggleItalic() {
        editor
            .chain()
            .focus()
            .toggleItalic()
            .run()
    }

    fun toggleBulletList() {
        editor
            .chain()
            .focus()
            .toggleBulletList()
            .run()
    }

    fun toggleOrderedList() {
        editor
            .chain()
            .focus()
            .toggleOrderedList()
            .run()
    }

    fun toggleTaskList() {
        editor
            .chain()
            .focus()
            .toggleTaskList()
            .run()
    }

    /**
     * Links the selected text, or changes the link at the cursor. With an empty selection outside a link, inserts the
     * address itself as linked text.
     */
    fun applyLink(href: LinkHref) {
        val attributes = jsObjectOf("href" to href.value)
        if (editor.isActive("link") || editor.state.selection.empty != true) {
            editor
                .chain()
                .focus()
                .extendMarkRange("link")
                .setLink(attributes)
                .run()
        } else {
            val linkedText =
                jsObjectOf(
                    "type" to "text",
                    "text" to href.value,
                    "marks" to arrayOf(jsObjectOf("type" to "link", "attrs" to attributes)),
                )
            editor
                .chain()
                .focus()
                .insertContent(linkedText)
                .run()
        }
    }

    /** Removes the link at the cursor or in the selection. The text stays. */
    fun removeLink() {
        editor
            .chain()
            .focus()
            .extendMarkRange("link")
            .unsetLink()
            .run()
    }

    fun focus() {
        editor.chain().focus().run()
    }

    /**
     * Shows [body] in place of the current content, for content that came from elsewhere, such as another device's
     * newer version. Does not call onChange: the body is already saved where it came from.
     */
    fun replaceContent(body: RichTextDocument) {
        if (failed || editor.isDestroyed) return
        editor.chain().setContent(RichTextEditorJson.toEditor(body), jsObjectOf("emitUpdate" to false)).run()
    }

    fun destroy() {
        mount.removeEventListener("change", readOnlyTick, true)
        if (!editor.isDestroyed) editor.destroy()
    }

    /**
     * Ticks a checklist item in view mode. Tiptap leaves the document alone when the editor is not editable, so the
     * item is found from the checkbox's place in the page and changed here.
     */
    private fun tickReadOnly(event: Event) {
        if (failed || editor.isDestroyed || editor.isEditable) return
        val box = event.target as? HTMLInputElement ?: return
        if (box.type != "checkbox") return
        val content = box.closest("li")?.querySelector(":scope > div") ?: return
        val view = editor.view
        val position = view.posAtDOM(content, 0)
        val resolved = view.state.doc.resolve(position)
        var depth: Int = resolved.depth.unsafeCast<Int>()
        while (depth > 0) {
            if (resolved.node(depth).type.name == "taskItem") {
                view.dispatch(view.state.tr.setNodeMarkup(resolved.before(depth), null, jsObjectOf("checked" to box.checked)))
                return
            }
            depth--
        }
    }

    private fun options(
        mount: HTMLElement,
        initial: RichTextDocument,
        editable: Boolean,
        elementId: String,
    ): dynamic {
        val editorProps = jsObjectOf()
        editorProps.attributes =
            jsObjectOf(
                "id" to elementId,
                "class" to "editor",
                "aria-label" to "Note text",
                "aria-multiline" to "true",
                "spellcheck" to "true",
            )
        editorProps.handleKeyDown = { view: dynamic, event: KeyboardEvent -> handleKeyDown(view, event) }

        val options = jsObjectOf()
        options.element = mount
        options.extensions = extensions()
        options.content = RichTextEditorJson.toEditor(initial)
        options.editorProps = editorProps
        options.editable = editable
        // Tiptap's base styles are copied into our stylesheet instead, so no <style> element is injected.
        options.injectCSS = false
        // Refuse content that does not fit the schema instead of silently dropping parts of it.
        options.enableContentCheck = true
        options.onContentError =
            { props: dynamic -> contentError = props.error as? Throwable ?: IllegalStateException("Invalid note content") }
        options.onUpdate = { props: dynamic -> emitChange(props.editor.unsafeCast<TiptapEditor>()) }
        options.onTransaction = { props: dynamic -> onFormatting(formatting(props.editor.unsafeCast<TiptapEditor>())) }
        return options
    }

    private fun emitChange(source: TiptapEditor) {
        if (failed) return
        val document =
            try {
                RichTextEditorJson.fromEditor(source.getJSON())
            } catch (e: Throwable) {
                fail(e)
                return
            }
        onChange(document)
    }

    private fun fail(error: Throwable) {
        if (failed) return
        failed = true
        if (!editor.isDestroyed) editor.setEditable(false, false)
        onFailure(error)
    }

    private fun handleKeyDown(
        view: dynamic,
        event: KeyboardEvent,
    ): Boolean {
        val tick = event.key == "Enter" && (event.metaKey || event.ctrlKey) && !event.shiftKey && !event.altKey
        return tick && !event.isComposing && toggleTaskItemAtCursor(view)
    }

    /** Ticks or unticks the innermost checklist item around the cursor. Returns false outside a checklist. */
    private fun toggleTaskItemAtCursor(view: dynamic): Boolean {
        val state = view.state
        val from = state.selection["\$from"]
        var depth: Int = from.depth.unsafeCast<Int>()
        while (depth > 0) {
            val node = from.node(depth)
            if (node.type.name == "taskItem") {
                val checked = node.attrs.checked == true
                view.dispatch(state.tr.setNodeMarkup(from.before(depth), null, jsObjectOf("checked" to !checked)))
                return true
            }
            depth--
        }
        return false
    }

    private companion object {
        fun formatting(editor: TiptapEditor): EditorFormatting =
            EditorFormatting(
                heading = editor.isActive("heading"),
                bold = editor.isActive("bold"),
                italic = editor.isActive("italic"),
                bulletList = editor.isActive("bulletList"),
                orderedList = editor.isActive("orderedList"),
                taskList = editor.isActive("taskList"),
                link = if (editor.isActive("link")) editor.getAttributes("link").href as? String else null,
            )

        fun extensions(): Array<TiptapExtension> {
            val starterKit = jsObjectOf("heading" to jsObjectOf("levels" to arrayOf(1, 2, 3)))
            // Not in RichText v1. Link and orderedList are replaced below by narrower versions.
            for (name in listOf("blockquote", "code", "codeBlock", "horizontalRule", "strike", "underline", "link", "orderedList")) {
                starterKit[name] = false
            }
            return arrayOf(
                TiptapStarterKit.configure(starterKit),
                hrefOnlyLink.configure(linkOptions()),
                startOnlyOrderedList,
                TiptapTaskList,
                // Nested: a checklist item may hold more blocks after its first paragraph, like a list item.
                TiptapTaskItem.configure(
                    jsObjectOf(
                        "nested" to true,
                        "a11y" to jsObjectOf("checkboxLabel" to { node: dynamic, _: Boolean -> checkboxLabel(node) }),
                        // View mode keeps the tick; tickReadOnly has already changed the document.
                        "onReadOnlyChecked" to { _: dynamic, _: Boolean -> true },
                    ),
                ),
            )
        }

        /** Checklist items are text, not Tasks (01-product-spec.md), so the label avoids Tiptap's "Task item" wording. */
        fun checkboxLabel(node: dynamic): String {
            val text = (node.textContent as? String).orEmpty().trim()
            return if (text.isEmpty()) "Empty checklist item" else "Checklist item: $text"
        }

        fun linkOptions(): dynamic =
            jsObjectOf(
                "openOnClick" to false,
                "autolink" to false,
                "linkOnPaste" to false,
                "isAllowedUri" to { url: String?, _: dynamic -> url != null && LinkHref.parse(url) != null },
            )

        /** Link with only the `href` attribute, so pasted `title`, `class`, `target` or `rel` values are not kept. */
        val hrefOnlyLink: TiptapExtension =
            TiptapLink.extend(
                jsObjectOf(
                    "addAttributes" to {
                        jsObjectOf(
                            "href" to
                                jsObjectOf(
                                    "default" to null,
                                    "parseHTML" to { element: Element -> element.getAttribute("href") },
                                ),
                        )
                    },
                ),
            )

        /** Ordered list with only the `start` attribute. RichText v1 has no list numbering type (a, i, A, I). */
        val startOnlyOrderedList: TiptapExtension =
            TiptapOrderedList.extend(
                jsObjectOf(
                    "addAttributes" to {
                        jsObjectOf(
                            "start" to
                                jsObjectOf(
                                    "default" to 1,
                                    "parseHTML" to { element: Element -> element.getAttribute("start")?.trim()?.toIntOrNull() ?: 1 },
                                ),
                        )
                    },
                ),
            )
    }
}
