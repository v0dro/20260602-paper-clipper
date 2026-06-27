import SwiftUI
import UIKit

/// Drives the capture pipeline as a full-screen flow: camera → Preview (Crop / Select / Use) →
/// Crop or Lasso → done. Mirrors the `Screen.Preview/Crop/Lasso` navigation in Android's
/// `ClipperApp`. The captured/cropped/lassoed image is written to the clippings dir; on finish the
/// home library reconciles and analyzes it.
struct CaptureFlowView: View {
    /// Called when the flow ends. `saved` is true if an image was kept (so the library should refresh).
    let onFinished: (_ saved: Bool) -> Void

    private enum Stage: Equatable {
        case camera
        case preview(String)
        case crop(String)
        case lasso(String)
    }

    @State private var stage: Stage = .camera

    var body: some View {
        switch stage {
        case .camera:
            CameraPicker(
                onImage: { image in
                    if let name = save(image) {
                        stage = .preview(name)
                    } else {
                        onFinished(false)
                    }
                },
                onCancel: { onFinished(false) }
            )
            .ignoresSafeArea()

        case let .preview(name):
            PreviewView(
                imageURL: ClippingStore.fileURL(name),
                onCrop: { stage = .crop(name) },
                onSelect: { stage = .lasso(name) },
                onUse: { onFinished(true) },
                onDiscard: {
                    ClippingStore.deleteFile(named: name)
                    onFinished(false)
                }
            )

        case let .crop(name):
            CropView(
                imageURL: ClippingStore.fileURL(name),
                // Android's CropScreen returns straight to the library on Done (the cropped file is
                // saved + reconciled there); cancel returns to Preview.
                onCropped: { _ in onFinished(true) },
                onCancel: { stage = .preview(name) }
            )

        case let .lasso(name):
            LassoView(
                imageURL: ClippingStore.fileURL(name),
                onSelected: { _ in onFinished(true) },
                onCancel: { stage = .preview(name) }
            )
        }
    }

    /// Normalizes the captured image upright and writes it as a JPEG into the clippings dir.
    private func save(_ image: UIImage) -> String? {
        let upright = ImageProcessing.normalizedUp(image)
        guard let data = upright.jpegData(compressionQuality: 0.95) else { return nil }
        let name = ClippingStore.newFileName(ext: "jpg")
        return (try? ClippingStore.save(data, name: name)) != nil ? name : nil
    }
}
