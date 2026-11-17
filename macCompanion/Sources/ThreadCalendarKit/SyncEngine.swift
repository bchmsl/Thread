import Foundation

// MARK: - What the engine needs from the app

public enum CalendarAccess: String, Equatable, Sendable {
    case granted, notDetermined, denied
}

/// The EventKit side, behind a protocol so passes can be tested with SYNTHETIC events.
@MainActor
public protocol CalendarReading: AnyObject {
    var access: CalendarAccess { get }
    /// The picked calendar's title, or nil when none is picked or the pick was lost.
    var pickedTitle: String? { get }
    /// The picked calendar's events from a day before the window to its end. Nil when it cannot be read.
    func events(in window: SyncWindow) async -> [SourceEvent]?
}

/// What survives a restart besides the index: the install's uploader id and the last stray check.
public protocol EngineStateStore: AnyObject {
    var uploaderId: String { get }
    var lastStrayCheck: Date? { get set }
}

public final class DefaultsEngineState: EngineStateStore {
    let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// A random id per install, "mac-" and 12 hex characters. It tells two Macs on one account apart.
    public var uploaderId: String {
        if let id = defaults.string(forKey: "uploaderId"), !id.isEmpty { return id }
        let id = "mac-" + RandomBytes.make(6).map { String(format: "%02x", $0) }.joined()
        defaults.set(id, forKey: "uploaderId")
        return id
    }

    public var lastStrayCheck: Date? {
        get { defaults.object(forKey: "lastStrayCheck") as? Date }
        set { defaults.set(newValue, forKey: "lastStrayCheck") }
    }
}

// MARK: - Status and reports

public enum SyncStatus: Equatable, Sendable {
    case starting
    case syncing
    case upToDate(at: Date, meetings: Int)
    case notSignedIn
    case signInAgain
    case offline
    case noAccess
    case noCalendar
    case refused
    case otherUploader
    case retrying(at: Date, code: String)
    case failed(code: String)

    /// For the diagnostics log: the case name only, never the count or time.
    public var code: String {
        switch self {
        case .starting: return "starting"
        case .syncing: return "syncing"
        case .upToDate: return "upToDate"
        case .notSignedIn: return "notSignedIn"
        case .signInAgain: return "signInAgain"
        case .offline: return "offline"
        case .noAccess: return "noAccess"
        case .noCalendar: return "noCalendar"
        case .refused: return "refused"
        case .otherUploader: return "otherUploader"
        case .retrying: return "retrying"
        case .failed: return "failed"
        }
    }

    public var isProblem: Bool {
        switch self {
        case .starting, .syncing, .upToDate: return false
        default: return true
        }
    }
}

public enum PassTrigger: String, Sendable {
    case launch, calendarChanged, schedule, wake, dayChanged, networkBack, retry
    case signIn, calendarPicked, syncNow, takeover

    /// The owner asked for it: it runs through the backoff and clears a refused upload.
    public var isManual: Bool {
        switch self {
        case .signIn, .calendarPicked, .syncNow, .takeover: return true
        default: return false
        }
    }
}

/// One pass, counts and codes only (calendar-plan.md section 4: the drift counters live here).
public struct PassReport: Equatable, Sendable {
    public var trigger: PassTrigger
    public var outcome = "skipped"
    public var read = 0
    public var meetings = 0
    public var hiddenCanceled = 0
    public var hiddenDeclined = 0
    public var skippedOutside = 0
    public var skippedOther = 0
    public var idCollisions = 0
    public var missingExternalId = 0
    public var detached = 0
    public var detachedWithSuffix = 0
    public var detachedLoneSeries = 0
    public var tentative = 0
    public var pending = 0
    public var upserts = 0
    public var deletes = 0
    public var unchanged = 0
    public var writes = 0
    public var commits = 0
    public var strayCheck = false
    public var sourceWritten = false
    public var durationMs = 0

    public init(trigger: PassTrigger) {
        self.trigger = trigger
    }

