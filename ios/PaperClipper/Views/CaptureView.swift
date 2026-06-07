import SwiftUI
import UIKit

/// Camera capture bridged from UIKit. On capture, writes a JPEG into the clippings dir and calls
/// back with its file name (nil if cancelled). Mirrors the capture step of Android's flow.
/// FOUNDATION: straight capture → save. Crop/lasso happen afterward (see CropView/LassoView).
struct CaptureView: UIViewControllerRepresentable {
    let onFinished: (String?) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = UIImagePickerController.isSourceTypeAvailable(.camera) ? .camera : .photoLibrary
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onFinished: onFinished) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onFinished: (String?) -> Void
        init(onFinished: @escaping (String?) -> Void) { self.onFinished = onFinished }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            guard let image = info[.originalImage] as? UIImage,
                  let data = image.jpegData(compressionQuality: 0.92) else {
                onFinished(nil); return
            }
            let fileName = "clipping_\(Int(Date().timeIntervalSince1970 * 1000)).jpg"
            let url = ClippingStore.fileURL(fileName)
            do {
                try data.write(to: url)
                onFinished(fileName)
            } catch {
                onFinished(nil)
            }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onFinished(nil)
        }
    }
}
