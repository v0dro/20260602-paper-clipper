import Foundation
import SwiftData

/// Processing state of a clipping's AI analysis. Mirrors Android's ClippingStatus.
enum ClippingStatus: String, Codable, CaseIterable {
    case pending, processing, success, error
}

/// One row per saved clipping image. Image bytes live on disk in the clippings dir; this model
/// holds the AI-derived text/summary + metadata. Mirrors Android's Room `ClippingEntity`.
@Model
final class Clipping {
    @Attribute(.unique) var fileName: String
    var createdAt: Date
    var statusRaw: String
    var extractedText: String?
    var summary: String?
    /// AI-generated short title (<5 words). Nullable; mirrors Android's `heading` column (DB v3).
    var heading: String?
    var errorMessage: String?
    var model: String?
    var processedAt: Date?

    @Relationship(deleteRule: .cascade, inverse: \Comment.clipping)
    var comments: [Comment] = []

    // Many-to-many with global Tag (inverse declared on Tag.clippings).
    var tags: [Tag] = []

    var status: ClippingStatus {
        get { ClippingStatus(rawValue: statusRaw) ?? .pending }
        set { statusRaw = newValue.rawValue }
    }

    init(fileName: String, createdAt: Date = .now, status: ClippingStatus = .pending) {
        self.fileName = fileName
        self.createdAt = createdAt
        self.statusRaw = status.rawValue
    }
}
