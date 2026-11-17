import Foundation

public enum BackendMode: String, Sendable {
    case emulator, cloud
}

/// Where the app signs in and uploads. scripts/build-app.sh writes `Config.plist` into the app bundle from the
/// git-ignored firebase/web-config.properties (apiKey, projectId) and macCompanion/oauth-desktop.properties
/// (clientId, clientSecret). With neither file, or with `--emulator`, the app uses the local emulators and the
/// demo-thread project, like the web (FirebaseBackend.kt). `description` never shows a value.
public struct AppConfig: Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    public static let emulatorProjectId = "demo-thread"
    public static let emulatorHost = "127.0.0.1"
    public static let authEmulatorPort = 9099
    public static let firestoreEmulatorPort = 8085
    /// Not a secret: the emulators accept any value (same constant as the web's EMULATOR_API_KEY).
    static let emulatorApiKey = "demo-api-key-for-emulators-only"
    /// Thread web on GitHub Pages (README.md, Deploy).
    public static let threadWebURL = URL(string: "https://bchmsl.github.io/Thread/")!
    static let valuePattern = "^[A-Za-z0-9._:-]+$"

    public let mode: BackendMode
    public let projectId: String
    let apiKey: String
    let oauthClient: GoogleDesktopClient?

    public static let emulator = AppConfig(mode: .emulator, projectId: emulatorProjectId, apiKey: emulatorApiKey, oauthClient: nil)

    public var description: String { "AppConfig(mode: \(mode.rawValue))" }
    public var debugDescription: String { description }

    /// Reads the keys build-app.sh writes. Nil when the plist claims cloud mode but a value is missing or malformed.
    public static func parse(_ plist: [String: Any]) -> AppConfig? {
        guard let mode = (plist["mode"] as? String).flatMap(BackendMode.init(rawValue:)) else { return nil }
        if mode == .emulator { return .emulator }
        func value(_ key: String) -> String? {
            guard let text = plist[key] as? String, text.range(of: valuePattern, options: .regularExpression) != nil else { return nil }
            return text
        }
        guard
            let apiKey = value("apiKey"), let projectId = value("projectId"),
            let clientId = value("clientId"), let clientSecret = value("clientSecret")
        else { return nil }
        return AppConfig(mode: .cloud, projectId: projectId, apiKey: apiKey, oauthClient: GoogleDesktopClient(clientId: clientId, clientSecret: clientSecret))
    }

    /// No Config.plist means emulator mode (a `swift run` build). A broken one returns nil, and the app says so.
    public static func load(bundle: Bundle = .main) -> AppConfig? {
        guard let url = bundle.url(forResource: "Config", withExtension: "plist") else { return .emulator }
        guard
            let data = try? Data(contentsOf: url),
            let plist = (try? PropertyListSerialization.propertyList(from: data, format: nil)) as? [String: Any]
        else { return nil }
        return parse(plist)
    }

    public var networkGuard: NetworkGuard { NetworkGuard(mode: mode) }

    public var authEndpoints: AuthEndpoints {
        switch mode {
        case .emulator: return .emulator(host: Self.emulatorHost, port: Self.authEmulatorPort, projectId: projectId)
        case .cloud: return .cloud(apiKey: apiKey)
        }
    }

    public func firestoreEndpoint() throws -> FirestoreEndpoint {
        switch mode {
        case .emulator: return try .emulator(host: Self.emulatorHost, port: Self.firestoreEmulatorPort, projectId: projectId)
        case .cloud: return .cloud(projectId: projectId)
        }
    }

    public var threadURL: URL? { mode == .cloud ? Self.threadWebURL : nil }
}

extension AppConfig {
    /// The Google id token for `signInWithIdp`: the browser flow in cloud mode, SYNTHETIC claims in emulator mode.
    public func googleIdToken(http: HTTPClient, openBrowser: (URL) -> Void) async throws -> String {
        switch mode {
        case .emulator:
            return FirebaseAuthREST.emulatorGoogleIdToken(sub: "SYNTHETIC-mac-owner", email: "synthetic.owner@example.test")
        case .cloud:
            guard let oauthClient else { throw CalendarSyncError.oauth("noClient") }
            return try await GoogleSignInFlow(client: oauthClient, http: http).run(openBrowser: openBrowser)
        }
    }
}
