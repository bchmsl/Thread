import Foundation

/// Values the app may log. Keys are checked identifiers; `code` accepts only short identifier-like text (enum raw
/// values, error codes). There is no case for free text, so a title, location, link, e-mail or the uid cannot be
/// logged by accident.
public enum LogValue: Equatable, Sendable {
    case int(Int)
    case bool(Bool)
    case code(String)

    static let pattern = "^[A-Za-z0-9_.:-]{1,64}$"

    static func isSafe(_ text: String) -> Bool {
        text.range(of: pattern, options: .regularExpression) != nil
    }

    var json: Any {
        switch self {
        case .int(let value): return value
        case .bool(let value): return value
        case .code(let value): return Self.isSafe(value) ? value : "<rejected>"
        }
    }

    var text: String {
        switch self {
        case .int(let value): return String(value)
        case .bool(let value): return String(value)
        case .code(let value): return Self.isSafe(value) ? value : "<rejected>"
        }
    }
}

/// `results.json` (the latest value per key) and `events.log` (one line per event, rotated near 512 KB), in
/// ~/Library/Application Support/ThreadCalendar. Counts, states and error codes only.
public final class DiagnosticsLog: @unchecked Sendable {
    public static let rotateBytes = 512 * 1024

    public let directory: URL
    private let queue = DispatchQueue(label: "threadcalendar.log")
    private let now: @Sendable () -> Date

    public init(directory: URL, now: @escaping @Sendable () -> Date = { Date() }) {
        self.directory = directory
        self.now = now
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    public static func folder(mode: BackendMode) -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent(mode == .cloud ? "ThreadCalendar" : "ThreadCalendar-Emulator", isDirectory: true)
    }

    public var resultsURL: URL { directory.appendingPathComponent("results.json") }
    public var eventsURL: URL { directory.appendingPathComponent("events.log") }
    var previousEventsURL: URL { directory.appendingPathComponent("events.1.log") }

    public func event(_ name: StaticString, _ values: [String: LogValue] = [:]) {
        var line = FSValue.iso(now()) + " \(name)"
        for key in values.keys.sorted() where LogValue.isSafe(key) {
            line += " \(key)=\(values[key]!.text)"
        }
        line += "\n"
        queue.sync {
            let size = (try? FileManager.default.attributesOfItem(atPath: eventsURL.path)[.size] as? Int) ?? 0
            if size > Self.rotateBytes {
                try? FileManager.default.removeItem(at: previousEventsURL)
                try? FileManager.default.moveItem(at: eventsURL, to: previousEventsURL)
            }
            if let handle = try? FileHandle(forWritingTo: eventsURL) {
                handle.seekToEndOfFile()
                handle.write(Data(line.utf8))
                try? handle.close()
            } else {
                try? Data(line.utf8).write(to: eventsURL)
            }
        }
    }

    public func set(_ values: [String: LogValue]) {
        queue.sync {
            var results = (try? JSONSerialization.jsonObject(with: Data(contentsOf: resultsURL))) as? [String: Any] ?? [:]
            for (key, value) in values where LogValue.isSafe(key) {
                results[key] = value.json
            }
            results["updatedAt"] = FSValue.iso(now())
            if let data = try? JSONSerialization.data(withJSONObject: results, options: [.prettyPrinted, .sortedKeys]) {
                try? data.write(to: resultsURL, options: .atomic)
            }
        }
    }

    public func readResults() -> [String: Any] {
        queue.sync { (try? JSONSerialization.jsonObject(with: Data(contentsOf: resultsURL))) as? [String: Any] ?? [:] }
    }

    /// Clears both files ("Stop syncing" keeps no history of the account).
    public func clear() {
        queue.sync {
            for url in [resultsURL, eventsURL, previousEventsURL] { try? FileManager.default.removeItem(at: url) }
        }
    }
}

/// id -> content hash of what the last successful pass uploaded. No titles.
public struct IndexStore: Sendable {
    public let url: URL

    public init(directory: URL, name: String = "index.json") {
        url = directory.appendingPathComponent(name)
    }

    /// Nil when there is no index yet (first pass, after sign-in, or a damaged file): the pass then lists remote ids.
    public func load() -> [String: String]? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode([String: String].self, from: data)
    }

    public func write(_ index: [String: String]) {
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(index) { try? data.write(to: url, options: .atomic) }
    }

    public func clear() {
        try? FileManager.default.removeItem(at: url)
    }
}
