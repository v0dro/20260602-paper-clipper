import SwiftUI

/// Full-screen review of a freshly captured image. Mirrors Android's `PreviewScreen` (Crop / Select),
/// plus an explicit "Use Photo" (keep the full image — Android does this via the back gesture) and a
/// "Discard". `onUse` keeps the current file; Crop/Select refine it further.
struct PreviewView: View {
    let imageURL: URL
    var onCrop: () -> Void = {}
    var onSelect: () -> Void = {}
    var onUse: () -> Void = {}
    var onDiscard: () -> Void = {}

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            AsyncImage(url: imageURL) { phase in
                if let image = phase.image {
                    image.resizable().scaledToFit()
                } else {
                    ProgressView().tint(.white)
                }
            }
            .padding()

            VStack {
                HStack {
                    Button(role: .destructive, action: onDiscard) {
                        Label("Discard", systemImage: "trash")
                    }
                    Spacer()
                    Button(action: onUse) {
                        Label("Use Photo", systemImage: "checkmark")
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding()

                Spacer()

                HStack(spacing: 16) {
                    Button(action: onCrop) {
                        Label("Crop", systemImage: "crop").frame(maxWidth: .infinity)
                    }
                    Button(action: onSelect) {
                        Label("Select", systemImage: "lasso").frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.bordered)
                .tint(.white)
                .padding()
            }
        }
    }
}
