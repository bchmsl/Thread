import Foundation
import Testing
@testable import ThreadCalendarKit

// Ported from the spike's SpikeKitTests (calendar-plan.md section 9). All data here is SYNTHETIC.
// No test touches the Keychain, EventKit or any real host.

let tbilisi = TimeZone(identifier: "Asia/Tbilisi")!

func at(_ text: String) -> Date {
    ISO8601DateFormatter().date(from: text)!
}

/// 2026-11-17 12:00 in Tbilisi (UTC+4, no DST).
let fixedNow = at("2026-11-17T12:00:00+04:00")
let window = SyncWindow.make(now: fixedNow, zone: tbilisi)

func event(
    _ externalId: String? = "SYNTHETIC-ext-1",
    title: String? = "SYNTHETIC meeting",
    start: String = "2026-11-17T10:00:00+04:00",
    end: String = "2026-11-17T11:00:00+04:00",
    allDay: Bool = false,
    occurrence: String? = nil,
    recurring: Bool = false,
    detached: Bool = false,
    status: SourceStatus = .confirmed,
    response: MeetingResponse = .accepted,
    location: String? = nil,
    url: URL? = nil,
    notes: String? = nil
) -> SourceEvent {
    SourceEvent(
        externalId: externalId, localId: "SYNTHETIC-local-1", title: title, isAllDay: allDay,
        start: at(start), end: at(end), occurrenceDate: occurrence.map(at), isRecurring: recurring, isDetached: detached,
        status: status, myResponse: response, location: location, url: url, notes: notes
    )
}

func uploaded(_ outcome: MapOutcome) -> MeetingDoc? {
    if case .upload(let doc) = outcome { return doc }
    return nil
}

// MARK: - Join links

@Suite struct JoinLinkTests {
    @Test func acceptsAllowlistedServices() {
        #expect(JoinLinkFinder.accept(URL(string: "https://teams.microsoft.com/l/meetup-join/SYNTHETIC")!)?.service == .teams)
        #expect(JoinLinkFinder.accept(URL(string: "https://us02web.zoom.us/j/0000000000")!)?.service == .zoom)
        #expect(JoinLinkFinder.accept(URL(string: "https://meet.google.com/aaa-bbbb-ccc")!)?.service == .meet)
        #expect(JoinLinkFinder.accept(URL(string: "https://acme.webex.com/meet/SYNTHETIC")!)?.service == .webex)
    }

    @Test func lowercasesTheHost() {
        #expect(JoinLinkFinder.accept(URL(string: "https://TEAMS.Microsoft.com/l/x")!)?.url == "https://teams.microsoft.com/l/x")
    }

    @Test(arguments: [
        "http://teams.microsoft.com/l/x",
        "https://teams.microsoft.com.evil.example/x",
        "https://teams.microsoft.com@evil.example/x",
        "https://user:pass@teams.microsoft.com/l/x",
        "https://teams.microsoft.com:8443/l/x",
        "https://evil.example.com/teams.microsoft.com",
        "https://notzoom.us/j/1",
        "https://webex.com/meet/x",
        "https://sub.meet.google.com/x",
    ])
    func rejects(_ text: String) {
        #expect(JoinLinkFinder.accept(URL(string: text)!) == nil)
    }

    @Test func rejectsOver2048() {
        let long = "https://teams.microsoft.com/" + String(repeating: "a", count: 2030)
        #expect(JoinLinkFinder.accept(URL(string: long)!) == nil)
    }

    @Test func unwrapsSafeLinks() throws {
        let inner = "https://teams.microsoft.com/l/meetup-join/SYNTHETIC"
        var wrapper = URLComponents(string: "https://eur01.safelinks.protection.outlook.com/")!
        wrapper.queryItems = [URLQueryItem(name: "url", value: inner), URLQueryItem(name: "data", value: "SYNTHETIC")]
        let accepted = try #require(JoinLinkFinder.accept(wrapper.url!))
        #expect(accepted.url == inner)
        #expect(accepted.unwrapped)
    }

    @Test func safeLinksToAnotherHostIsRejected() {
        var wrapper = URLComponents(string: "https://eur01.safelinks.protection.outlook.com/")!
        wrapper.queryItems = [URLQueryItem(name: "url", value: "https://evil.example/x")]
        #expect(JoinLinkFinder.accept(wrapper.url!) == nil)
    }

