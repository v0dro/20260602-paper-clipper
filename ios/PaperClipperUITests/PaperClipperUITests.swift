import XCTest

/// End-to-end UI flows over the real app, seeded deterministically via `-uiTestSeed` (in-memory
/// store + fixed sample clippings). Mirrors the intent of Android's Compose UI tests
/// (HomeScreen / DetailScreen / FilterDialog) at the app level.
final class PaperClipperUITests: XCTestCase {

    private let appleSummary = "Apple pie recipe wins the county fair"
    private let teamSummary = "Local team clinches the championship"

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launchSeeded() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestSeed"]
        app.launch()
        return app
    }

    private func text(containing substring: String) -> NSPredicate {
        NSPredicate(format: "label CONTAINS %@", substring)
    }

    func testLaunchShowsSeededLibraryAndCaptureButton() {
        let app = launchSeeded()
        XCTAssertTrue(app.staticTexts[appleSummary].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts[teamSummary].exists)
        XCTAssertTrue(app.buttons["takePhotoButton"].exists)
    }

    func testSearchFiltersOutNonMatchingClippings() {
        let app = launchSeeded()
        XCTAssertTrue(app.staticTexts[teamSummary].waitForExistence(timeout: 15))

        let search = app.searchFields.firstMatch
        search.tap()
        search.typeText("Apple")

        // The matching clipping's text remains; the non-matching one is filtered out.
        XCTAssertTrue(app.staticTexts.element(matching: text(containing: "Apple pie recipe wins")).waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts[teamSummary].exists)
    }

    func testOpenDetailShowsSummaryAndExtractedText() {
        let app = launchSeeded()
        let apple = app.staticTexts[appleSummary]
        XCTAssertTrue(apple.waitForExistence(timeout: 15))
        apple.tap()

        XCTAssertTrue(app.staticTexts["Summary"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Extracted text"].exists)
        XCTAssertTrue(app.staticTexts.element(matching: text(containing: "Apple pie recipe wins the county fair this year")).exists)
    }

    func testAddTagAndComment() {
        let app = launchSeeded()
        let apple = app.staticTexts[appleSummary]
        XCTAssertTrue(apple.waitForExistence(timeout: 15))
        apple.tap()

        let tagField = app.textFields["newTagField"]
        XCTAssertTrue(tagField.waitForExistence(timeout: 5))
        tagField.tap()
        tagField.typeText("News")
        app.buttons["addTagButton"].tap()
        XCTAssertTrue(app.staticTexts["News"].waitForExistence(timeout: 5))

        let commentField = app.textFields["newCommentField"]
        commentField.tap()
        commentField.typeText("great clipping")
        app.buttons["addCommentButton"].tap()
        XCTAssertTrue(app.staticTexts["great clipping"].waitForExistence(timeout: 5))
    }

    func testMenuShowsActions() {
        let app = launchSeeded()
        XCTAssertTrue(app.staticTexts[appleSummary].waitForExistence(timeout: 15))

        app.buttons["Open menu"].tap()
        XCTAssertTrue(app.buttons["Export"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Clear all"].exists)
        XCTAssertTrue(app.buttons["Give feedback"].exists)
        XCTAssertTrue(app.buttons["Log in"].exists)
    }

    func testSortMenuTogglesWithoutLosingClippings() {
        let app = launchSeeded()
        XCTAssertTrue(app.staticTexts[appleSummary].waitForExistence(timeout: 15))

        app.buttons["Filter"].tap()
        app.buttons["Date — oldest first"].tap()

        // Both clippings still present after re-sorting.
        XCTAssertTrue(app.staticTexts[appleSummary].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts[teamSummary].exists)
    }
}
