import XCTest
@testable import PaperClipper

/// Freezes the JSON contract with the proxy server. Exercises the pure parse helpers. Mirrors
/// Android's `GeminiClientParseTest`.
final class AnalyzeClientTests: XCTestCase {

    func testParseResultSuccessTrimsBothFields() {
        let result = AnalyzeClient.parseResult(#"{"extractedText":"  hello  ","summary":"  sum  "}"#)
        XCTAssertEqual(result, .success(extractedText: "hello", summary: "sum"))
    }

    func testParseResultSucceedsWithOnlyOneField() {
        if case .failure = AnalyzeClient.parseResult(#"{"summary":"only summary"}"#) {
            XCTFail("summary-only should succeed")
        }
        if case .failure = AnalyzeClient.parseResult(#"{"extractedText":"only text"}"#) {
            XCTFail("text-only should succeed")
        }
    }

    func testParseResultBothEmptyIsError() {
        XCTAssertEqual(
            AnalyzeClient.parseResult(#"{"extractedText":"","summary":""}"#),
            .failure("Server returned no text")
        )
    }

    func testParseResultInvalidJsonIsError() {
        XCTAssertEqual(
            AnalyzeClient.parseResult("this is not json"),
            .failure("Unexpected response from server")
        )
    }

    func testServerErrorPrefersErrorFieldElseFallsBackToHttpCode() {
        XCTAssertEqual(AnalyzeClient.serverError(#"{"error":"nope"}"#, code: 500), "nope")
        XCTAssertEqual(AnalyzeClient.serverError("garbage", code: 503), "Analysis request failed (HTTP 503)")
        XCTAssertEqual(AnalyzeClient.serverError("{}", code: 404), "Analysis request failed (HTTP 404)")
    }
}
