import SwiftUI

/// Full-screen review of a freshly captured image with Crop / Select actions.
/// Mirrors Android's PreviewScreen. STUB: wire into the capture flow when Crop/Lasso land.
struct PreviewView: View {
    let imageURL: URL
    var onCrop: () -> Void = {}
    var onSelect: () -> Void = {}

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            AsyncImage(url: imageURL) { $0.resizable().scaledToFit() } placeholder: { ProgressView() }
            HStack {
                Button("Crop", action: onCrop)
                Spacer()
                Button("Select", action: onSelect)  // -> LassoView
            }
            .padding()
            .buttonStyle(.borderedProminent)
        }
    }
}
