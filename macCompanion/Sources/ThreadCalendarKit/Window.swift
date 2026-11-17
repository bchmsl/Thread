import Foundation

public enum DayFormat {
    public static func calendar(_ zone: TimeZone) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        return calendar
    }

    public static func day(_ date: Date, _ zone: TimeZone) -> String {
        let parts = calendar(zone).dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }
}

/// Upload pass step 3: the start of yesterday to the end of today + 14, in the workspace zone.
public struct SyncWindow: Equatable, Sendable {
    public let start: Date
    /// Exclusive: the start of today + 15.
    public let end: Date
    public let fromDay: String
    /// Inclusive: today + 14.
    public let toDay: String
    public let zone: TimeZone

    public static func make(now: Date, zone: TimeZone) -> SyncWindow {
        let calendar = DayFormat.calendar(zone)
        let today = calendar.startOfDay(for: now)
        let start = calendar.date(byAdding: .day, value: -1, to: today)!
        let end = calendar.date(byAdding: .day, value: 15, to: today)!
        let last = calendar.date(byAdding: .day, value: 14, to: today)!
        return SyncWindow(start: start, end: end, fromDay: DayFormat.day(start, zone), toDay: DayFormat.day(last, zone), zone: zone)
    }

    /// Timed meetings: any overlap. A zero-length meeting counts when it starts inside the window.
    public func overlaps(start meetingStart: Date, end meetingEnd: Date) -> Bool {
        if meetingStart == meetingEnd { return meetingStart >= start && meetingStart < end }
        return meetingStart < end && meetingEnd > start
    }

    /// All-day meetings: compared as day strings.
    public func overlaps(firstDay: String, lastDayExclusive: String) -> Bool {
        firstDay <= toDay && lastDayExclusive > fromDay
    }
}
