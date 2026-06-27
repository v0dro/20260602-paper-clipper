import XCTest
@testable import PaperClipper

/// Contract tests for the persisted enum + model defaults. `ClippingStatus` is stored in SwiftData as
/// a raw string, so reordering/renaming its cases would silently corrupt existing rows — this freezes
/// the set and the default field values. Mirrors Android's `EntitiesContractTest`.
final class ModelsContractTests: XCTestCase {

    func testClippingStatusHasExactlyTheExpectedValues() {
        XCTAssertEqual(ClippingStatus.allCases.map(\.rawValue), ["pending", "processing", "success", "error"])
    }

    func testClippingDefaultsToPendingWithNilAnalysisFields() {
        let clip = Clipping(fileName: "a.jpg", createdAt: Date(timeIntervalSince1970: 1))
        XCTAssertEqual(clip.status, .pending)
        XCTAssertNil(clip.extractedText)
        XCTAssertNil(clip.summary)
        XCTAssertNil(clip.errorMessage)
        XCTAssertNil(clip.model)
        XCTAssertNil(clip.processedAt)
        XCTAssertTrue(clip.tags.isEmpty)
        XCTAssertTrue(clip.comments.isEmpty)
    }

    func testStatusComputedPropertyRoundTrips() {
        let clip = Clipping(fileName: "a.jpg")
        clip.status = .success
        XCTAssertEqual(clip.statusRaw, "success")
        clip.statusRaw = "error"
        XCTAssertEqual(clip.status, .error)
        // Unknown raw values fall back to pending (matches Android's valueOf fallback).
        clip.statusRaw = "NOT_A_STATUS"
        XCTAssertEqual(clip.status, .pending)
    }
}
