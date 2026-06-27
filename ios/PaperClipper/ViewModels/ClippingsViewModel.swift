import Foundation
import Observation
import SwiftData

/// Coordinates analysis + edits over the SwiftData store. Views read the list reactively via
/// `@Query`; this drives refresh/analyze, deletion, tag/comment ops, clear-all, export and feedback.
/// Mirrors Android's `ClippingsViewModel` + the tag/comment/export parts of `ClippingsRepository`.
@MainActor
@Observable
final class ClippingsViewModel {
    let context: ModelContext

    /// Injected analyze call (defaults to the real network client). Lets tests drive the pipeline
    /// with a stub, mirroring how the Android tests mock `GeminiClient`.
    private let analyze: AnalyzeFunction

    init(context: ModelContext, analyze: @escaping AnalyzeFunction = AnalyzeClient.analyze) {
        self.context = context
        self.analyze = analyze
    }

    /// Reconcile DB with disk and analyze pending clippings. Call when the library appears.
    func refresh() {
        Task { await ClippingStore.reconcileAndProcess(context: context, analyze: analyze) }
    }

    func delete(_ clipping: Clipping) {
        delete([clipping])
    }

    /// Deletes the given clippings: their image files, rows, comments (cascade) and tag links. Global
    /// tags are kept — matching Android's `delete`.
    func delete(_ clippings: [Clipping]) {
        for clipping in clippings {
            try? FileManager.default.removeItem(at: ClippingStore.fileURL(clipping.fileName))
            context.delete(clipping)
        }
        try? context.save()
    }

    /// Deletes a clipping addressed by file name (used by multi-select on the home list).
    func delete(fileNames: Set<String>) {
        guard !fileNames.isEmpty else { return }
        let all = (try? context.fetch(FetchDescriptor<Clipping>())) ?? []
        delete(all.filter { fileNames.contains($0.fileName) })
    }

    /// Deletes ALL user data (clippings, tags, comments + image files). Mirrors Android's `clearAll`.
    func clearAll() {
        ClippingStore.clearAll(context: context)
    }

    func retry(_ clipping: Clipping) {
        clipping.status = .pending
        try? context.save()
        refresh()
    }

    // --- Tags (global) ---
    func createAndAssignTag(named rawName: String, to clipping: Clipping) {
        let name = rawName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        let existing = try? context.fetch(
            FetchDescriptor<Tag>(predicate: #Predicate { $0.name == name })
        ).first
        let tag = existing ?? Tag(name: name)
        if existing == nil { context.insert(tag) }
        if !clipping.tags.contains(where: { $0.name == name }) {
            clipping.tags.append(tag)
        }
        try? context.save()
    }

    func toggle(_ tag: Tag, on clipping: Clipping) {
        if let idx = clipping.tags.firstIndex(where: { $0.name == tag.name }) {
            clipping.tags.remove(at: idx)
        } else {
            clipping.tags.append(tag)
        }
        try? context.save()
    }

    // --- Comments ---
    func addComment(_ text: String, to clipping: Clipping) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let comment = Comment(text: trimmed, clipping: clipping)
        context.insert(comment)
        clipping.comments.append(comment)
        try? context.save()
    }

    func deleteComment(_ comment: Comment) {
        context.delete(comment)
        try? context.save()
    }

    // --- Export ---
    /// Builds the export ZIP (`images/` + `metadata.json` + `index.html`) for every clipping.
    /// Mirrors Android's `ClippingsRepository.exportTo`.
    func exportData() -> Data {
        let clippings = (try? context.fetch(FetchDescriptor<Clipping>())) ?? []
        return ClippingExporter.zipData(for: ClippingExporter.items(from: clippings))
    }

    /// Writes the export ZIP to a temporary file and returns its URL (for ShareLink / fileExporter).
    func writeExportToTemp() -> URL? {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("paper-clippings.zip")
        do {
            try exportData().write(to: url)
            return url
        } catch {
            return nil
        }
    }

    // --- Feedback ---
    /// Sends feedback to the backend; returns success/failure. Mirrors Android's `sendFeedback`.
    func sendFeedback(_ message: String, email: String?) async -> Bool {
        await FeedbackClient.send(message: message.trimmingCharacters(in: .whitespacesAndNewlines), email: email)
    }
}
