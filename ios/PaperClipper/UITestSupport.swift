#if DEBUG
import Foundation
import SwiftData
import UIKit

/// Deterministic seeding for XCUITests. When the app is launched with `-uiTestSeed`, it runs against
/// an in-memory store (see `PaperClipperApp`) and seeds a fixed set of success-state clippings (with
/// matching image files on disk) so the list, search and detail screens can be driven reliably
/// without the camera or the network. Compiled only in DEBUG.
enum UITestSupport {
    static var isActive: Bool {
        ProcessInfo.processInfo.arguments.contains("-uiTestSeed")
    }

    /// Fixed sample clippings shown in UI tests. Public so tests can assert on the same strings.
    static let samples: [(fileName: String, heading: String, summary: String, extractedText: String)] = [
        ("seed_apple.png", "County Fair Win", "Apple pie recipe wins the county fair", "Apple pie recipe wins the county fair this year."),
        ("seed_team.png", "Championship Clinched", "Local team clinches the championship", "The local team clinches the championship in overtime."),
    ]

    @MainActor
    static func seedIfEmpty(into context: ModelContext) {
        let existing = (try? context.fetch(FetchDescriptor<Clipping>())) ?? []
        guard existing.isEmpty else { return }

        // Start from a clean clippings dir so leftover files from a prior run don't appear.
        let dir = ClippingStore.clippingsDir()
        for url in (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [] {
            try? FileManager.default.removeItem(at: url)
        }

        let png = onePixelPNG()
        var created = Date(timeIntervalSince1970: 1_700_000_000)
        for sample in samples {
            try? png.write(to: dir.appendingPathComponent(sample.fileName))
            let clip = Clipping(fileName: sample.fileName, createdAt: created, status: .success)
            clip.heading = sample.heading
            clip.summary = sample.summary
            clip.extractedText = sample.extractedText
            clip.model = "server"
            context.insert(clip)
            created = created.addingTimeInterval(86_400)
        }
        try? context.save()
    }

    /// A valid 1×1 PNG so `AsyncImage` and the reconcile step have a real file to work with.
    private static func onePixelPNG() -> Data {
        let base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        return Data(base64Encoded: base64) ?? Data()
    }
}
#endif
