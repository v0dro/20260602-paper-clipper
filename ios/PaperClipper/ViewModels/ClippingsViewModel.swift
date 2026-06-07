import Foundation
import Observation
import SwiftData
import UIKit

/// Coordinates analysis + edits over the SwiftData store. Views read the list reactively via
/// `@Query`; this drives refresh/analyze, deletion, tag/comment ops and export. Mirrors Android's
/// `ClippingsViewModel` + the tag/comment/export parts of `ClippingsRepository`.
@MainActor
@Observable
final class ClippingsViewModel {
    let context: ModelContext

    init(context: ModelContext) {
        self.context = context
    }

    /// Reconcile DB with disk and analyze pending clippings. Call when the library appears.
    func refresh() {
        Task { await ClippingStore.reconcileAndProcess(context: context) }
    }

    func delete(_ clipping: Clipping) {
        try? FileManager.default.removeItem(at: ClippingStore.fileURL(clipping.fileName))
        context.delete(clipping)
        try? context.save()
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
        try? context.save()
    }

    func deleteComment(_ comment: Comment) {
        context.delete(comment)
        try? context.save()
    }

    // TODO(parity): export ZIP (images + metadata.json + index.html) via ShareLink/Files,
    // mirroring Android's ClippingsRepository.exportTo. See ANDROID_TO_IOS.md.
}
