import Foundation

/// Result of analyzing a clipping via the backend proxy. Mirrors Android's GeminiResult.
enum AnalyzeResult {
    case success(extractedText: String, summary: String)
    case failure(String)
}

/// Calls the SAME backend proxy as the Android app: POST {mimeType, imageBase64} to
/// `<SERVER_URL>/analyze` with `Authorization: Bearer <PROXY_TOKEN>`. The Gemini key, model and
/// prompt all live server-side. Mirrors Android's `GeminiClient`.
enum AnalyzeClient {
    static func analyze(imageData: Data, mimeType: String) async -> AnalyzeResult {
        let base = AppConfig.serverURL.trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
        guard !base.isEmpty, let url = URL(string: "\(base)/analyze") else {
            return .failure("Server URL not configured. Set SERVER_URL in Config.xcconfig.")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 90
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(AppConfig.proxyToken)", forHTTPHeaderField: "Authorization")
        request.httpBody = try? JSONSerialization.data(withJSONObject: [
            "mimeType": mimeType,
            "imageBase64": imageData.base64EncodedString(),
        ])

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]

            guard (200..<300).contains(code) else {
                return .failure((json?["error"] as? String) ?? "Request failed (HTTP \(code))")
            }
            let extracted = (json?["extractedText"] as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let summary = (json?["summary"] as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if extracted.isEmpty && summary.isEmpty {
                return .failure("Server returned no text")
            }
            return .success(extractedText: extracted, summary: summary)
        } catch {
            return .failure(error.localizedDescription)
        }
    }
}
