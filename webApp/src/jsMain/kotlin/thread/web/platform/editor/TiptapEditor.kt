@file:JsModule("@tiptap/core")
@file:JsNonModule

package thread.web.platform.editor

/** The Tiptap editor (`Editor` in `@tiptap/core`). Mounts into `options.element` when that is set. */
@JsName("Editor")
internal external class TiptapEditor(
    options: dynamic,
) {
    val isDestroyed: Boolean

    /** The ProseMirror editor state. */
    val state: dynamic

    fun chain(): TiptapChain

    fun isActive(name: String): Boolean

    fun getAttributes(name: String): dynamic

    fun getJSON(): dynamic

    fun getText(): String

    fun setEditable(
        editable: Boolean,
        emitUpdate: Boolean,
    )

    fun destroy()
}
