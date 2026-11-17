import CryptoKit
import Foundation

/// calendar-plan.md section 4, "Ids". Hashes keep raw Exchange ids out of Firestore.
public enum Ids {
    public static func sha256Hex(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    public static func hex32(_ text: String) -> String {
        String(sha256Hex(text).prefix(32))
    }

    /// `calendarItemExternalIdentifier`, or the local item id when an event has none.
    /// A moved ("detached") occurrence carries the series id plus an occurrence suffix (spike U7, CalDAV and Exchange:
    /// `<series id>/RID=<digits>`). The suffix is removed so the moved occurrence keeps its series key and doc id.
    static func baseKey(_ event: SourceEvent) -> String {
        if let external = event.externalId, !external.isEmpty {
            return event.isRecurring ? seriesExternalId(external) : external
        }
        return "local:" + event.localId
    }

    /// The occurrence suffix a detached occurrence's external id ends with, if any. Never the whole id.
    public static func occurrenceSuffixRange(_ external: String) -> Range<String.Index>? {
        guard let range = external.range(of: "/RID=[0-9]+$", options: .regularExpression),
              range.lowerBound > external.startIndex else { return nil }
        return range
    }

    public static func seriesExternalId(_ external: String) -> String {
        guard let range = occurrenceSuffixRange(external) else { return external }
        return String(external[..<range.lowerBound])
    }

    /// Same for every occurrence of a series. Used for Topic links.
    public static func seriesKey(for event: SourceEvent) -> String {
        hex32(baseKey(event))
    }

    /// Recurring: series external id + occurrenceDate, which stays the same when one occurrence is moved.
    /// Single event: the series key, which stays the same when the event is moved.
    public static func docId(for event: SourceEvent) -> String {
        if event.isRecurring, let occurrence = event.occurrenceDate {
            let seconds = Int64(occurrence.timeIntervalSince1970.rounded())
            return hex32(baseKey(event) + "|" + String(seconds))
        }
        return hex32(baseKey(event))
    }

    public static func contentHash(_ doc: MeetingDoc) -> String {
        var parts = [doc.id, doc.seriesKey, doc.title, doc.response.rawValue, doc.location ?? "", doc.joinUrl ?? "", "v1"]
        switch doc.time {
        case .timed(let start, let end):
            parts += ["timed", String(start.timeIntervalSince1970), String(end.timeIntervalSince1970)]
        case .allDay(let first, let last):
            parts += ["allDay", first, last]
        }
        return sha256Hex(parts.joined(separator: "\u{1F}"))
    }

    public static func isHex32(_ text: String) -> Bool {
        text.count == 32 && text.allSatisfy { ("0" ... "9").contains($0) || ("a" ... "f").contains($0) }
    }
}
