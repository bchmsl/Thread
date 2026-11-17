import CryptoKit
import Foundation
import Network

/// RFC 7636 PKCE with S256.
public struct PKCE: Sendable {
    public let verifier: String
    public let challenge: String

    public init(verifier: String) {
        self.verifier = verifier
        self.challenge = Base64URL.encode(Data(SHA256.hash(data: Data(verifier.utf8))))
    }

    public static func make() -> PKCE {
        PKCE(verifier: Base64URL.encode(RandomBytes.make(32)))
    }
}

public enum Base64URL {
    public static func encode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

public enum RandomBytes {
    public static func make(_ count: Int) -> Data {
        var generator = SystemRandomNumberGenerator()
        return Data((0 ..< count).map { _ in UInt8.random(in: .min ... .max, using: &generator) })
    }
}

/// The Google "Desktop app" OAuth client. Google treats its secret as not confidential; it still stays out of git.
public struct GoogleDesktopClient: Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    public let clientId: String
    let clientSecret: String

    public init(clientId: String, clientSecret: String) {
        self.clientId = clientId
        self.clientSecret = clientSecret
    }

    public var description: String { "GoogleDesktopClient(<redacted>)" }
    public var debugDescription: String { description }
}

/// Google OAuth for installed apps: system browser, loopback redirect, PKCE, scopes `openid email` only.
public enum GoogleOAuth {
    public static func authorizationURL(client: GoogleDesktopClient, redirectUri: String, pkce: PKCE, state: String) -> URL {
        var components = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
        components.queryItems = [
            URLQueryItem(name: "client_id", value: client.clientId),
            URLQueryItem(name: "redirect_uri", value: redirectUri),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: "openid email"),
            URLQueryItem(name: "code_challenge", value: pkce.challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "prompt", value: "select_account"),
        ]
        return components.url!
    }

    /// Returns Google's id token only. The access token in the same response is dropped.
    public static func exchange(code: String, client: GoogleDesktopClient, redirectUri: String, pkce: PKCE, http: HTTPClient) async throws -> String {
        let result = try await http.form(URL(string: "https://oauth2.googleapis.com/token")!, [
            ("code", code),
            ("client_id", client.clientId),
            ("client_secret", client.clientSecret),
            ("redirect_uri", redirectUri),
            ("grant_type", "authorization_code"),
            ("code_verifier", pkce.verifier),
        ])
        guard result.status == 200, let json = result.json else {
            throw CalendarSyncError.oauth(OAuthErrorCode.clean(result.json?["error"] as? String) ?? result.errorCode)
        }
        guard let idToken = json["id_token"] as? String else { throw CalendarSyncError.badResponse("noIdToken") }
        return idToken
    }
}

enum OAuthErrorCode {
    /// OAuth error codes are short identifiers ("access_denied"); anything else is not shown.
    static func clean(_ code: String?) -> String? {
        guard let code, code.range(of: "^[a-z_]{3,40}$", options: .regularExpression) != nil else { return nil }
        return code
    }
}

/// calendar-plan.md section 4, "Sign-in": a one-time loopback listener, the system browser, PKCE, then the code
/// exchange. Returns Google's id token for `signInWithIdp`.
public struct GoogleSignInFlow {
    public static let timeout: TimeInterval = 5 * 60

    let client: GoogleDesktopClient
    let http: HTTPClient

    public init(client: GoogleDesktopClient, http: HTTPClient) {
        self.client = client
        self.http = http
    }

    public func run(openBrowser: (URL) -> Void, timeout: TimeInterval = GoogleSignInFlow.timeout) async throws -> String {
        let receiver = LoopbackReceiver()
        defer { receiver.stop() }
        let port = try await receiver.start()
        let redirectUri = "http://127.0.0.1:\(port)"
        let pkce = PKCE.make()
        let state = Base64URL.encode(RandomBytes.make(16))
        openBrowser(GoogleOAuth.authorizationURL(client: client, redirectUri: redirectUri, pkce: pkce, state: state))
        let params = try await receiver.waitForCallback(timeout: timeout)
        guard params["state"] == state else { throw CalendarSyncError.stateMismatch }
        if let error = params["error"] { throw CalendarSyncError.oauth(OAuthErrorCode.clean(error) ?? "error") }
        guard let code = params["code"], !code.isEmpty else { throw CalendarSyncError.oauth("noCode") }
        return try await GoogleOAuth.exchange(code: code, client: client, redirectUri: redirectUri, pkce: pkce, http: http)
    }
}

