import XCTest

/// End-to-end UI flows over the real app, seeded deterministically via `-uiTestSeed` (in-memory
/// store + fixed sample clippings). Mirrors the intent of Android's Compose UI tests
/// (HomeScreen / DetailScreen / FilterDialog) at the app level.
final class PaperClipperUITests: XCTestCase {

    private let appleHeading = "County Fair Win"
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

    func testLaunchShowsSeededLibraryAndCaptureButtons() {
        let app = launchSeeded()
        XCTAssertTrue(app.staticTexts[appleSummary].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts[teamSummary].exists)
        XCTAssertTrue(app.buttons["takePhotoButton"].exists)
        XCTAssertTrue(app.buttons["choosePhotoButton"].exists)
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

    func testOpenDetailShowsHeadingAndArticle() {
        let app = launchSeeded()
        let apple = app.staticTexts[appleSummary]
        XCTAssertTrue(apple.waitForExistence(timeout: 15))
        apple.tap()

        // The AI heading replaces the "Summary" label; "Extracted text" is now "Article".
        XCTAssertTrue(app.staticTexts[appleHeading].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Article"].exists)
        XCTAssertTrue(app.staticTexts.element(matching: text(containing: "Apple pie recipe wins the county fair this year")).exists)
        XCTAssertTrue(app.buttons["Share"].exists)
    }

    func testAddTagAndComment() {
        let app = launchSeeded()
        let apple = app.staticTexts[appleSummary]
        XCTAssertTrue(apple.waitForExistence(timeout: 15))
        apple.tap()

        // Trailing "\n" submits the single-line field, dismissing the keyboard so the Add button
        // isn't occluded (a software-keyboard hang otherwise).
        let tagField = app.textFields["newTagField"]
        XCTAssertTrue(tagField.waitForExistence(timeout: 5))
        tagField.tap()
        tagField.typeText("News\n")
        let addTag = app.buttons["addTagButton"]
        XCTAssertTrue(addTag.waitForExistence(timeout: 5))
        addTag.tap()
        // The tag chip is a Button (its Text is absorbed into the button's label).
        XCTAssertTrue(app.buttons["News"].waitForExistence(timeout: 5))

        let commentField = app.textFields["newCommentField"]
        XCTAssertTrue(commentField.waitForExistence(timeout: 5))
        commentField.tap()
        commentField.typeText("great clipping\n")
        let addComment = app.buttons["addCommentButton"]
        XCTAssertTrue(addComment.waitForExistence(timeout: 5))
        addComment.tap()
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

    func testFilterDialogAppliesSortWithoutLosingClippings() {
        let app = launchSeeded()
        XCTAssertTrue(app.staticTexts[appleSummary].waitForExistence(timeout: 15))

        app.buttons["Filter"].tap()
        XCTAssertTrue(app.buttons["Date — oldest first"].waitForExistence(timeout: 5))
        app.buttons["Date — oldest first"].tap()
        app.buttons["Apply"].tap()

        // Both clippings still present after re-sorting.
        XCTAssertTrue(app.staticTexts[appleSummary].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts[teamSummary].exists)
    }
}
