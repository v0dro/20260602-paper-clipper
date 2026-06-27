import XCTest
import SwiftData
@testable import PaperClipper

/// Tag/comment CRUD, deletion semantics (global tags survive) and clear-all, driven through the
/// ViewModel over an in-memory store. Mirrors the tag/comment/delete/clearAll parts of Android's
/// `ClippingsRepositoryTest` + `TagDaoTest` + `CommentDaoTest`.
@MainActor
final class ClippingsViewModelTests: XCTestCase {

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

    private func insertClipping(_ name: String, withFile: Bool = false) -> Clipping {
        if withFile { SwiftDataTestSupport.writeImage(name) }
        let clip = Clipping(fileName: name, createdAt: Date(timeIntervalSince1970: 1), status: .success)
        context.insert(clip)
        try? context.save()
        return clip
    }

    private func allTags() throws -> [Tag] { try context.fetch(FetchDescriptor<Tag>()) }
    private func allComments() throws -> [Comment] { try context.fetch(FetchDescriptor<Comment>()) }
    private func allClippings() throws -> [Clipping] { try context.fetch(FetchDescriptor<Clipping>()) }

    // MARK: - tags

    func testCreateAndAssignTagTrimsDeduplicatesAndLinks() throws {
        let clip = insertClipping("a.jpg")
        vm.createAndAssignTag(named: "  Travel  ", to: clip)
        vm.createAndAssignTag(named: "Travel", to: clip) // exact duplicate -> no new global tag

        XCTAssertEqual(clip.tags.map(\.name), ["Travel"])
        XCTAssertEqual(try allTags().count, 1)
    }

    func testCreateAndAssignTagBlankNameIsNoOp() throws {
        let clip = insertClipping("a.jpg")
        vm.createAndAssignTag(named: "   ", to: clip)
        XCTAssertTrue(try allTags().isEmpty)
        XCTAssertTrue(clip.tags.isEmpty)
    }

    func testToggleAddsThenRemovesLink() throws {
        let source = insertClipping("src.jpg")
        vm.createAndAssignTag(named: "Food", to: source)
        let food = try XCTUnwrap(try allTags().first)

        let target = insertClipping("b.jpg")
        vm.toggle(food, on: target)
        XCTAssertEqual(target.tags.map(\.name), ["Food"])

        vm.toggle(food, on: target)
        XCTAssertTrue(target.tags.isEmpty)
        // The global tag is untouched by toggling.
        XCTAssertEqual(try allTags().count, 1)
    }

    // MARK: - comments

    func testAddCommentTrimsAndDeleteRemoves() throws {
        let clip = insertClipping("a.jpg")
        vm.addComment("  hello  ", to: clip)
        vm.addComment("   ", to: clip) // blank -> no-op

        let comments = try allComments()
        XCTAssertEqual(comments.count, 1)
        XCTAssertEqual(comments[0].text, "hello")

        vm.deleteComment(comments[0])
        XCTAssertTrue(try allComments().isEmpty)
    }

    // MARK: - delete / clearAll

    func testDeleteRemovesClippingFilesCommentsButKeepsGlobalTags() throws {
        let clip = insertClipping("d.jpg", withFile: true)
        vm.createAndAssignTag(named: "Keep", to: clip)
        vm.addComment("a comment", to: clip)

        vm.delete(clip)

        XCTAssertFalse(FileManager.default.fileExists(atPath: ClippingStore.fileURL("d.jpg").path))
        XCTAssertTrue(try allClippings().isEmpty)
        XCTAssertTrue(try allComments().isEmpty)
        // The global tag survives a clipping deletion.
        XCTAssertEqual(try allTags().map(\.name), ["Keep"])
    }

    func testMultiSelectDeleteByFileNames() throws {
        _ = insertClipping("a.jpg", withFile: true)
        _ = insertClipping("b.jpg", withFile: true)

        vm.delete(fileNames: ["a.jpg"])

        XCTAssertEqual(try allClippings().map(\.fileName), ["b.jpg"])
        XCTAssertFalse(FileManager.default.fileExists(atPath: ClippingStore.fileURL("a.jpg").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: ClippingStore.fileURL("b.jpg").path))
    }

    func testClearAllWipesFilesRowsTagsAndComments() throws {
        let clip = insertClipping("e.jpg", withFile: true)
        vm.createAndAssignTag(named: "Tag", to: clip)
        vm.addComment("comment", to: clip)

        vm.clearAll()

        XCTAssertFalse(FileManager.default.fileExists(atPath: ClippingStore.fileURL("e.jpg").path))
        XCTAssertTrue(try allClippings().isEmpty)
        XCTAssertTrue(try allTags().isEmpty)
        XCTAssertTrue(try allComments().isEmpty)
    }

    func testRetrySetsStatusToPending() throws {
        let clip = insertClipping("r.jpg", withFile: true)
        clip.status = .error
        clip.errorMessage = "boom"
        try context.save()

        vm.retry(clip)

        // retry flips the row back to pending synchronously (analysis happens asynchronously after).
        XCTAssertEqual(clip.status, .pending)
    }
}