    @Test func orderIsUrlThenLocationThenNotes() {
        let found = JoinLinkFinder.find(
            url: URL(string: "https://example.com/agenda"),
            location: "Room 5 https://zoom.us/j/1111111111",
            notes: "Join https://teams.microsoft.com/l/meetup-join/SYNTHETIC"
        )
        #expect(found?.service == .zoom)
        #expect(found?.source == .location)
    }

    /// The Swift allowlist and the rules regex must agree host by host.
    @Test(arguments: [
        "teams.microsoft.com", "teams.live.com", "zoom.us", "us02web.zoom.us", "meet.google.com", "acme.webex.com",
        "webex.com", "notzoom.us", "zoom.us.evil.example", "meet.google.com.evil.example", "sub.meet.google.com", "evil.example",
    ])
    func rulesParity(_ host: String) {
        let swift = JoinLinkFinder.service(forHost: host) != nil
        let rules = ("https://" + host + "/x").range(of: JoinLinkFinder.rulesPattern, options: .regularExpression) != nil
        #expect(swift == rules)
    }
}

// MARK: - Ids

@Suite struct IdTests {
    @Test func occurrencesShareSeriesKeyButNotDocId() {
        let first = event(start: "2026-11-17T10:00:00+04:00", occurrence: "2026-11-17T10:00:00+04:00", recurring: true)
        let second = event(start: "2026-11-18T10:00:00+04:00", end: "2026-11-18T11:00:00+04:00", occurrence: "2026-11-18T10:00:00+04:00", recurring: true)
        #expect(Ids.seriesKey(for: first) == Ids.seriesKey(for: second))
        #expect(Ids.docId(for: first) != Ids.docId(for: second))
    }

    @Test func movedOccurrenceKeepsItsDocId() {
        let original = event(start: "2026-11-18T10:00:00+04:00", end: "2026-11-18T11:00:00+04:00", occurrence: "2026-11-18T10:00:00+04:00", recurring: true)
        let moved = event(start: "2026-11-18T15:00:00+04:00", end: "2026-11-18T16:00:00+04:00", occurrence: "2026-11-18T10:00:00+04:00", recurring: true, detached: true)
        #expect(Ids.docId(for: original) == Ids.docId(for: moved))
    }

    /// Spike U7 on a CalDAV calendar: EventKit gives a moved occurrence `<series id>/RID=<digits>`.
    @Test func movedOccurrenceWithOccurrenceSuffixKeepsSeriesKeyAndDocId() {
        let original = event("SYNTHETIC-series-1", start: "2026-11-18T10:00:00+04:00", end: "2026-11-18T11:00:00+04:00", occurrence: "2026-11-18T10:00:00+04:00", recurring: true)
        let moved = event("SYNTHETIC-series-1/RID=816328800", start: "2026-11-18T11:00:00+04:00", end: "2026-11-18T12:00:00+04:00", occurrence: "2026-11-18T10:00:00+04:00", recurring: true, detached: true)
        #expect(Ids.seriesKey(for: moved) == Ids.seriesKey(for: original))
        #expect(Ids.docId(for: moved) == Ids.docId(for: original))
    }

    @Test func occurrenceSuffixIsOnlyStrippedWhenItEndsARecurringId() {
        #expect(Ids.seriesExternalId("SYNTHETIC-series-1/RID=816328800") == "SYNTHETIC-series-1")
        #expect(Ids.seriesExternalId("/RID=816328800") == "/RID=816328800")
        #expect(Ids.seriesExternalId("SYNTHETIC/RID=8163x") == "SYNTHETIC/RID=8163x")
        #expect(Ids.seriesExternalId("SYNTHETIC/RID=1/more") == "SYNTHETIC/RID=1/more")
        let single = event("SYNTHETIC-single/RID=1")
        #expect(Ids.docId(for: single) == Ids.hex32("SYNTHETIC-single/RID=1"))
    }

    @Test func movedSingleEventKeepsItsDocId() {
        #expect(Ids.docId(for: event()) == Ids.docId(for: event(start: "2026-11-20T09:00:00+04:00", end: "2026-11-20T10:00:00+04:00")))
    }

    @Test func missingExternalIdFallsBackToLocalId() {
        let id = Ids.docId(for: event(nil))
        #expect(Ids.isHex32(id))
        #expect(id == Ids.hex32("local:SYNTHETIC-local-1"))
    }

    @Test func idsAreHex32() {
        #expect(Ids.isHex32(Ids.docId(for: event())))
        #expect(!Ids.isHex32("ABC"))
    }
}

// MARK: - Mapper

@Suite struct MapperTests {
    @Test func windowIsYesterdayToTodayPlus14() {
        #expect(window.fromDay == "2026-11-16")
        #expect(window.toDay == "2026-12-01")
    }

