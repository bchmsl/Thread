import Foundation

// MARK: - Firebase Auth REST (calendar-plan.md section 4, sign-in steps 4 and 5)

public struct AuthEndpoints: Sendable {
    public let identityToolkit: String
    public let secureToken: String
    let apiKey: String

    /// The Auth emulator serves the production paths under its own host: http://host:port/identitytoolkit.googleapis.com/v1/...
    public static func emulator(host: String = "127.0.0.1", port: Int, projectId: String) -> AuthEndpoints {
        AuthEndpoints(
            identityToolkit: "http://\(host):\(port)/identitytoolkit.googleapis.com/v1",
            secureToken: "http://\(host):\(port)/securetoken.googleapis.com/v1",
            apiKey: AppConfig.emulatorApiKey
        )
    }

    public static func cloud(apiKey: String) -> AuthEndpoints {
        AuthEndpoints(
            identityToolkit: "https://identitytoolkit.googleapis.com/v1",
            secureToken: "https://securetoken.googleapis.com/v1",
            apiKey: apiKey
        )
    }
}

/// Tokens live in memory. `description` never prints them, the uid or the e-mail.
public struct FirebaseSession: Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    public let idToken: String
    public let refreshToken: String
    public let uid: String
    public let email: String?
    public let expiresAt: Date

    public init(idToken: String, refreshToken: String, uid: String, email: String?, expiresAt: Date) {
        self.idToken = idToken
        self.refreshToken = refreshToken
        self.uid = uid
        self.email = email
        self.expiresAt = expiresAt
    }

    public var description: String { "FirebaseSession(<redacted>)" }
    public var debugDescription: String { description }
}

/// The two Firebase Auth calls, behind a protocol so the account logic can be tested without a server.
public protocol AuthService: Sendable {
    func signInWithIdp(googleIdToken: String) async throws -> FirebaseSession
    func refresh(refreshToken: String, email: String?) async throws -> FirebaseSession
}

public struct FirebaseAuthREST: AuthService {
    let http: HTTPClient
    let endpoints: AuthEndpoints
    let now: @Sendable () -> Date

    public init(http: HTTPClient, endpoints: AuthEndpoints, now: @escaping @Sendable () -> Date = { Date() }) {
        self.http = http
        self.endpoints = endpoints
        self.now = now
    }

    /// `accounts:signInWithIdp` with a Google id token. In the emulator the id token is a JSON string of claims,
    /// the same trick the web tests use with `GoogleAuthProvider.credential(JSON.stringify(claims))`.
    public func signInWithIdp(googleIdToken: String) async throws -> FirebaseSession {
        let url = URL(string: endpoints.identityToolkit + "/accounts:signInWithIdp?key=" + FormEncoding.escape(endpoints.apiKey))!
        let body: [String: Any] = [
            "postBody": "id_token=" + FormEncoding.escape(googleIdToken) + "&providerId=google.com",
            "requestUri": "http://localhost",
            "returnSecureToken": true,
        ]
        let result = try await http.json("POST", url, body: body)
        guard result.status == 200, let json = result.json else {
            throw CalendarSyncError.http(status: result.status, code: result.errorReason ?? result.errorCode)
        }
        guard
            let idToken = json["idToken"] as? String,
            let refreshToken = json["refreshToken"] as? String,
            let uid = json["localId"] as? String
        else { throw CalendarSyncError.badResponse("signInWithIdp") }
        let expiresIn = Double(json["expiresIn"] as? String ?? "") ?? 3600
        return FirebaseSession(idToken: idToken, refreshToken: refreshToken, uid: uid, email: json["email"] as? String, expiresAt: now().addingTimeInterval(expiresIn))
    }

    public func refresh(refreshToken: String, email: String?) async throws -> FirebaseSession {
        let url = URL(string: endpoints.secureToken + "/token?key=" + FormEncoding.escape(endpoints.apiKey))!
        let result = try await http.form(url, [("grant_type", "refresh_token"), ("refresh_token", refreshToken)])
        guard result.status == 200, let json = result.json else {
            throw CalendarSyncError.http(status: result.status, code: result.errorReason ?? result.errorCode)
        }
        guard
            let idToken = json["id_token"] as? String,
            let newRefreshToken = json["refresh_token"] as? String,
            let uid = json["user_id"] as? String
        else { throw CalendarSyncError.badResponse("refresh") }
        let expiresIn = Double(json["expires_in"] as? String ?? "") ?? 3600
        return FirebaseSession(idToken: idToken, refreshToken: newRefreshToken, uid: uid, email: email, expiresAt: now().addingTimeInterval(expiresIn))
    }

