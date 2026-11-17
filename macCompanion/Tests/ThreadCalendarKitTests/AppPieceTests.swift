import Foundation
import Testing
@testable import ThreadCalendarKit

// All data here is SYNTHETIC.

/// The spike's SYNTHETIC calendar (calendar-spike EmulatorSelfTest), moved into the tests.
enum SyntheticEvents {
    static let teamsInner = "https://teams.microsoft.com/l/meetup-join/19%3ameeting_SYNTHETIC%40thread.v2/0?context=%7b%22Tid%22%3a%22synthetic%22%7d"

    static var safeLinksTeams: String {
        "https://eur02.safelinks.protection.outlook.com/?url=" + FormEncoding.escape(teamsInner) + "&data=05%7C02%7Csynthetic&reserved=0"
    }

    static func make(now: Date, zone: TimeZone) -> [SourceEvent] {
        let calendar = DayFormat.calendar(zone)
        let today = calendar.startOfDay(for: now)
        func at(_ dayOffset: Int, _ hour: Int, _ minute: Int = 0) -> Date {
            calendar.date(byAdding: DateComponents(day: dayOffset, hour: hour, minute: minute), to: today)!
        }
        let standupStart = now.addingTimeInterval(3600)
        return [
            SourceEvent(
                externalId: "SYNTHETIC-series-standup", localId: "L1", title: "SYNTHETIC Standup",
                start: standupStart, end: standupStart.addingTimeInterval(1800),
                occurrenceDate: standupStart, isRecurring: true, myResponse: .accepted,
                location: "Room 4 (SYNTHETIC)", notes: "Join the meeting now<\(safeLinksTeams)>"
            ),
            SourceEvent(
                externalId: "SYNTHETIC-offsite", localId: "L2", title: "SYNTHETIC Offsite", isAllDay: true,
                start: at(0, 0), end: at(1, 0).addingTimeInterval(-1)
            ),
            SourceEvent(
                externalId: "SYNTHETIC-planning", localId: "L3", title: "SYNTHETIC Planning",
                start: at(1, 10), end: at(1, 11), myResponse: .tentative,
                location: "https://us02web.zoom.us/j/1234567890?pwd=SYNTHETICpass"
            ),
            SourceEvent(
                externalId: "SYNTHETIC-georgian", localId: "L4", title: String(repeating: "ა", count: 300),
                start: at(1, 12), end: at(1, 13), myResponse: .pending
            ),
            SourceEvent(
                externalId: "SYNTHETIC-conference", localId: "L5", title: "SYNTHETIC Conference", isAllDay: true,
                start: at(2, 0), end: at(5, 0)
            ),
            SourceEvent(
                externalId: "SYNTHETIC-canceled", localId: "L6", title: "SYNTHETIC Canceled",
                start: at(1, 15), end: at(1, 16), status: .canceled
            ),
            SourceEvent(
                externalId: "SYNTHETIC-declined", localId: "L7", title: "SYNTHETIC Declined",
                start: at(1, 16), end: at(1, 17), myResponse: .declined
            ),
        ]
    }
}

// MARK: - The four mapper checks of spike U9

@Suite struct U9MapperTests {
    let summary = Mapper.mapAll(SyntheticEvents.make(now: fixedNow, zone: tbilisi), window: window, allDayZone: tbilisi)

    @Test func syntheticSummary() {
        #expect(summary.docs.count == 5)
        #expect(summary.hidden[.canceled] == 1)
        #expect(summary.hidden[.declined] == 1)
    }

    @Test func safeLinksUnwrapped() throws {
        let standup = try #require(summary.docs.first { $0.title == "SYNTHETIC Standup" })
        #expect(standup.joinUrl == SyntheticEvents.teamsInner)
        #expect(standup.joinUnwrapped)
    }

    @Test func locationKept() throws {
        let standup = try #require(summary.docs.first { $0.title == "SYNTHETIC Standup" })
        #expect(standup.location == "Room 4 (SYNTHETIC)")
        #expect(MeetingEncoding.fields(standup)["location"] != nil)
    }

    @Test func zoomLinkNotInLocation() throws {
        let planning = try #require(summary.docs.first { $0.title == "SYNTHETIC Planning" })
        #expect(planning.location == nil)
        #expect(planning.joinService == .zoom)
    }
}

// MARK: - Rules parity

@Suite struct RulesParityTests {
    /// firebase/firestore.rules is three folders up from this file.
    static var rules: String {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent()
        return (try? String(contentsOf: root.appendingPathComponent("firebase/firestore.rules"), encoding: .utf8)) ?? ""
    }

    @Test func joinUrlPatternIsTheRulesPattern() {
        #expect(Self.rules.contains("matches('" + JoinLinkFinder.rulesPattern + "')"))
    }

