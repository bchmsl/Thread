package thread.domain.note

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The stored JSON form of [RichTextDocument] (adr/ADR-003-editor-and-richtext.md).
 *
 * ```json
 * {"schemaVersion":1,"content":[
 *   {"type":"heading","level":2,"content":[{"type":"text","text":"Plan"}]},
 *   {"type":"paragraph","content":[{"type":"text","text":"Read ","marks":[{"type":"bold"}]},{"type":"hardBreak"}]},
 *   {"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph","content":[]}]}]},
 *   {"type":"orderedList","start":1,"content":[...listItem]},
 *   {"type":"taskList","content":[{"type":"taskItem","checked":false,"content":[...blocks]}]}
 * ]}
 * ```
 *
 * Marks are `bold`, `italic` and `link` (with `href`), written in that order. [decode] is strict: an unknown
 * schema version, node type, mark or key, a wrong value type, or a link that [LinkHref] refuses makes the whole
 * document fail. Nothing is dropped silently, so a later schema cannot lose content by being read as version 1.
 */
object RichTextJson {
    const val SCHEMA_VERSION: Int = 1

    fun encode(document: RichTextDocument): String =
        buildJsonObject {
            put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            put(KEY_CONTENT, JsonArray(document.blocks.map(::encodeBlock)))
        }.toString()

    /** Reads a stored document. Throws [RichTextFormatException] when [json] is not a valid version 1 document. */
    fun decode(json: String): RichTextDocument {
        val root =
            try {
                Json.parseToJsonElement(json)
            } catch (e: IllegalArgumentException) {
                throw RichTextFormatException("Not valid JSON", e)
            }
        return try {
            decodeDocument(root)
        } catch (e: RichTextFormatException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw RichTextFormatException("Invalid document: ${e.message}", e)
        }
    }
}

/** A stored rich text document that cannot be read as [RichTextJson.SCHEMA_VERSION]. */
class RichTextFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

private const val KEY_SCHEMA_VERSION = "schemaVersion"
private const val KEY_CONTENT = "content"
private const val KEY_TYPE = "type"
private const val KEY_LEVEL = "level"
private const val KEY_START = "start"
private const val KEY_CHECKED = "checked"
private const val KEY_TEXT = "text"
private const val KEY_MARKS = "marks"
private const val KEY_HREF = "href"

private const val TYPE_PARAGRAPH = "paragraph"
private const val TYPE_HEADING = "heading"
private const val TYPE_BULLET_LIST = "bulletList"
private const val TYPE_ORDERED_LIST = "orderedList"
private const val TYPE_TASK_LIST = "taskList"
private const val TYPE_LIST_ITEM = "listItem"
private const val TYPE_TASK_ITEM = "taskItem"
private const val TYPE_TEXT = "text"
private const val TYPE_HARD_BREAK = "hardBreak"
private const val MARK_BOLD = "bold"
private const val MARK_ITALIC = "italic"
private const val MARK_LINK = "link"

// ---------------------------------------------------------------- Encode

private fun encodeBlock(block: RichTextBlock): JsonObject =
    when (block) {
        is RichTextBlock.Paragraph ->
            buildJsonObject {
                put(KEY_TYPE, TYPE_PARAGRAPH)
                put(KEY_CONTENT, encodeInlines(block.content))
            }
        is RichTextBlock.Heading ->
            buildJsonObject {
                put(KEY_TYPE, TYPE_HEADING)
                put(KEY_LEVEL, block.level)
                put(KEY_CONTENT, encodeInlines(block.content))
            }
        is RichTextBlock.BulletList ->
            buildJsonObject {
                put(KEY_TYPE, TYPE_BULLET_LIST)
                put(KEY_CONTENT, JsonArray(block.items.map(::encodeListItem)))
            }
        is RichTextBlock.OrderedList ->
            buildJsonObject {
                put(KEY_TYPE, TYPE_ORDERED_LIST)
                put(KEY_START, block.start)
                put(KEY_CONTENT, JsonArray(block.items.map(::encodeListItem)))
            }
        is RichTextBlock.TaskList ->
            buildJsonObject {
                put(KEY_TYPE, TYPE_TASK_LIST)
                put(KEY_CONTENT, JsonArray(block.items.map(::encodeTaskItem)))
            }
    }

private fun encodeListItem(item: ListItem): JsonObject =
    buildJsonObject {
        put(KEY_TYPE, TYPE_LIST_ITEM)
        put(KEY_CONTENT, JsonArray(item.blocks.map(::encodeBlock)))
    }

private fun encodeTaskItem(item: TaskItem): JsonObject =
    buildJsonObject {
        put(KEY_TYPE, TYPE_TASK_ITEM)
        put(KEY_CHECKED, item.checked)
        put(KEY_CONTENT, JsonArray(item.blocks.map(::encodeBlock)))
    }

