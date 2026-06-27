import Foundation
import SwiftData
@testable import PaperClipper

/// Shared helpers for the SwiftData-backed tests: a fresh in-memory store and a clean clippings dir.
@MainActor
enum SwiftDataTestSupport {
    static func makeContainer() throws -> ModelContainer {
        try ModelContainer(
            for: Clipping.self, Tag.self, Comment.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
    }

    static func cleanClippingsDir() {
        let dir = ClippingStore.clippingsDir()
        for url in (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [] {
            try? FileManager.default.removeItem(at: url)
        }
    }

    @discardableResult
    static func writeImage(_ name: String, bytes: [UInt8] = [0x1, 0x2, 0x3]) -> URL {
        let url = ClippingStore.fileURL(name)
        try? Data(bytes).write(to: url)
        return url
    }
}
