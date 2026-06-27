import XCTest
@testable import PaperClipper

/// Locks down the backend URL contract. The HTTPS guard is a security control — if it regresses, the
/// proxy token / user id / feedback email could be sent in cleartext over an http:// SERVER_URL.
/// Mirrors Android's `BackendTest`.
final class BackendTests: XCTestCase {

    private func value(_ result: Result<String, BackendError>) -> String? { try? result.get() }
    private func isFailure(_ result: Result<String, BackendError>) -> Bool {
        if case .failure = result { return true } else { return false }
    }
    private func isSuccess(_ result: Result<String, BackendError>) -> Bool { !isFailure(result) }

    func testAcceptsHttpsAndTrimsTrailingSlash() {
        XCTAssertEqual(value(Backend.resolveBaseUrl("https://clipper.example.com/")), "https://clipper.example.com")
    }

    func testAcceptsHttpsWithoutTrailingSlash() {
        XCTAssertEqual(value(Backend.resolveBaseUrl("https://a.b")), "https://a.b")
    }

    func testTrimsSurroundingWhitespace() {
        XCTAssertEqual(value(Backend.resolveBaseUrl("  https://a.b/  ")), "https://a.b")
    }

    func testRejectsBlank() {
        XCTAssertTrue(isFailure(Backend.resolveBaseUrl("")))
        XCTAssertTrue(isFailure(Backend.resolveBaseUrl("   ")))
    }

    func testRejectsCleartextHttp() {
        XCTAssertTrue(isFailure(Backend.resolveBaseUrl("http://a.b")))
    }

    func testRejectsHttpRegardlessOfCase() {
        XCTAssertTrue(isFailure(Backend.resolveBaseUrl("HTTP://a.b")))
        XCTAssertTrue(isSuccess(Backend.resolveBaseUrl("https://a.b")))
        XCTAssertTrue(isSuccess(Backend.resolveBaseUrl("HTTPS://a.b")))
    }

    func testRejectsSchemelessUrl() {
        XCTAssertTrue(isFailure(Backend.resolveBaseUrl("clipper.example.com")))
        XCTAssertTrue(isFailure(Backend.resolveBaseUrl("ftp://a.b")))
    }

    func testBuildUrlJoinsWithSingleSlash() {
        XCTAssertEqual(Backend.buildUrl("https://a.b", "analyze"), "https://a.b/analyze")
        XCTAssertEqual(Backend.buildUrl("https://a.b/", "analyze"), "https://a.b/analyze")
        XCTAssertEqual(Backend.buildUrl("https://a.b", "/analyze"), "https://a.b/analyze")
        XCTAssertEqual(Backend.buildUrl("https://a.b/", "/analyze"), "https://a.b/analyze")
    }

    func testBuildUrlDoesNotDoubleSlash() {
        XCTAssertFalse(Backend.buildUrl("https://a.b/", "/feedback").contains("//feedback"))
    }
}
