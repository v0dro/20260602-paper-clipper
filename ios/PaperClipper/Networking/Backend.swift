import Foundation

/// Why a configured `SERVER_URL` can be rejected. Mirrors the two failure messages in Android's
/// `net/Backend.kt`.
enum BackendError: Error, Equatable {
    case notConfigured
    case cleartext

    var message: String {
        switch self {
        case .notConfigured:
            return "Server URL not configured. Set SERVER_URL in Config.xcconfig and rebuild."
        case .cleartext:
            return "Server URL must use https:// — refusing to send data in cleartext."
        }
    }
}

/// Shared plumbing for the two backend calls (`/analyze`, `/feedback`). Centralizes URL handling and
/// the HTTPS guard so it isn't duplicated between `AnalyzeClient` and `FeedbackClient`. Mirrors
/// Android's `net/Backend.kt`. The URL helpers are pure (no I/O) so they are unit-testable.
enum Backend {
    /// Validates the configured backend base URL. Trims surrounding whitespace and trailing slashes,
    /// rejects a blank value, and — critically — rejects any non-HTTPS scheme. A misconfigured
    /// `http://` SERVER_URL would send the proxy token, user id and feedback email in cleartext, so
    /// we refuse it outright.
    static func resolveBaseUrl(_ raw: String) -> Result<String, BackendError> {
        var trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        while trimmed.hasSuffix("/") { trimmed.removeLast() }
        if trimmed.isEmpty {
            return .failure(.notConfigured)
        }
        if !trimmed.lowercased().hasPrefix("https://") {
            return .failure(.cleartext)
        }
        return .success(trimmed)
    }

    /// Joins a validated base URL and an endpoint path with exactly one separating slash.
    static func buildUrl(_ base: String, _ path: String) -> String {
        var b = base
        while b.hasSuffix("/") { b.removeLast() }
        var p = path
        while p.hasPrefix("/") { p.removeFirst() }
        return b + "/" + p
    }
}
