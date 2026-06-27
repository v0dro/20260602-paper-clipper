import XCTest
import SwiftData
@testable import PaperClipper

/// The ZIP/HTML/JSON exporter, including HTML escaping of user/AI content. Mirrors the `exportTo`
/// test in Android's `ClippingsRepositoryTest`.
@MainActor
final class ClippingExporterTests: XCTestCase {

    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }
    private var vm: ClippingsViewModel!

    override func setUpWithError() throws {
        container = try SwiftDataTestSupport.makeContainer()
        SwiftDataTestSupport.cleanClippingsDir()
        vm = ClippingsViewModel(context: context)
    }

    override func tearDown() {
        SwiftDataTestSupport.cleanClippingsDir()
        vm = nil
        container = nil
    }

    func testHtmlEscapeMatchesAndroid() {
        XCTAssertEqual(
            ClippingExporter.htmlEscape("Tom & \"Jerry\" <b>"),
            "Tom &amp; &quot;Jerry&quot; &lt;b&gt;"
        )
    }

    func testExportWritesImagesMetadataAndEscapedHtml() throws {
        let imageBytes: [UInt8] = [0x9, 0x8, 0x7]
        SwiftDataTestSupport.writeImage("p.jpg", bytes: imageBytes)

        let clip = Clipping(fileName: "p.jpg", createdAt: Date(timeIntervalSince1970: 1), status: .success)
        clip.extractedText = "the text"
        clip.summary = "Tom & \"Jerry\" <b>"
        context.insert(clip)
        vm.createAndAssignTag(named: "<news>", to: clip)
        vm.addComment("great & <stuff>", to: clip)

        let entries = ZipTestSupport.readStoredZip(vm.exportData())

        // Image is included verbatim.
        XCTAssertEqual(entries["images/p.jpg"], Data(imageBytes))

        // metadata.json is valid and carries the structured data (raw, unescaped).
        let meta = try JSONSerialization.jsonObject(with: try XCTUnwrap(entries["metadata.json"])) as? [[String: Any]]
        let obj = try XCTUnwrap(meta?.first)
        XCTAssertEqual(obj["fileName"] as? String, "p.jpg")
        XCTAssertEqual(obj["status"] as? String, "SUCCESS")
        XCTAssertEqual(obj["summary"] as? String, "Tom & \"Jerry\" <b>")
        XCTAssertEqual(obj["extractedText"] as? String, "the text")
        XCTAssertEqual((obj["tags"] as? [String])?.first, "<news>")
        let comments = obj["comments"] as? [[String: Any]]
        XCTAssertEqual(comments?.first?["text"] as? String, "great & <stuff>")

        // index.html HTML-escapes user/AI content (no raw angle brackets from our data leak through).
        let html = try XCTUnwrap(String(data: try XCTUnwrap(entries["index.html"]), encoding: .utf8))
        XCTAssertTrue(html.contains("Tom &amp; &quot;Jerry&quot; &lt;b&gt;"))
        XCTAssertTrue(html.contains("&lt;news&gt;"))
        XCTAssertTrue(html.contains("great &amp; &lt;stuff&gt;"))
        XCTAssertFalse(html.contains("<b>"))
        XCTAssertFalse(html.contains("<news>"))
    }

    func testExportSkipsWhitespaceOnlySummaryAndTextInHtml() throws {
        SwiftDataTestSupport.writeImage("w.jpg")
        let clip = Clipping(fileName: "w.jpg", createdAt: Date(timeIntervalSince1970: 1), status: .success)
        clip.summary = "   "          // whitespace only
        clip.extractedText = "  \n "  // whitespace only
        context.insert(clip)
        try context.save()

        let entries = ZipTestSupport.readStoredZip(vm.exportData())
        let html = try XCTUnwrap(String(data: try XCTUnwrap(entries["index.html"]), encoding: .utf8))
        // Matches Android's isNullOrBlank(): whitespace-only fields are not rendered.
        XCTAssertFalse(html.contains("<h3>Summary</h3>"))
        XCTAssertFalse(html.contains("<h3>Extracted text</h3>"))
    }

    func testExportOrdersClippingsNewestFirst() throws {
        SwiftDataTestSupport.writeImage("old.jpg")
        SwiftDataTestSupport.writeImage("new.jpg")
        let old = Clipping(fileName: "old.jpg", createdAt: Date(timeIntervalSince1970: 1000), status: .success)
        let new = Clipping(fileName: "new.jpg", createdAt: Date(timeIntervalSince1970: 5000), status: .success)
        context.insert(old)
        context.insert(new)
        try context.save()

        let entries = ZipTestSupport.readStoredZip(vm.exportData())
        let meta = try JSONSerialization.jsonObject(with: try XCTUnwrap(entries["metadata.json"])) as? [[String: Any]]
        XCTAssertEqual(meta?.map { $0["fileName"] as? String }, ["new.jpg", "old.jpg"])
    }
}
