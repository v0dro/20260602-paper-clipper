import Foundation

/// Posts user feedback to the same backend (behind the Cloudflare tunnel) as `/analyze`. Mirrors
/// Android's `FeedbackClient.kt`: POST `/feedback` with `{message, email, appVersion}` and the proxy
/// bearer token. Returns whether the server accepted it (2xx).
enum FeedbackClient {
    static func send(message: String, email: String?) async -> Bool {
        guard case let .success(base) = Backend.resolveBaseUrl(AppConfig.serverURL),
              let url = URL(string: Backend.buildUrl(base, "feedback")) else {
            return false
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(AppConfig.proxyToken)", forHTTPHeaderField: "Authorization")

        var body: [String: Any] = [
            "message": message,
            "appVersion": AppConfig.appVersion,
        ]
        body["email"] = email ?? NSNull()
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)

        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            return (200..<300).contains(code)
        } catch {
            return false
        }
    }
}
