import SwiftUI
import SwiftData

@main
struct PaperClipperApp: App {
    let container: ModelContainer
    @State private var auth = AuthManager()

    init() {
        do {
            if Self.useInMemoryStore {
                // Tests (UI seeded, or hosting unit tests) run against a clean in-memory store so
                // they're deterministic and never touch the real on-disk database.
                let config = ModelConfiguration(isStoredInMemoryOnly: true)
                container = try ModelContainer(for: Clipping.self, Tag.self, Comment.self, configurations: config)
            } else {
                container = try ModelContainer(for: Clipping.self, Tag.self, Comment.self)
            }
        } catch {
            fatalError("Failed to create SwiftData container: \(error)")
        }
        // Firebase is configured lazily from HomeView's .task (keeps App.init off the main-actor hook).
    }

    private static var useInMemoryStore: Bool {
        if TestRuntime.isUnitTesting { return true }
        #if DEBUG
        if UITestSupport.isActive { return true }
        #endif
        return false
    }

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environment(auth)
        }
        .modelContainer(container)
    }
}
