import XCTest
import SwiftData
@testable import PaperClipper

/// The reconcile/analyze pipeline with a stubbed analyze call: disk<->DB reconciliation, success and
/// error recording, pruning of rows whose files vanished, and reprocessing pending rows (the retry
/// path). Mirrors the reconcile/process parts of Android's `ClippingsRepositoryTest`.
@MainActor
final class ClippingStoreTests: XCTestCase {

    private var container: ModelContainer!
    private var context: ModelContext { container.mainContext }

    private let stubSuccess: AnalyzeFunction = { _, _, _ in .success(extractedText: "extracted", summary: "summary", heading: "Big Headline") }
    private let stubFailure: AnalyzeFunction = { _, _, _ in .failure("boom") }

    override func setUpWithError() throws {
        container = try SwiftDataTestSupport.makeContainer()
        SwiftDataTestSupport.cleanClippingsDir()
    }

    override func tearDown() {
        SwiftDataTestSupport.cleanClippingsDir()
        container = nil
    }

    private func allClippings() throws -> [Clipping] {
        try context.fetch(FetchDescriptor<Clipping>())
    }

    func testReconcileInsertsNewFilesAndAnalyzesThemToSuccess() async throws {
        SwiftDataTestSupport.writeImage("new.jpg")

        await ClippingStore.reconcileAndProcess(context: context, analyze: stubSuccess)

        let rows = try allClippings()
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0].fileName, "new.jpg")
        XCTAssertEqual(rows[0].status, .success)
        XCTAssertEqual(rows[0].extractedText, "extracted")
        XCTAssertEqual(rows[0].summary, "summary")
        XCTAssertEqual(rows[0].heading, "Big Headline")
    }

    func testReconcileEmptyHeadingStoredAsNil() async throws {
        SwiftDataTestSupport.writeImage("nohead.jpg")
        let stub: AnalyzeFunction = { _, _, _ in .success(extractedText: "x", summary: "y", heading: "") }

        await ClippingStore.reconcileAndProcess(context: context, analyze: stub)

        let row = try XCTUnwrap(try allClippings().first)
        XCTAssertEqual(row.status, .success)
        XCTAssertNil(row.heading)
    }

    func testReconcileAnalysisErrorIsRecorded() async throws {
        SwiftDataTestSupport.writeImage("bad.jpg")

        await ClippingStore.reconcileAndProcess(context: context, analyze: stubFailure)

        let row = try XCTUnwrap(try allClippings().first)
        XCTAssertEqual(row.status, .error)
        XCTAssertEqual(row.errorMessage, "boom")
        XCTAssertNil(row.summary)
    }

    func testReconcileDeletesRowsWhoseFilesAreGone() async throws {
        let ghost = Clipping(fileName: "ghost.jpg", createdAt: Date(timeIntervalSince1970: 1), status: .success)
        context.insert(ghost)
        try context.save()

        await ClippingStore.reconcileAndProcess(context: context, analyze: stubSuccess)

        XCTAssertTrue(try allClippings().isEmpty)
    }

    func testProcessPendingReprocessesPendingClippingWithFile() async throws {
        SwiftDataTestSupport.writeImage("z.jpg")
        let clip = Clipping(fileName: "z.jpg", createdAt: Date(timeIntervalSince1970: 1), status: .error)
        clip.errorMessage = "old failure"
        context.insert(clip)
        clip.status = .pending // simulate retry
        try context.save()

        await ClippingStore.processPending(context: context, analyze: stubSuccess)

        let row = try XCTUnwrap(try allClippings().first)
        XCTAssertEqual(row.status, .success)
        XCTAssertNil(row.errorMessage)
    }

    func testProcessPendingPrunesPendingRowWhenFileMissing() async throws {
        let gone = Clipping(fileName: "gone.jpg", createdAt: Date(timeIntervalSince1970: 1), status: .pending)
        context.insert(gone)
        try context.save()

        await ClippingStore.processPending(context: context, analyze: stubSuccess)

        XCTAssertTrue(try allClippings().isEmpty)
    }

    func testReconcileKeepsExistingSuccessRowsWithMatchingFiles() async throws {
        SwiftDataTestSupport.writeImage("keep.jpg")
        let clip = Clipping(fileName: "keep.jpg", createdAt: Date(timeIntervalSince1970: 1), status: .success)
        clip.summary = "already analyzed"
        context.insert(clip)
        try context.save()

        // stubFailure would overwrite to error if it re-analyzed — it must not, since this is success.
        await ClippingStore.reconcileAndProcess(context: context, analyze: stubFailure)

        let row = try XCTUnwrap(try allClippings().first)
        XCTAssertEqual(row.status, .success)
        XCTAssertEqual(row.summary, "already analyzed")
    }
}
