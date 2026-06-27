import XCTest
import UIKit
@testable import PaperClipper

/// Tests the crop/lasso pixel math (mirrors Android's saveCrop / saveLasso geometry).
final class ImageProcessingTests: XCTestCase {

    private func solidImage(_ size: CGSize, color: UIColor = .red) -> UIImage {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            color.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
        }
    }

    func testCropProducesExpectedSubrectSize() {
        let image = solidImage(CGSize(width: 100, height: 100))
        let cropped = ImageProcessing.crop(image, normalizedRect: CGRect(x: 0.25, y: 0.25, width: 0.5, height: 0.5))
        let result = try? XCTUnwrap(cropped)
        XCTAssertEqual(result?.cgImage?.width, 50)
        XCTAssertEqual(result?.cgImage?.height, 50)
    }

    func testCropFullRectReturnsFullImage() {
        let image = solidImage(CGSize(width: 80, height: 60))
        let cropped = ImageProcessing.crop(image, normalizedRect: CGRect(x: 0, y: 0, width: 1, height: 1))
        XCTAssertEqual(cropped?.cgImage?.width, 80)
        XCTAssertEqual(cropped?.cgImage?.height, 60)
    }

    func testLassoMaskRequiresAtLeastThreePoints() {
        let image = solidImage(CGSize(width: 100, height: 100))
        XCTAssertNil(ImageProcessing.lassoMask(image, normalizedPoints: [CGPoint(x: 0, y: 0), CGPoint(x: 0.5, y: 0.5)]))
    }

    func testLassoMaskProducesBoundedTransparentImage() {
        let image = solidImage(CGSize(width: 100, height: 100))
        // A triangle over the top-left quadrant.
        let points = [CGPoint(x: 0, y: 0), CGPoint(x: 0.5, y: 0), CGPoint(x: 0, y: 0.5)]
        let masked = ImageProcessing.lassoMask(image, normalizedPoints: points)
        let result = try? XCTUnwrap(masked)
        // Cropped to the path's bounding box (~50x50), and carries an alpha channel for the cut-out.
        XCTAssertEqual(result?.cgImage?.width, 50)
        XCTAssertEqual(result?.cgImage?.height, 50)
        let alpha = result?.cgImage?.alphaInfo
        XCTAssertTrue(alpha != .none && alpha != .noneSkipLast && alpha != .noneSkipFirst)
    }

    func testLassoMaskClampsPointsDrawnOutsideImage() {
        let image = solidImage(CGSize(width: 100, height: 100))
        // Points extend beyond [0,1]; the result must still be bounded by the image.
        let points = [CGPoint(x: -0.5, y: -0.5), CGPoint(x: 1.5, y: -0.5), CGPoint(x: 0.5, y: 1.5)]
        let masked = ImageProcessing.lassoMask(image, normalizedPoints: points)
        let result = try? XCTUnwrap(masked)
        XCTAssertLessThanOrEqual(result?.cgImage?.width ?? 999, 100)
        XCTAssertLessThanOrEqual(result?.cgImage?.height ?? 999, 100)
    }
}
