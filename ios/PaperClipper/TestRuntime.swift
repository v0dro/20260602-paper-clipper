import Foundation

/// Detects whether the host app has been launched merely to host XCTest unit tests (which run
/// in-process). In that case the app should stay inert — no auto refresh/network — so the unit tests
/// own the SwiftData store and the clippings directory. UI tests launch the app in a separate process
/// (no XCTest loaded there) with `-uiTestSeed`, so they are excluded here and run normally.
enum TestRuntime {
    static var isUnitTesting: Bool {
        NSClassFromString("XCTestCase") != nil
            && !ProcessInfo.processInfo.arguments.contains("-uiTestSeed")
    }
}
