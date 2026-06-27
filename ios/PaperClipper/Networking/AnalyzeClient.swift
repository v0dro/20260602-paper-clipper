import Foundation

/// Result of analyzing a clipping via the backend proxy. Mirrors Android's `GeminiResult`.
enum AnalyzeResult: Equatable {
    case success(extractedText: String, summary: String, heading: String)
    case failure(String)
}

/// The analyze call's signature, so it can be injected (and stubbed in tests) wherever the pipeline
/// runs it. Mirrors how the Android tests mock `GeminiClient.analyze`.
typealias AnalyzeFunction = (_ imageData: Data, _ mimeType: String, _ userId: String) async -> AnalyzeResult

/// Calls the SAME backend proxy as the Android app: POST `{mimeType, imageBase64}` to
/// `<SERVER_URL>/analyze` with `Authorization: Bearer <PROXY_TOKEN>` and an `X-User-Id` header for
/// the per-user daily quota. The Gemini key, model and prompt all live server-side. Mirrors
/// Android's `gemini/GeminiClient`. The `parseResult`/`serverError` helpers are pure and unit-tested.
enum AnalyzeClient {
    static func analyze(imageData: Data, mimeType: String, userId: String) async -> AnalyzeResult {
        let base: String
        switch Backend.resolveBaseUrl(AppConfig.serverURL) {
        case let .success(value): base = value
        case let .failure(error): return .failure(error.message)
        }
        guard let url = URL(string: Backend.buildUrl(base, "analyze")) else {
            return .failure(BackendError.notConfigured.message)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 90
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(AppConfig.proxyToken)", forHTTPHeaderField: "Authorization")
        request.setValue(userId, forHTTPHeaderField: "X-User-Id")
        request.httpBody = try? JSONSerialization.data(withJSONObject: [
            "mimeType": mimeType,
            "imageBase64": imageData.base64EncodedString(),
        ])

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            let body = String(data: data, encoding: .utf8) ?? ""
            guard (200..<300).contains(code) else {
                return .failure(serverError(body, code: code))
            }
            return parseResult(body)
        } catch {
            return .failure(error.localizedDescription)
        }
    }

    /// Parses the `{extractedText, summary}` proxy response, trimming both fields. Both-empty is an
    /// error; unparseable input is an error. Mirrors `GeminiClient.parseResult`.
    static func parseResult(_ response: String) -> AnalyzeResult {
        guard let data = response.data(using: .utf8),
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return .failure("Unexpected response from server")
        }
        let extracted = (json["extractedText"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let summary = (json["summary"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let heading = (json["heading"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if extracted.isEmpty && summary.isEmpty {
            return .failure("Server returned no text")
        }
        return .success(extractedText: extracted, summary: summary, heading: heading)
    }

    /// Prefers the server's `error` field, otherwise falls back to the HTTP code. Mirrors
    /// `GeminiClient.serverError`.
    static func serverError(_ response: String, code: Int) -> String {
        if let data = response.data(using: .utf8),
           let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
           let message = json["error"] as? String {
            return message
        }
        return "Analysis request failed (HTTP \(code))"
    }
}
