import Foundation
import Testing
@testable import ThreadCalendarKit

// Upload passes against in-memory fakes. All data here is SYNTHETIC.

final class Clock: @unchecked Sendable {
    var now: Date
    init(_ now: Date) { self.now = now }
    func advance(_ seconds: TimeInterval) { now = now.addingTimeInterval(seconds) }
}

final class FakeAuth: AuthService, @unchecked Sendable {
    let clock: Clock
    var refreshes = 0
    var rejectRefresh = false

    init(clock: Clock) { self.clock = clock }

    func signInWithIdp(googleIdToken: String) async throws -> FirebaseSession {
        FirebaseSession(idToken: "SYNTHETIC-id-0", refreshToken: "SYNTHETIC-refresh", uid: "SYNTHETICuid1", email: "synthetic@example.test",
                        expiresAt: clock.now.addingTimeInterval(3600))
    }

    func refresh(refreshToken: String, email: String?) async throws -> FirebaseSession {
        if rejectRefresh { throw CalendarSyncError.http(status: 400, code: "TOKEN_EXPIRED") }
        refreshes += 1
        return FirebaseSession(idToken: "SYNTHETIC-id-\(refreshes)", refreshToken: refreshToken, uid: "SYNTHETICuid1", email: email,
                               expiresAt: clock.now.addingTimeInterval(3600))
    }
}

final class MemoryVault: AccountVault {
    var stored: StoredAccount?
    func load() -> StoredAccount? { stored }
    func save(_ account: StoredAccount) throws { stored = account }
    func clear() { stored = nil }
}

final class MemoryEngineState: EngineStateStore {
    var uploaderId = "mac-aaaaaaaaaaaa"
    var lastStrayCheck: Date?
}

/// An in-memory Firestore: applies commits (with the request-time transform), answers GETs and id lists.
final class FakeFirestore: FirestoreAPI {
    let root = "projects/demo-thread/databases/(default)/documents"
    let clock: Clock
    var docs: [String: [String: Any]] = [:]
    var commits = 0
    var gets = 0
    var lists = 0
    var tokens: [String] = []
    /// Status codes the next commits answer with instead of applying (for example [401] or [429]).
    var commitFailures: [(Int, String)] = []

    init(clock: Clock) { self.clock = clock }

    func name(_ path: String) -> String { root + "/" + path }

    private func path(_ name: String) -> String { String(name.dropFirst(root.count + 1)) }

    private func response(_ status: Int, _ json: [String: Any] = [:]) -> HTTPResult {
        HTTPResult(status: status, body: try! JSONSerialization.data(withJSONObject: json))
    }

    func commit(_ writes: [[String: Any]], idToken: String) async throws -> HTTPResult {
        tokens.append(idToken)
        if !commitFailures.isEmpty {
            let (status, code) = commitFailures.removeFirst()
            return response(status, ["error": ["code": status, "status": code, "message": code]])
        }
        commits += 1
        for write in writes {
            if let name = write["delete"] as? String {
                docs[path(name)] = nil
            } else if let update = write["update"] as? [String: Any], let name = update["name"] as? String {
                var fields = update["fields"] as? [String: Any] ?? [:]
                for transform in write["updateTransforms"] as? [[String: Any]] ?? [] {
                    fields[transform["fieldPath"] as! String] = FSValue.timestamp(clock.now)
                }
                docs[path(name)] = fields
            }
        }
        return response(200, ["writeResults": []])
    }

    func get(_ path: String, idToken: String) async throws -> HTTPResult {
        gets += 1
        guard let fields = docs[path] else { return response(404, ["error": ["code": 404, "status": "NOT_FOUND"]]) }
        return response(200, ["name": name(path), "fields": fields])
    }

    func listIds(_ collectionPath: String, idToken: String) async throws -> (HTTPResult, [String]) {
        lists += 1
        let prefix = collectionPath + "/"
        let ids = docs.keys.filter { $0.hasPrefix(prefix) && !$0.dropFirst(prefix.count).contains("/") }.map { String($0.dropFirst(prefix.count)) }
        return (response(200, [:]), ids.sorted())
    }

    func meetingIds(uid: String = "SYNTHETICuid1") -> Set<String> {
        let prefix = "users/\(uid)/meetings/"
        return Set(docs.keys.filter { $0.hasPrefix(prefix) }.map { String($0.dropFirst(prefix.count)) })
    }

    var source: [String: Any]? { docs["users/SYNTHETICuid1/meetingSource/main"] }

