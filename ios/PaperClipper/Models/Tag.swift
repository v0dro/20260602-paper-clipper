import Foundation
import SwiftData

/// A globally-reusable tag, usable from any clipping once created. Mirrors Android's `tags` table
/// + `clipping_tags` many-to-many.
@Model
final class Tag {
    @Attribute(.unique) var name: String

    @Relationship(inverse: \Clipping.tags)
    var clippings: [Clipping] = []

    init(name: String) {
        self.name = name
    }
}