    /// SYNTHETIC Google claims for the Auth emulator.
    public static func emulatorGoogleIdToken(sub: String, email: String) -> String {
        let claims: [String: Any] = ["sub": sub, "email": email, "email_verified": true]
        let data = try! JSONSerialization.data(withJSONObject: claims, options: [.sortedKeys])
        return String(data: data, encoding: .utf8)!
    }
}

// MARK: - The signed-in account

/// What survives a restart: the Firebase refresh token, plus the uid and e-mail the menu shows on this Mac.
public struct StoredAccount: Codable, Equatable, Sendable {
    public var refreshToken: String
    public var uid: String
    public var email: String?

    public init(refreshToken: String, uid: String, email: String?) {
        self.refreshToken = refreshToken
        self.uid = uid
        self.email = email
    }
}

public protocol AccountVault: AnyObject {
    func load() -> StoredAccount?
    func save(_ account: StoredAccount) throws
    func clear()
}

/// The login Keychain item that holds the account. Google's own tokens are never stored.
public final class KeychainAccountVault: AccountVault {
    let item: KeychainItem

    public init(service: String) {
        item = KeychainItem(service: service, account: "firebase-session")
    }

    public func load() -> StoredAccount? {
        guard let data = item.read().data else { return nil }
        return try? JSONDecoder().decode(StoredAccount.self, from: data)
    }

    public func save(_ account: StoredAccount) throws {
        let status = item.write(try JSONEncoder().encode(account))
        guard status == errSecSuccess else { throw CalendarSyncError.keychain(KeychainItem.statusCode(status)) }
    }

    public func clear() {
        item.erase()
    }
}

/// Keeps the Firebase ID token in memory and renews it when under 5 minutes are left.
@MainActor
public final class AccountSession {
    public static let refreshMargin: TimeInterval = 5 * 60

    let auth: AuthService
    let vault: AccountVault
    let now: () -> Date
    private var session: FirebaseSession?
    public private(set) var stored: StoredAccount?

    public init(auth: AuthService, vault: AccountVault, now: @escaping () -> Date = { Date() }) {
        self.auth = auth
        self.vault = vault
        self.now = now
        stored = vault.load()
    }

    public var isSignedIn: Bool { stored != nil }
    public var email: String? { stored?.email }
    /// Shown on this Mac only, so the owner can compare it with the Firebase console. Never logged or uploaded.
    public var uid: String? { stored?.uid }

    public func signIn(googleIdToken: String) async throws {
        let fresh = try await auth.signInWithIdp(googleIdToken: googleIdToken)
        let account = StoredAccount(refreshToken: fresh.refreshToken, uid: fresh.uid, email: fresh.email)
        try vault.save(account)
        session = fresh
        stored = account
    }

    /// A refresh the server rejects (400, 401 or 403: revoked, expired, user disabled) signs out, and the menu asks
    /// to sign in again. Offline and 5xx errors pass through, and the pass retries later.
    public func validSession(forceRefresh: Bool = false) async throws -> FirebaseSession {
        guard let account = stored else { throw CalendarSyncError.signInNeeded }
        if !forceRefresh, let session, session.expiresAt.timeIntervalSince(now()) > Self.refreshMargin {
            return session
        }
        do {
            let fresh = try await auth.refresh(refreshToken: account.refreshToken, email: account.email)
            session = fresh
            if fresh.refreshToken != account.refreshToken || fresh.uid != account.uid {
                let updated = StoredAccount(refreshToken: fresh.refreshToken, uid: fresh.uid, email: account.email)
                try? vault.save(updated)
                stored = updated
            }
            return fresh
        } catch CalendarSyncError.http(let status, _) where [400, 401, 403].contains(status) {
            signOut()
            throw CalendarSyncError.signInNeeded
        }
    }

    public func signOut() {
        session = nil
        stored = nil
        vault.clear()
    }
}
