import Foundation

public enum TextCap {
    /// Trims, turns line breaks into spaces and caps at `max` UTF-16 units without splitting a character.
    /// The rules' `size()` counts UTF-16 units in the emulator (spike U9: 300 Georgian letters pass, 200 emoji fail).
    public static func cap(_ text: String?, max: Int) -> String? {
        guard let raw = text else { return nil }
        var value = raw.replacingOccurrences(of: "\r\n", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\r", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return nil }
        if value.utf16.count <= max { return value }
        var out = ""
        var count = 0
        for character in value {
            let size = String(character).utf16.count
            if count + size > max { break }
            out.append(character)
            count += size
        }
        value = out.trimmingCharacters(in: .whitespaces)
        return value.isEmpty ? nil : value
    }
}

public enum LocationText {
    /// Removes every link from the location, so a Zoom link with `pwd=` never shows as location text.
    /// The join link travels only in `joinUrl`. A location that was only a link becomes nil.
    public static func clean(_ location: String?) -> String? {
        guard let location else { return nil }
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else { return nil }
        let range = NSRange(location.startIndex..., in: location)
        var text = location
        for match in detector.matches(in: location, options: [], range: range).reversed() {
            guard let swiftRange = Range(match.range, in: text) else { continue }
            text.replaceSubrange(swiftRange, with: " ")
        }
        let trimmed = text
            .components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.joined(separator: " ")
            .trimmingCharacters(in: CharacterSet(charactersIn: " ;,|-"))
        return TextCap.cap(trimmed, max: 300)
    }
}

public enum HiddenReason: String, Sendable, CaseIterable { case canceled, declined }

public enum SkipReason: String, Sendable, CaseIterable { case outsideWindow, tooLong, badTimes }

public enum MapOutcome: Equatable, Sendable {
    case upload(MeetingDoc)
    case hidden(HiddenReason)
    case skipped(SkipReason)
}

/// Counts only, plus the docs to upload. The counts can never hold a title, location or link.
public struct MapSummary: Sendable {
    public var docs: [MeetingDoc] = []
    public var read = 0
    public var hidden: [HiddenReason: Int] = [:]
    public var skipped: [SkipReason: Int] = [:]
    public var idCollisions = 0
    public var missingExternalId = 0
    public var joinByService: [JoinService: Int] = [:]
    public var joinUnwrapped = 0
    public var withLocation = 0
    public var allDay = 0
    public var multiDay = 0
    public var recurring = 0
    /// Drift guard (calendar-plan.md section 4, "Ids"): moved occurrences,
    public var detached = 0
    /// those whose raw external id has the `/RID=` occurrence suffix,
    public var detachedWithSuffix = 0
    /// and those that share their series key with no other meeting in the pass. More than 0 can mean a new id form,
    /// or only that the rest of the series is outside the window.
    public var detachedLoneSeries = 0
    public var tentative = 0
    public var pending = 0

    public init() {}
}

public enum Mapper {
    public static let maxTimedLength: TimeInterval = 14 * 24 * 3600

    /// `allDayZone` is the zone EventKit uses for floating all-day dates (the Mac's current zone).
    public static func map(_ event: SourceEvent, window: SyncWindow, allDayZone: TimeZone) -> MapOutcome {
        if event.status == .canceled { return .hidden(.canceled) }
        if event.myResponse == .declined { return .hidden(.declined) }
        guard event.end >= event.start else { return .skipped(.badTimes) }

        let time: MeetingTime
        if event.isAllDay {
            let calendar = DayFormat.calendar(allDayZone)
            let first = DayFormat.day(event.start, allDayZone)
            // EventKit may end an all-day event at 23:59:59 or at the next midnight; both mean the same last day.
            let lastInstant = event.end > event.start ? event.end.addingTimeInterval(-1) : event.start
            let lastDay = calendar.startOfDay(for: lastInstant)
            let exclusive = DayFormat.day(calendar.date(byAdding: .day, value: 1, to: lastDay)!, allDayZone)
            guard window.overlaps(firstDay: first, lastDayExclusive: exclusive) else { return .skipped(.outsideWindow) }
            time = .allDay(firstDay: first, lastDayExclusive: exclusive)
        } else {
            guard event.end.timeIntervalSince(event.start) <= maxTimedLength else { return .skipped(.tooLong) }
            guard window.overlaps(start: event.start, end: event.end) else { return .skipped(.outsideWindow) }
            time = .timed(start: event.start, end: event.end)
        }

        let join = JoinLinkFinder.find(url: event.url, location: event.location, notes: event.notes)
        return .upload(MeetingDoc(
            id: Ids.docId(for: event),
            seriesKey: Ids.seriesKey(for: event),
            title: TextCap.cap(event.title, max: 300) ?? "",
            time: time,
            response: event.myResponse,
            location: LocationText.clean(event.location),
            joinUrl: join?.url,
            joinService: join?.service,
            joinUnwrapped: join?.unwrappedFromSafeLinks ?? false,
            isRecurring: event.isRecurring
        ))
    }

