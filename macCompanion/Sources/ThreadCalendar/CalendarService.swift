@preconcurrency import EventKit
import Foundation
import ThreadCalendarKit

/// Reads one calendar with EventKit. It never writes: no save, remove or commit call on the store
/// (scripts/build-app.sh fails the build if one appears in a file that imports EventKit).
@MainActor
final class CalendarService: CalendarReading {
    let store = EKEventStore()
    private let queue = DispatchQueue(label: "threadcalendar.eventkit")
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var status: EKAuthorizationStatus { EKEventStore.authorizationStatus(for: .event) }

    var access: CalendarAccess {
        switch status {
        case .fullAccess: return .granted
        case .notDetermined: return .notDetermined
        default: return .denied
        }
    }

    func requestAccess() async -> Bool {
        do { return try await store.requestFullAccessToEvents() } catch { return false }
    }

    func calendars() -> [EKCalendar] {
        guard access == .granted else { return [] }
        return store.calendars(for: .event).sorted { ($0.source.title, $0.title) < ($1.source.title, $1.title) }
    }

    /// The pick is saved by calendar identifier, with the title as the fallback when a full sync changes the
    /// identifier (spike U3). Resolved on every pass, so a removed calendar shows as "Pick a calendar".
    var picked: EKCalendar? {
        guard access == .granted, let id = defaults.string(forKey: "pickedCalendarId") else { return nil }
        if let calendar = store.calendar(withIdentifier: id) { return calendar }
        guard let title = defaults.string(forKey: "pickedCalendarTitle") else { return nil }
        let matches = store.calendars(for: .event).filter { $0.title == title }
        guard matches.count == 1, let calendar = matches.first else { return nil }
        defaults.set(calendar.calendarIdentifier, forKey: "pickedCalendarId")
        return calendar
    }

    var pickedTitle: String? { picked?.title }

    func pick(_ calendar: EKCalendar) {
        defaults.set(calendar.calendarIdentifier, forKey: "pickedCalendarId")
        defaults.set(calendar.title, forKey: "pickedCalendarTitle")
    }

    func clearPick() {
        defaults.removeObject(forKey: "pickedCalendarId")
        defaults.removeObject(forKey: "pickedCalendarTitle")
    }

    /// `events(matching:)` is synchronous, so it runs on a private queue, off the main actor.
    func events(in window: SyncWindow) async -> [SourceEvent]? {
        guard let calendar = picked else { return nil }
        let read = Read(store: store, calendar: calendar, start: window.start.addingTimeInterval(-86_400), end: window.end)
        return await withCheckedContinuation { continuation in
            queue.async {
                let predicate = read.store.predicateForEvents(withStart: read.start, end: read.end, calendars: [read.calendar])
                continuation.resume(returning: read.store.events(matching: predicate).map(CalendarService.source(from:)))
            }
        }
    }

    /// Handed to the read queue. The store is used there for this read only, the way Apple's EventKit guide fetches
    /// events off the main thread.
    private struct Read: @unchecked Sendable {
        let store: EKEventStore
        let calendar: EKCalendar
        let start: Date
        let end: Date
    }

    /// My response comes from the `isCurrentUser` attendee. A meeting I organize has none, so its response is `none`.
    nonisolated static func source(from event: EKEvent) -> SourceEvent {
        let response: MeetingResponse
        if let me = event.attendees?.first(where: \.isCurrentUser) {
            switch me.participantStatus {
            case .accepted: response = .accepted
            case .tentative: response = .tentative
            case .declined: response = .declined
            case .pending, .unknown: response = .pending
            default: response = .none
            }
        } else {
            response = .none
        }
        let status: SourceStatus
        switch event.status {
        case .canceled: status = .canceled
        case .tentative: status = .tentative
        case .confirmed: status = .confirmed
        default: status = .none
        }
        // A moved occurrence has no recurrence rules but is detached (spike U7), so "recurring" means either flag.
        let recurring = event.hasRecurrenceRules || event.isDetached
        return SourceEvent(
            externalId: event.calendarItemExternalIdentifier,
            localId: event.calendarItemIdentifier,
            title: event.title,
            isAllDay: event.isAllDay,
            start: event.startDate,
            end: event.endDate,
            occurrenceDate: recurring ? event.occurrenceDate : nil,
            isRecurring: recurring,
            isDetached: event.isDetached,
            status: status,
            myResponse: response,
            location: event.location,
            url: event.url,
            notes: event.notes
        )
    }
}
