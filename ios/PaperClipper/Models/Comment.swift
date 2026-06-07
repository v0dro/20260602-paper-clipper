import Foundation
import SwiftData

/// A free-text comment attached to a single clipping. Mirrors Android's `comments` table.
@Model
final class Comment {
    var text: String
    var createdAt: Date
    var clipping: Clipping?

    init(text: String, createdAt: Date = .now, clipping: Clipping? = nil) {
        self.text = text
        self.createdAt = createdAt
        self.clipping = clipping
    }
}
