import SwiftUI
import UIKit

/// Freeform "lasso" selection. Mirrors Android's `LassoScreen`: the user drags a closed path over
/// the image; the region inside the path is kept and everything outside becomes transparent. On
/// "Done" the masked region is written as a new PNG, the original deleted, and the new name returned.
struct LassoView: View {
    let imageURL: URL
    var onSelected: (String) -> Void = { _ in }
    var onCancel: () -> Void = {}

    @State private var image: UIImage?
    /// Drawn points in the container's coordinate space.
    @State private var points: [CGPoint] = []

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button("Cancel", action: onCancel)
                Spacer()
                Button("Done") { applyLasso() }
                    .disabled(points.count < 3 || image == nil)
            }
            .padding()
            .foregroundStyle(.white)

            GeometryReader { geo in
                ZStack {
                    if let image {
                        let frame = CropView.fittedRect(imageSize: image.size, in: geo.size)
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                        Canvas { context, _ in
                            guard points.count > 1 else { return }
                            var path = Path()
                            path.move(to: points[0])
                            for p in points.dropFirst() { path.addLine(to: p) }
                            path.closeSubpath()
                            context.fill(path, with: .color(.white.opacity(0.25)))
                            context.stroke(path, with: .color(.white),
                                           style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
                        }
                        .contentShape(Rectangle())
                        .gesture(
                            DragGesture(minimumDistance: 0)
                                .onChanged { value in
                                    // Collect raw points (the path may extend into the padding, like
                                    // Android); clamping to image bounds happens at mask time.
                                    lastFrame = frame
                                    if value.translation == .zero { points = [value.location] }
                                    else { points.append(value.location) }
                                }
                        )
                        .onAppear { lastFrame = frame }
                    } else {
                        ProgressView().tint(.white)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height)
                .onChange(of: geo.size) { _, newValue in
                    if let image { lastFrame = CropView.fittedRect(imageSize: image.size, in: newValue) }
                }
            }
            .padding(24)
        }
        .background(Color.black.ignoresSafeArea())
        .task { if image == nil { image = ImageProcessing.loadDownsampled(imageURL) } }
    }

    /// The displayed image frame at the time of the last gesture (for mapping points → pixels).
    @State private var lastFrame: CGRect = .zero

    private func applyLasso() {
        guard let image, points.count >= 3, lastFrame.width > 0, lastFrame.height > 0 else { return }
        let normalized = points.map { point in
            CGPoint(
                x: (point.x - lastFrame.minX) / lastFrame.width,
                y: (point.y - lastFrame.minY) / lastFrame.height
            )
        }
        guard let masked = ImageProcessing.lassoMask(image, normalizedPoints: normalized),
              let data = masked.pngData() else { return }
        let name = ClippingStore.newFileName(ext: "png")
        guard (try? ClippingStore.save(data, name: name)) != nil else { return }
        ClippingStore.deleteFile(named: imageURL.lastPathComponent)
        onSelected(name)
    }
}
