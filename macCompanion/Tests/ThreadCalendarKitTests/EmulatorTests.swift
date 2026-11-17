import Foundation
import Testing
@testable import ThreadCalendarKit

// Runs against the local Firebase emulators (project demo-thread) with the committed firestore.rules.
// Off unless THREAD_CALENDAR_EMULATOR_TESTS=1, which `firebase/emulators.sh exec` runs set. All data is SYNTHETIC.
//
//   THREAD_CALENDAR_EMULATOR_TESTS=1 firebase/emulators.sh exec "swift test --package-path macCompanion ..."

let emulatorTestsOn = ProcessInfo.processInfo.environment["THREAD_CALENDAR_EMULATOR_TESTS"] == "1"

enum Emulator {
    static let config = AppConfig.emulator
    static let http = HTTPClient(networkGuard: config.networkGuard)
    static let auth = FirebaseAuthREST(http: http, endpoints: config.authEndpoints)
    static let run = UUID().uuidString.prefix(8).lowercased()

    /// A SYNTHETIC Google subject that is new on every test run.
    static func sub(_ name: String) -> String { "SYNTHETIC-\(run)-\(name)" }

    static func signIn(_ name: String) async throws -> FirebaseSession {
        try await auth.signInWithIdp(googleIdToken: FirebaseAuthREST.emulatorGoogleIdToken(sub: sub(name), email: "\(name).\(run)@example.test"))
    }

    static func firestore() throws -> FirestoreClient {
        try FirestoreClient(endpoint: config.firestoreEndpoint(), http: http)
    }
}

/// The four Auth REST cases from spike U9, now on the shipped client.
@Suite(.enabled(if: emulatorTestsOn)) struct EmulatorAuthTests {
    @Test func signInWithIdpReturnsAFirebaseSession() async throws {
        let session = try await Emulator.signIn("owner")
        #expect(!session.idToken.isEmpty)
        #expect(!session.refreshToken.isEmpty)
        #expect(!session.uid.isEmpty)
        #expect(session.expiresAt > Date())
    }

    @Test func refreshTokenKeepsTheUid() async throws {
        let first = try await Emulator.signIn("refresh")
        let renewed = try await Emulator.auth.refresh(refreshToken: first.refreshToken, email: first.email)
        #expect(renewed.uid == first.uid)
        #expect(!renewed.idToken.isEmpty)
    }

    @Test func anotherGoogleAccountIsAnotherUser() async throws {
        let owner = try await Emulator.signIn("first")
        let other = try await Emulator.signIn("second")
        #expect(owner.uid != other.uid)
    }

    @Test func sameGoogleAccountIsTheSameUser() async throws {
        let once = try await Emulator.signIn("same")
        let again = try await Emulator.signIn("same")
        #expect(once.uid == again.uid)
    }
}

