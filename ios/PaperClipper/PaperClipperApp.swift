import SwiftUI
import SwiftData

@main
struct PaperClipperApp: App {
    let container: ModelContainer
    @State private var auth = AuthManager()

    init() {
        do {
            #if DEBUG
            if UITestSupport.isActive {
                // UI tests run against a clean in-memory store (seeded from HomeView's .task).
                let config = ModelConfiguration(isStoredInMemoryOnly: true)
                container = try ModelContainer(for: Clipping.self, Tag.self, Comment.self, configurations: config)
            } else {
                container = try ModelContainer(for: Clipping.self, Tag.self, Comment.self)
            }
            #else
            container = try ModelContainer(for: Clipping.self, Tag.self, Comment.self)
            #endif
        } catch {
            fatalError("Failed to create SwiftData container: \(error)")
        }
        // Firebase is configured lazily from HomeView's .task (keeps App.init off the main-actor hook).
    }

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environment(auth)
        }
        .modelContainer(container)
    }
}
