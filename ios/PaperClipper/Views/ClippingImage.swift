import SwiftUI
import UIKit

/// Displays a clipping image loaded directly from disk. Replaces `AsyncImage(url:)` for local files —
/// `AsyncImage` is meant for remote URLs and, with local file URLs in reused list/detail views, can
/// show a stale/wrong image. This loads the `UIImage` itself (off the main thread, downsampled like
/// Android's Coil) and reloads whenever the `url` changes, so each clipping shows its own image.
struct ClippingImage: View {
    let url: URL
    var contentMode: ContentMode = .fit
    var maxDim: CGFloat = 2048

    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
            } else {
                Color.secondary.opacity(0.2)
            }
        }
        .task(id: url) {
            let target = url
            let dim = maxDim
            let loaded = await Task.detached(priority: .userInitiated) {
                ImageProcessing.loadDownsampled(target, maxDim: dim)
            }.value
            // Ignore a result that arrived after the url changed (view reused for another clipping).
            if target == url { image = loaded }
        }
    }
}