    @Test func meetingFieldsAreTheRulesFields() {
        var doc = MeetingDoc(id: Ids.hex32("a"), seriesKey: Ids.hex32("s"), title: "SYNTHETIC", time: .timed(start: fixedNow, end: fixedNow), response: .accepted)
        doc.location = "SYNTHETIC room"
        doc.joinUrl = "https://zoom.us/j/1"
        for key in MeetingEncoding.fields(doc).keys {
            #expect(Self.rules.contains("'\(key)'"), "field \(key) is not in the rules")
        }
    }
}

// MARK: - Firestore values and the source document

@Suite struct SourceDocumentTests {
    @Test func parsesNanosecondTimestamps() throws {
        let parsed = try #require(FSValue.parseTimestamp("2026-11-17T08:00:00.123456789Z"))
        #expect(abs(parsed.timeIntervalSince(at("2026-11-17T08:00:00Z")) - 0.123456789) < 0.000_001)
        #expect(FSValue.parseTimestamp("2026-11-17T08:00:00Z") == at("2026-11-17T08:00:00Z"))
        #expect(FSValue.parseTimestamp("not a time") == nil)
    }

    private let fields = SourceFields(
        state: .ok, calendarTitle: "SYNTHETIC Work", fromDay: "2026-11-16", toDay: "2026-12-01",
        zone: "Asia/Tbilisi", uploaderId: "mac-0123456789ab", appVersion: "1.0"
    )

    private func update(_ write: [String: Any]) -> (fields: [String: Any], transforms: [String]) {
        let update = write["update"] as! [String: Any]
        let transforms = (write["updateTransforms"] as? [[String: Any]] ?? []).compactMap { $0["fieldPath"] as? String }
        return (update["fields"] as! [String: Any], transforms)
    }

    @Test func successSetsBothServerTimes() {
        let (fields, transforms) = update(MeetingEncoding.sourceWrite(name: "n", fields, success: true, previousSuccess: fixedNow))
        #expect(transforms == ["updatedAt", "lastSuccessAt"])
        #expect(fields["lastSuccessAt"] == nil)
        #expect(fields["updatedAt"] == nil)
    }

    @Test func failureKeepsThePreviousSuccessOrNull() {
        let kept = update(MeetingEncoding.sourceWrite(name: "n", fields, success: false, previousSuccess: fixedNow))
        #expect(kept.transforms == ["updatedAt"])
        #expect((kept.fields["lastSuccessAt"] as? [String: Any])?["timestampValue"] as? String == FSValue.iso(fixedNow))
        let none = update(MeetingEncoding.sourceWrite(name: "n", fields, success: false, previousSuccess: nil))
        #expect((none.fields["lastSuccessAt"] as? [String: Any])?["nullValue"] != nil)
    }

    @Test func remoteSourceReadsTheFields() {
        let document: [String: Any] = ["fields": [
            "uploaderId": ["stringValue": "mac-other"],
            "updatedAt": ["timestampValue": "2026-11-17T08:00:00.5Z"],
            "fromDay": ["stringValue": "2026-11-16"],
        ]]
        let remote = RemoteSource(document: document)
        #expect(remote.uploaderId == "mac-other")
        #expect(remote.updatedAt == at("2026-11-17T08:00:00Z").addingTimeInterval(0.5))
        #expect(remote.fromDay == "2026-11-16")
        #expect(remote.lastSuccessAt == nil)
        #expect(RemoteSource(document: nil).uploaderId == nil)
    }
}

// MARK: - Config, debounce, backoff, status line

@Suite struct ConfigTests {
    @Test func missingValuesFailCloudMode() {
        #expect(AppConfig.parse(["mode": "cloud", "apiKey": "SYNTHETICkey", "projectId": "demo-x", "clientId": "SYNTHETIC.apps"]) == nil)
        #expect(AppConfig.parse(["mode": "cloud", "apiKey": "has space", "projectId": "p", "clientId": "c", "clientSecret": "s"]) == nil)
        #expect(AppConfig.parse(["mode": "other"]) == nil)
    }

    @Test func fullCloudConfigParsesAndHidesValues() throws {
        let config = try #require(AppConfig.parse([
            "mode": "cloud", "apiKey": "SYNTHETICkey", "projectId": "synthetic-project",
            "clientId": "SYNTHETIC-client.apps.googleusercontent.com", "clientSecret": "SYNTHETICsecret",
        ]))
        #expect(config.mode == .cloud)
        #expect(config.threadURL == AppConfig.threadWebURL)
        #expect(config.networkGuard.mode == .cloud)
        let text = "\(config) \(String(reflecting: config)) \(String(describing: config.oauthClient))"
        #expect(!text.contains("SYNTHETIC"))
        #expect(!text.contains("synthetic-project"))
    }

    @Test func emulatorModeIsTheDemoProject() throws {
        let config = try #require(AppConfig.parse(["mode": "emulator"]))
        #expect(config.projectId == "demo-thread")
        #expect(config.threadURL == nil)
        #expect(try config.firestoreEndpoint().baseURL == "http://127.0.0.1:8085/v1/")
        #expect(config.authEndpoints.identityToolkit == "http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1")
    }

    @Test func emulatorSignInNeedsNoBrowser() async throws {
        var opened = false
        let token = try await AppConfig.emulator.googleIdToken(http: HTTPClient(networkGuard: NetworkGuard(mode: .emulator))) { _ in opened = true }
        #expect(!opened)
        #expect(token.contains("SYNTHETIC-mac-owner"))
    }

    @Test func signInHintsNameTheConsoleStep() {
        #expect(SignInHint.text(for: "INVALID_IDP_RESPONSE").contains("Safelist"))
        #expect(SignInHint.text(for: "API_KEY_HTTP_REFERRER_BLOCKED").contains("Credentials"))
        #expect(SignInHint.text(for: "timeout").contains("5 minutes"))
    }
}

