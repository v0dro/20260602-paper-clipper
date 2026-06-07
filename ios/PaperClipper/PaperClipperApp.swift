import SwiftUI
import SwiftData

@main
struct PaperClipperApp: App {
    let container: ModelContainer
    @State private var auth = AuthManager()

    init() {
        do {
            container = try ModelContainer(for: Clipping.self, Tag.self, Comment.self)
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