/// The upload pass against the real rules: what the engine writes must be accepted, and nothing else may be.
@Suite(.enabled(if: emulatorTestsOn), .serialized) @MainActor struct EmulatorEngineTests {
    @MainActor final class Rig {
        let vault = MemoryVault()
        let account: AccountSession
        let calendar = FakeCalendar(SyntheticEvents.make(now: Date(), zone: tbilisi))
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("tc-emu-" + UUID().uuidString)
        let firestore: FirestoreClient

        init(_ name: String) throws {
            account = AccountSession(auth: Emulator.auth, vault: vault)
            firestore = try Emulator.firestore()
            _ = name
        }

        func engine(uploaderId: String) -> SyncEngine {
            let state = MemoryEngineState()
            state.uploaderId = uploaderId
            return SyncEngine(
                account: account, firestore: firestore, calendar: calendar, index: IndexStore(directory: folder.appendingPathComponent(uploaderId)),
                state: state, log: DiagnosticsLog(directory: folder.appendingPathComponent(uploaderId)), appVersion: "1.0"
            )
        }

        func meetingIds() async throws -> [String] {
            let token = try await account.validSession().idToken
            let (result, ids) = try await firestore.listIds(MeetingEncoding.meetingsPath(uid: account.uid!), idToken: token)
            #expect(result.status == 200)
            return ids
        }

        func source() async throws -> HTTPResult {
            let token = try await account.validSession().idToken
            return try await firestore.get(MeetingEncoding.sourcePath(uid: account.uid!), idToken: token)
        }

        deinit { try? FileManager.default.removeItem(at: folder) }
    }

    @Test func passesAreAcceptedByTheRules() async throws {
        let rig = try Rig("engine")
        try await rig.account.signIn(googleIdToken: FirebaseAuthREST.emulatorGoogleIdToken(sub: Emulator.sub("engine"), email: "engine@example.test"))
        let engine = rig.engine(uploaderId: "mac-aaaaaaaaaaaa")
        engine.signedIn()

        let first = try #require(await engine.run(.signIn))
        #expect(first.outcome == "written")
        #expect(first.upserts == 5)
        #expect(try await rig.meetingIds().count == 5)
        #expect(try await rig.source().status == 200)

        let quiet = try #require(await engine.run(.schedule))
        #expect(quiet.outcome == "quiet")
        #expect(quiet.writes == 0)

        rig.calendar.list[0].title = "SYNTHETIC Standup (renamed)"
        let changed = try #require(await engine.run(.calendarChanged))
        #expect(changed.outcome == "written")
        #expect(changed.upserts == 1)

        rig.calendar.access = .denied
        let noAccess = try #require(await engine.run(.schedule))
        #expect(noAccess.outcome == "written")
        #expect(noAccess.writes == 1)
        rig.calendar.access = .granted

        // A second Mac on the same account is held back until the owner approves the takeover.
        let other = rig.engine(uploaderId: "mac-bbbbbbbbbbbb")
        let blocked = try #require(await other.run(.launch))
        #expect(blocked.outcome == "otherUploader")
        other.approveTakeover()
        let taken = try #require(await other.run(.takeover))
        #expect(taken.outcome == "written")

        try await other.stopSyncing()
        #expect(!rig.account.isSignedIn)
        try await rig.account.signIn(googleIdToken: FirebaseAuthREST.emulatorGoogleIdToken(sub: Emulator.sub("engine"), email: "engine@example.test"))
        #expect(try await rig.meetingIds().isEmpty)
        #expect(try await rig.source().status == 404)
    }

    @Test func anotherUsersMeetingsAreRefused() async throws {
        let owner = try await Emulator.signIn("owner-paths")
        let stranger = try await Emulator.signIn("stranger-paths")
        let firestore = try Emulator.firestore()
        let name = firestore.name(MeetingEncoding.meetingPath(uid: owner.uid, id: Ids.hex32("SYNTHETIC-x")))
        let write = Writes.upsert(name: name, fields: ["title": FSValue.string("SYNTHETIC")])
        let result = try await firestore.commit([write], idToken: stranger.idToken)
        #expect(result.status == 403)
        #expect(result.errorCode == "PERMISSION_DENIED")
    }

    @Test func aMeetingOutsideTheWindowIsRefused() async throws {
        let owner = try await Emulator.signIn("window")
        let firestore = try Emulator.firestore()
        let far = Date().addingTimeInterval(40 * 86_400)
        let meeting = MeetingDoc(
            id: Ids.hex32("SYNTHETIC-far"), seriesKey: Ids.hex32("SYNTHETIC-far"), title: "SYNTHETIC far",
            time: .timed(start: far, end: far.addingTimeInterval(3600)), response: .none
        )
        let write = Writes.upsert(
            name: firestore.name(MeetingEncoding.meetingPath(uid: owner.uid, id: meeting.id)), fields: MeetingEncoding.fields(meeting)
        )
        let result = try await firestore.commit([write], idToken: owner.idToken)
        #expect(result.status == 403)
    }
}
