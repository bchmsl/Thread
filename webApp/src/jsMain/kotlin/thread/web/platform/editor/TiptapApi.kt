package thread.web.platform.editor

/*
 * Kotlin declarations for the small part of Tiptap 3 that the note editor uses (adr/ADR-003-editor-and-richtext.md).
 * Tiptap ships no Kotlin types, so these follow its TypeScript declarations and declare only the members used here.
 * Options and editor JSON stay `dynamic`. Only NoteEditor and RichTextEditorJson touch them.
 */

/** A Tiptap extension, node or mark, as a Tiptap package exports it. */
internal external interface TiptapExtension {
    fun configure(options: dynamic): TiptapExtension

    fun extend(config: dynamic): TiptapExtension
}

/** A command chain. Each call adds a command, and [run] applies them as one transaction. */
internal external interface TiptapChain {
    fun focus(): TiptapChain

    fun toggleBold(): TiptapChain

    fun toggleItalic(): TiptapChain

    fun setHeading(attributes: dynamic): TiptapChain

    fun setParagraph(): TiptapChain

    fun toggleBulletList(): TiptapChain

    fun toggleOrderedList(): TiptapChain

    fun toggleTaskList(): TiptapChain

    fun extendMarkRange(typeOrName: String): TiptapChain

    fun setLink(attributes: dynamic): TiptapChain

    fun unsetLink(): TiptapChain

    fun insertContent(value: dynamic): TiptapChain

    fun run(): Boolean
}