/// A one-shot http listener on 127.0.0.1 with a random port, open only during sign-in.
public final class LoopbackReceiver: @unchecked Sendable {
    private let queue = DispatchQueue(label: "threadcalendar.loopback")
    private var listener: NWListener?
    private var waiting: CheckedContinuation<[String: String], Error>?
    private var pending: [String: String]?

    public init() {}

    public func start() async throws -> UInt16 {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = NWEndpoint.hostPort(host: .ipv4(.loopback), port: .any)
        let listener = try NWListener(using: parameters)
        self.listener = listener
        return try await withCheckedThrowingContinuation { continuation in
            let once = ResumeOnce() // handlers run on the serial `queue`
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    if !once.done, let port = listener.port?.rawValue {
                        once.done = true
                        continuation.resume(returning: port)
                    }
                case .failed(let error):
                    if !once.done {
                        once.done = true
                        continuation.resume(throwing: error)
                    }
                default:
                    break
                }
            }
            listener.newConnectionHandler = { [weak self] connection in self?.handle(connection) }
            listener.start(queue: queue)
        }
    }

    public func waitForCallback(timeout: TimeInterval) async throws -> [String: String] {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                if let pending = self.pending {
                    self.pending = nil
                    continuation.resume(returning: pending)
                    return
                }
                self.waiting = continuation
                self.queue.asyncAfter(deadline: .now() + timeout) {
                    if let waiting = self.waiting {
                        self.waiting = nil
                        waiting.resume(throwing: CalendarSyncError.timeout)
                    }
                }
            }
        }
    }

    public func stop() {
        queue.async {
            self.listener?.cancel()
            self.listener = nil
        }
    }

    private func handle(_ connection: NWConnection) {
        connection.start(queue: queue)
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, _, _ in
            guard let self, let data, let text = String(data: data, encoding: .utf8) else {
                connection.cancel()
                return
            }
            let requestLine = text.components(separatedBy: "\r\n").first ?? ""
            let parts = requestLine.split(separator: " ")
            var params: [String: String] = [:]
            if parts.count >= 2, let components = URLComponents(string: "http://127.0.0.1" + parts[1]) {
                for item in components.queryItems ?? [] { params[item.name] = item.value ?? "" }
            }
            let isCallback = params["code"] != nil || params["error"] != nil
            let body = isCallback
                ? "<!doctype html><meta charset=utf-8><title>Thread Calendar</title><p style=\"font:16px -apple-system,sans-serif\">Thread Calendar: sign-in finished. You can close this tab."
                : "Not found"
            let head = "HTTP/1.1 \(isCallback ? "200 OK" : "404 Not Found")\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n"
            connection.send(content: Data((head + body).utf8), completion: .contentProcessed { _ in connection.cancel() })
            if isCallback { self.deliver(params) }
        }
    }

    private func deliver(_ params: [String: String]) {
        if let waiting {
            self.waiting = nil
            waiting.resume(returning: params)
        } else if pending == nil {
            pending = params
        }
    }
}

/// Guards a continuation that the listener state handler could otherwise resume twice.
final class ResumeOnce: @unchecked Sendable {
    var done = false
}

/// What the sign-in error alert says. Codes only; the hints name the console step that fixes the known cases
/// (calendar-plan.md section 9, "P3 first run, only if it fails").
public enum SignInHint {
    public static func text(for code: String) -> String {
        if code.hasPrefix("INVALID_IDP_RESPONSE") {
            return "Add this app's Desktop client ID in Firebase console › Authentication › Sign-in method › Google › "
                + "Safelist client IDs from external projects, then sign in again."
        }
        if code.hasPrefix("API_KEY_") {
            return "The Web API key refuses this app. In Google Cloud console › Credentials, let the key call the Identity "
                + "Toolkit, Token Service and Cloud Firestore APIs from apps without a website, then sign in again."
        }
        switch code {
        case "timeout": return "Sign-in took longer than 5 minutes. Try again."
        case "oauth_access_denied": return "Sign-in was cancelled in the browser."
        case "offline": return "No network. Try again when you are online."
        case "stateMismatch": return "The browser answer did not match this sign-in. Try again."
        default: return "Try again. If it keeps failing, the code above says why."
        }
    }
}
