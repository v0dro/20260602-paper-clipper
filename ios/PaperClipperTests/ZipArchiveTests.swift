import XCTest
@testable import PaperClipper

/// Verifies the dependency-free ZIP writer: a correct CRC-32 and a readable STORE archive that
/// round-trips entry names + bytes verbatim.
final class ZipArchiveTests: XCTestCase {

    func testCrc32KnownVector() {
        // Standard CRC-32 test vector for "123456789".
        XCTAssertEqual(ZipArchive.crc32(Data("123456789".utf8)), 0xCBF4_3926)
    }

    func testCrc32EmptyIsZero() {
        XCTAssertEqual(ZipArchive.crc32(Data()), 0)
    }

    func testRoundTripsStoredEntriesVerbatim() {
        let imageBytes = Data([0x9, 0x8, 0x7, 0x0, 0xFF])
        let entries = [
            ZipArchive.Entry(name: "images/a.jpg", data: imageBytes),
            ZipArchive.Entry(name: "metadata.json", data: Data("[]".utf8)),
        ]
        let zip = ZipArchive.zip(entries)
        let read = ZipTestSupport.readStoredZip(zip)

        XCTAssertEqual(read.count, 2)
        XCTAssertEqual(read["images/a.jpg"], imageBytes)
        XCTAssertEqual(read["metadata.json"], Data("[]".utf8))
    }

    func testEmptyArchiveHasEndOfCentralDirectory() {
        let zip = ZipArchive.zip([])
        // EOCD signature 0x06054b50 little-endian at the end of an empty archive.
        XCTAssertEqual(Array(zip.suffix(22).prefix(4)), [0x50, 0x4b, 0x05, 0x06])
    }
}