    public static func mapAll(_ events: [SourceEvent], window: SyncWindow, allDayZone: TimeZone) -> MapSummary {
        var summary = MapSummary()
        var seen = Set<String>()
        var detachedSeries: [String] = []
        for event in events {
            summary.read += 1
            if (event.externalId ?? "").isEmpty { summary.missingExternalId += 1 }
            switch map(event, window: window, allDayZone: allDayZone) {
            case .hidden(let reason):
                summary.hidden[reason, default: 0] += 1
            case .skipped(let reason):
                summary.skipped[reason, default: 0] += 1
            case .upload(let doc):
                guard seen.insert(doc.id).inserted else {
                    summary.idCollisions += 1
                    continue
                }
                summary.docs.append(doc)
                if let service = doc.joinService { summary.joinByService[service, default: 0] += 1 }
                if doc.joinUnwrapped { summary.joinUnwrapped += 1 }
                if doc.location != nil { summary.withLocation += 1 }
                if case .allDay(let first, let last) = doc.time {
                    summary.allDay += 1
                    if DayMath.days(from: first, to: last) > 1 { summary.multiDay += 1 }
                }
                if event.isRecurring { summary.recurring += 1 }
                if event.isDetached {
                    summary.detached += 1
                    detachedSeries.append(doc.seriesKey)
                    if Ids.occurrenceSuffixRange(event.externalId ?? "") != nil { summary.detachedWithSuffix += 1 }
                }
                if doc.response == .tentative { summary.tentative += 1 }
                if doc.response == .pending { summary.pending += 1 }
            }
        }
        let seriesSizes = Dictionary(grouping: summary.docs, by: \.seriesKey).mapValues(\.count)
        summary.detachedLoneSeries = detachedSeries.filter { (seriesSizes[$0] ?? 0) < 2 }.count
        return summary
    }
}

public enum DayMath {
    public static func days(from first: String, to lastExclusive: String) -> Int {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        guard let a = formatter.date(from: first), let b = formatter.date(from: lastExclusive) else { return 0 }
        return Int((b.timeIntervalSince(a) / 86_400).rounded())
    }
}

/// Upload pass steps 6 to 8: what changed since the last successful pass.
public struct UploadPlan: Equatable, Sendable {
    public var upserts: [MeetingDoc]
    public var deletes: [String]
    public var unchanged: Int

    public var isEmpty: Bool { upserts.isEmpty && deletes.isEmpty }
}

public enum Differ {
    public static func plan(docs: [MeetingDoc], index: [String: String]) -> UploadPlan {
        var upserts: [MeetingDoc] = []
        var unchanged = 0
        var current = Set<String>()
        for doc in docs {
            current.insert(doc.id)
            if index[doc.id] == doc.contentHash { unchanged += 1 } else { upserts.append(doc) }
        }
        let deletes = index.keys.filter { !current.contains($0) }.sorted()
        return UploadPlan(upserts: upserts, deletes: deletes, unchanged: unchanged)
    }

    /// The stray check: with the meeting ids Firestore holds, a doc Firestore lacks is written again and an id only
    /// Firestore knows is deleted. The local index decides only for ids both sides have.
    public static func plan(docs: [MeetingDoc], index: [String: String], remoteIds: [String]?) -> UploadPlan {
        guard let remoteIds else { return plan(docs: docs, index: index) }
        let effective = Dictionary(remoteIds.map { ($0, index[$0] ?? "") }, uniquingKeysWith: { first, _ in first })
        return plan(docs: docs, index: effective)
    }

    public static func index(for docs: [MeetingDoc]) -> [String: String] {
        Dictionary(docs.map { ($0.id, $0.contentHash) }, uniquingKeysWith: { first, _ in first })
    }
}