    func sourceString(_ key: String) -> String? { (source?[key] as? [String: Any])?["stringValue"] as? String }
    func sourceTime(_ key: String) -> String? { (source?[key] as? [String: Any])?["timestampValue"] as? String }
}

@MainActor
final class FakeCalendar: CalendarReading {
    var access = CalendarAccess.granted
    var pickedTitle: String? = "SYNTHETIC Work"
    var list: [SourceEvent]

    init(_ list: [SourceEvent]) { self.list = list }

    func events(in window: SyncWindow) async -> [SourceEvent]? { pickedTitle == nil ? nil : list }
}

@MainActor
final class Harness {
    let clock = Clock(fixedNow)
    let auth: FakeAuth
    let vault = MemoryVault()
    let firestore: FakeFirestore
    let calendar: FakeCalendar
    let state = MemoryEngineState()
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent("tc-engine-" + UUID().uuidString)
    let account: AccountSession
    let engine: SyncEngine
    var online = true

    init(events: [SourceEvent] = SyntheticEvents.make(now: fixedNow, zone: tbilisi), signedIn: Bool = true) {
        auth = FakeAuth(clock: clock)
        firestore = FakeFirestore(clock: clock)
        calendar = FakeCalendar(events)
        if signedIn { vault.stored = StoredAccount(refreshToken: "SYNTHETIC-refresh", uid: "SYNTHETICuid1", email: nil) }
        let clock = clock
        account = AccountSession(auth: auth, vault: vault, now: { clock.now })
        var online: () -> Bool = { true }
        engine = SyncEngine(
            account: account, firestore: firestore, calendar: calendar, index: IndexStore(directory: folder), state: state,
            log: DiagnosticsLog(directory: folder, now: { clock.now }), appVersion: "1.0", now: { clock.now }, isOnline: { online() }
        )
        online = { [unowned self] in self.online }
    }

    deinit { try? FileManager.default.removeItem(at: folder) }

    var index: [String: String]? { IndexStore(directory: folder).load() }

    func run(_ trigger: PassTrigger = .schedule) async -> PassReport {
        await engine.run(trigger)!
    }
}

@Suite @MainActor struct SyncEngineTests {
    @Test func firstPassWritesMeetingsAndSourceInOneCommit() async {
        let h = Harness()
        let report = await h.run(.launch)
        #expect(report.outcome == "written")
        #expect(report.strayCheck)
        #expect(report.upserts == 5)
        #expect(report.writes == 6)
        #expect(report.commits == 1)
        #expect(h.firestore.meetingIds().count == 5)
        #expect(h.firestore.sourceString("state") == "ok")
        #expect(h.firestore.sourceString("uploaderId") == "mac-aaaaaaaaaaaa")
        #expect(h.firestore.sourceString("zone") == "Asia/Tbilisi")
        #expect(h.firestore.sourceString("fromDay") == "2026-11-16")
        #expect(h.firestore.sourceString("toDay") == "2026-12-01")
        #expect(h.firestore.sourceTime("lastSuccessAt") == FSValue.iso(fixedNow))
        #expect(h.index?.count == 5)
        #expect(h.state.lastStrayCheck == fixedNow)
        #expect(h.engine.status == .upToDate(at: fixedNow, meetings: 5))
    }

    @Test func quietPassWritesAndReadsNothing() async {
        let h = Harness()
        _ = await h.run(.launch)
        let (commits, gets, lists) = (h.firestore.commits, h.firestore.gets, h.firestore.lists)
        h.clock.advance(5 * 60)
        let report = await h.run()
        #expect(report.outcome == "quiet")
        #expect(report.writes == 0)
        #expect(report.commits == 0)
        #expect(report.unchanged == 5)
        #expect(h.firestore.commits == commits)
        #expect(h.firestore.gets == gets)
        #expect(h.firestore.lists == lists)
        let logged = DiagnosticsLog(directory: h.folder).readResults()
        #expect(logged["pass.writes"] as? Int == 0)
        #expect(logged["pass.outcome"] as? String == "quiet")
    }

    @Test func heartbeatAfterThirtyMinutesWritesOnlyTheSource() async {
        let h = Harness()
        _ = await h.run(.launch)
        h.clock.advance(31 * 60)
        let report = await h.run()
        #expect(report.outcome == "written")
        #expect(report.writes == 1)
        #expect(report.upserts == 0)
        #expect(h.firestore.sourceTime("updatedAt") == FSValue.iso(h.clock.now))
    }

