import Foundation

/// Backend configuration, surfaced from Config.xcconfig -> Info.plist. Mirrors the Android
/// BuildConfig.SERVER_URL / PROXY_TOKEN. The valuable Gemini key lives only on the server.
enum AppConfig {
    static var serverURL: String {
        (Bundle.main.object(forInfoDictionaryKey: "SERVER_URL") as? String) ?? ""
    }

    static var proxyToken: String {
        (Bundle.main.object(forInfoDictionaryKey: "PROXY_TOKEN") as? String) ?? ""
    }

    /// App version string for feedback reports. Mirrors Android's `BuildConfig.VERSION_NAME`.
    static var appVersion: String {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? ""
    }
}
