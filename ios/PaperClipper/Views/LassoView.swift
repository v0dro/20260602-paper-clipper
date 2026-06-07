import SwiftUI

/// Freeform "lasso" selection. Mirrors Android's LassoScreen (Compose Canvas + drag gesture, then
/// mask the bitmap to the path and save a transparent PNG).
///
/// PARTIAL: the drag-to-draw path capture + live overlay are implemented below. Still TODO:
/// map the on-screen points to image pixels, clip a CGContext to the CGPath, render the masked
/// region to a new PNG in the clippings dir, delete the original, and return. See ANDROID_TO_IOS.md.
struct LassoView: View {
    let imageURL: URL
    var onSelected: (String) -> Void = { _ in }   // new file name
    var onCancel: () -> Void = {}

    @State private var points: [CGPoint] = []

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button("Cancel", action: onCancel)
                Spacer()
                Button("Done") {
                    // TODO(parity): mask the image to `points` and save a PNG, then onSelected(name).
                }
                .disabled(points.count < 3)
            }
            .padding()

            GeometryReader { _ in
                ZStack {
                    Color.black
                    AsyncImage(url: imageURL) { $0.resizable().scaledToFit() } placeholder: { ProgressView() }
                    Canvas { ctx, _ in
                        guard points.count > 1 else { return }
                        var path = Path()
                        path.move(to: points[0])
                        for p in points.dropFirst() { path.addLine(to: p) }
                        path.closeSubpath()
                        ctx.fill(path, with: .color(.white.opacity(0.25)))
                        ctx.stroke(path, with: .color(.white), lineWidth: 3)
                    }
                }
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            if value.translation == .zero { points = [value.location] }
                            else { points.append(value.location) }
                        }
                )
            }
        }
    }
}
