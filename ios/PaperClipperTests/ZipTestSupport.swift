import Foundation

/// Minimal reader for STORE-method ZIPs produced by `ZipArchive`, used to verify export output.
/// Mirrors the `readZip` helper in Android's `ClippingsRepositoryTest`.
enum ZipTestSupport {
    static func readStoredZip(_ data: Data) -> [String: Data] {
        var entries: [String: Data] = [:]
        let bytes = [UInt8](data)
        var i = 0

        func le16(_ at: Int) -> Int { Int(bytes[at]) | (Int(bytes[at + 1]) << 8) }
        func le32(_ at: Int) -> Int {
            Int(bytes[at]) | (Int(bytes[at + 1]) << 8) | (Int(bytes[at + 2]) << 16) | (Int(bytes[at + 3]) << 24)
        }

        while i + 30 <= bytes.count {
            let sig = le32(i)
            guard sig == 0x0403_4b50 else { break } // reached central directory
            let compSize = le32(i + 18)
            let nameLen = le16(i + 26)
            let extraLen = le16(i + 28)
            let nameStart = i + 30
            let name = String(bytes: bytes[nameStart..<(nameStart + nameLen)], encoding: .utf8) ?? ""
            let dataStart = nameStart + nameLen + extraLen
            let payload = Data(bytes[dataStart..<(dataStart + compSize)])
            entries[name] = payload
            i = dataStart + compSize
        }
        return entries
    }
}