private fun encodeInlines(inlines: List<RichTextInline>): JsonArray = JsonArray(inlines.map(::encodeInline))

private fun encodeInline(inline: RichTextInline): JsonObject =
    when (inline) {
        RichTextInline.HardBreak -> buildJsonObject { put(KEY_TYPE, TYPE_HARD_BREAK) }
        is RichTextInline.Text ->
            buildJsonObject {
                put(KEY_TYPE, TYPE_TEXT)
                put(KEY_TEXT, inline.text)
                val marks =
                    buildList {
                        if (inline.bold) add(buildJsonObject { put(KEY_TYPE, MARK_BOLD) })
                        if (inline.italic) add(buildJsonObject { put(KEY_TYPE, MARK_ITALIC) })
                        inline.link?.let { link ->
                            add(
                                buildJsonObject {
                                    put(KEY_TYPE, MARK_LINK)
                                    put(KEY_HREF, link.value)
                                },
                            )
                        }
                    }
                if (marks.isNotEmpty()) put(KEY_MARKS, JsonArray(marks))
            }
    }

// ---------------------------------------------------------------- Decode

private fun decodeDocument(root: JsonElement): RichTextDocument {
    val path = "$"
    val obj = root.asObject(path)
    obj.requireOnlyKeys(path, KEY_SCHEMA_VERSION, KEY_CONTENT)
    val version = obj.requireInt(path, KEY_SCHEMA_VERSION)
    if (version != RichTextJson.SCHEMA_VERSION) {
        formatError("$path.$KEY_SCHEMA_VERSION", "unsupported schema version $version")
    }
    val blocks = obj.requireArray(path, KEY_CONTENT).mapIndexed { i, e -> decodeBlock(e, "$path.$KEY_CONTENT[$i]") }
    if (blocks.isEmpty()) formatError("$path.$KEY_CONTENT", "a document has at least one block")
    return RichTextDocument(blocks)
}

private fun decodeBlock(
    element: JsonElement,
    path: String,
): RichTextBlock {
    val obj = element.asObject(path)
    return when (val type = obj.requireString(path, KEY_TYPE)) {
        TYPE_PARAGRAPH -> {
            obj.requireOnlyKeys(path, KEY_TYPE, KEY_CONTENT)
            RichTextBlock.Paragraph(decodeInlines(obj, path))
        }
        TYPE_HEADING -> {
            obj.requireOnlyKeys(path, KEY_TYPE, KEY_LEVEL, KEY_CONTENT)
            val level = obj.requireInt(path, KEY_LEVEL)
            if (level !in RichTextBlock.Heading.LEVELS) formatError("$path.$KEY_LEVEL", "heading level $level is not allowed")
            RichTextBlock.Heading(level, decodeInlines(obj, path))
        }
        TYPE_BULLET_LIST -> {
            obj.requireOnlyKeys(path, KEY_TYPE, KEY_CONTENT)
            RichTextBlock.BulletList(decodeItems(obj, path, TYPE_LIST_ITEM, ::decodeListItem))
        }
        TYPE_ORDERED_LIST -> {
            obj.requireOnlyKeys(path, KEY_TYPE, KEY_START, KEY_CONTENT)
            RichTextBlock.OrderedList(obj.requireInt(path, KEY_START), decodeItems(obj, path, TYPE_LIST_ITEM, ::decodeListItem))
        }
        TYPE_TASK_LIST -> {
            obj.requireOnlyKeys(path, KEY_TYPE, KEY_CONTENT)
            RichTextBlock.TaskList(decodeItems(obj, path, TYPE_TASK_ITEM, ::decodeTaskItem))
        }
        else -> formatError("$path.$KEY_TYPE", "unknown block type \"$type\"")
    }
}

private fun <T> decodeItems(
    list: JsonObject,
    path: String,
    itemType: String,
    decode: (JsonObject, String) -> T,
): List<T> {
    val items =
        list.requireArray(path, KEY_CONTENT).mapIndexed { i, element ->
            val itemPath = "$path.$KEY_CONTENT[$i]"
            val item = element.asObject(itemPath)
            val type = item.requireString(itemPath, KEY_TYPE)
            if (type != itemType) formatError("$itemPath.$KEY_TYPE", "expected \"$itemType\", found \"$type\"")
            decode(item, itemPath)
        }
    if (items.isEmpty()) formatError("$path.$KEY_CONTENT", "a list has at least one item")
    return items
}

private fun decodeListItem(
    item: JsonObject,
    path: String,
): ListItem {
    item.requireOnlyKeys(path, KEY_TYPE, KEY_CONTENT)
    return ListItem(decodeItemBlocks(item, path))
}

