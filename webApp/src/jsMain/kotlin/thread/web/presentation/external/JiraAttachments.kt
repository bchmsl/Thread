package thread.web.presentation.external

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.ATarget
import org.jetbrains.compose.web.attributes.target
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul
import thread.domain.external.JiraAttachment
import thread.domain.time.LocalDay
import thread.web.presentation.Icon
import thread.web.presentation.IconView
import kotlin.math.round

/**
 * A Jira ticket's attachments (D75): each file's name, type, size and when it was added. Thread never fetches a file. A
 * name opens the file on the Jira site in a new tab, with the person's own Jira sign-in. In the fixture mode
 * ([linksHidden]) the names are plain text, as "Open in Jira" is hidden there.
 */
@Composable
internal fun JiraAttachments(
    attachments: List<JiraAttachment>,
    linksHidden: Boolean,
) {
    Ul({ classes("att") }) {
        attachments.forEach { file ->
            Li({ classes("att__row") }) {
                Span({ classes("att__icon") }) { IconView(if (isImageFile(file.mimeType)) Icon.Image else Icon.Doc, 18) }
                Div({ classes("att__main") }) {
                    val href = file.openUrl
                    if (href == null || linksHidden) {
                        Span({ classes("att__name") }) { Text(file.filename) }
                    } else {
                        A(href.value, {
                            classes("lnk", "att__name")
                            target(ATarget.Blank)
                            attr("rel", "noopener noreferrer")
                        }) {
                            Text(file.filename)
                            Span({ classes("vh") }) { Text(" (opens in Jira)") }
                            IconView(Icon.External, 13)
                        }
                    }
                    Span({ classes("att__meta") }) { Text(attachmentMeta(file)) }
                }
            }
        }
    }
}

/** `PNG image · 240 KB · Today 16:40`, leaving out what Jira did not say. */
internal fun attachmentMeta(
    file: JiraAttachment,
    nowMillis: Long = Times.now(),
): String =
    listOfNotNull(
        fileKindText(file.filename, file.mimeType),
        file.sizeBytes?.let(::fileSizeText),
        file.createdAtMillis?.let { Times.whenText(it, nowMillis) },
    ).joinToString(" · ")

/** `512 B`, `240 KB`, `1.5 MB`: binary units, one decimal under 10. */
internal fun fileSizeText(bytes: Long): String {
    if (bytes < UNIT) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / UNIT.toDouble()
    var unit = 0
    // A value that rounds to 1024 moves up a unit, so it reads 1 MB, not 1024 KB.
    while (value >= UNIT - 0.05 && unit < units.lastIndex) {
        value /= UNIT
        unit++
    }
    val tenths = round(value * 10).toLong()
    val number =
        when {
            tenths >= 100 -> round(value).toLong().toString()
            tenths % 10 == 0L -> (tenths / 10).toString()
            else -> "${tenths / 10}.${tenths % 10}"
        }
    return "$number ${units[unit]}"
}

/** `PNG image`, `PDF document`, `ZIP archive`; just `Image` or `File` when the name has no usable extension. */
internal fun fileKindText(
    filename: String,
    mimeType: String?,
): String {
    val family =
        fileFamily(
            mimeType
                ?.lowercase()
                ?.substringBefore(';')
                ?.trim()
                .orEmpty(),
        )
    val extension =
        filename
            .substringAfterLast('.', "")
            .takeIf { filename.lastIndexOf('.') > 0 && EXTENSION.matches(it) }
            ?.uppercase()
    return if (extension == null) family.replaceFirstChar { it.uppercase() } else "$extension $family"
}

internal fun isImageFile(mimeType: String?): Boolean = mimeType?.lowercase()?.startsWith("image/") == true

private fun fileFamily(mime: String): String =
    when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("video/") -> "video"
        mime.startsWith("audio/") -> "audio"
        mime == "text/csv" || "spreadsheet" in mime || mime == "application/vnd.ms-excel" -> "spreadsheet"
        "presentation" in mime || mime == "application/vnd.ms-powerpoint" -> "presentation"
        mime == "application/pdf" ||
            mime == "application/msword" ||
            mime == "application/rtf" ||
            "wordprocessingml" in mime ||
            mime == "application/vnd.oasis.opendocument.text" -> "document"
        mime.startsWith("text/") -> "text file"
        mime in ARCHIVES -> "archive"
        else -> "file"
    }

/** A due date as a calendar day: `Fri 20 Nov`, with the year when it is not [thisYear]. It never shifts by time zone. */
internal fun dueDayText(
    day: LocalDay,
    thisYear: Int,
): String {
    val year = if (day.year != thisYear) " ${day.year}" else ""
    return "${WEEKDAYS[day.dayOfWeek - 1]} ${day.day} ${MONTHS[day.month - 1]}$year"
}

private const val UNIT = 1024L
private val EXTENSION = Regex("^[A-Za-z0-9]{1,8}$")
private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val ARCHIVES =
    setOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/gzip",
        "application/x-gzip",
        "application/x-tar",
        "application/x-7z-compressed",
        "application/x-rar-compressed",
        "application/vnd.rar",
    )
