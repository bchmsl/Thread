package thread.domain.workspace

import thread.domain.calendar.MEETING_TEXT_MAX_LENGTH
import thread.domain.calendar.MeetingAlternate
import thread.domain.calendar.SeriesKey
import thread.domain.calendar.alternateTitleOf
import thread.domain.calendar.isAlternateJoinUrl

/*
 * The owner's alternate title and join link for a meeting series (D100). The Meeting card edits each one on its own.
 * Empty text clears it, so the calendar's value shows again. The calendar's meeting is never written.
 */

/** The stored alternate of the series [seriesKey], or null. */
fun alternateOfSeries(
    seriesKey: SeriesKey,
    alternates: List<MeetingAlternate>,
): MeetingAlternate? = alternates.firstOrNull { it.seriesKey == seriesKey }

/** Each series' alternate that sets anything, by series. */
fun alternatesBySeries(alternates: List<MeetingAlternate>): Map<SeriesKey, MeetingAlternate> =
    alternates.filterNot { it.isEmpty }.associateBy { it.seriesKey }

/** Sets the alternate title of [seriesKey] to [text], trimmed. Blank text clears it. Undo puts back the one before. */
fun setAlternateTitle(
    seriesKey: SeriesKey,
    text: String,
    data: WorkspaceData,
): EditResult {
    val trimmed = text.trim()
    if (trimmed.length > MEETING_TEXT_MAX_LENGTH) return EditResult.Refused(EditProblem.TextTooLong)
    val before = alternateOfSeries(seriesKey, data.meetingAlternates)
    return alternateChange(before, (before ?: MeetingAlternate(seriesKey)).copy(title = alternateTitleOf(trimmed)))
}

/**
 * Sets the alternate join link of [seriesKey] to [text], trimmed. Blank text clears it; anything that is not a full
 * https link is refused with [EditProblem.BadLink]. Undo puts back the one before.
 */
fun setAlternateJoinUrl(
    seriesKey: SeriesKey,
    text: String,
    data: WorkspaceData,
): EditResult {
    val trimmed = text.trim()
    if (trimmed.isNotEmpty() && !isAlternateJoinUrl(trimmed)) return EditResult.Refused(EditProblem.BadLink)
    val before = alternateOfSeries(seriesKey, data.meetingAlternates)
    return alternateChange(before, (before ?: MeetingAlternate(seriesKey)).copy(joinUrl = trimmed.ifEmpty { null }))
}

private fun alternateChange(
    before: MeetingAlternate?,
    after: MeetingAlternate,
): EditResult {
    val was = before ?: MeetingAlternate(after.seriesKey)
    if (after == was) return EditResult.Unchanged
    return EditResult.Changed(
        WorkspaceEdit(
            writes = listOf(WorkspaceWrite.PutMeetingAlternate(after, before = before)),
            undo = listOf(WorkspaceWrite.PutMeetingAlternate(was, before = after)),
        ),
    )
}
