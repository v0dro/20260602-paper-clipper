import Foundation
import SwiftData
import UIKit

/// Filesystem + analysis layer. Mirrors Android's `ClippingsRepository`: reconciles SwiftData rows
/// with image files on disk, then analyzes anything pending via the backend proxy (sequentially,
/// to respect the free-tier rate limit). SwiftData is the source of truth for the list.
@MainActor
enum ClippingStore {
    /// Documents/clippings — where captured/cropped/lassoed images live.
    static func clippingsDir() -> URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dir = docs.appendingPathComponent("clippings", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func fileURL(_ fileName: String) -> URL {
        clippingsDir().appendingPathComponent(fileName)
    }

    static func mimeType(for fileName: String) -> String {
        fileName.lowercased().hasSuffix(".png") ? "image/png" : "image/jpeg"
    }

    private static func imageFiles() -> [URL] {
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: clippingsDir(),
            includingPropertiesForKeys: [.contentModificationDateKey]
        )) ?? []
        return urls.filter { ["jpg", "jpeg", "png"].contains($0.pathExtension.lowercased()) }
    }

    /// Sync DB with disk, then analyze pending clippings.
    static func reconcileAndProcess(context: ModelContext) async {
        let files = imageFiles()
        let fileNames = Set(files.map { $0.lastPathComponent })

        let existing = (try? context.fetch(FetchDescriptor<Clipping>())) ?? []
        let known = Set(existing.map { $0.fileName })

        // Insert rows for new files.
        for url in files where !known.contains(url.lastPathComponent) {
            let created = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?
                .contentModificationDate ?? .now
            context.insert(Clipping(fileName: url.lastPathComponent, createdAt: created))
        }
        // Delete rows whose file is gone.
        for row in existing where !fileNames.contains(row.fileName) {
            context.delete(row)
        }
        try? context.save()

        await processPending(context: context)
    }

    private static func processPending(context: ModelContext) async {
        let pending = (try? context.fetch(FetchDescriptor<Clipping>()))?
            .filter { $0.status == .pending } ?? []
        for clip in pending {
            let url = fileURL(clip.fileName)
            guard let data = try? Data(contentsOf: url) else {
                context.delete(clip)
                continue
            }
            clip.status = .processing
            try? context.save()

            let result = await AnalyzeClient.analyze(imageData: data, mimeType: mimeType(for: clip.fileName))
            switch result {
            case let .success(extractedText, summary):
                clip.extractedText = extractedText
                clip.summary = summary
                clip.errorMessage = nil
                clip.status = .success
            case let .failure(message):
                clip.errorMessage = message
                clip.status = .error
            }
            clip.model = "server"
            clip.processedAt = .now
            try? context.save()
        }
    }
}