    @Test func georgianTitleCapsAt300() throws {
        let doc = try #require(uploaded(Mapper.map(event(title: String(repeating: "ა", count: 320)), window: window, allDayZone: tbilisi)))
        #expect(doc.title.utf16.count == 300)
        #expect(doc.title.count == 300)
    }

    @Test func emojiTitleNeverSplitsACharacter() throws {
        let doc = try #require(uploaded(Mapper.map(event(title: String(repeating: "😀", count: 200)), window: window, allDayZone: tbilisi)))
        #expect(doc.title.utf16.count == 300)
        #expect(doc.title == String(repeating: "😀", count: 150))
    }

    @Test func lineBreaksBecomeSpaces() throws {
        let doc = try #require(uploaded(Mapper.map(event(title: "  Line one\nLine two  "), window: window, allDayZone: tbilisi)))
        #expect(doc.title == "Line one Line two")
    }

    @Test func locationLosesItsLinks() throws {
        let doc = try #require(uploaded(Mapper.map(
            event(location: "Room 5; https://zoom.us/j/1111111111?pwd=SYNTHETIC"), window: window, allDayZone: tbilisi
        )))
        #expect(doc.location == "Room 5")
        #expect(doc.joinService == .zoom)
        #expect(doc.joinUrl?.contains("pwd=SYNTHETIC") == true)
    }

    @Test func linkOnlyLocationBecomesNil() throws {
        let doc = try #require(uploaded(Mapper.map(event(location: "https://teams.microsoft.com/l/x"), window: window, allDayZone: tbilisi)))
        #expect(doc.location == nil)
        #expect(doc.joinService == .teams)
    }

    @Test func canceledAndDeclinedAreHidden() {
        #expect(Mapper.map(event(status: .canceled), window: window, allDayZone: tbilisi) == .hidden(.canceled))
        #expect(Mapper.map(event(response: .declined), window: window, allDayZone: tbilisi) == .hidden(.declined))
    }

    @Test func badTimesAndTooLongAreSkipped() {
        #expect(Mapper.map(event(start: "2026-11-17T11:00:00+04:00", end: "2026-11-17T10:00:00+04:00"), window: window, allDayZone: tbilisi) == .skipped(.badTimes))
        #expect(Mapper.map(event(start: "2026-11-17T10:00:00+04:00", end: "2026-12-02T10:00:00+04:00"), window: window, allDayZone: tbilisi) == .skipped(.tooLong))
    }

    @Test func windowEdges() {
        #expect(Mapper.map(event(start: "2026-11-15T22:00:00+04:00", end: "2026-11-15T23:00:00+04:00"), window: window, allDayZone: tbilisi) == .skipped(.outsideWindow))
        #expect(uploaded(Mapper.map(event(start: "2026-11-15T23:30:00+04:00", end: "2026-11-16T00:30:00+04:00"), window: window, allDayZone: tbilisi)) != nil)
        #expect(uploaded(Mapper.map(event(start: "2026-12-01T23:00:00+04:00", end: "2026-12-01T23:30:00+04:00"), window: window, allDayZone: tbilisi)) != nil)
        #expect(Mapper.map(event(start: "2026-12-02T00:00:00+04:00", end: "2026-12-02T01:00:00+04:00"), window: window, allDayZone: tbilisi) == .skipped(.outsideWindow))
    }

    @Test func allDayUsesDayStrings() throws {
        let midnightEnd = try #require(uploaded(Mapper.map(event(start: "2026-11-18T00:00:00+04:00", end: "2026-11-19T00:00:00+04:00", allDay: true), window: window, allDayZone: tbilisi)))
        #expect(midnightEnd.time == .allDay(firstDay: "2026-11-18", lastDayExclusive: "2026-11-19"))
        let secondEnd = try #require(uploaded(Mapper.map(event(start: "2026-11-18T00:00:00+04:00", end: "2026-11-18T23:59:59+04:00", allDay: true), window: window, allDayZone: tbilisi)))
        #expect(secondEnd.time == .allDay(firstDay: "2026-11-18", lastDayExclusive: "2026-11-19"))
        let threeDays = try #require(uploaded(Mapper.map(event(start: "2026-11-30T00:00:00+04:00", end: "2026-12-03T00:00:00+04:00", allDay: true), window: window, allDayZone: tbilisi)))
        #expect(threeDays.time == .allDay(firstDay: "2026-11-30", lastDayExclusive: "2026-12-03"))
        #expect(DayMath.days(from: "2026-11-30", to: "2026-12-03") == 3)
    }

    @Test func mapAllCountsWithoutTitles() {
        let events = [
            event(),
            event(),
            event("SYNTHETIC-ext-2", status: .canceled),
            event("SYNTHETIC-ext-3", response: .declined),
            event(nil, response: .tentative, location: "Room 1"),
        ]
        let summary = Mapper.mapAll(events, window: window, allDayZone: tbilisi)
        #expect(summary.read == 5)
        #expect(summary.docs.count == 2)
        #expect(summary.idCollisions == 1)
        #expect(summary.hidden[.canceled] == 1)
        #expect(summary.hidden[.declined] == 1)
        #expect(summary.missingExternalId == 1)
        #expect(summary.tentative == 1)
        #expect(summary.withLocation == 1)
    }

    @Test func detachedCountersFlagSuffixAndLoneSeries() {
        let events = [
            event("SYNTHETIC-series-1", start: "2026-11-18T10:00:00+04:00", end: "2026-11-18T11:00:00+04:00", occurrence: "2026-11-18T10:00:00+04:00", recurring: true),
            event("SYNTHETIC-series-1/RID=816415200", start: "2026-11-19T11:00:00+04:00", end: "2026-11-19T12:00:00+04:00", occurrence: "2026-11-19T10:00:00+04:00", recurring: true, detached: true),
            event("SYNTHETIC-other-format", start: "2026-11-20T11:00:00+04:00", end: "2026-11-20T12:00:00+04:00", occurrence: "2026-11-20T10:00:00+04:00", recurring: true, detached: true),
        ]
        let summary = Mapper.mapAll(events, window: window, allDayZone: tbilisi)
        #expect(summary.docs.count == 3)
        #expect(summary.detached == 2)
        #expect(summary.detachedWithSuffix == 1)
        #expect(summary.detachedLoneSeries == 1)
        #expect(summary.idCollisions == 0)
    }
}

