import Foundation

/// A tiny, dependency-free ZIP writer using the STORE method (no compression). The clipping images
/// are already-compressed JPEG/PNG and the metadata/HTML are small, so storing is fine and keeps the
/// format trivial and fully under test. Replaces Android's `java.util.zip.ZipOutputStream` usage in
/// `ClippingsRepository.exportTo`.
enum ZipArchive {
    struct Entry {
        let name: String
        let data: Data
    }

    static func zip(_ entries: [Entry]) -> Data {
        var output = Data()
        var central = Data()
        var offsets: [Int] = []

        for entry in entries {
            offsets.append(output.count)
            let nameBytes = Array(entry.name.utf8)
            let crc = crc32(entry.data)
            let size = UInt32(entry.data.count)

            // Local file header.
            output.append(le32(0x0403_4b50))
            output.append(le16(20))           // version needed
            output.append(le16(0))            // flags
            output.append(le16(0))            // method: store
            output.append(le16(0))            // mod time
            output.append(le16(0))            // mod date
            output.append(le32(crc))
            output.append(le32(size))         // compressed size
            output.append(le32(size))         // uncompressed size
            output.append(le16(UInt16(nameBytes.count)))
            output.append(le16(0))            // extra length
            output.append(contentsOf: nameBytes)
            output.append(entry.data)
        }

        for (index, entry) in entries.enumerated() {
            let nameBytes = Array(entry.name.utf8)
            let crc = crc32(entry.data)
            let size = UInt32(entry.data.count)

            central.append(le32(0x0201_4b50))
            central.append(le16(20))          // version made by
            central.append(le16(20))          // version needed
            central.append(le16(0))           // flags
            central.append(le16(0))           // method: store
            central.append(le16(0))           // mod time
            central.append(le16(0))           // mod date
            central.append(le32(crc))
            central.append(le32(size))        // compressed size
            central.append(le32(size))        // uncompressed size
            central.append(le16(UInt16(nameBytes.count)))
            central.append(le16(0))           // extra length
            central.append(le16(0))           // comment length
            central.append(le16(0))           // disk number start
            central.append(le16(0))           // internal attrs
            central.append(le32(0))           // external attrs
            central.append(le32(UInt32(offsets[index])))
            central.append(contentsOf: nameBytes)
        }

        let centralOffset = output.count
        output.append(central)

        // End of central directory record.
        output.append(le32(0x0605_4b50))
        output.append(le16(0))                                // disk number
        output.append(le16(0))                                // disk with central dir
        output.append(le16(UInt16(entries.count)))           // entries on this disk
        output.append(le16(UInt16(entries.count)))           // total entries
        output.append(le32(UInt32(central.count)))           // central dir size
        output.append(le32(UInt32(centralOffset)))           // central dir offset
        output.append(le16(0))                               // comment length

        return output
    }

    // MARK: - Byte helpers

    private static func le16(_ value: UInt16) -> Data {
        Data([UInt8(value & 0xff), UInt8((value >> 8) & 0xff)])
    }

    private static func le32(_ value: UInt32) -> Data {
        Data([
            UInt8(value & 0xff),
            UInt8((value >> 8) & 0xff),
            UInt8((value >> 16) & 0xff),
            UInt8((value >> 24) & 0xff),
        ])
    }

    // MARK: - CRC-32 (IEEE 802.3, polynomial 0xEDB88320)

    private static let crcTable: [UInt32] = {
        (0..<256).map { i -> UInt32 in
            var c = UInt32(i)
            for _ in 0..<8 {
                c = (c & 1) != 0 ? (0xEDB8_8320 ^ (c >> 1)) : (c >> 1)
            }
            return c
        }
    }()

    static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            let index = Int((crc ^ UInt32(byte)) & 0xff)
            crc = crcTable[index] ^ (crc >> 8)
        }
        return crc ^ 0xFFFF_FFFF
    }
}