    public var logValues: [String: LogValue] {
        [
            "pass.trigger": .code(trigger.rawValue), "pass.outcome": .code(outcome), "pass.read": .int(read),
            "pass.meetings": .int(meetings), "pass.hiddenCanceled": .int(hiddenCanceled), "pass.hiddenDeclined": .int(hiddenDeclined),
            "pass.skippedOutside": .int(skippedOutside), "pass.skippedOther": .int(skippedOther), "pass.idCollisions": .int(idCollisions),
            "pass.missingExternalId": .int(missingExternalId), "pass.detached": .int(detached),
            "pass.detachedWithSuffix": .int(detachedWithSuffix), "pass.detachedLoneSeries": .int(detachedLoneSeries),
            "pass.tentative": .int(tentative), "pass.pending": .int(pending), "pass.upserts": .int(upserts),
            "pass.deletes": .int(deletes), "pass.unchanged": .int(unchanged), "pass.writes": .int(writes),
            "pass.commits": .int(commits), "pass.strayCheck": .bool(strayCheck), "pass.sourceWritten": .bool(sourceWritten),
            "pass.durationMs": .int(durationMs),
        ]
    }

    mutating func count(_ summary: MapSummary) {
        read = summary.read
        meetings = summary.docs.count
        hiddenCanceled = summary.hidden[.canceled] ?? 0
        hiddenDeclined = summary.hidden[.declined] ?? 0
        skippedOutside = summary.skipped[.outsideWindow] ?? 0
        skippedOther = (summary.skipped[.tooLong] ?? 0) + (summary.skipped[.badTimes] ?? 0)
        idCollisions = summary.idCollisions
        missingExternalId = summary.missingExternalId
        detached = summary.detached
        detachedWithSuffix = summary.detachedWithSuffix
        detachedLoneSeries = summary.detachedLoneSeries
        tentative = summary.tentative
        pending = summary.pending
    }
}

// MARK: - The upload pass

/// calendar-plan.md section 4, "Upload pass", one pass at a time. Writes only what changed; a pass with no change
/// writes nothing, except the `meetingSource/main` heartbeat every 30 minutes, so Thread can tell a running Mac app
/// from a stopped one.
@MainActor
public final class SyncEngine {
    public static let heartbeat: TimeInterval = 30 * 60
    public static let zoneCacheLife: TimeInterval = 60 * 60
    public static let strayCheckEvery: TimeInterval = 24 * 60 * 60
    public static let otherUploaderWindow: TimeInterval = 24 * 60 * 60
    /// The web's fallback (`DEFAULT_WORKSPACE_ZONE` in shared/domain/.../time/LocalDay.kt).
    public static let fallbackZone = "Asia/Tbilisi"

    let account: AccountSession
    let firestore: FirestoreAPI
    let calendar: CalendarReading
    let index: IndexStore
    let state: EngineStateStore
    let log: DiagnosticsLog?
    let appVersion: String
    let now: () -> Date
    let isOnline: () -> Bool

    public private(set) var status: SyncStatus = .starting {
        didSet { if status != oldValue { onChange?(status) } }
    }
    public var onChange: ((SyncStatus) -> Void)?

    private var running = false
    private var stopping = false
    private var rerun: PassTrigger?
    private var refused = false
    private var needsSignInAgain = false
    private var takeoverApproved = false
    private var backoff = RetryBackoff()
    private var lastSourceWrite: (fields: SourceFields, at: Date)?
    private var zoneCache: (uid: String, zone: String, at: Date)?

    public init(
        account: AccountSession,
        firestore: FirestoreAPI,
        calendar: CalendarReading,
        index: IndexStore,
        state: EngineStateStore,
        log: DiagnosticsLog?,
        appVersion: String,
        now: @escaping () -> Date = { Date() },
        isOnline: @escaping () -> Bool = { true }
    ) {
        self.account = account
        self.firestore = firestore
        self.calendar = calendar
        self.index = index
        self.state = state
        self.log = log
        self.appVersion = appVersion
        self.now = now
        self.isOnline = isOnline
        if !account.isSignedIn { status = .notSignedIn }
    }