final class ManualScheduler: MainScheduler {
    final class Work: ScheduledWork {
        let delay: TimeInterval
        let run: @MainActor () -> Void
        var cancelled = false
        init(delay: TimeInterval, run: @escaping @MainActor () -> Void) {
            self.delay = delay
            self.run = run
        }

        func cancel() { cancelled = true }
    }

    var scheduled: [Work] = []

    func schedule(after delay: TimeInterval, _ work: @escaping @MainActor () -> Void) -> ScheduledWork {
        let item = Work(delay: delay, run: work)
        scheduled.append(item)
        return item
    }

    @MainActor func fireLive() {
        for item in scheduled where !item.cancelled { item.run() }
    }
}

@Suite @MainActor struct TimingTests {
    @Test func calendarChangesWaitThirtySeconds() {
        let scheduler = ManualScheduler()
        var passes = 0
        let debouncer = Debouncer(scheduler: scheduler) { passes += 1 }
        debouncer.signal()
        debouncer.signal()
        debouncer.signal()
        #expect(scheduler.scheduled.map(\.delay) == [30, 30, 30])
        #expect(scheduler.scheduled.filter { !$0.cancelled }.count == 1)
        #expect(debouncer.isPending)
        scheduler.fireLive()
        #expect(passes == 1)
        #expect(!debouncer.isPending)
    }

    @Test func backoffIsOneFiveFifteenMinutes() {
        var backoff = RetryBackoff()
        #expect(backoff.fail(now: fixedNow) == fixedNow.addingTimeInterval(60))
        #expect(backoff.blocks(at: fixedNow.addingTimeInterval(59)))
        #expect(!backoff.blocks(at: fixedNow.addingTimeInterval(60)))
        #expect(backoff.fail(now: fixedNow) == fixedNow.addingTimeInterval(300))
        #expect(backoff.fail(now: fixedNow) == fixedNow.addingTimeInterval(900))
        #expect(backoff.fail(now: fixedNow) == fixedNow.addingTimeInterval(900))
        backoff.reset()
        #expect(!backoff.blocks(at: fixedNow))
        #expect(backoff.fail(now: fixedNow) == fixedNow.addingTimeInterval(60))
    }

    @Test func statusLine() {
        #expect(StatusText.line(.upToDate(at: at("2026-11-17T10:42:00+04:00"), meetings: 18), zone: tbilisi) == "Up to date, 10:42, 18 meetings")
        #expect(StatusText.line(.upToDate(at: at("2026-11-17T10:42:00+04:00"), meetings: 1), zone: tbilisi) == "Up to date, 10:42, 1 meeting")
        #expect(StatusText.line(.refused) == "Upload refused: update Thread Calendar")
        #expect(StatusText.line(.otherUploader) == "Another Mac is uploading meetings")
        #expect(StatusText.line(.retrying(at: at("2026-11-17T10:47:00+04:00"), code: "RESOURCE_EXHAUSTED"), zone: tbilisi)
            == "Sync failed (RESOURCE_EXHAUSTED), retrying at 10:47")
        #expect(!SyncStatus.upToDate(at: fixedNow, meetings: 0).isProblem)
        #expect(SyncStatus.signInAgain.isProblem)
    }
}

// MARK: - Diagnostics

@Suite struct DiagnosticsTests {
    @Test func logKeepsSafeKeysAndRotates() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("tc-log-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let log = DiagnosticsLog(directory: folder)
        log.set(["pass.writes": .int(0), "a real title": .int(1), "pass.outcome": .code("Real meeting title")])
        let results = log.readResults()
        #expect(results["pass.writes"] as? Int == 0)
        #expect(results["a real title"] == nil)
        #expect(results["pass.outcome"] as? String == "<rejected>")

        try Data(count: DiagnosticsLog.rotateBytes + 1).write(to: log.eventsURL)
        log.event("pass", ["pass.writes": .int(0)])
        let rotated = try String(contentsOf: log.eventsURL, encoding: .utf8)
        #expect(rotated.hasSuffix(" pass pass.writes=0\n"))
        #expect(FileManager.default.fileExists(atPath: folder.appendingPathComponent("events.1.log").path))
    }

    @Test func indexStoreTellsMissingFromEmpty() {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("tc-index-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = IndexStore(directory: folder)
        #expect(store.load() == nil)
        store.write([:])
        #expect(store.load() == [:])
        store.clear()
        #expect(store.load() == nil)
    }
}