    @Test func oneChangedMeetingIsOneUpsert() async {
        let h = Harness()
        _ = await h.run(.launch)
        h.calendar.list[0].title = "SYNTHETIC Standup (moved)"
        h.clock.advance(60)
        let report = await h.run(.calendarChanged)
        #expect(report.upserts == 1)
        #expect(report.deletes == 0)
        #expect(report.writes == 2)
        #expect(h.firestore.meetingIds().count == 5)
    }

    @Test func removedMeetingIsDeleted() async {
        let h = Harness()
        _ = await h.run(.launch)
        h.calendar.list.remove(at: 2)
        let report = await h.run(.calendarChanged)
        #expect(report.deletes == 1)
        #expect(h.firestore.meetingIds().count == 4)
        #expect(h.index?.count == 4)
    }

    @Test func noAccessKeepsMeetingsAndTheLastGoodDays() async {
        let h = Harness()
        _ = await h.run(.launch)
        h.calendar.access = .denied
        h.clock.advance(60)
        let report = await h.run()
        #expect(report.outcome == "written")
        #expect(report.writes == 1)
        #expect(h.firestore.sourceString("state") == "noAccess")
        #expect(h.firestore.sourceString("fromDay") == "2026-11-16")
        #expect(h.firestore.sourceString("calendarTitle") == "SYNTHETIC Work")
        #expect(h.firestore.sourceTime("lastSuccessAt") == FSValue.iso(fixedNow))
        #expect(h.firestore.sourceTime("updatedAt") == FSValue.iso(h.clock.now))
        #expect(h.firestore.meetingIds().count == 5)
        #expect(h.engine.status == .noAccess)

        h.clock.advance(60)
        #expect(await h.run().outcome == "quiet")
    }

    @Test func noCalendarBeforeAnyPassWritesNullLastSuccess() async {
        let h = Harness()
        h.calendar.pickedTitle = nil
        let report = await h.run(.launch)
        #expect(report.writes == 1)
        #expect(h.firestore.sourceString("state") == "noCalendar")
        #expect((h.firestore.source?["lastSuccessAt"] as? [String: Any])?["nullValue"] != nil)
        #expect(h.firestore.source?["fromDay"] == nil)
        #expect(h.engine.status == .noCalendar)
    }

    @Test func anotherMacBlocksUntilTakeover() async {
        let h = Harness()
        h.firestore.docs["users/SYNTHETICuid1/meetingSource/main"] = [
            "uploaderId": FSValue.string("mac-bbbbbbbbbbbb"),
            "updatedAt": FSValue.timestamp(fixedNow.addingTimeInterval(-3600)),
        ]
        let blocked = await h.run(.launch)
        #expect(blocked.outcome == "otherUploader")
        #expect(blocked.commits == 0)
        #expect(h.firestore.meetingIds().isEmpty)
        #expect(h.engine.status == .otherUploader)
        #expect(h.index == nil)

        h.engine.approveTakeover()
        let taken = await h.run(.takeover)
        #expect(taken.outcome == "written")
        #expect(h.firestore.sourceString("uploaderId") == "mac-aaaaaaaaaaaa")
        #expect(h.firestore.meetingIds().count == 5)
    }

    @Test func anotherMacSilentForADayIsReplaced() async {
        let h = Harness()
        h.firestore.docs["users/SYNTHETICuid1/meetingSource/main"] = [
            "uploaderId": FSValue.string("mac-bbbbbbbbbbbb"),
            "updatedAt": FSValue.timestamp(fixedNow.addingTimeInterval(-25 * 3600)),
        ]
        #expect(await h.run(.launch).outcome == "written")
        #expect(h.firestore.sourceString("uploaderId") == "mac-aaaaaaaaaaaa")
    }

    @Test func unauthorizedRenewsTheTokenOnce() async {
        let h = Harness()
        _ = await h.run(.launch)
        let refreshes = h.auth.refreshes
        h.calendar.list[0].title = "SYNTHETIC changed"
        h.firestore.commitFailures = [(401, "UNAUTHENTICATED")]
        let report = await h.run()
        #expect(report.outcome == "written")
        #expect(h.auth.refreshes == refreshes + 1)
        #expect(h.firestore.tokens.suffix(2).count == 2)
        #expect(Set(h.firestore.tokens.suffix(2)).count == 2)
    }

