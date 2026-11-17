package thread.domain.workspace

import thread.domain.calendar.MeetingLink
import thread.domain.calendar.SeriesKey
import thread.domain.topic.TopicId

/*
 * Topic links to meeting series (D78 P5, plan section 6 "Topics (Q8)", adr/ADR-020-calendar-meetings-sync.md). A
 * repeating meeting links as a whole series (QB), so one link covers every occurrence. Linking never changes the
 * meeting, and unlinking writes `linked: false`: the rules allow no delete.
 */

/** The Topics the series [seriesKey] is linked to, in link order. Links to Topics that are gone are left out by the caller. */
fun linkedTopicIdsOfSeries(
    seriesKey: SeriesKey,
    links: List<MeetingLink>,
): List<TopicId> = links.filter { it.linked && it.seriesKey == seriesKey }.map { it.topicId }.distinct()

/**
 * Makes [topicIds] the Topics of the series [seriesKey] (the Meeting card's "Link to Topic", many to many). Each new link
 * stores [title] as its snapshot (QA), so the Topic still names the series after it leaves the window; a blank title
 * stores none. An unlink keeps the stored snapshot. Undo puts every link back as it was.
 */
fun setMeetingLinks(
    seriesKey: SeriesKey,
    title: String,
    topicIds: List<TopicId>,
    data: WorkspaceData,
): EditResult {
    val current = linkedTopicIdsOfSeries(seriesKey, data.meetingLinks)
    val wanted = topicIds.distinct()
    val added = wanted.filter { it !in current }
    val removed = current.filter { it !in wanted }
    if (added.isEmpty() && removed.isEmpty()) return EditResult.Unchanged
    val snapshot = title.takeIf { it.isNotBlank() }
    val writes = mutableListOf<WorkspaceWrite>()
    val undo = mutableListOf<WorkspaceWrite>()
    added.forEach {
        writes += WorkspaceWrite.PutMeetingLink(MeetingLink(it, seriesKey, linked = true, title = snapshot))
        undo += WorkspaceWrite.PutMeetingLink(MeetingLink(it, seriesKey, linked = false))
    }
    removed.forEach {
        writes += WorkspaceWrite.PutMeetingLink(MeetingLink(it, seriesKey, linked = false))
        undo += WorkspaceWrite.PutMeetingLink(MeetingLink(it, seriesKey, linked = true))
    }
    return EditResult.Changed(WorkspaceEdit(writes, undo))
}

/** Links the series [seriesKey] to one more Topic (Topic Details, "Link meeting"). */
fun linkMeeting(
    seriesKey: SeriesKey,
    title: String,
    topicId: TopicId,
    data: WorkspaceData,
): EditResult = setMeetingLinks(seriesKey, title, linkedTopicIdsOfSeries(seriesKey, data.meetingLinks) + topicId, data)

/** Unlinks the series [seriesKey] from [topicId]. Its other links are not touched. */
fun unlinkMeeting(
    seriesKey: SeriesKey,
    topicId: TopicId,
    data: WorkspaceData,
): EditResult = setMeetingLinks(seriesKey, "", linkedTopicIdsOfSeries(seriesKey, data.meetingLinks) - topicId, data)
