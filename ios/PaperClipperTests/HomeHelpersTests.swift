import XCTest
@testable import PaperClipper

/// Pure-logic tests for the home helpers (date grouping, search highlighting, status labels, date
/// formatting). Locale + time zone are pinned so the date assertions are deterministic. Mirrors
/// Android's `HelpersTest`.
final class HomeHelpersTests: XCTestCase {

    private let enUS = Locale(identifier: "en_US")
    private let utc = TimeZone(identifier: "UTC")!

    private func date(_ y: Int, _ mo: Int, _ d: Int, _ h: Int = 12) -> Date {
        var c = DateComponents()
        c.year = y; c.month = mo; c.day = d; c.hour = h
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = utc
        return cal.date(from: c)!
    }

    private func clip(_ when: Date, _ name: String = "c.jpg") -> Clipping {
        Clipping(fileName: name, createdAt: when, status: .success)
    }

    private func fmt(_ pattern: String, _ when: Date) -> String {
        HomeHelpers.fmt(pattern, when, locale: enUS, timeZone: utc)
    }

    private func sections(_ items: [Clipping]) -> [(header: String, items: [Clipping])] {
        HomeHelpers.dateSections(items, locale: enUS, timeZone: utc)
    }

    // MARK: - statusLabel

    func testStatusLabelIsExhaustiveAndStable() {
        XCTAssertEqual(HomeHelpers.statusLabel(.pending), "Analyzing…")
        XCTAssertEqual(HomeHelpers.statusLabel(.processing), "Analyzing…")
        XCTAssertEqual(HomeHelpers.statusLabel(.success), "Summary")
        XCTAssertEqual(HomeHelpers.statusLabel(.error), "Analysis failed")
    }

    // MARK: - fmt

    func testFmtFormatsKnownPatterns() {
        let t = date(2026, 6, 7)
        XCTAssertEqual(fmt("yyyy-MM", t), "2026-06")
        XCTAssertEqual(fmt("MMMM yyyy", t), "June 2026")
        XCTAssertEqual(fmt("d MMMM yyyy", t), "7 June 2026")
    }

    // MARK: - dateSections

    func testDateSectionsEmptyInputYieldsEmpty() {
        XCTAssertTrue(sections([]).isEmpty)
    }

    func testDateSectionsSingleClippingInMonthUsesMonthHeader() {
        let result = sections([clip(date(2026, 6, 7))])
        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result[0].header, "June 2026")
        XCTAssertEqual(result[0].items.count, 1)
    }

    func testDateSectionsMultipleInMonthSplitIntoPerDayHeaders() {
        let a = clip(date(2026, 6, 7), "a.jpg")
        let b = clip(date(2026, 6, 7), "b.jpg")
        let c = clip(date(2026, 6, 8), "c.jpg")
        let result = sections([a, b, c])

        XCTAssertEqual(result.count, 2)
        XCTAssertEqual(result[0].header, "7 June 2026")
        XCTAssertEqual(result[0].items.count, 2)
        XCTAssertEqual(result[1].header, "8 June 2026")
        XCTAssertEqual(result[1].items.count, 1)
    }

    func testDateSectionsSpanningTwoMonthsKeepsOrderAndMonthHeaders() {
        let may = clip(date(2026, 5, 30), "may.jpg")
        let june = clip(date(2026, 6, 1), "june.jpg")
        XCTAssertEqual(sections([may, june]).map(\.header), ["May 2026", "June 2026"])
    }

    // MARK: - searchSnippet

    func testSearchSnippetHighlightsMatchWithinShortSource() {
        let source = "The quick brown fox jumps over the lazy dog"
        let result = HomeHelpers.searchSnippet(source, "fox")

        XCTAssertEqual(result.text, source)
        XCTAssertFalse(result.text.hasPrefix("…"))
        XCTAssertFalse(result.text.hasSuffix("…"))
        XCTAssertEqual(result.ranges.count, 1)
        let foxIndex = (source as NSString).range(of: "fox").location
        XCTAssertEqual(result.ranges[0].lowerBound, foxIndex)
        XCTAssertEqual(result.ranges[0].upperBound, foxIndex + 3)
    }

    func testSearchSnippetTruncatesWithEllipsesAroundMatch() {
        let source = String(repeating: "a", count: 100) + "NEEDLE" + String(repeating: "b", count: 100)
        let result = HomeHelpers.searchSnippet(source, "NEEDLE")

        XCTAssertTrue(result.text.hasPrefix("…"))
        XCTAssertTrue(result.text.hasSuffix("…"))
        XCTAssertTrue(result.text.contains("NEEDLE"))
        let at = (result.text as NSString).range(of: "NEEDLE").location
        XCTAssertEqual(result.ranges.count, 1)
        XCTAssertEqual(result.ranges[0].lowerBound, at)
        XCTAssertEqual(result.ranges[0].upperBound, at + "NEEDLE".count)
    }

    func testSearchSnippetIsCaseInsensitive() {
        XCTAssertEqual(HomeHelpers.searchSnippet("Breaking NEWS today", "news").ranges.count, 1)
    }

    func testSearchSnippetHighlightsEveryOccurrence() {
        XCTAssertEqual(HomeHelpers.searchSnippet("cat cat cat", "cat").ranges.count, 3)
    }

    func testSearchSnippetNoMatchProducesNoRangesAndDoesNotCrash() {
        XCTAssertTrue(HomeHelpers.searchSnippet("nothing to see here", "zzz").ranges.isEmpty)
    }

    func testSearchSnippetCollapsesNewlinesToSpaces() {
        let result = HomeHelpers.searchSnippet("line1\nline2 fox", "fox")
        XCTAssertFalse(result.text.contains("\n"))
        XCTAssertTrue(result.text.contains("line1 line2"))
    }
}
