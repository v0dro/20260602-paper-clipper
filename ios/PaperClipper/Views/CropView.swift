import SwiftUI
import UIKit

/// Rectangular crop. Mirrors Android's `CropScreen` (which used the Cropify library): a draggable
/// crop rectangle with four corner handles over the fitted image; on "Done" the underlying image is
/// cropped to the selection, written as a new JPEG, the original deleted, and the new name returned.
struct CropView: View {
    let imageURL: URL
    var onCropped: (String) -> Void = { _ in }
    var onCancel: () -> Void = {}

    @State private var image: UIImage?
    /// Crop rect normalized (0…1) within the displayed image.
    @State private var crop = CGRect(x: 0.08, y: 0.08, width: 0.84, height: 0.84)

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button("Cancel", action: onCancel)
                Spacer()
                Button("Done") { applyCrop() }.disabled(image == nil)
            }
            .padding()
            .foregroundStyle(.white)

            GeometryReader { geo in
                ZStack {
                    if let image {
                        let frame = Self.fittedRect(imageSize: image.size, in: geo.size)
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                        CropOverlay(crop: $crop, imageFrame: frame)
                    } else {
                        ProgressView().tint(.white)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
            .padding(24)
        }
        .background(Color.black.ignoresSafeArea())
        .task { if image == nil { image = ImageProcessing.loadDownsampled(imageURL) } }
    }

    private func applyCrop() {
        guard let image, let cropped = ImageProcessing.crop(image, normalizedRect: crop),
              let data = cropped.jpegData(compressionQuality: 0.95) else { return }
        let name = ClippingStore.newFileName(ext: "jpg")
        guard (try? ClippingStore.save(data, name: name)) != nil else { return }
        ClippingStore.deleteFile(named: imageURL.lastPathComponent)
        onCropped(name)
    }

    /// The aspect-fit rect of an image inside `container` (centered) — matches `scaledToFit`.
    static func fittedRect(imageSize: CGSize, in container: CGSize) -> CGRect {
        guard imageSize.width > 0, imageSize.height > 0, container.width > 0, container.height > 0 else {
            return CGRect(origin: .zero, size: container)
        }
        let scale = min(container.width / imageSize.width, container.height / imageSize.height)
        let w = imageSize.width * scale
        let h = imageSize.height * scale
        return CGRect(x: (container.width - w) / 2, y: (container.height - h) / 2, width: w, height: h)
    }
}

/// The dim mask, border and four corner handles for [CropView]. `crop` is normalized within
/// `imageFrame` (both in the container's coordinate space).
private struct CropOverlay: View {
    @Binding var crop: CGRect
    let imageFrame: CGRect

    private let minSize: CGFloat = 0.12

    var body: some View {
        let r = viewRect
        ZStack(alignment: .topLeading) {
            // Dim everything outside the crop rect (even-odd hole).
            Canvas { context, size in
                var path = Path(CGRect(origin: .zero, size: size))
                path.addRect(r)
                context.fill(path, with: .color(.black.opacity(0.5)), style: FillStyle(eoFill: true))
                context.stroke(Path(r), with: .color(.white), lineWidth: 2)
            }
            .allowsHitTesting(false)

            // Move the whole rect.
            Rectangle()
                .fill(Color.white.opacity(0.001))
                .frame(width: max(r.width, 1), height: max(r.height, 1))
                .position(x: r.midX, y: r.midY)
                .gesture(moveGesture)

            handle(at: CGPoint(x: r.minX, y: r.minY)) { translate(dx: $0, dy: $1, edges: [.left, .top]) }
            handle(at: CGPoint(x: r.maxX, y: r.minY)) { translate(dx: $0, dy: $1, edges: [.right, .top]) }
            handle(at: CGPoint(x: r.minX, y: r.maxY)) { translate(dx: $0, dy: $1, edges: [.left, .bottom]) }
            handle(at: CGPoint(x: r.maxX, y: r.maxY)) { translate(dx: $0, dy: $1, edges: [.right, .bottom]) }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private var viewRect: CGRect {
        CGRect(
            x: imageFrame.minX + crop.minX * imageFrame.width,
            y: imageFrame.minY + crop.minY * imageFrame.height,
            width: crop.width * imageFrame.width,
            height: crop.height * imageFrame.height
        )
    }

    private struct Edge: OptionSet { let rawValue: Int
        static let left = Edge(rawValue: 1), right = Edge(rawValue: 2), top = Edge(rawValue: 4), bottom = Edge(rawValue: 8)
    }

    @State private var moveLast: CGSize = .zero

    private var moveGesture: some Gesture {
        DragGesture()
            .onChanged { value in
                let dx = (value.translation.width - moveLast.width) / imageFrame.width
                let dy = (value.translation.height - moveLast.height) / imageFrame.height
                moveLast = value.translation
                var newX = crop.minX + dx
                var newY = crop.minY + dy
                newX = min(max(0, newX), 1 - crop.width)
                newY = min(max(0, newY), 1 - crop.height)
                crop.origin = CGPoint(x: newX, y: newY)
            }
            .onEnded { _ in moveLast = .zero }
    }

    private func handle(at point: CGPoint, onDrag: @escaping (CGFloat, CGFloat) -> Void) -> some View {
        Circle()
            .fill(Color.white)
            .frame(width: 26, height: 26)
            .position(point)
            .gesture(
                DragGesture()
                    .onChanged { value in
                        let dx = (value.translation.width - cornerLast.width) / imageFrame.width
                        let dy = (value.translation.height - cornerLast.height) / imageFrame.height
                        cornerLast = value.translation
                        onDrag(dx, dy)
                    }
                    .onEnded { _ in cornerLast = .zero }
            )
    }

    @State private var cornerLast: CGSize = .zero

    private func translate(dx: CGFloat, dy: CGFloat, edges: Edge) {
        var minX = crop.minX, minY = crop.minY, maxX = crop.maxX, maxY = crop.maxY
        if edges.contains(.left) { minX = min(max(0, minX + dx), maxX - minSize) }
        if edges.contains(.right) { maxX = max(min(1, maxX + dx), minX + minSize) }
        if edges.contains(.top) { minY = min(max(0, minY + dy), maxY - minSize) }
        if edges.contains(.bottom) { maxY = max(min(1, maxY + dy), minY + minSize) }
        crop = CGRect(x: minX, y: minY, width: maxX - minX, height: maxY - minY)
    }
}
