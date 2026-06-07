import Foundation
import Observation
import UIKit

#if canImport(FirebaseCore)
import FirebaseCore
import FirebaseAuth
import GoogleSignIn
#endif

/// Firebase + Google Sign-In wrapper. SCAFFOLD: fully wired but inert until Firebase is configured
/// (i.e. `GoogleService-Info.plist` is added to the app target). Every Firebase call is guarded so
/// the app never crashes while unconfigured. Mirrors Android's `auth/AuthManager.kt`.
@MainActor
@Observable
final class AuthManager {
    private(set) var email: String?

    /// Call once at launch. Configures Firebase only if GoogleService-Info.plist is present.
    func configureIfNeeded() {
        #if canImport(FirebaseCore)
        guard FirebaseApp.app() == nil else { return }
        guard Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil else {
            return // not configured yet — stay inert
        }
        FirebaseApp.configure()
        email = Auth.auth().currentUser?.email
        #endif
    }

    var isConfigured: Bool {
        #if canImport(FirebaseCore)
        return FirebaseApp.app() != nil
        #else
        return false
        #endif
    }

    func signIn(presenting: UIViewController) async -> String? {
        #if canImport(FirebaseCore)
        guard isConfigured, let clientID = FirebaseApp.app()?.options.clientID else {
            return "Sign-in isn't set up yet — add GoogleService-Info.plist."
        }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        do {
            let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenting)
            guard let idToken = result.user.idToken?.tokenString else { return "No id token" }
            let credential = GoogleAuthProvider.credential(
                withIDToken: idToken,
                accessToken: result.user.accessToken.tokenString
            )
            let authResult = try await Auth.auth().signIn(with: credential)
            email = authResult.user.email
            return nil
        } catch {
            return error.localizedDescription
        }
        #else
        return "Firebase not available"
        #endif
    }

    func signOut() {
        #if canImport(FirebaseCore)
        try? Auth.auth().signOut()
        GIDSignIn.sharedInstance.signOut()
        #endif
        email = nil
    }
}
