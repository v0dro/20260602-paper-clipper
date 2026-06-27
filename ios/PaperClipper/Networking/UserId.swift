import Foundation

#if canImport(FirebaseCore)
import FirebaseCore
import FirebaseAuth
#endif

/// Stable identifier for the per-user daily quota the server enforces (sent as the `X-User-Id`
/// header on `/analyze`). Uses the signed-in Firebase UID when available, otherwise a persistent
/// per-install UUID kept in `UserDefaults`. Mirrors Android's `UserId.kt` (`uid:` vs `dev:` prefix).
enum UserId {
    private static let installIdKey = "paperclipper.install_id"

    static func get() -> String {
        #if canImport(FirebaseCore)
        // Guard on FirebaseApp being configured — touching Auth before configure() crashes.
        if FirebaseApp.app() != nil, let uid = Auth.auth().currentUser?.uid {
            return "uid:\(uid)"
        }
        #endif
        let defaults = UserDefaults.standard
        if let existing = defaults.string(forKey: installIdKey) {
            return "dev:\(existing)"
        }
        let fresh = UUID().uuidString
        defaults.set(fresh, forKey: installIdKey)
        return "dev:\(fresh)"
    }
}
