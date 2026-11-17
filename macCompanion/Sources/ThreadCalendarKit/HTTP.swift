import Foundation

/// Which hosts the app may call, checked before every request.
/// Emulator mode: the local emulators over http only. Cloud mode: the four Google hosts sign-in and Firestore need.
public struct NetworkGuard: Sendable {
    public static let cloudHosts: Set<String> = [
        "oauth2.googleapis.com",
        "identitytoolkit.googleapis.com",
        "securetoken.googleapis.com",
        "firestore.googleapis.com",
    ]

    public let mode: BackendMode

    public init(mode: BackendMode) {
        self.mode = mode
    }

    public func check(_ url: URL) throws {
        guard let host = url.host?.lowercased() else { throw CalendarSyncError.blockedHost("none") }
        let scheme = url.scheme?.lowercased()
        switch mode {
        case .emulator:
            guard scheme == "http", host == "127.0.0.1" || host == "localhost" else { throw CalendarSyncError.blockedHost(host) }
        case .cloud:
            guard scheme == "https", Self.cloudHosts.contains(host) else { throw CalendarSyncError.blockedHost(host) }
        }
    }
}

public struct HTTPResult: Sendable {
    public let status: Int
    public let body: Data

    public init(status: Int, body: Data) {
        self.status = status
        self.body = body
    }

    public var json: [String: Any]? {
        (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
    }

    /// Google API error status ("PERMISSION_DENIED") or Identity Toolkit message code ("INVALID_IDP_RESPONSE").
    public var errorCode: String {
        guard let error = json?["error"] as? [String: Any] else { return status == 0 ? "NO_RESPONSE" : "HTTP_\(status)" }
        if let status = error["status"] as? String, !status.isEmpty, status != "INVALID_ARGUMENT" { return status }
        if let message = error["message"] as? String {
            let code = message.components(separatedBy: " ").first ?? message
            if code.range(of: "^[A-Z_]{3,64}$", options: .regularExpression) != nil { return code }
        }
        if let status = error["status"] as? String { return status }
        return "HTTP_\(status)"
    }

    /// API key problems (`API_KEY_HTTP_REFERRER_BLOCKED` and friends) come in `error.details[].reason`.
    public var errorReason: String? {
        guard let error = json?["error"] as? [String: Any], let details = error["details"] as? [[String: Any]] else { return nil }
        return details.compactMap { $0["reason"] as? String }.first
    }
}

/// Every request goes through here. The session is ephemeral: no cookies, cache or credentials persist.
public struct HTTPClient: Sendable {
    public let networkGuard: NetworkGuard
    let session: URLSession

    public init(networkGuard: NetworkGuard, session: URLSession = URLSession(configuration: .ephemeral)) {
        self.networkGuard = networkGuard
        self.session = session
    }

    static let offlineCodes: Set<URLError.Code> = [
        .notConnectedToInternet, .networkConnectionLost, .cannotConnectToHost, .cannotFindHost,
        .dnsLookupFailed, .timedOut, .internationalRoamingOff, .dataNotAllowed,
    ]

    public func send(_ request: URLRequest) async throws -> HTTPResult {
        guard let url = request.url else { throw CalendarSyncError.blockedHost("none") }
        try networkGuard.check(url)
        do {
            let (data, response) = try await session.data(for: request)
            return HTTPResult(status: (response as? HTTPURLResponse)?.statusCode ?? 0, body: data)
        } catch let error as URLError where Self.offlineCodes.contains(error.code) {
            throw CalendarSyncError.offline
        }
    }

    public func json(_ method: String, _ url: URL, body: Any? = nil, bearer: String? = nil) async throws -> HTTPResult {
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 30
        if let body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        if let bearer { request.setValue("Bearer " + bearer, forHTTPHeaderField: "Authorization") }
        return try await send(request)
    }

    public func form(_ url: URL, _ fields: [(String, String)]) async throws -> HTTPResult {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.httpBody = Data(FormEncoding.encode(fields).utf8)
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        return try await send(request)
    }
}

public enum FormEncoding {
    static let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    public static func escape(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? ""
    }

    public static func encode(_ fields: [(String, String)]) -> String {
        fields.map { escape($0.0) + "=" + escape($0.1) }.joined(separator: "&")
    }
}
