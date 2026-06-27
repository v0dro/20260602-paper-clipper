import XCTest
@testable import PaperClipper

/// `Logx.redactEmail` keeps PII out of logs, so a regression here is a silent privacy leak. Mirrors
/// Android's `LogxTest`.
final class LogxTests: XCTestCase {

    func testRedactsNormalEmail() {
        XCTAssertEqual(Logx.redactEmail("jane.doe@example.com"), "j***@example.com")
    }

    func testKeepsOnlyFirstCharAndDomain() {
        XCTAssertEqual(Logx.redactEmail("a@b.co"), "a***@b.co")
    }

    func testNullOrBlankCollapses() {
        XCTAssertEqual(Logx.redactEmail(nil), "<none>")
        XCTAssertEqual(Logx.redactEmail(""), "<none>")
        XCTAssertEqual(Logx.redactEmail("   "), "<none>")
    }

    func testNonAddressesNeverEchoedVerbatim() {
        XCTAssertEqual(Logx.redactEmail("not-an-email"), "<redacted>")
        XCTAssertEqual(Logx.redactEmail("@example.com"), "<redacted>")
        XCTAssertEqual(Logx.redactEmail("user@"), "<redacted>")
    }
}
