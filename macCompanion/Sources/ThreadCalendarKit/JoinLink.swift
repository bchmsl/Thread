import Foundation

public enum JoinService: String, Sendable, CaseIterable {
    case teams, zoom, meet, webex

    public var label: String {
        switch self {
        case .teams: return "Teams"
        case .zoom: return "Zoom"
        case .meet: return "Google Meet"
        case .webex: return "Webex"
        }
    }
}

public struct JoinLink: Equatable, Sendable {
    public enum Source: String, Sendable { case url, location, notes }

    public let url: String
    public let service: JoinService
    public let unwrappedFromSafeLinks: Bool
    public let source: Source
}

/// calendar-plan.md section 4, "Join link and location". The allowlist must match `validJoinUrl` in
/// firebase/firestore.rules and `joinServiceOf` in the web domain (tests check both against the rules file).
public enum JoinLinkFinder {
    public static let maxLength = 2048

    /// The regex `validJoinUrl` uses in firebase/firestore.rules.
    public static let rulesPattern =
        "^https://(teams[.]microsoft[.]com|teams[.]live[.]com|([a-z0-9-]+[.])*zoom[.]us|meet[.]google[.]com|([a-z0-9-]+[.])+webex[.]com)([/?#].*)?$"

    public static func service(forHost rawHost: String) -> JoinService? {
        let host = rawHost.lowercased()
        if host == "teams.microsoft.com" || host == "teams.live.com" { return .teams }
        if host == "zoom.us" || host.hasSuffix(".zoom.us") { return .zoom }
        if host == "meet.google.com" { return .meet }
        if host.hasSuffix(".webex.com") { return .webex }
        return nil
    }

    static func isSafeLinksHost(_ host: String) -> Bool {
        host.lowercased().hasSuffix(".safelinks.protection.outlook.com")
    }

    static func hostIsPlain(_ host: String) -> Bool {
        host.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == ".") }
    }

    /// Returns the normalized link when the candidate is an allowlisted https link, after an optional Safe Links unwrap.
    public static func accept(_ candidate: URL) -> (url: String, service: JoinService, unwrapped: Bool)? {
        guard var components = URLComponents(url: candidate, resolvingAgainstBaseURL: false) else { return nil }
        var unwrapped = false
        if components.scheme?.lowercased() == "https", let host = components.host, isSafeLinksHost(host) {
            guard
                let inner = components.queryItems?.first(where: { $0.name == "url" })?.value,
                let innerUrl = URL(string: inner),
                let innerComponents = URLComponents(url: innerUrl, resolvingAgainstBaseURL: false)
            else { return nil }
            components = innerComponents
            unwrapped = true
        }
        guard
            components.scheme?.lowercased() == "https",
            let host = components.host, hostIsPlain(host),
            let service = service(forHost: host),
            components.user == nil, components.password == nil, components.port == nil
        else { return nil }
        components.scheme = "https"
        components.host = host.lowercased()
        guard let text = components.string, text.count <= maxLength else { return nil }
        guard text.range(of: rulesPattern, options: .regularExpression) != nil else { return nil }
        return (text, service, unwrapped)
    }

    public static func links(in text: String) -> [URL] {
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else { return [] }
        let range = NSRange(text.startIndex..., in: text)
        return detector.matches(in: text, options: [], range: range).compactMap(\.url)
    }

    /// Order: the event URL, then the location, then the notes. The first allowlisted link wins.
    public static func find(url: URL?, location: String?, notes: String?) -> JoinLink? {
        var candidates: [(URL, JoinLink.Source)] = []
        if let url { candidates.append((url, .url)) }
        if let location { candidates += links(in: location).map { ($0, .location) } }
        if let notes { candidates += links(in: notes).map { ($0, .notes) } }
        for (candidate, source) in candidates {
            if let accepted = accept(candidate) {
                return JoinLink(url: accepted.url, service: accepted.service, unwrappedFromSafeLinks: accepted.unwrapped, source: source)
            }
        }
        return nil
    }
}
