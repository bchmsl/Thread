package thread.web.presentation.external

import thread.domain.external.BlockTableCell
import thread.domain.external.BlockTableRow
import thread.domain.external.ExternalBlock
import thread.domain.external.JiraAttachment
import thread.domain.time.LocalDay
import kotlin.js.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D74 and D75: the text of a ticket's attachments, its due date and the numbers of a Jira table. SYNTHETIC files only. */
class JiraRichContentTest {
    @Test
    fun sizesUseBinaryUnitsWithOneDecimalUnderTen() {
        assertEquals("0 B", fileSizeText(0))
        assertEquals("512 B", fileSizeText(512))
        assertEquals("1023 B", fileSizeText(1_023))
        assertEquals("1 KB", fileSizeText(1_024))
        assertEquals("1.5 KB", fileSizeText(1_536))
        assertEquals("10 KB", fileSizeText(10_239))
        assertEquals("240 KB", fileSizeText(245_760))
        assertEquals("1 MB", fileSizeText(1_048_575), "rounds up into the next unit")
        assertEquals("1.5 MB", fileSizeText(1_572_864))
        assertEquals("3 GB", fileSizeText(3L * 1_024 * 1_024 * 1_024))
    }

    @Test
    fun theKindNamesTheExtensionAndTheFamily() {
        assertEquals("PNG image", fileKindText("synthetic-screen.png", "image/png"))
        assertEquals("PDF document", fileKindText("synthetic-spec.pdf", "application/pdf"))
        assertEquals("ZIP archive", fileKindText("synthetic-logs.zip", "application/zip"))
        assertEquals("XLSX spreadsheet", fileKindText("a.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        assertEquals("CSV spreadsheet", fileKindText("a.csv", "text/csv; charset=utf-8"))
        assertEquals(
            "PPTX presentation",
            fileKindText("a.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        )
        assertEquals("DOCX document", fileKindText("a.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        assertEquals("LOG text file", fileKindText("server.log", "text/plain"))
        assertEquals("MP4 video", fileKindText("clip.mp4", "video/mp4"))
        assertEquals("M4A audio", fileKindText("note.m4a", "audio/mp4"))
        assertEquals("BIN file", fileKindText("dump.bin", "application/octet-stream"))
        assertEquals("Image", fileKindText("no-extension", "image/png"))
        assertEquals("File", fileKindText(".hidden", null))
        assertEquals("File", fileKindText("odd.<b>", null), "an extension that is not plain letters and digits is left out")
    }

    @Test
    fun onlyImageTypesGetThePictureIcon() {
        assertTrue(isImageFile("image/png"))
        assertTrue(isImageFile("IMAGE/JPEG"))
        assertFalse(isImageFile("application/pdf"))
        assertFalse(isImageFile(null))
    }

    @Test
    fun theMetaLineLeavesOutWhatJiraDidNotSay() {
        val now = Date(2026, 10, 12, 18, 0).getTime().toLong()
        val added = Date(2026, 10, 12, 16, 40).getTime().toLong()
        val file = JiraAttachment("10501", "synthetic-screen.png", 245_760, "image/png", added, null)

        assertEquals("PNG image · 240 KB · Today 16:40", attachmentMeta(file, now))
        assertEquals("PNG file", attachmentMeta(file.copy(sizeBytes = null, mimeType = null, createdAtMillis = null), now))
    }

    @Test
    fun aDueDateIsACalendarDayWithTheYearOnlyWhenItDiffers() {
        assertEquals("Fri 20 Nov", dueDayText(LocalDay(2026, 11, 20), thisYear = 2026))
        assertEquals("Mon 4 Jan 2027", dueDayText(LocalDay(2027, 1, 4), thisYear = 2026))
        assertEquals("Sun 1 Mar 2026", dueDayText(LocalDay(2026, 3, 1), thisYear = 2025))
    }

    @Test
    fun aNumberedTableSkipsRowsOfOnlyHeaderCells() {
        fun row(vararg header: Boolean) = BlockTableRow(header.map { BlockTableCell(it, 1, 1, emptyList()) })
        val table = ExternalBlock.BlockTable(listOf(row(true, true), row(true, false), row(false, false), row(true)), numbered = true)

        assertEquals(listOf(null, 1, 2, null), blockTableRowNumbers(table))
    }
}
