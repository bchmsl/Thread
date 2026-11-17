import Foundation

// MARK: - Firestore REST (calendar-plan.md section 4, upload pass step 8)

public struct FirestoreEndpoint: Sendable {
    public let baseURL: String
    public let projectId: String
    public let isEmulator: Bool

    /// Refuses anything but a local host and a `demo-` project, so an emulator endpoint can never reach a real project.
    public static func emulator(host: String = "127.0.0.1", port: Int, projectId: String) throws -> FirestoreEndpoint {
        guard host == "127.0.0.1" || host == "localhost" else { throw CalendarSyncError.notEmulator }
        guard projectId.hasPrefix("demo-") else { throw CalendarSyncError.notDemoProject }
        return FirestoreEndpoint(baseURL: "http://\(host):\(port)/v1/", projectId: projectId, isEmulator: true)
    }

    public static func cloud(projectId: String) -> FirestoreEndpoint {
        FirestoreEndpoint(baseURL: "https://firestore.googleapis.com/v1/", projectId: projectId, isEmulator: false)
    }

    public var documentsRoot: String { "projects/\(projectId)/databases/(default)/documents" }

    public func name(_ path: String) -> String { documentsRoot + "/" + path }
}

/// The three calls a pass makes, behind a protocol so passes can be tested with an in-memory store.
public protocol FirestoreAPI {
    func name(_ path: String) -> String
    func commit(_ writes: [[String: Any]], idToken: String) async throws -> HTTPResult
    func get(_ path: String, idToken: String) async throws -> HTTPResult
    /// Document ids only: a one-field mask keeps each listed document small.
    func listIds(_ collectionPath: String, idToken: String) async throws -> (HTTPResult, [String])
}

public struct FirestoreClient: FirestoreAPI {
    public let endpoint: FirestoreEndpoint
    let http: HTTPClient

    public init(endpoint: FirestoreEndpoint, http: HTTPClient) {
        self.endpoint = endpoint
        self.http = http
    }

    public func name(_ path: String) -> String { endpoint.name(path) }

    public func commit(_ writes: [[String: Any]], idToken: String) async throws -> HTTPResult {
        try await http.json("POST", URL(string: endpoint.baseURL + endpoint.documentsRoot + ":commit")!, body: ["writes": writes], bearer: idToken)
    }

    public func get(_ path: String, idToken: String) async throws -> HTTPResult {
        try await http.json("GET", URL(string: endpoint.baseURL + name(path))!, bearer: idToken)
    }

    public func listIds(_ collectionPath: String, idToken: String) async throws -> (HTTPResult, [String]) {
        var ids: [String] = []
        var pageToken: String?
        var last: HTTPResult
        repeat {
            var url = endpoint.baseURL + name(collectionPath) + "?pageSize=300&mask.fieldPaths=schemaVersion"
            if let pageToken { url += "&pageToken=" + FormEncoding.escape(pageToken) }
            last = try await http.json("GET", URL(string: url)!, bearer: idToken)
            guard last.status == 200, let json = last.json else { return (last, ids) }
            for doc in json["documents"] as? [[String: Any]] ?? [] {
                if let name = doc["name"] as? String, let id = name.split(separator: "/").last { ids.append(String(id)) }
            }
            pageToken = json["nextPageToken"] as? String
        } while pageToken != nil
        return (last, ids)
    }
}

// MARK: - Values and writes

public enum FSValue {
    public static func string(_ value: String) -> [String: Any] { ["stringValue": value] }
    public static func bool(_ value: Bool) -> [String: Any] { ["booleanValue": value] }
    public static func int(_ value: Int) -> [String: Any] { ["integerValue": String(value)] }
    public static func timestamp(_ value: Date) -> [String: Any] { ["timestampValue": iso(value)] }
    public static var null: [String: Any] { ["nullValue": NSNull()] }

    public static func iso(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter.string(from: date)
    }

    /// Firestore sends RFC 3339 with up to nine fraction digits; the fraction is parsed by hand.
    public static func parseTimestamp(_ text: String) -> Date? {
        let pattern = "^([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})([.][0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$"
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(text.startIndex..., in: text)
        guard let match = regex.firstMatch(in: text, range: range) else { return nil }
        func group(_ index: Int) -> String? {
            Range(match.range(at: index), in: text).map { String(text[$0]) }
        }
        guard let base = group(1), let zone = group(3), let whole = ISO8601DateFormatter().date(from: base + zone) else { return nil }
        let fraction = group(2).flatMap { Double("0" + $0) } ?? 0
        return whole.addingTimeInterval(fraction)
    }

    /// A field of a document a GET returned.
    public static func field(_ document: [String: Any]?, _ key: String) -> [String: Any]? {
        (document?["fields"] as? [String: Any])?[key] as? [String: Any]
    }

    public static func stringField(_ document: [String: Any]?, _ key: String) -> String? {
        field(document, key)?["stringValue"] as? String
    }

    public static func timestampField(_ document: [String: Any]?, _ key: String) -> Date? {
        (field(document, key)?["timestampValue"] as? String).flatMap(parseTimestamp)
    }
}

