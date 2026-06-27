import Foundation

/// Small logging/PII helper. Mirrors the testable part of Android's `util/Logx.kt`. The main reason
/// this exists on iOS is [redactEmail], so an email can be referenced in a log line without exposing
/// the full address.
enum Logx {
    /// Masks an email so it can be logged for debugging without exposing the full address:
    /// `jane.doe@example.com` -> `j***@example.com`. Anything that isn't a plausible address (no `@`,
    /// `@` at the start, or `@` at the end) collapses to a constant so the input is never echoed back.
    static func redactEmail(_ email: String?) -> String {
        guard let email, !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return "<none>"
        }
        guard let at = email.firstIndex(of: "@"), at != email.startIndex else {
            return "<redacted>"
        }
        let domainStart = email.index(after: at)
        guard domainStart != email.endIndex else {
            return "<redacted>"
        }
        let first = email[email.startIndex]
        let domain = email[domainStart...]
        return "\(first)***@\(domain)"
    }
}
