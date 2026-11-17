import Foundation

/// The owner's answer to an invite. `declined` is read from EventKit to hide the meeting; it never reaches Firestore.
public enum MeetingResponse: String, Codable, Sendable, CaseIterable {
    case accepted, tentative, pending, none, declined
}

public enum SourceStatus: Sendable, Equatable {
    case none, confirmed, tentative, canceled
}

/// One event as the EventKit reader hands it over. Tests build these from SYNTHETIC data only.
public struct SourceEvent: Sendable, Equatable {
    public var externalId: String?
    public var localId: String
    public var title: String?
    public var isAllDay: Bool
    public var start: Date
    public var end: Date
    /// Set for recurring events and detached occurrences only.
    public var occurrenceDate: Date?
    public var isRecurring: Bool
    public var isDetached: Bool
    public var status: SourceStatus
    public var myResponse: MeetingResponse
    public var location: String?
    public var url: URL?
    /// Read on the Mac to find a join link. Never uploaded, logged or shown.
    public var notes: String?

    public init(
        externalId: String?,
        localId: String,
        title: String?,
        isAllDay: Bool = false,
        start: Date,
        end: Date,
        occurrenceDate: Date? = nil,
        isRecurring: Bool = false,
        isDetached: Bool = false,
        status: SourceStatus = .confirmed,
        myResponse: MeetingResponse = .none,
        location: String? = nil,
        url: URL? = nil,
        notes: String? = nil
    ) {
        self.externalId = externalId
        self.localId = localId
        self.title = title
        self.isAllDay = isAllDay
        self.start = start
        self.end = end
        self.occurrenceDate = occurrenceDate
        self.isRecurring = isRecurring
        self.isDetached = isDetached
        self.status = status
        self.myResponse = myResponse
        self.location = location
        self.url = url
        self.notes = notes
    }
}

public enum MeetingTime: Equatable, Sendable {
    case timed(start: Date, end: Date)
    /// Day strings in YYYY-MM-DD, never instants (like Task planned days).
    case allDay(firstDay: String, lastDayExclusive: String)
}

/// The upload shape (`validMeeting` in firebase/firestore.rules).
public struct MeetingDoc: Equatable, Sendable {
    public var id: String
    public var seriesKey: String
    public var title: String
    public var time: MeetingTime
    public var response: MeetingResponse
    public var location: String?
    public var joinUrl: String?
    public var joinService: JoinService?
    public var joinUnwrapped: Bool
    public var isRecurring: Bool

    public init(
        id: String,
        seriesKey: String,
        title: String,
        time: MeetingTime,
        response: MeetingResponse,
        location: String? = nil,
        joinUrl: String? = nil,
        joinService: JoinService? = nil,
        joinUnwrapped: Bool = false,
        isRecurring: Bool = false
    ) {
        self.id = id
        self.seriesKey = seriesKey
        self.title = title
        self.time = time
        self.response = response
        self.location = location
        self.joinUrl = joinUrl
        self.joinService = joinService
        self.joinUnwrapped = joinUnwrapped
        self.isRecurring = isRecurring
    }

    /// Hash of every uploaded field. The local index keeps id -> hash only, never titles.
    public var contentHash: String { Ids.contentHash(self) }
}

/// Errors carry codes only: never a token, uid, title or link.
public enum CalendarSyncError: Error, Equatable, CustomStringConvertible {
    case notEmulator
    case notDemoProject
    case blockedHost(String)
    case http(status: Int, code: String)
    case badResponse(String)
    case offline
    case timeout
    case stateMismatch
    case oauth(String)
    case signInNeeded
    case keychain(String)

    public var description: String { code }

    /// A short identifier for logs and the menu.
    public var code: String {
        switch self {
        case .notEmulator: return "notEmulator"
        case .notDemoProject: return "notDemoProject"
        case .blockedHost: return "blockedHost"
        case .http(let status, let code): return code.isEmpty ? "HTTP_\(status)" : code
        case .badResponse(let what): return "badResponse_\(what)"
        case .offline: return "offline"
        case .timeout: return "timeout"
        case .stateMismatch: return "stateMismatch"
        case .oauth(let code): return "oauth_\(code)"
        case .signInNeeded: return "signInNeeded"
        case .keychain(let status): return "keychain_\(status)"
        }
    }
}