    @Test func secondUnauthorizedAsksToSignInAgain() async {
        let h = Harness()
        h.firestore.commitFailures = [(401, "UNAUTHENTICATED"), (401, "UNAUTHENTICATED")]
        let report = await h.run(.launch)
        #expect(report.outcome == "signInNeeded")
        #expect(h.engine.status == .signInAgain)
        #expect(!h.account.isSignedIn)
        #expect(h.vault.stored == nil)
        #expect(await h.run().outcome == "notSignedIn")
        #expect(h.engine.status == .signInAgain)
    }

    @Test func rejectedRefreshAsksToSignInAgain() async {
        let h = Harness()
        h.auth.rejectRefresh = true
        #expect(await h.run(.launch).outcome == "signInNeeded")
        #expect(h.engine.status == .signInAgain)
        #expect(!h.account.isSignedIn)
    }

    @Test func refusedUploadStopsUntilTheOwnerSyncs() async {
        let h = Harness()
        h.firestore.commitFailures = [(403, "PERMISSION_DENIED")]
        #expect(await h.run(.launch).outcome == "PERMISSION_DENIED")
        #expect(h.engine.status == .refused)
        let commits = h.firestore.commits
        #expect(await h.run().outcome == "refused")
        #expect(h.firestore.commits == commits)
        #expect(await h.run(.syncNow).outcome == "written")
    }

    @Test func rateLimitBacksOff() async {
        let h = Harness()
        h.firestore.commitFailures = [(429, "RESOURCE_EXHAUSTED")]
        #expect(await h.run(.launch).outcome == "RESOURCE_EXHAUSTED")
        #expect(h.engine.status == .retrying(at: fixedNow.addingTimeInterval(60), code: "RESOURCE_EXHAUSTED"))
        h.clock.advance(30)
        #expect(await h.run().outcome == "backoff")
        h.clock.advance(31)
        h.firestore.commitFailures = [(503, "UNAVAILABLE")]
        #expect(await h.run(.retry).outcome == "UNAVAILABLE")
        #expect(h.engine.retryAt == h.clock.now.addingTimeInterval(300))
        #expect(await h.run(.syncNow).outcome == "written")
        #expect(h.engine.retryAt == nil)
    }

    @Test func offlineSkipsWithoutCalls() async {
        let h = Harness()
        h.online = false
        #expect(await h.run(.launch).outcome == "offline")
        #expect(h.engine.status == .offline)
        #expect(h.firestore.gets + h.firestore.commits + h.firestore.lists == 0)
    }

    @Test func notSignedInSkipsWithoutCalls() async {
        let h = Harness(signedIn: false)
        #expect(h.engine.status == .notSignedIn)
        #expect(await h.run(.launch).outcome == "notSignedIn")
        #expect(h.firestore.gets + h.firestore.commits + h.firestore.lists == 0)
    }

    @Test func driftCountersReachTheDiagnostics() async {
        let h = Harness(events: [
            event("SYNTHETIC-series-1", start: "2026-11-18T10:00:00+04:00", end: "2026-11-18T11:00:00+04:00", occurrence: "2026-11-18T10:00:00+04:00", recurring: true),
            event("SYNTHETIC-series-1/RID=816415200", start: "2026-11-19T11:00:00+04:00", end: "2026-11-19T12:00:00+04:00", occurrence: "2026-11-19T10:00:00+04:00", recurring: true, detached: true),
            event("SYNTHETIC-other-format", start: "2026-11-20T11:00:00+04:00", end: "2026-11-20T12:00:00+04:00", occurrence: "2026-11-20T10:00:00+04:00", recurring: true, detached: true),
        ])
        let report = await h.run(.launch)
        #expect(report.detached == 2)
        #expect(report.detachedWithSuffix == 1)
        #expect(report.detachedLoneSeries == 1)
        let logged = DiagnosticsLog(directory: h.folder).readResults()
        #expect(logged["pass.detached"] as? Int == 2)
        #expect(logged["pass.detachedWithSuffix"] as? Int == 1)
        #expect(logged["pass.detachedLoneSeries"] as? Int == 1)
        let events = (try? String(contentsOf: DiagnosticsLog(directory: h.folder).eventsURL, encoding: .utf8)) ?? ""
        #expect(events.contains("pass.detachedLoneSeries=1"))
        #expect(!events.contains("SYNTHETIC"))
    }

