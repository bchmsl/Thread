import Foundation
import Security

/// A generic password item in the login keychain: this Mac only, never iCloud-synced (spike U11: read after a rebuild
/// with the same signing identity, no prompt, no entitlements).
public struct KeychainItem: Sendable {
    public let service: String
    public let account: String

    public init(service: String, account: String) {
        self.service = service
        self.account = account
    }

    private var query: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    @discardableResult
    public func write(_ data: Data) -> OSStatus {
        SecItemDelete(query as CFDictionary)
        var attributes = query
        attributes[kSecValueData as String] = data
        attributes[kSecAttrSynchronizable as String] = kCFBooleanFalse
        attributes[kSecAttrLabel as String] = "Thread Calendar (\(account))"
        return SecItemAdd(attributes as CFDictionary, nil)
    }

    public func read() -> (status: OSStatus, data: Data?) {
        var request = query
        request[kSecReturnData as String] = kCFBooleanTrue
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(request as CFDictionary, &result)
        return (status, result as? Data)
    }

    @discardableResult
    public func erase() -> OSStatus {
        SecItemDelete(query as CFDictionary)
    }

    public static func statusCode(_ status: OSStatus) -> String {
        switch status {
        case errSecSuccess: return "ok"
        case errSecItemNotFound: return "notFound"
        case errSecAuthFailed: return "authFailed"
        case errSecInteractionNotAllowed: return "interactionNotAllowed"
        case errSecUserCanceled: return "userCanceled"
        case errSecMissingEntitlement: return "missingEntitlement"
        default: return "osstatus_\(status)"
        }
    }
}