private fun decodeTaskItem(
    item: JsonObject,
    path: String,
): TaskItem {
    item.requireOnlyKeys(path, KEY_TYPE, KEY_CHECKED, KEY_CONTENT)
    return TaskItem(item.requireBoolean(path, KEY_CHECKED), decodeItemBlocks(item, path))
}

private fun decodeItemBlocks(
    item: JsonObject,
    path: String,
): List<RichTextBlock> {
    val blocks = item.requireArray(path, KEY_CONTENT).mapIndexed { i, e -> decodeBlock(e, "$path.$KEY_CONTENT[$i]") }
    if (blocks.firstOrNull() !is RichTextBlock.Paragraph) formatError("$path.$KEY_CONTENT", "a list item starts with a paragraph")
    return blocks
}

private fun decodeInlines(
    block: JsonObject,
    path: String,
): List<RichTextInline> = block.requireArray(path, KEY_CONTENT).mapIndexed { i, e -> decodeInline(e, "$path.$KEY_CONTENT[$i]") }

private fun decodeInline(
    element: JsonElement,
    path: String,
): RichTextInline {
    val obj = element.asObject(path)
    return when (val type = obj.requireString(path, KEY_TYPE)) {
        TYPE_HARD_BREAK -> {
            obj.requireOnlyKeys(path, KEY_TYPE)
            RichTextInline.HardBreak
        }
        TYPE_TEXT -> {
            obj.requireOnlyKeys(path, KEY_TYPE, KEY_TEXT, KEY_MARKS)
            val text = obj.requireString(path, KEY_TEXT)
            if (text.isEmpty()) formatError("$path.$KEY_TEXT", "text is never empty")
            decodeMarks(obj, path, text)
        }
        else -> formatError("$path.$KEY_TYPE", "unknown inline type \"$type\"")
    }
}

private fun decodeMarks(
    obj: JsonObject,
    path: String,
    text: String,
): RichTextInline.Text {
    var bold = false
    var italic = false
    var link: LinkHref? = null
    val seen = mutableSetOf<String>()
    val marks = if (KEY_MARKS in obj) obj.requireArray(path, KEY_MARKS) else JsonArray(emptyList())
    marks.forEachIndexed { i, element ->
        val markPath = "$path.$KEY_MARKS[$i]"
        val mark = element.asObject(markPath)
        val type = mark.requireString(markPath, KEY_TYPE)
        if (!seen.add(type)) formatError(markPath, "mark \"$type\" appears twice")
        when (type) {
            MARK_BOLD -> {
                mark.requireOnlyKeys(markPath, KEY_TYPE)
                bold = true
            }
            MARK_ITALIC -> {
                mark.requireOnlyKeys(markPath, KEY_TYPE)
                italic = true
            }
            MARK_LINK -> {
                mark.requireOnlyKeys(markPath, KEY_TYPE, KEY_HREF)
                link = LinkHref.parse(mark.requireString(markPath, KEY_HREF))
                    ?: formatError("$markPath.$KEY_HREF", "link address is not allowed")
            }
            else -> formatError("$markPath.$KEY_TYPE", "unknown mark \"$type\"")
        }
    }
    return RichTextInline.Text(text = text, bold = bold, italic = italic, link = link)
}

// ---------------------------------------------------------------- JSON helpers

private fun formatError(
    path: String,
    problem: String,
): Nothing = throw RichTextFormatException("$path: $problem")

private fun JsonElement.asObject(path: String): JsonObject = this as? JsonObject ?: formatError(path, "expected an object")

private fun JsonObject.requireOnlyKeys(
    path: String,
    vararg allowed: String,
) {
    val unknown = keys - allowed.toSet()
    if (unknown.isNotEmpty()) formatError(path, "unknown key \"${unknown.first()}\"")
}

private fun JsonObject.requireField(
    path: String,
    key: String,
): JsonElement = this[key] ?: formatError(path, "missing \"$key\"")

private fun JsonObject.requireArray(
    path: String,
    key: String,
): JsonArray = requireField(path, key) as? JsonArray ?: formatError("$path.$key", "expected an array")

private fun JsonObject.requireString(
    path: String,
    key: String,
): String {
    val value = requireField(path, key) as? JsonPrimitive
    if (value == null || !value.isString) formatError("$path.$key", "expected a string")
    return value.content
}

private fun JsonObject.requireInt(
    path: String,
    key: String,
): Int {
    val value = requireField(path, key) as? JsonPrimitive
    if (value == null || value.isString) formatError("$path.$key", "expected an integer")
    return value.intOrNull ?: formatError("$path.$key", "expected an integer")
}

private fun JsonObject.requireBoolean(
    path: String,
    key: String,
): Boolean {
    val value = requireField(path, key) as? JsonPrimitive
    if (value == null || value.isString) formatError("$path.$key", "expected true or false")
    return value.booleanOrNull ?: formatError("$path.$key", "expected true or false")
}
