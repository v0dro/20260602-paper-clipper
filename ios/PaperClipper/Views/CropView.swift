import SwiftUI

/// Rectangular crop. Mirrors Android's CropScreen (which used the Cropify Compose library).
///
/// TODO(parity): implement a draggable crop rectangle over the image (4 corner handles), then crop
/// the underlying `UIImage` to the selected rect on "Done", write a new JPEG to the clippings dir,
/// delete the original, and return. No off-the-shelf lib needed — a SwiftUI overlay with
/// DragGestures on corner handles + `CGImage.cropping(to:)` covers it. See ANDROID_TO_IOS.md.
struct CropView: View {
    let imageURL: URL
    var onCropped: (String) -> Void = { _ in }   // new file name
    var onCancel: () -> Void = {}

    var body: some View {
        ContentUnavailableView(
            "Crop — coming soon",
            systemImage: "crop",
            description: Text("Rectangular crop is not implemented yet (see ANDROID_TO_IOS.md).")
        )
    }
}
