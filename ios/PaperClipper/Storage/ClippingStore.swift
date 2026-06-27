import Foundation
import SwiftData

/// Filesystem + analysis layer. Mirrors Android's `ClippingsRepository`: reconciles SwiftData rows
/// with image files on disk, then analyzes anything pending via the backend proxy (sequentially, to
/// respect the free-tier rate limit). SwiftData is the source of truth for the list.
///
/// The analyze call is injected (defaulting to the real `AnalyzeClient.analyze`) so the pipeline can
/// be exercised with a stub in tests — mirroring how the Android tests mock `GeminiClient`.
///
/// The pure file helpers are not actor-isolated; only the three methods that touch a `ModelContext`
/// (`reconcileAndProcess`, `processPending`, `clearAll`) are `@MainActor`.
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

    /// A fresh, time-stamped clipping file name. Mirrors Android's `clipping_<millis>.<ext>`.
    static func newFileName(ext: String) -> String {
        "clipping_\(Int(Date().timeIntervalSince1970 * 1000)).\(ext)"
    }

    /// Writes image data into the clippings dir under `name` and returns its URL.
    @discardableResult
    static func save(_ data: Data, name: String) throws -> URL {
        let url = fileURL(name)
        try data.write(to: url)
        return url
    }

    /// Removes the image file `name` from disk (the DB row is reconciled away on next refresh).
    static func deleteFile(named name: String) {
        try? FileManager.default.removeItem(at: fileURL(name))
    }

    /// Imports image bytes (from the photo picker or a share/open-in) into a new clipping file,
    /// preserving the bytes verbatim so JPEG EXIF orientation survives. PNG is detected by its magic
    /// header; everything else is treated as JPEG. Mirrors Android's `importImageToClipping`.
    static func importImage(_ data: Data) -> String? {
        let isPNG = data.starts(with: [0x89, 0x50, 0x4E, 0x47])
        let name = newFileName(ext: isPNG ? "png" : "jpg")
        return (try? save(data, name: name)) != nil ? name : nil
    }

    /// Imports an image referenced by a file URL (e.g. from `.onOpenURL` / "Open in"). Returns the
    /// new clipping file name, or nil if it couldn't be read.
    static func importImage(from url: URL) -> String? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { return nil }
        return importImage(data)
    }

    /// The clipping image files (jpg/png) on disk, newest first. Mirrors `listClippingFiles`.
    static func imageFiles() -> [URL] {
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: clippingsDir(),
            includingPropertiesForKeys: [.contentModificationDateKey]
        )) ?? []
        // jpg/png only, matching Android's listClippingFiles (the app only ever writes .jpg/.png).
        return urls
            .filter { ["jpg", "png"].contains($0.pathExtension.lowercased()) }
            .sorted { lhs, rhs in
                let l = (try? lhs.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
                let r = (try? rhs.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? .distantPast
                return l > r
            }
    }

    /// Sync DB with disk, then analyze pending clippings.
    @MainActor
    static func reconcileAndProcess(
        context: ModelContext,
        analyze: @escaping AnalyzeFunction = AnalyzeClient.analyze
    ) async {
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

        await processPending(context: context, analyze: analyze)
    }

    @MainActor
    static func processPending(
        context: ModelContext,
        analyze: @escaping AnalyzeFunction = AnalyzeClient.analyze
    ) async {
        // Stable per-user id for the server's daily quota (X-User-Id). Mirrors Android's UserId.
        let userId = UserId.get()
        let pending = (try? context.fetch(FetchDescriptor<Clipping>()))?
            .filter { $0.status == .pending } ?? []
        for clip in pending {
            let url = fileURL(clip.fileName)
            guard let data = try? Data(contentsOf: url) else {
                context.delete(clip)
                try? context.save()
                continue
            }
            clip.status = .processing
            try? context.save()

            let result = await analyze(data, mimeType(for: clip.fileName), userId)
            switch result {
            case let .success(extractedText, summary, heading):
                clip.extractedText = extractedText
                clip.summary = summary
                clip.heading = heading.isEmpty ? nil : heading
                clip.errorMessage = nil
                clip.status = .success
            case let .failure(message):
                clip.extractedText = nil
                clip.summary = nil
                clip.heading = nil
                clip.errorMessage = message
                clip.status = .error
            }
            clip.model = "server"
            clip.processedAt = .now
            try? context.save()
        }
    }

    /// Wipes ALL user data: every clipping image on disk plus all clippings, tags and comments.
    /// Mirrors Android's `ClippingsRepository.clearAll`.
    @MainActor
    static func clearAll(context: ModelContext) {
        for url in imageFiles() {
            try? FileManager.default.removeItem(at: url)
        }
        for clip in (try? context.fetch(FetchDescriptor<Clipping>())) ?? [] {
            context.delete(clip)
        }
        for tag in (try? context.fetch(FetchDescriptor<Tag>())) ?? [] {
            context.delete(tag)
        }
        for comment in (try? context.fetch(FetchDescriptor<Comment>())) ?? [] {
            context.delete(comment)
        }
        try? context.save()
    }
}