public enum Writes {
    /// The commit size. The emulator does not enforce the production limit (spike U9), so stay well under it.
    public static let chunkSize = 250

    /// A full replace of the document, with server-time transforms (`updatedAt == request.time` in the rules).
    public static func upsert(name: String, fields: [String: Any], serverTimeFields: [String] = ["updatedAt"]) -> [String: Any] {
        var write: [String: Any] = ["update": ["name": name, "fields": fields]]
        if !serverTimeFields.isEmpty {
            write["updateTransforms"] = serverTimeFields.map { ["fieldPath": $0, "setToServerValue": "REQUEST_TIME"] }
        }
        return write
    }

    public static func delete(name: String) -> [String: Any] {
        ["delete": name]
    }

    public static func chunks<T>(_ items: [T], size: Int = chunkSize) -> [[T]] {
        stride(from: 0, to: items.count, by: size).map { Array(items[$0 ..< min($0 + size, items.count)]) }
    }
}

// MARK: - The documents the app writes (adr/ADR-020-calendar-meetings-sync.md, Collections)

public enum MeetingSourceState: String, Sendable {
    case ok, noAccess, noCalendar, paused
}

/// `meetingSource/main` without the two times, which the pass sets.
public struct SourceFields: Equatable, Sendable {
    public var state: MeetingSourceState
    public var calendarTitle: String?
    public var fromDay: String?
    public var toDay: String?
    public var zone: String
    public var uploaderId: String
    public var appVersion: String

    public init(state: MeetingSourceState, calendarTitle: String?, fromDay: String?, toDay: String?, zone: String, uploaderId: String, appVersion: String) {
        self.state = state
        self.calendarTitle = calendarTitle
        self.fromDay = fromDay
        self.toDay = toDay
        self.zone = zone
        self.uploaderId = uploaderId
        self.appVersion = appVersion
    }
}

/// What a pass needs to know about the `meetingSource/main` already in Firestore.
public struct RemoteSource: Equatable, Sendable {
    public var uploaderId: String?
    public var updatedAt: Date?
    public var lastSuccessAt: Date?
    public var calendarTitle: String?
    public var fromDay: String?
    public var toDay: String?

    public init(document: [String: Any]?) {
        uploaderId = FSValue.stringField(document, "uploaderId")
        updatedAt = FSValue.timestampField(document, "updatedAt")
        lastSuccessAt = FSValue.timestampField(document, "lastSuccessAt")
        calendarTitle = FSValue.stringField(document, "calendarTitle")
        fromDay = FSValue.stringField(document, "fromDay")
        toDay = FSValue.stringField(document, "toDay")
    }
}

public enum MeetingEncoding {
    public static let schemaVersion = 1

    public static func fields(_ doc: MeetingDoc) -> [String: Any] {
        var fields: [String: Any] = [
            "title": FSValue.string(doc.title),
            "response": FSValue.string(doc.response.rawValue),
            "seriesKey": FSValue.string(doc.seriesKey),
            "schemaVersion": FSValue.int(schemaVersion),
        ]
        switch doc.time {
        case .timed(let start, let end):
            fields["allDay"] = FSValue.bool(false)
            fields["start"] = FSValue.timestamp(start)
            fields["end"] = FSValue.timestamp(end)
        case .allDay(let first, let last):
            fields["allDay"] = FSValue.bool(true)
            fields["firstDay"] = FSValue.string(first)
            fields["lastDayExclusive"] = FSValue.string(last)
        }
        if let location = doc.location { fields["location"] = FSValue.string(location) }
        if let joinUrl = doc.joinUrl { fields["joinUrl"] = FSValue.string(joinUrl) }
        return fields
    }

    public static func meetingsPath(uid: String) -> String { "users/\(uid)/meetings" }
    public static func meetingPath(uid: String, id: String) -> String { "users/\(uid)/meetings/\(id)" }
    public static func sourcePath(uid: String) -> String { "users/\(uid)/meetingSource/main" }
    public static func prefsPath(uid: String) -> String { "users/\(uid)/prefs/main" }

    /// A successful pass sets `lastSuccessAt` to the request time. Any other pass keeps the previous value (or null).
    public static func sourceWrite(name: String, _ source: SourceFields, success: Bool, previousSuccess: Date?) -> [String: Any] {
        var fields: [String: Any] = [
            "state": FSValue.string(source.state.rawValue),
            "zone": FSValue.string(source.zone),
            "uploaderId": FSValue.string(source.uploaderId),
            "appVersion": FSValue.string(source.appVersion),
            "schemaVersion": FSValue.int(schemaVersion),
        ]
        if let title = source.calendarTitle { fields["calendarTitle"] = FSValue.string(title) }
        if let from = source.fromDay { fields["fromDay"] = FSValue.string(from) }
        if let to = source.toDay { fields["toDay"] = FSValue.string(to) }
        var serverTime = ["updatedAt"]
        if success {
            serverTime.append("lastSuccessAt")
        } else if let previousSuccess {
            fields["lastSuccessAt"] = FSValue.timestamp(previousSuccess)
        } else {
            fields["lastSuccessAt"] = FSValue.null
        }
        return Writes.upsert(name: name, fields: fields, serverTimeFields: serverTime)
    }
}
