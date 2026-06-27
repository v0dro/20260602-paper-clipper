import UIKit
import ImageIO

/// Pixel-level image operations for the crop and lasso flows. Mirrors Android's `saveCrop`,
/// `saveLasso` and `applyExifOrientation` in `MainActivity.kt`. All inputs are normalized (0…1)
/// coordinates in the *displayed, upright* image so the views don't have to reason about EXIF
/// orientation or pixel scale.
enum ImageProcessing {
    /// Loads an image downsampled so its longest edge is at most `maxDim` px, to bound memory —
    /// mirrors Android's `decodeSampledBitmap(file, 2048)`. The thumbnail is created with the EXIF
    /// transform applied, so the result is already upright.
    static func loadDownsampled(_ url: URL, maxDim: CGFloat = 2048) -> UIImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else {
            return UIImage(contentsOfFile: url.path)
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxDim,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            return UIImage(contentsOfFile: url.path)
        }
        return UIImage(cgImage: cg)
    }

    /// Redraws the image with `.up` orientation at full pixel resolution, so its `CGImage` pixels
    /// line up with what the user saw. Replaces Android's EXIF-matrix step.
    static func normalizedUp(_ image: UIImage) -> UIImage {
        if image.imageOrientation == .up && image.scale == 1 { return image }
        let pixelSize = CGSize(width: image.size.width * image.scale,
                               height: image.size.height * image.scale)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: pixelSize, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: pixelSize))
        }
    }

    /// Crops to `normalizedRect` (0…1 of the upright image, origin top-left). Mirrors `saveCrop`.
    static func crop(_ image: UIImage, normalizedRect: CGRect) -> UIImage? {
        let up = normalizedUp(image)
        guard let cg = up.cgImage else { return nil }
        let w = CGFloat(cg.width), h = CGFloat(cg.height)
        let rect = CGRect(
            x: normalizedRect.minX * w,
            y: normalizedRect.minY * h,
            width: normalizedRect.width * w,
            height: normalizedRect.height * h
        ).integral
        let bounded = rect.intersection(CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
        guard !bounded.isNull, bounded.width >= 1, bounded.height >= 1,
              let cropped = cg.cropping(to: bounded) else { return nil }
        return UIImage(cgImage: cropped)
    }

    /// Masks the image to the freeform `normalizedPoints` (0…1 of the upright image) and returns the
    /// cropped-to-bounds result with everything outside the path transparent. Mirrors `saveLasso`.
    static func lassoMask(_ image: UIImage, normalizedPoints points: [CGPoint]) -> UIImage? {
        guard points.count >= 3 else { return nil }
        let up = normalizedUp(image)
        guard let cg = up.cgImage else { return nil }
        let w = CGFloat(cg.width), h = CGFloat(cg.height)

        // Clamp each point to the image bounds (points may be drawn into the padding), mirroring the
        // per-point coerceIn in Android's saveLasso.
        let path = CGMutablePath()
        let pixelPoints = points.map { point in
            CGPoint(x: min(max(point.x * w, 0), w), y: min(max(point.y * h, 0), h))
        }
        path.move(to: pixelPoints[0])
        for p in pixelPoints.dropFirst() { path.addLine(to: p) }
        path.closeSubpath()

        let box = path.boundingBox
        let left = max(0, floor(box.minX))
        let top = max(0, floor(box.minY))
        let width = min(w - left, ceil(box.width))
        let height = min(h - top, ceil(box.height))
        guard width >= 1, height >= 1 else { return nil }

        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = false
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format)
        return renderer.image { ctx in
            let cgContext = ctx.cgContext
            cgContext.translateBy(x: -left, y: -top)
            cgContext.addPath(path)
            cgContext.clip()
            up.draw(in: CGRect(x: 0, y: 0, width: w, height: h))
        }
    }
}
