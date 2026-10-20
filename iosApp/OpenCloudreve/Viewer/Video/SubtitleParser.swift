import Foundation

/// 外挂字幕解析：SRT / WebVTT（含 vrr，同为“时间 --> 时间”块）与 ASS / SSA（`[Events]` 中的 `Dialogue:`）。
/// 只取纯文本：去掉 HTML 标签与 ASS 覆盖代码 `{...}`，`\N`/`\n` 换行，`\h` 空格。
enum SubtitleParser {
    static func parse(_ text: String, format: String) -> [SubtitleCue] {
        switch format.lowercased() {
        case "ass", "ssa": parseAss(text)
        default: parseSrt(text)
        }
    }

    // MARK: - SRT / VTT

    static func parseSrt(_ text: String) -> [SubtitleCue] {
        let lines = normalizedLines(text)
        var cues: [SubtitleCue] = []
        var index = 0
        while index < lines.count {
            guard lines[index].contains("-->"), let range = timing(lines[index]) else {
                index += 1
                continue
            }
            let (start, end) = range
            index += 1
            var body: [String] = []
            while index < lines.count, !lines[index].trimmingCharacters(in: .whitespaces).isEmpty {
                body.append(lines[index])
                index += 1
            }
            let cleaned = cleanSrt(body.joined(separator: "\n"))
            if !cleaned.isEmpty && end > start {
                cues.append(SubtitleCue(start: start, end: end, text: cleaned))
            }
        }
        return cues.sorted { $0.start < $1.start }
    }

    /// `00:00:01,000 --> 00:00:04,500 [设置]`
    private static func timing(_ line: String) -> (Double, Double)? {
        let parts = line.components(separatedBy: "-->")
        guard parts.count == 2,
              let startToken = parts[0].split(whereSeparator: \.isWhitespace).last,
              let endToken = parts[1].split(whereSeparator: \.isWhitespace).first,
              let start = timestamp(String(startToken)),
              let end = timestamp(String(endToken)) else { return nil }
        return (start, end)
    }

    private static func cleanSrt(_ text: String) -> String {
        text.replacing(#/<[^>]*>/#, with: "")
            .replacing(#/\{\\[^}]*\}/#, with: "")
            .components(separatedBy: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .joined(separator: "\n")
    }

    // MARK: - ASS / SSA

    static func parseAss(_ text: String) -> [SubtitleCue] {
        let defaultFormat = ["layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text"]
        var format = defaultFormat
        var inEvents = false
        var cues: [SubtitleCue] = []
        for rawLine in normalizedLines(text) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("[") {
                inEvents = line.lowercased() == "[events]"
                continue
            }
            guard inEvents else { continue }
            if line.lowercased().hasPrefix("format:") {
                format = line.dropFirst("format:".count)
                    .split(separator: ",")
                    .map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
                continue
            }
            guard line.lowercased().hasPrefix("dialogue:") else { continue }
            let fields = line.dropFirst("dialogue:".count)
                .split(separator: ",", maxSplits: max(format.count - 1, 0), omittingEmptySubsequences: false)
                .map(String.init)
            guard fields.count == format.count,
                  let startIndex = format.firstIndex(of: "start"),
                  let endIndex = format.firstIndex(of: "end"),
                  let textIndex = format.firstIndex(of: "text"),
                  let start = timestamp(fields[startIndex].trimmingCharacters(in: .whitespaces)),
                  let end = timestamp(fields[endIndex].trimmingCharacters(in: .whitespaces)) else { continue }
            let cleaned = cleanAss(fields[textIndex])
            if !cleaned.isEmpty && end > start {
                cues.append(SubtitleCue(start: start, end: end, text: cleaned))
            }
        }
        return cues.sorted { $0.start < $1.start }
    }

    private static func cleanAss(_ text: String) -> String {
        text.replacing(#/\{[^}]*\}/#, with: "")
            .replacingOccurrences(of: "\\N", with: "\n")
            .replacingOccurrences(of: "\\n", with: "\n")
            .replacingOccurrences(of: "\\h", with: " ")
            .components(separatedBy: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .joined(separator: "\n")
    }

    // MARK: - 公共

    /// `h:mm:ss,ms`、`hh:mm:ss.ms`、`mm:ss.ms`（VTT）、`h:mm:ss.cc`（ASS）→ 秒
    static func timestamp(_ token: String) -> Double? {
        let parts = token.replacingOccurrences(of: ",", with: ".").split(separator: ":")
        guard parts.count == 2 || parts.count == 3, let seconds = Double(parts[parts.count - 1]) else { return nil }
        guard let minutes = Double(parts[parts.count - 2]) else { return nil }
        var hours = 0.0
        if parts.count == 3 {
            guard let h = Double(parts[0]) else { return nil }
            hours = h
        }
        return hours * 3600 + minutes * 60 + seconds
    }

    private static func normalizedLines(_ text: String) -> [String] {
        var content = text
        if content.hasPrefix("\u{FEFF}") { content.removeFirst() }
        return content
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .components(separatedBy: "\n")
    }
}