    public var isRunning: Bool { running }
    public var retryAt: Date? { backoff.until }

    /// Runs a pass, or queues one if a pass is running (the queued pass keeps the manual trigger, if any).
    @discardableResult
    public func run(_ trigger: PassTrigger) async -> PassReport? {
        if running || stopping {
            if !stopping, rerun == nil || trigger.isManual { rerun = trigger }
            return nil
        }
        running = true
        defer { running = false }
        var report = await pass(trigger)
        while let next = rerun {
            rerun = nil
            report = await pass(next)
        }
        return report
    }

    /// After a successful sign-in: forget the previous account's index and caches, so the next pass checks strays.
    public func signedIn() {
        resetLocalState()
    }

    private func resetLocalState() {
        index.clear()
        state.lastStrayCheck = nil
        lastSourceWrite = nil
        zoneCache = nil
        needsSignInAgain = false
        refused = false
        takeoverApproved = false
        backoff.reset()
    }

    /// "Another Mac is uploading meetings. Take over?" answered yes. The next pass writes anyway.
    public func approveTakeover() {
        takeoverApproved = true
    }

    // MARK: Pass

    private func pass(_ trigger: PassTrigger) async -> PassReport {
        let started = now()
        var report = PassReport(trigger: trigger)
        defer {
            report.durationMs = Int((now().timeIntervalSince(started) * 1000).rounded())
            log?.event("pass", report.logValues)
            log?.set(report.logValues)
        }
        if trigger.isManual { refused = false }
        guard account.isSignedIn else {
            status = needsSignInAgain ? .signInAgain : .notSignedIn
            report.outcome = "notSignedIn"
            return report
        }
        if refused {
            report.outcome = "refused"
            return report
        }
        if !trigger.isManual, backoff.blocks(at: now()) {
            report.outcome = "backoff"
            return report
        }
        guard isOnline() else {
            status = .offline
            report.outcome = "offline"
            return report
        }
        if case .upToDate = status {} else { status = .syncing }
        do {
            try await upload(&report)
        } catch let error as CalendarSyncError {
            handle(error, &report)
        } catch {
            handle(.badResponse("unexpected"), &report)
        }
        return report
    }

    private func handle(_ error: CalendarSyncError, _ report: inout PassReport) {
        report.outcome = error.code
        switch error {
        case .signInNeeded:
            needsSignInAgain = true
            status = .signInAgain
        case .offline:
            status = .offline
        case .http(let code, let reason) where code == 403 && reason == "PERMISSION_DENIED":
            refused = true
            status = .refused
        default:
            let at = backoff.fail(now: now())
            status = .retrying(at: at, code: error.code)
        }
    }