// MARK: - Differ and encoding

@Suite struct UploadShapeTests {
    private func doc(_ id: String, title: String = "SYNTHETIC") -> MeetingDoc {
        MeetingDoc(id: Ids.hex32(id), seriesKey: Ids.hex32("series"), title: title, time: .timed(start: fixedNow, end: fixedNow.addingTimeInterval(3600)), response: .accepted)
    }

    @Test func differFindsUpsertsDeletesAndUnchanged() {
        let a = doc("a"), b = doc("b"), c = doc("c")
        let index = Differ.index(for: [a, b, c])
        let plan = Differ.plan(docs: [a, doc("b", title: "SYNTHETIC changed"), doc("d")], index: index)
        #expect(plan.unchanged == 1)
        #expect(plan.upserts.map(\.id).sorted() == [b.id, Ids.hex32("d")].sorted())
        #expect(plan.deletes == [c.id])
    }

    @Test func timedEncodingHasNoDays() {
        let fields = MeetingEncoding.fields(doc("a"))
        #expect(fields["start"] != nil && fields["end"] != nil)
        #expect(fields["firstDay"] == nil && fields["lastDayExclusive"] == nil)
        #expect(fields["joinUrl"] == nil && fields["location"] == nil)
        #expect(fields["updatedAt"] == nil)
    }

    @Test func allDayEncodingHasNoInstants() {
        var allDay = doc("a")
        allDay.time = .allDay(firstDay: "2026-11-18", lastDayExclusive: "2026-11-19")
        let fields = MeetingEncoding.fields(allDay)
        #expect(fields["start"] == nil && fields["end"] == nil)
        #expect((fields["firstDay"] as? [String: Any])?["stringValue"] as? String == "2026-11-18")
    }

    @Test func chunksOf250() {
        #expect(Writes.chunks(Array(0 ..< 501)).map(\.count) == [250, 250, 1])
    }
}

// MARK: - Network guards

@Suite struct GuardTests {
    @Test(arguments: [
        "http://127.0.0.1:8085/v1/projects/demo-thread/databases/(default)/documents:commit",
        "http://localhost:9099/identitytoolkit.googleapis.com/v1/accounts:signInWithIdp",
    ])
    func emulatorAllows(_ text: String) throws {
        try NetworkGuard(mode: .emulator).check(URL(string: text)!)
    }

    @Test(arguments: [
        "https://firestore.googleapis.com/v1/projects/x/databases/(default)/documents",
        "https://oauth2.googleapis.com/token",
        "https://127.0.0.1:8085/v1/x",
        "http://example.com/",
    ])
    func emulatorBlocks(_ text: String) {
        #expect(throws: CalendarSyncError.self) { try NetworkGuard(mode: .emulator).check(URL(string: text)!) }
    }

