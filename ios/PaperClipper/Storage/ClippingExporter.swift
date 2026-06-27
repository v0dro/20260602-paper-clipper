import Foundation
import SwiftData

/// Builds a user-readable export of all clippings, mirroring Android's `ClippingsRepository.exportTo`:
/// every clipping image under `images/`, a structured `metadata.json`, and an `index.html` gallery
/// that shows each image with its text / summary / tags / comments. Content building is pure (works
/// off plain value structs) so it is unit-testable; `ZipArchive` turns the entries into a `.zip`.
@MainActor
enum ClippingExporter {
    /// A flattened, value-type snapshot of a clipping for export — decoupled from SwiftData so the
    /// HTML/JSON builders can be tested without a `ModelContext`.
    struct Item {
        let fileName: String
        let createdAt: Date
        let status: ClippingStatus
        let summary: String?
        let extractedText: String?
        let tags: [String]
        let comments: [(text: String, createdAt: Date)]
        let imageData: Data?
    }

    /// Snapshots the clippings (newest first, matching Android's `getAll()` ordering) into export items.
    static func items(from clippings: [Clipping]) -> [Item] {
        let ordered = clippings.sorted { $0.createdAt > $1.createdAt }
        return ordered.map { clip in
            let tagNames: [String] = clip.tags
                .map(\.name)
                .sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
            let sortedComments = clip.comments.sorted { $0.createdAt < $1.createdAt }
            let comments: [(text: String, createdAt: Date)] = sortedComments.map {
                (text: $0.text, createdAt: $0.createdAt)
            }
            let imageData = try? Data(contentsOf: ClippingStore.fileURL(clip.fileName))
            return Item(
                fileName: clip.fileName,
                createdAt: clip.createdAt,
                status: clip.status,
                summary: clip.summary,
                extractedText: clip.extractedText,
                tags: tagNames,
                comments: comments,
                imageData: imageData
            )
        }
    }

    /// The full set of ZIP entries: `images/<name>` (verbatim), `metadata.json`, `index.html`.
    static func entries(for items: [Item]) -> [ZipArchive.Entry] {
        var entries: [ZipArchive.Entry] = []
        for item in items where item.imageData != nil {
            entries.append(ZipArchive.Entry(name: "images/\(item.fileName)", data: item.imageData!))
        }
        entries.append(ZipArchive.Entry(name: "metadata.json", data: metadataJSON(items)))
        entries.append(ZipArchive.Entry(name: "index.html", data: Data(indexHTML(items).utf8)))
        return entries
    }

    static func zipData(for items: [Item]) -> Data {
        ZipArchive.zip(entries(for: items))
    }

    // MARK: - metadata.json

    static func metadataJSON(_ items: [Item]) -> Data {
        let array: [[String: Any]] = items.map { item in
            [
                "fileName": item.fileName,
                "createdAt": Int(item.createdAt.timeIntervalSince1970 * 1000),
                // Android persists the status as an upper-cased name (e.g. "SUCCESS").
                "status": item.status.rawValue.uppercased(),
                "summary": item.summary ?? "",
                "extractedText": item.extractedText ?? "",
                "tags": item.tags,
                "comments": item.comments.map { comment in
                    ["text": comment.text, "createdAt": Int(comment.createdAt.timeIntervalSince1970 * 1000)] as [String: Any]
                },
            ]
        }
        // Pretty-printed; slashes left escaped (the JSONSerialization default) to match Android's
        // org.json output. Keys sorted for deterministic bytes.
        let options: JSONSerialization.WritingOptions = [.prettyPrinted, .sortedKeys]
        return (try? JSONSerialization.data(withJSONObject: array, options: options)) ?? Data("[]".utf8)
    }

    // MARK: - index.html

    /// HTML-escapes user/AI content. Ported 1:1 from Android's `esc()` (order matters: `&` first).
    static func htmlEscape(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
    }

    static func indexHTML(_ items: [Item]) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short

        var html = ""
        html += "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
        html += "<title>Paper Clipper AI export</title><style>"
        html += "body{font-family:sans-serif;margin:24px;background:#f5f5f5}"
        html += ".c{background:#fff;border-radius:8px;padding:16px;margin:0 0 16px;box-shadow:0 1px 3px rgba(0,0,0,.15)}"
        html += "img{max-width:100%;border-radius:6px}.tag{display:inline-block;background:#e0e0ff;"
        html += "border-radius:12px;padding:2px 10px;margin:2px;font-size:13px}"
        html += "h3{margin:8px 0 4px}.meta{color:#666;font-size:13px}pre{white-space:pre-wrap}</style></head><body>"
        html += "<h1>Paper Clipper AI — \(items.count) clippings</h1>"

        for item in items {
            html += "<div class=\"c\">"
            if item.imageData != nil {
                html += "<img src=\"images/\(htmlEscape(item.fileName))\">"
            }
            html += "<div class=\"meta\">\(htmlEscape(formatter.string(from: item.createdAt)))</div>"
            if !item.tags.isEmpty {
                html += "<div>"
                for tag in item.tags { html += "<span class=\"tag\">\(htmlEscape(tag))</span>" }
                html += "</div>"
            }
            if let summary = item.summary, !summary.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                html += "<h3>Summary</h3><div>\(htmlEscape(summary))</div>"
            }
            if let text = item.extractedText, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                html += "<h3>Extracted text</h3><pre>\(htmlEscape(text))</pre>"
            }
            if !item.comments.isEmpty {
                html += "<h3>Comments</h3>"
                for comment in item.comments { html += "<div>• \(htmlEscape(comment.text))</div>" }
            }
            html += "</div>"
        }
        html += "</body></html>"
        return html
    }
}