    private func upload(_ report: inout PassReport) async throws {
        let session = try await account.validSession()
        let uid = session.uid
        let zoneId = try await workspaceZone(uid: uid)
        let zone = TimeZone(identifier: zoneId) ?? TimeZone(identifier: Self.fallbackZone)!
        let time = now()
        let window = SyncWindow.make(now: time, zone: zone)

        var desired = SourceFields(
            state: .ok, calendarTitle: nil, fromDay: nil, toDay: nil,
            zone: zoneId, uploaderId: state.uploaderId, appVersion: appVersion
        )
        var docs: [MeetingDoc] = []
        if calendar.access != .granted {
            desired.state = .noAccess
        } else if let title = calendar.pickedTitle, let events = await calendar.events(in: window) {
            let summary = Mapper.mapAll(events, window: window, allDayZone: .current)
            report.count(summary)
            docs = summary.docs
            desired.calendarTitle = TextCap.cap(title, max: 100)
            desired.fromDay = window.fromDay
            desired.toDay = window.toDay
        } else {
            desired.state = .noCalendar
        }
        let success = desired.state == .ok

        var plan = UploadPlan(upserts: [], deletes: [], unchanged: 0)
        var strayDue = false
        if success {
            let saved = index.load()
            strayDue = saved == nil || state.lastStrayCheck.map { time.timeIntervalSince($0) >= Self.strayCheckEvery } ?? true
            let remoteIds = strayDue ? try await listMeetingIds(uid: uid) : nil
            plan = Differ.plan(docs: docs, index: saved ?? [:], remoteIds: remoteIds)
        }
        report.strayCheck = strayDue
        report.upserts = plan.upserts.count
        report.deletes = plan.deletes.count
        report.unchanged = plan.unchanged

        let sourceDue = lastSourceWrite.map { $0.fields != desired || time.timeIntervalSince($0.at) >= Self.heartbeat } ?? true
        if plan.isEmpty && !sourceDue {
            if success {
                index.write(Differ.index(for: docs))
                if strayDue { state.lastStrayCheck = time }
            }
            finish(desired.state, meetings: docs.count, at: time)
            report.outcome = "quiet"
            return
        }

        // Single uploader: read the source before any write.
        let (sourceResult, _) = try await authorized { (try await self.firestore.get(MeetingEncoding.sourcePath(uid: uid), idToken: $0), ()) }
        guard sourceResult.status == 200 || sourceResult.status == 404 else {
            throw CalendarSyncError.http(status: sourceResult.status, code: sourceResult.errorCode)
        }
        let remote = RemoteSource(document: sourceResult.status == 200 ? sourceResult.json : nil)
        if let other = remote.uploaderId, other != state.uploaderId, !takeoverApproved,
           let updated = remote.updatedAt, time.timeIntervalSince(updated) < Self.otherUploaderWindow {
            status = .otherUploader
            report.outcome = "otherUploader"
            return
        }

        var written = desired
        if !success {
            // A failed pass keeps what the last good pass covered, so Thread's "today known" stays true to it.
            written.calendarTitle = remote.calendarTitle
            written.fromDay = remote.fromDay
            written.toDay = remote.toDay
        }
        var writes = plan.upserts.map {
            Writes.upsert(name: firestore.name(MeetingEncoding.meetingPath(uid: uid, id: $0.id)), fields: MeetingEncoding.fields($0))
        }
        writes += plan.deletes.map { Writes.delete(name: firestore.name(MeetingEncoding.meetingPath(uid: uid, id: $0))) }
        writes.append(MeetingEncoding.sourceWrite(
            name: firestore.name(MeetingEncoding.sourcePath(uid: uid)), written, success: success, previousSuccess: remote.lastSuccessAt
        ))
        for chunk in Writes.chunks(writes) {
            let (result, _) = try await authorized { (try await self.firestore.commit(chunk, idToken: $0), ()) }
            guard result.status == 200 else { throw CalendarSyncError.http(status: result.status, code: result.errorCode) }
            report.commits += 1
            report.writes += chunk.count
        }
        report.sourceWritten = true

        if success {
            index.write(Differ.index(for: docs))
            if strayDue { state.lastStrayCheck = time }
        }
        lastSourceWrite = (desired, time)
        takeoverApproved = false
        finish(desired.state, meetings: docs.count, at: time)
        report.outcome = "written"
    }

    private func finish(_ sourceState: MeetingSourceState, meetings: Int, at time: Date) {
        backoff.reset()
        switch sourceState {
        case .ok: status = .upToDate(at: time, meetings: meetings)
        case .noAccess: status = .noAccess
        case .noCalendar, .paused: status = .noCalendar
        }
    }

    // MARK: Calls

    /// Sends a call with a valid ID token. A 401 renews the token and retries once; a second 401 signs out.
    private func authorized<T>(_ call: (String) async throws -> (HTTPResult, T)) async throws -> (HTTPResult, T) {
        var session = try await account.validSession()
        var response = try await call(session.idToken)
        if response.0.status == 401 {
            session = try await account.validSession(forceRefresh: true)
            response = try await call(session.idToken)
            if response.0.status == 401 {
                account.signOut()
                throw CalendarSyncError.signInNeeded
            }
        }
        return response
    }