    @Test(arguments: [
        "https://oauth2.googleapis.com/token",
        "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp",
        "https://securetoken.googleapis.com/v1/token",
        "https://firestore.googleapis.com/v1/projects/x/databases/(default)/documents:commit",
    ])
    func cloudAllows(_ text: String) throws {
        try NetworkGuard(mode: .cloud).check(URL(string: text)!)
    }

    @Test(arguments: [
        "http://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp",
        "http://127.0.0.1:8085/v1/x",
        "https://example.com/",
        "https://www.googleapis.com/",
        "https://firestore.googleapis.com.evil.example/v1/x",
    ])
    func cloudBlocks(_ text: String) {
        #expect(throws: CalendarSyncError.self) { try NetworkGuard(mode: .cloud).check(URL(string: text)!) }
    }

    @Test func emulatorEndpointRefusesTheRealProject() {
        #expect(throws: CalendarSyncError.notEmulator) { try FirestoreEndpoint.emulator(host: "firestore.googleapis.com", port: 443, projectId: "demo-x") }
        #expect(throws: CalendarSyncError.notDemoProject) { try FirestoreEndpoint.emulator(port: 8085, projectId: "thread-real") }
        #expect((try? FirestoreEndpoint.emulator(port: 8085, projectId: "demo-thread")) != nil)
    }

    @Test func sessionDescriptionIsRedacted() {
        let session = FirebaseSession(idToken: "SYNTHETIC-id", refreshToken: "SYNTHETIC-refresh", uid: "SYNTHETIC-uid", email: "synthetic@example.com", expiresAt: fixedNow)
        let text = "\(session) \(String(reflecting: session))"
        #expect(!text.contains("SYNTHETIC"))
        #expect(!text.contains("synthetic@"))
    }

    @Test func logRejectsFreeText() {
        #expect(LogValue.code("calendarRead_ok").json as? String == "calendarRead_ok")
        #expect(LogValue.code("A real meeting title").json as? String == "<rejected>")
        #expect(LogValue.code("https://teams.microsoft.com/l/x").json as? String == "<rejected>")
    }
}

// MARK: - OAuth pieces

@Suite struct OAuthTests {
    /// Expected value computed independently:
    /// printf %s <verifier> | openssl dgst -sha256 -binary | base64 | tr '+/' '-_' | tr -d '='
    @Test func pkceMatchesOpenSSL() {
        #expect(PKCE(verifier: "dBjftJeZ4CVP-mJ92EGJ5pYKuP3t8x89G5JS8qCdlGs").challenge == "6xVppbj3jnUEb0GpUTmpFjyEInEc2237BvbpyVIzEog")
        #expect(PKCE.make().verifier.count == 43)
    }

    @Test func authorizationUrlCarriesPkceAndState() throws {
        let pkce = PKCE(verifier: "SYNTHETICverifierSYNTHETICverifierSYNTHETICv")
        let url = GoogleOAuth.authorizationURL(
            client: GoogleDesktopClient(clientId: "SYNTHETIC.apps.googleusercontent.com", clientSecret: "SYNTHETIC"),
            redirectUri: "http://127.0.0.1:50000", pkce: pkce, state: "SYNTHETICstate"
        )
        let items = Dictionary(uniqueKeysWithValues: (URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        #expect(items["code_challenge"] == pkce.challenge)
        #expect(items["code_challenge_method"] == "S256")
        #expect(items["state"] == "SYNTHETICstate")
        #expect(items["scope"] == "openid email")
        #expect(items["client_secret"] == nil)
    }

    @Test func loopbackReceivesTheCallback() async throws {
        let receiver = LoopbackReceiver()
        defer { receiver.stop() }
        let port = try await receiver.start()
        let session = URLSession(configuration: .ephemeral)
        let (_, response) = try await session.data(from: URL(string: "http://127.0.0.1:\(port)/?code=SYNTHETICcode&state=SYNTHETICstate")!)
        #expect((response as? HTTPURLResponse)?.statusCode == 200)
        let params = try await receiver.waitForCallback(timeout: 5)
        #expect(params["code"] == "SYNTHETICcode")
        #expect(params["state"] == "SYNTHETICstate")
    }

    @Test func loopbackTimesOut() async throws {
        let receiver = LoopbackReceiver()
        defer { receiver.stop() }
        _ = try await receiver.start()
        await #expect(throws: CalendarSyncError.timeout) { try await receiver.waitForCallback(timeout: 0.3) }
    }
}
