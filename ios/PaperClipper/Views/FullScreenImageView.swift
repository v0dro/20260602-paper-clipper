import SwiftUI

/// Full-screen image viewer with pinch-to-zoom, pan (clamped to bounds) and double-tap to zoom.
/// Mirrors Android's `ImageViewerScreen`.
struct FullScreenImageView: View {
    let imageURL: URL
    var onClose: () -> Void = {}

    @State private var scale: CGFloat = 1
    @State private var lastScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                Color.black.ignoresSafeArea()

                AsyncImage(url: imageURL) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFit()
                    } else {
                        ProgressView().tint(.white)
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height)
                .scaleEffect(scale)
                .offset(offset)
                .gesture(
                    SimultaneousGesture(
                        MagnificationGesture()
                            .onChanged { value in
                                scale = min(max(lastScale * value, 1), 5)
                                offset = clamp(offset, scale: scale, in: geo.size)
                            }
                            .onEnded { _ in lastScale = scale },
                        DragGesture()
                            .onChanged { value in
                                guard scale > 1 else { return }
                                let proposed = CGSize(
                                    width: lastOffset.width + value.translation.width,
                                    height: lastOffset.height + value.translation.height
                                )
                                offset = clamp(proposed, scale: scale, in: geo.size)
                            }
                            .onEnded { _ in lastOffset = offset }
                    )
                )
                .onTapGesture(count: 2) {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        if scale > 1 {
                            scale = 1; lastScale = 1; offset = .zero; lastOffset = .zero
                        } else {
                            scale = 2.5; lastScale = 2.5
                        }
                    }
                }

                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .foregroundStyle(.white)
                        .padding(12)
                        .background(.black.opacity(0.4), in: Circle())
                }
                .padding()
                .accessibilityLabel("Close")
            }
        }
    }

    /// Clamps `proposed` so the zoomed image can't be panned past its edges.
    private func clamp(_ proposed: CGSize, scale: CGFloat, in size: CGSize) -> CGSize {
        let maxX = size.width * (scale - 1) / 2
        let maxY = size.height * (scale - 1) / 2
        return CGSize(
            width: min(max(proposed.width, -maxX), maxX),
            height: min(max(proposed.height, -maxY), maxY)
        )
    }
}