    private func listMeetingIds(uid: String) async throws -> [String] {
        let (result, ids) = try await authorized { try await self.firestore.listIds(MeetingEncoding.meetingsPath(uid: uid), idToken: $0) }
        // A user with no meetings yet gets 200 with no documents; 404 is treated the same.
        guard result.status == 200 || result.status == 404 else {
            throw CalendarSyncError.http(status: result.status, code: result.errorCode)
        }
        return ids
    }

    /// `users/{uid}/prefs/main.timeZone`, cached for an hour. A missing field or document means the web's fallback.
    private func workspaceZone(uid: String) async throws -> String {
        let time = now()
        if let cached = zoneCache, cached.uid == uid, time.timeIntervalSince(cached.at) < Self.zoneCacheLife {
            return cached.zone
        }
        let (result, _) = try await authorized { (try await self.firestore.get(MeetingEncoding.prefsPath(uid: uid), idToken: $0), ()) }
        let zone: String
        switch result.status {
        case 200:
            let value = FSValue.stringField(result.json, "timeZone")
            zone = value.flatMap { TimeZone(identifier: $0) != nil && $0.count <= 64 ? $0 : nil } ?? Self.fallbackZone
        case 404:
            zone = Self.fallbackZone
        default:
            throw CalendarSyncError.http(status: result.status, code: result.errorCode)
        }
        zoneCache = (uid, zone, time)
        return zone
    }

    // MARK: Stop syncing

    /// "Stop syncing and remove meetings": deletes every meeting and `meetingSource/main`, clears the local state,
    /// then signs out. Topic links stay. On failure nothing local is cleared and the owner can try again.
    public func stopSyncing() async throws {
        while running { try? await Task.sleep(nanoseconds: 100_000_000) }
        stopping = true
        defer { stopping = false }
        if account.isSignedIn {
            let session = try await account.validSession()
            let uid = session.uid
            let ids = try await listMeetingIds(uid: uid)
            var writes = ids.map { Writes.delete(name: firestore.name(MeetingEncoding.meetingPath(uid: uid, id: $0))) }
            writes.append(Writes.delete(name: firestore.name(MeetingEncoding.sourcePath(uid: uid))))
            for chunk in Writes.chunks(writes) {
                let (result, _) = try await authorized { (try await self.firestore.commit(chunk, idToken: $0), ()) }
                guard result.status == 200 else { throw CalendarSyncError.http(status: result.status, code: result.errorCode) }
            }
            log?.event("stopSyncing", ["deleted": .int(ids.count)])
        }
        account.signOut()
        resetLocalState()
        status = .notSignedIn
    }
}

// MARK: - The menu's status line

public enum StatusText {
    public static func line(_ status: SyncStatus, zone: TimeZone = .current) -> String {
        switch status {
        case .starting: return "Starting…"
        case .syncing: return "Syncing…"
        case .upToDate(let at, let meetings):
            return "Up to date, \(clock(at, zone)), \(meetings) \(meetings == 1 ? "meeting" : "meetings")"
        case .notSignedIn: return "Not signed in"
        case .signInAgain: return "Sign in again"
        case .offline: return "Offline: syncs when the network is back"
        case .noAccess: return "No calendar access"
        case .noCalendar: return "Pick a calendar"
        case .refused: return "Upload refused: update Thread Calendar"
        case .otherUploader: return "Another Mac is uploading meetings"
        case .retrying(let at, let code): return "Sync failed (\(code)), retrying at \(clock(at, zone))"
        case .failed(let code): return "Failed (\(code))"
        }
    }

    static func clock(_ date: Date, _ zone: TimeZone) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_GB")
        formatter.timeZone = zone
        formatter.dateFormat = "HH:mm"
        return formatter.string(from: date)
    }
}