    @Test func strayCheckDeletesWhatOnlyFirestoreHasAndRewritesWhatItLost() async {
        let h = Harness()
        let stray = Ids.hex32("SYNTHETIC-stray")
        h.firestore.docs["users/SYNTHETICuid1/meetings/\(stray)"] = ["title": FSValue.string("SYNTHETIC old")]
        let first = await h.run(.launch)
        #expect(first.deletes == 1)
        #expect(!h.firestore.meetingIds().contains(stray))

        // Firestore lost a document the index still lists: the daily check writes it again.
        let lost = h.firestore.meetingIds().sorted()[0]
        h.firestore.docs["users/SYNTHETICuid1/meetings/\(lost)"] = nil
        h.clock.advance(60 * 60)
        let hourLater = await h.run()
        #expect(!hourLater.strayCheck)
        #expect(hourLater.upserts == 0)
        h.clock.advance(24 * 60 * 60)
        let dayLater = await h.run()
        #expect(dayLater.strayCheck)
        #expect(dayLater.upserts == 1)
        #expect(h.firestore.meetingIds().contains(lost))
    }

    @Test func workspaceZoneComesFromPrefs() async {
        let h = Harness()
        h.firestore.docs["users/SYNTHETICuid1/prefs/main"] = ["timeZone": FSValue.string("Europe/London")]
        _ = await h.run(.launch)
        #expect(h.firestore.sourceString("zone") == "Europe/London")
        #expect(h.firestore.sourceString("fromDay") == "2026-11-16")

        let bad = Harness()
        bad.firestore.docs["users/SYNTHETICuid1/prefs/main"] = ["timeZone": FSValue.string("Not/AZone")]
        _ = await bad.run(.launch)
        #expect(bad.firestore.sourceString("zone") == "Asia/Tbilisi")
    }

    @Test func stopSyncingRemovesMeetingsKeepsLinksAndSignsOut() async throws {
        let h = Harness()
        _ = await h.run(.launch)
        let link = "users/SYNTHETICuid1/topicMeetingLinks/topic-SYNTHETIC_\(Ids.hex32("s"))"
        h.firestore.docs[link] = ["linked": FSValue.bool(true)]
        try await h.engine.stopSyncing()
        #expect(h.firestore.meetingIds().isEmpty)
        #expect(h.firestore.source == nil)
        #expect(h.firestore.docs[link] != nil)
        #expect(!h.account.isSignedIn)
        #expect(h.index == nil)
        #expect(h.state.lastStrayCheck == nil)
        #expect(h.engine.status == .notSignedIn)
    }

    @Test func failedStopKeepsTheAccount() async {
        let h = Harness()
        _ = await h.run(.launch)
        h.firestore.commitFailures = [(503, "UNAVAILABLE")]
        await #expect(throws: CalendarSyncError.self) { try await h.engine.stopSyncing() }
        #expect(h.account.isSignedIn)
        #expect(h.index != nil)
    }

    @Test func signInStartsWithAStrayCheck() async throws {
        let h = Harness(signedIn: false)
        try await h.account.signIn(googleIdToken: "SYNTHETIC")
        h.engine.signedIn()
        let report = await h.run(.signIn)
        #expect(report.strayCheck)
        #expect(report.outcome == "written")
    }
}

@Suite @MainActor struct AccountSessionTests {
    @Test func tokenIsRenewedOnlyWhenUnderFiveMinutesAreLeft() async throws {
        let clock = Clock(fixedNow)
        let auth = FakeAuth(clock: clock)
        let account = AccountSession(auth: auth, vault: MemoryVault(), now: { clock.now })
        try await account.signIn(googleIdToken: "SYNTHETIC")
        _ = try await account.validSession()
        #expect(auth.refreshes == 0)
        clock.advance(3600 - 301)
        _ = try await account.validSession()
        #expect(auth.refreshes == 0)
        clock.advance(2)
        _ = try await account.validSession()
        #expect(auth.refreshes == 1)
        _ = try await account.validSession(forceRefresh: true)
        #expect(auth.refreshes == 2)
    }

    @Test func signInStoresOnlyTheFirebaseAccount() async throws {
        let clock = Clock(fixedNow)
        let vault = MemoryVault()
        let account = AccountSession(auth: FakeAuth(clock: clock), vault: vault, now: { clock.now })
        try await account.signIn(googleIdToken: "SYNTHETIC-google-token")
        #expect(vault.stored == StoredAccount(refreshToken: "SYNTHETIC-refresh", uid: "SYNTHETICuid1", email: "synthetic@example.test"))
        #expect(account.uid == "SYNTHETICuid1")
        account.signOut()
        #expect(vault.stored == nil)
    }
}
