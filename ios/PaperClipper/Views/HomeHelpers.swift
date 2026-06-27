import Foundation
import SwiftUI

/// Pure UI helpers for the home library (date grouping, search highlighting, status labels, date
/// formatting). Kept free of view state so they can be unit-tested deterministically — mirroring the
/// `@VisibleForTesting` helpers in Android's `MainActivity.kt` (frozen by `HelpersTest`). Locale and
/// time zone are injectable so date assertions don't depend on the test host's region.
enum HomeHelpers {

    /// Status -> caption text. Mirrors Android's `statusLabel`.
    static func statusLabel(_ status: ClippingStatus) -> String {
        switch status {
        case .pending, .processing: return "Analyzing…"
        case .success: return "Summary"
        case .error: return "Analysis failed"
        }
    }

    /// The home card's caption label: a successful clipping's AI heading, else the status word.
    /// Mirrors Android's `clipping.heading?.takeIf { ... SUCCESS } ?: statusLabel(status)`.
    static func cardLabel(status: ClippingStatus, heading: String?) -> String {
        if status == .success,
           let heading, !heading.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return heading
        }
        return statusLabel(status)
    }

    /// Formats a date with a `DateFormatter` pattern. Mirrors Android's `fmt`.
    static func fmt(_ pattern: String, _ date: Date, locale: Locale = .current, timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeZone = timeZone
        formatter.dateFormat = pattern
        return formatter.string(from: date)
    }

    /// Groups an already-sorted clipping list into date sections (Google Photos style): a month with a
    /// single clipping becomes one "June 2026" header; a month with several is split into per-day
    /// headers ("7 June 2026"). Order is preserved. Mirrors Android's `dateSections`.
    static func dateSections(
        _ visible: [Clipping],
        locale: Locale = .current,
        timeZone: TimeZone = .current
    ) -> [(header: String, items: [Clipping])] {
        // Group by month, preserving first-seen order.
        var monthOrder: [String] = []
        var byMonth: [String: [Clipping]] = [:]
        for clip in visible {
            let key = fmt("yyyy-MM", clip.createdAt, locale: locale, timeZone: timeZone)
            if byMonth[key] == nil { monthOrder.append(key) }
            byMonth[key, default: []].append(clip)
        }

        var out: [(header: String, items: [Clipping])] = []
        for monthKey in monthOrder {
            let monthItems = byMonth[monthKey] ?? []
            if monthItems.count == 1 {
                out.append((fmt("MMMM yyyy", monthItems[0].createdAt, locale: locale, timeZone: timeZone), monthItems))
            } else {
                var dayOrder: [String] = []
                var byDay: [String: [Clipping]] = [:]
                for clip in monthItems {
                    let key = fmt("yyyy-MM-dd", clip.createdAt, locale: locale, timeZone: timeZone)
                    if byDay[key] == nil { dayOrder.append(key) }
                    byDay[key, default: []].append(clip)
                }
                for dayKey in dayOrder {
                    let dayItems = byDay[dayKey] ?? []
                    out.append((fmt("d MMMM yyyy", dayItems[0].createdAt, locale: locale, timeZone: timeZone), dayItems))
                }
            }
        }
        return out
    }

    /// A search snippet: a window of text around the first match, with the character ranges of every
    /// (case-insensitive) occurrence of the query highlighted. Mirrors Android's `searchSnippet`
    /// (which returns an `AnnotatedString`); the UI turns `ranges` into bold/amber styling.
    struct SearchSnippet: Equatable {
        let text: String
        /// Character-offset ranges into `text`, one per highlighted occurrence.
        let ranges: [Range<Int>]
    }

    /// Builds a short excerpt of `source` centered on the first occurrence of `query`, every
    /// occurrence highlighted — like the macOS Preview search results. Mirrors Android's `searchSnippet`.
    static func searchSnippet(_ source: String, _ query: String) -> SearchSnippet {
        let radius = 60
        let chars = Array(source)
        let length = chars.count

        // Offset (in characters) of the first match, or 0 if not present (mirrors Android's
        // indexOf(...).coerceAtLeast(0)).
        let first: Int
        if let r = source.range(of: query, options: .caseInsensitive) {
            first = source.distance(from: source.startIndex, to: r.lowerBound)
        } else {
            first = 0
        }
        let start = max(first - radius, 0)
        let end = min(first + query.count + radius, length)
        let prefix = start > 0 ? "…" : ""
        let suffix = end < length ? "…" : ""

        let middleRaw = String(chars[start..<end])
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let window = prefix + middleRaw + suffix

        return SearchSnippet(text: window, ranges: caseInsensitiveRanges(of: query, in: window))
    }

    /// Renders a `SearchSnippet` as an `AttributedString` with bold amber highlights (UI use).
    static func attributed(_ snippet: SearchSnippet) -> AttributedString {
        var result = AttributedString(snippet.text)
        let scalars = Array(snippet.text)
        for range in snippet.ranges {
            guard range.lowerBound >= 0, range.upperBound <= scalars.count else { continue }
            let lower = result.index(result.startIndex, offsetByCharacters: range.lowerBound)
            let upper = result.index(result.startIndex, offsetByCharacters: range.upperBound)
            result[lower..<upper].font = .body.bold()
            result[lower..<upper].foregroundColor = Color(red: 1.0, green: 0.878, blue: 0.510) // #FFE082
        }
        return result
    }

    /// Character-offset ranges of every case-insensitive occurrence of `query` in `text`. An empty
    /// query yields no ranges.
    private static func caseInsensitiveRanges(of query: String, in text: String) -> [Range<Int>] {
        guard !query.isEmpty else { return [] }
        var result: [Range<Int>] = []
        var searchStart = text.startIndex
        while let r = text.range(of: query, options: .caseInsensitive, range: searchStart..<text.endIndex) {
            let lo = text.distance(from: text.startIndex, to: r.lowerBound)
            let hi = text.distance(from: text.startIndex, to: r.upperBound)
            result.append(lo..<hi)
            searchStart = r.upperBound == r.lowerBound ? text.index(after: r.upperBound) : r.upperBound
            if searchStart >= text.endIndex { break }
        }
        return result
    }
}
